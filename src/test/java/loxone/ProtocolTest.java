package loxone;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ProtocolTest {
 static ByteBuffer buffer(int n){return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);}
 static void uuid(ByteBuffer b){b.putInt(0x12345678).putShort((short)0x9abc).putShort((short)0xdef0).put(new byte[]{1,2,3,4,5,6,7,8});}
 @Test void valuesAreIndividualEventsAndUuidUsesLoxoneByteOrder(){
  var b=buffer(48);uuid(b);b.putDouble(1.5);uuid(b);b.putDouble(-2);
  var events=Events.decode(2,b.array());assertEquals(2,events.size());
  assertEquals("12345678-9abc-def0-0102030405060708",events.getFirst().uuid());
  assertEquals(1.5,events.getFirst().payload().get("value"));assertEquals(24,events.getFirst().raw().length);
 }
 @Test void textLengthUsesUtf8BytesAndPadding(){
  byte[] word="café".getBytes(StandardCharsets.UTF_8);var b=buffer(88);
  for(int i=0;i<2;i++){uuid(b);uuid(b);b.putInt(word.length).put(word).put(new byte[3]);}
  var events=Events.decode(3,b.array());assertEquals(2,events.size());assertEquals("café",events.get(1).payload().get("text"));
 }
 @Test void scheduleAndWeatherEntriesAreDecoded(){
  var d=buffer(52);uuid(d);d.putDouble(0).putInt(1).putInt(2).putInt(60).putInt(120).putInt(1).putDouble(21.5);
  assertEquals(1,((List<?>)Events.decode(4,d.array()).getFirst().payload().get("entries")).size());
  var w=buffer(92);uuid(w);w.putInt(123).putInt(1);for(int i=0;i<5;i++)w.putInt(i);for(int i=0;i<6;i++)w.putDouble(i+.5);
  assertEquals(1,((List<?>)Events.decode(7,w.array()).getFirst().payload().get("entries")).size());
 }
 @Test void estimatedHeadersAndBinaryPayloadAreSeparate(){
  var f=new Framing();var h=buffer(8).put((byte)3).put((byte)2).put((byte)1).put((byte)0).putInt(24).array();
  assertNull(f.binary(h));h[2]=0;assertNull(f.binary(h));
  var payload=buffer(24);uuid(payload);payload.putDouble(3);
  assertArrayEquals(payload.array(),f.binary(payload.array()).payload());
  h[1]=6;assertEquals(6,f.binary(h).type());
 }
 @Test void badLengthsCannotBeSilentlyAccepted(){
  assertThrows(RuntimeException.class,()->Events.decode(2,new byte[23]));
  var b=buffer(36);uuid(b);uuid(b);b.putInt(-1);
  assertThrows(IllegalArgumentException.class,()->Events.decode(3,b.array()));
  var f=new Framing();f.binary(buffer(8).put((byte)3).put((byte)2).putShort((short)0).putInt(24).array());
  assertThrows(IllegalArgumentException.class,()->f.binary(new byte[8]));
 }
 @Test void authenticationHashesAreDeterministic()throws Exception {
  assertEquals("F7BC83F430538424B13298E6AA6FB143EF4D59A14946175997479DBC2D1A3CD8".toLowerCase(),
    Collector.hmac("SHA256","6b6579","The quick brown fox jumps over the lazy dog"));
  assertEquals(64,Collector.passwordHash("password","salt","SHA256").length());
 }
}
