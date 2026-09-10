package loxone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SpoolTest {
 @TempDir Path state;
 @Test void receivedEventsSurviveRestartAndMalformedEventsRetainRawBytes()throws Exception {
  byte[] bytes={1,2,3};
  try(var spool=new Spool(state)){spool.append("test",UUID.randomUUID(),1,2,bytes);}
  try(var spool=new Spool(state);var files=Files.list(spool.directory)){
   var packet=Config.JSON.readValue(Files.readAllBytes(files.findFirst().orElseThrow()),Spool.Packet.class);
   assertArrayEquals(bytes,packet.events().getFirst().raw());
   assertEquals("decode_error",packet.events().getFirst().payload().get("kind"));
  }
 }
 @Test void databaseReplayIsIdempotent()throws Exception {
  String url=System.getenv("LOXONE_TEST_JDBC_URL");org.junit.jupiter.api.Assumptions.assumeTrue(url!=null);
  var c=new Config(java.net.URI.create("wss://example.test/ws/rfc6455"),"unused","unused",url,System.getenv("LOXONE_TEST_USER"),System.getenv("LOXONE_TEST_PASSWORD"),state);
  try(var spool=new Spool(state);var db=spool.connect(c)) {
   try(var st=db.createStatement()){st.execute(new String(Objects.requireNonNull(getClass().getResourceAsStream("/schema.sql")).readAllBytes()));}
   try(var st=db.createStatement()){st.execute(new String(Objects.requireNonNull(getClass().getResourceAsStream("/mapping.sql")).readAllBytes()));}
   UUID connection=UUID.randomUUID();var b=ProtocolTest.buffer(48);ProtocolTest.uuid(b);b.putDouble(1);ProtocolTest.uuid(b);b.putDouble(2);
   spool.append("test",connection,1,2,b.array());Path file;try(var files=Files.list(spool.directory)){file=files.findFirst().orElseThrow();}
   byte[] pending=Files.readAllBytes(file);assertEquals(2,spool.drain(db));
   Files.write(file,pending);assertEquals(2,spool.drain(db));
   String uuid;
   try(var q=db.createStatement();var rs=q.executeQuery("SELECT event_uuid FROM unresolved_states WHERE source='test'")){assertTrue(rs.next());uuid=rs.getString(1);}
   StateMapping.save(db,"test",Map.of(uuid,List.of(Map.of("name","Meter","state","actual"))));
   try(var q=db.createStatement();var rs=q.executeQuery("SELECT count(*) FROM unresolved_states WHERE source='test'")){rs.next();assertEquals(0,rs.getInt(1));}
   // A later structure can remove a control: retain its old labels but expose it as unresolved.
   StateMapping.save(db,"test",Map.of());
   try(var q=db.createStatement();var rs=q.executeQuery("SELECT mappings->0->>'name',first_seen_at IS NOT NULL FROM unresolved_states WHERE source='test'")){assertTrue(rs.next());assertEquals("Meter",rs.getString(1));assertTrue(rs.getBoolean(2));}
   StateMapping.save(db,"test",Map.of(uuid,List.of(Map.of("name","Renamed meter"))));

   try(var q=db.prepareStatement("SELECT count(*) FROM events WHERE connection_id=?")){q.setObject(1,connection);try(var rs=q.executeQuery()){rs.next();assertEquals(2,rs.getInt(1));}}
  }
 }
}
