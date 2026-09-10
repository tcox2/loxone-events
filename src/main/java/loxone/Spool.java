package loxone;

import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.*;
import java.util.*;

final class Spool implements AutoCloseable {
    final Path directory;private final FileChannel lockChannel;private final FileLock lock;
    Spool(Path state)throws Exception {
        Files.createDirectories(state);Files.setPosixFilePermissions(state,PosixFilePermissions.fromString("rwx------"));
        directory=state.resolve("spool");Files.createDirectories(directory);
        lockChannel=FileChannel.open(state.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        lock=lockChannel.tryLock();Events.require(lock!=null,"Another collector is using this state directory");
    }
    record Packet(String source,UUID connection,long number,String receivedAt,int type,List<StoredEvent> events) {}
    record StoredEvent(UUID id,String uuid,Map<String,Object> payload,byte[] raw) {}
    void append(String source,UUID connection,long sequence,int type,byte[] bytes)throws Exception {
        List<Events.Event> decoded;
        try {decoded=Events.decode(type,bytes);}
        catch(RuntimeException e){
            Main.log("event_decode_failed type="+type+" bytes="+bytes.length+"; retaining raw message");
            decoded=List.of(new Events.Event(null,Map.of("kind","decode_error","error",e.getClass().getSimpleName()),bytes));
        }
        if(decoded.isEmpty())return;
        var events=decoded.stream().map(e->new StoredEvent(UUID.randomUUID(),e.uuid(),e.payload(),e.raw())).toList();
        var packet=new Packet(source,connection,sequence,Instant.now().toString(),type,events);
        String name=System.currentTimeMillis()+"-"+UUID.randomUUID();
        atomic(directory.resolve(name+".json"),Config.JSON.writeValueAsBytes(packet));
    }
    static void atomic(Path target,byte[] bytes)throws Exception {
        Path temporary=Files.createTempFile(target.getParent(),".pending-",".tmp");
        try {
            Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                var b=ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);channel.force(true);
            }
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var dir=FileChannel.open(target.getParent(),StandardOpenOption.READ)){dir.force(true);}
        }finally{Files.deleteIfExists(temporary);}
    }
    Connection connect(Config c)throws Exception {
        var p=new Properties();p.setProperty("user",c.databaseUser());p.setProperty("password",c.databasePassword());
        p.setProperty("connectTimeout","10");p.setProperty("socketTimeout","30");p.setProperty("ApplicationName","loxone-events");
        return DriverManager.getConnection(c.jdbcUrl(),p);
    }
    int drain(Connection db)throws Exception {
        List<Path> paths;
        try(var entries=Files.list(directory)){paths=entries.filter(p->p.toString().endsWith(".json")).sorted().limit(100).toList();}
        int count=0;
        for(Path file:paths) {
            var packet=Config.JSON.readValue(Files.readAllBytes(file),Packet.class);
            db.setAutoCommit(false);
            try(var insert=db.prepareStatement("INSERT INTO events(id,received_at,source,connection_id,message_number,event_index,event_type,event_uuid,payload,raw) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT(id) DO NOTHING")) {
                int index=0;
                for(var event:packet.events()) {
                    insert.setObject(1,event.id());insert.setObject(2,OffsetDateTime.parse(packet.receivedAt()));insert.setString(3,packet.source());
                    insert.setObject(4,packet.connection());insert.setLong(5,packet.number());insert.setInt(6,index++);insert.setInt(7,packet.type());
                    insert.setString(8,event.uuid());insert.setString(9,Config.JSON.writeValueAsString(event.payload()));insert.setBytes(10,event.raw());insert.addBatch();
                }
                insert.executeBatch();db.commit();
            }catch(Exception e){db.rollback();throw e;}
            finally{db.setAutoCommit(true);}
            Files.delete(file);count+=packet.events().size();
        }
        return count;
    }
    @Override public void close()throws Exception {lock.release();lockChannel.close();}
}
