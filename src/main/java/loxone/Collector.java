package loxone;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Collector implements WebSocket.Listener,AutoCloseable {
    private final Config config;private final Spool spool;private final Framing framing=new Framing();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final UUID connection=UUID.randomUUID();private long sequence;
    private final ByteArrayOutputStream binary=new ByteArrayOutputStream();private final StringBuilder text=new StringBuilder();
    private final BlockingQueue<JsonNode> responses=new LinkedBlockingQueue<>(32);
    private final CompletableFuture<Void> ended=new CompletableFuture<>();
    private volatile long lastReceived=System.nanoTime();private volatile boolean subscribed;private WebSocket socket;
    Collector(Config c,Spool spool){config=c;this.spool=spool;}
    void run()throws Exception {
        socket=http.newWebSocketBuilder().subprotocols("remotecontrol").connectTimeout(Duration.ofSeconds(15)).buildAsync(config.endpoint(),this).get(20,TimeUnit.SECONDS);
        authenticate();
        // Initial tables can precede the command acknowledgment.
        subscribed=true;command("jdev/sps/enablebinstatusupdate");
        Main.log("subscribed source="+config.endpoint().getHost()+" connection="+connection);
        long refresh=System.nanoTime()+Duration.ofHours(12).toNanos();
        while(!ended.isDone()) {
            try {ended.get(30,TimeUnit.SECONDS);}catch(TimeoutException ignored){}
            if(ended.isDone())break;
            if(System.nanoTime()-lastReceived>Duration.ofSeconds(100).toNanos())throw new IOException("Miniserver keepalive timed out");
            socket.sendText("keepalive",true).get(10,TimeUnit.SECONDS);
            if(System.nanoTime()>refresh){refreshToken();refresh=System.nanoTime()+Duration.ofHours(12).toNanos();}
        }
        ended.get();throw new IOException("Miniserver connection closed");
    }
    private void authenticate()throws Exception {
        var key=command("jdev/sys/getkey2/"+encode(config.username())).path("value");
        Path tokenFile=config.stateDirectory().resolve("token.json");
        if(Files.exists(tokenFile)) {
            var saved=Config.JSON.readTree(Files.readAllBytes(tokenFile));
            if(saved.path("validUntil").asLong()+1230768000L>Instant.now().getEpochSecond()+86400) {
                try {command("authwithtoken/"+hmac(Config.text(key,"hashAlg"),Config.text(key,"key"),Config.text(saved,"token"))+"/"+encode(config.username()));return;}
                catch(Exception e){Files.deleteIfExists(tokenFile);throw e;}
            }
        }
        String hash=passwordHash(config.password(),Config.text(key,"salt"),Config.text(key,"hashAlg"));
        String auth=hmac(Config.text(key,"hashAlg"),Config.text(key,"key"),config.username()+":"+hash);
        Path clientFile=config.stateDirectory().resolve("client-id");
        if(!Files.exists(clientFile))Spool.atomic(clientFile,UUID.randomUUID().toString().replaceFirst("-([^-]+)$","$1").getBytes(StandardCharsets.UTF_8));
        String clientId=Files.readString(clientFile);
        var token=command("jdev/sys/getjwt/"+auth+"/"+encode(config.username())+"/4/"+clientId+"/Loxone%20PostgreSQL%20event%20recorder").path("value");
        saveToken(token);
    }
    private void refreshToken()throws Exception {
        var key=command("jdev/sys/getkey2/"+encode(config.username())).path("value");
        var saved=Config.JSON.readTree(Files.readAllBytes(config.stateDirectory().resolve("token.json")));
        var token=command("jdev/sys/refreshjwt/"+hmac(Config.text(key,"hashAlg"),Config.text(key,"key"),Config.text(saved,"token"))+"/"+encode(config.username())).path("value");
        saveToken(token);
    }
    private void saveToken(JsonNode token)throws Exception {
        Config.text(token,"token");Events.require(token.path("validUntil").canConvertToLong(),"Missing token expiry");
        Spool.atomic(config.stateDirectory().resolve("token.json"),Config.JSON.writeValueAsBytes(token));
    }
    static String passwordHash(String password,String salt,String algorithm)throws Exception {
        Events.require(Set.of("SHA1","SHA256").contains(algorithm),"Unsupported Loxone hash algorithm");
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance(algorithm).digest((password+":"+salt).getBytes(StandardCharsets.UTF_8)));
    }
    static String hmac(String algorithm,String key,String value)throws Exception {
        Events.require(Set.of("SHA1","SHA256").contains(algorithm),"Unsupported Loxone HMAC algorithm");
        var mac=Mac.getInstance("Hmac"+algorithm);mac.init(new SecretKeySpec(HexFormat.of().parseHex(key),mac.getAlgorithm()));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
    static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");}
    private JsonNode command(String command)throws Exception {
        socket.sendText(command,true).get(10,TimeUnit.SECONDS);
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<deadline) {
            if(ended.isDone()){ended.get();throw new IOException("Connection closed during command");}
            var response=responses.poll(1,TimeUnit.SECONDS);
            if(response!=null) {
                int code=response.path("Code").asInt(response.path("code").asInt());
                if(code!=200)throw new IOException("Miniserver command rejected, status="+code);
                return response;
            }
        }
        throw new IOException("Miniserver command timed out");
    }
    @Override public void onOpen(WebSocket ws){ws.request(1);}
    @Override public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer data,boolean last) {
        try {
            Events.require(binary.size()+data.remaining()<=Framing.MAX_BYTES,"WebSocket message exceeds maximum");
            byte[] bytes=new byte[data.remaining()];data.get(bytes);binary.write(bytes);
            if(last){var message=framing.binary(binary.toByteArray());binary.reset();accept(message);}
            ws.request(1);
        }catch(Exception e){fail(e);}
        return null;
    }
    @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {
        try {
            Events.require(text.length()+data.length()<=Framing.MAX_BYTES,"WebSocket text exceeds maximum");text.append(data);
            if(last){accept(framing.text(text.toString().getBytes(StandardCharsets.UTF_8)));text.setLength(0);}
            ws.request(1);
        }catch(Exception e){fail(e);}
        return null;
    }
    private void accept(Framing.Message message)throws Exception {
        lastReceived=System.nanoTime();if(message==null)return;
        if(message.type()==6)return;
        if(message.type()==5)throw new IOException("Miniserver out of service");
        if(message.type()==0) {
            var node=Config.JSON.readTree(message.payload());
            if(node!=null&&node.has("LL")) {Events.require(responses.offer(node.path("LL")),"Unconsumed command responses");return;}
        }
        if(subscribed)spool.append(config.endpoint().getHost(),connection,sequence++,message.type(),message.payload());
    }
    private void fail(Throwable e){ended.completeExceptionally(e);if(socket!=null)socket.abort();}
    @Override public void onError(WebSocket ws,Throwable error){fail(error);}
    @Override public CompletionStage<?> onClose(WebSocket ws,int code,String reason){fail(new IOException("Miniserver WebSocket closed code="+code));return null;}
    @Override public void close(){if(socket!=null)socket.abort();http.close();}
}
