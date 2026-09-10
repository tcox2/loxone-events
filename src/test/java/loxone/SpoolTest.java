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
   UUID connection=UUID.randomUUID();var b=ProtocolTest.buffer(48);ProtocolTest.uuid(b);b.putDouble(1);ProtocolTest.uuid(b);b.putDouble(2);
   spool.append("test",connection,1,2,b.array());Path file;try(var files=Files.list(spool.directory)){file=files.findFirst().orElseThrow();}
   byte[] pending=Files.readAllBytes(file);assertEquals(2,spool.drain(db));
   Files.write(file,pending);assertEquals(2,spool.drain(db));
   try(var q=db.prepareStatement("SELECT count(*) FROM events WHERE connection_id=?")){q.setObject(1,connection);try(var rs=q.executeQuery()){rs.next();assertEquals(2,rs.getInt(1));}}
  }
 }
}
