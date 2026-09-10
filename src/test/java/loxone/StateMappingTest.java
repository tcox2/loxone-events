package loxone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StateMappingTest {
 @Test void mapsSharedNestedGlobalAndWeatherStatesWithoutCopyingSensitiveDetails()throws Exception {
  String uuid="12345678-abcd-1234-0123456789abcdef";
  var root=Config.JSON.readTree("""
   {"rooms":{"r":{"name":"Kitchen"}},"controls":{"parent":{"name":"Power","type":"Meter","room":"r",
   "details":{"actualFormat":"%%.3fkW","password":"secret"},"states":{"actual":"%s"},
   "subControls":{"child":{"name":"Display","type":"InfoOnlyAnalog","states":{"value":"%s"}}}}},
   "globalStates":{"clock":"%s"},"weatherServer":{"states":{"actual":"%s"}}}
   """.formatted(uuid,uuid,uuid,uuid));
  var parsed=StateMapping.parse(root);assertEquals(1,parsed.size());
  var refs=parsed.get(uuid);assertEquals(4,refs.size());
  assertEquals("Kitchen",refs.get(1).get("room"));assertEquals("%.3fkW",refs.getFirst().get("format"));
  assertFalse(Config.JSON.writeValueAsString(parsed).contains("secret"));
  assertThrows(IllegalArgumentException.class,()->StateMapping.parse(Config.JSON.readTree("{}")));
 }
}
