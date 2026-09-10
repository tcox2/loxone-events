package loxone;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;

/** Metadata failures never interrupt the event receiver or its durable writer. */
final class StateMapping implements Runnable {
    private final Config config;
    private final Spool spool;
    StateMapping(Config config,Spool spool){this.config=config;this.spool=spool;}

    static Map<String,List<Map<String,String>>> parse(JsonNode root) {
        Events.require(root.path("controls").isObject(),"Missing structure controls");
        var result=new TreeMap<String,List<Map<String,String>>>();
        controls(root.path("controls"),root,"",result);
        references(root.path("globalStates"),"",Map.of("name","Global","type","Global"),result);
        references(root.path("weatherServer").path("states"),"",Map.of("name","Weather","type","Weather","formats",root.path("weatherServer").path("format").toString()),result);
        return result;
    }
    private static void controls(JsonNode controls,JsonNode root,String inheritedRoom,Map<String,List<Map<String,String>>> result) {
        controls.fields().forEachRemaining(entry->{
            var c=entry.getValue();
            String room=root.path("rooms").path(c.path("room").asText()).path("name").asText(inheritedRoom);
            var info=new LinkedHashMap<String,String>();
            info.put("control_uuid",entry.getKey());info.put("name",c.path("name").asText());
            info.put("type",c.path("type").asText());info.put("room",room);
            info.put("category",root.path("cats").path(c.path("cat").asText()).path("name").asText());
            c.path("states").fields().forEachRemaining(state->{
                var ref=new LinkedHashMap<>(info);
                String key=state.getKey();var details=c.path("details");
                String format=details.path(key.equals("actual")?"actualFormat":key.startsWith("total")?"totalFormat":"format").asText();
                if(!format.isEmpty())ref.put("format",format);
                references(state.getValue(),key,ref,result);
            });
            controls(c.path("subControls"),root,room,result);
        });
    }
    private static void references(JsonNode node,String path,Map<String,String> info,Map<String,List<Map<String,String>>> result) {
        if(node.isTextual()&&node.asText().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{16}")) {
            var ref=new LinkedHashMap<>(info);ref.put("state",path);
            result.computeIfAbsent(node.asText().toLowerCase(Locale.ROOT),k->new ArrayList<>()).add(ref);
        } else if(node.isObject())node.fields().forEachRemaining(e->references(e.getValue(),path.isEmpty()?e.getKey():path+"."+e.getKey(),info,result));
        else if(node.isArray())for(int i=0;i<node.size();i++)references(node.get(i),path+"["+i+"]",info,result);
    }
    static void save(Connection db,String source,Map<String,List<Map<String,String>>> mappings)throws Exception {
        db.setAutoCommit(false);
        try {
            try(var q=db.prepareStatement("UPDATE state_mapping SET present_in_structure=false WHERE source=?")){q.setString(1,source);q.executeUpdate();}
            try(var q=db.prepareStatement("INSERT INTO state_mapping(source,event_uuid,mappings,metadata_updated_at,present_in_structure) VALUES(?,?,?::jsonb,now(),true) ON CONFLICT(source,event_uuid) DO UPDATE SET mappings=EXCLUDED.mappings,metadata_updated_at=EXCLUDED.metadata_updated_at,present_in_structure=true")) {
                for(var e:mappings.entrySet()){q.setString(1,source);q.setString(2,e.getKey());q.setString(3,Config.JSON.writeValueAsString(e.getValue()));q.addBatch();}
                q.executeBatch();
            }
            db.commit();
        }catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    @Override public void run() {
        String source=config.endpoint().getHost();long refreshed=0;Set<String> previous=Set.of();
        try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            while(!Thread.currentThread().isInterrupted()) {
                try(var db=spool.connect(config)) {
                    Set<String> unknown=new TreeSet<>();
                    try(var q=db.prepareStatement("SELECT event_uuid FROM state_mapping WHERE source=? AND first_seen_at IS NOT NULL AND NOT present_in_structure")) {
                        q.setString(1,source);try(var rs=q.executeQuery()){while(rs.next())unknown.add(rs.getString(1));}
                    }
                    long elapsed=System.nanoTime()-refreshed;
                    if(refreshed==0||elapsed>Duration.ofHours(1).toNanos()||(!unknown.isEmpty()&&elapsed>Duration.ofMinutes(5).toNanos())) {
                        String auth=Base64.getEncoder().encodeToString((config.username()+":"+config.password()).getBytes(StandardCharsets.UTF_8));
                        var uri=new URI("https",null,source,config.endpoint().getPort(),"/data/LoxAPP3.json",null,null);
                        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Authorization","Basic "+auth).GET().build();
                        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
                        try(var body=response.body()) {
                            Events.require(response.statusCode()==200,"Structure request failed");
                            byte[] bytes=body.readNBytes(Framing.MAX_BYTES+1);Events.require(bytes.length<=Framing.MAX_BYTES,"Structure too large");
                            var mappings=parse(Config.JSON.readTree(bytes));save(db,source,mappings);
                            unknown.removeAll(mappings.keySet());
                            Main.log("state_mapping_refreshed states="+mappings.size()+" unresolved="+unknown.size());
                        }
                        refreshed=System.nanoTime();
                    }
                    if(!unknown.equals(previous)) {
                        for(String uuid:unknown)if(!previous.contains(uuid))Main.log("unrecognized_state uuid="+uuid+"; tracked in unresolved_states");
                        previous=Set.copyOf(unknown);
                    }
                }catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                catch(Exception e){Main.log("state_mapping_refresh_failed type="+e.getClass().getSimpleName()+"; retrying in 60 seconds");}
                try{Thread.sleep(60000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            }
        }
    }
}
