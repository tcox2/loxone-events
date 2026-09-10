package loxone;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;

final class Events {
    record Event(String uuid, Map<String,Object> payload, byte[] raw) {}
    static List<Event> decode(int type,byte[] bytes) {
        if(!Set.of(2,3,4,7).contains(type))return List.of(new Event(null,Map.of("kind","unrecognized","type",type),bytes));
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var events=new ArrayList<Event>();
        while(b.hasRemaining()) {
            int start=b.position();String uuid=uuid(b);var data=new LinkedHashMap<String,Object>();
            switch(type) {
                case 2 -> {data.put("kind","value");data.put("value",number(b.getDouble()));}
                case 3 -> {
                    data.put("kind","text");data.put("icon_uuid",uuid(b));
                    int length=b.getInt();require(length>=0&&length<=b.remaining(),"Invalid text length");
                    byte[] text=new byte[length];b.get(text);
                    try {data.put("text",StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(text)).toString());}
                    catch(CharacterCodingException e){throw new IllegalArgumentException("Invalid UTF-8 text event",e);}
                    int padding=(4-length%4)%4;require(b.remaining()>=padding,"Truncated text padding");b.position(b.position()+padding);
                }
                case 4 -> {
                    data.put("kind","daytimer");data.put("default_value",number(b.getDouble()));
                    int count=count(b,24);var entries=new ArrayList<Map<String,Object>>();
                    for(int n=0;n<count;n++)entries.add(Map.of("mode",b.getInt(),"from",b.getInt(),"to",b.getInt(),"need_activate",b.getInt(),"value",number(b.getDouble())));
                    data.put("entries",entries);
                }
                case 7 -> {
                    data.put("kind","weather");data.put("last_update_seconds_since_2009",Integer.toUnsignedLong(b.getInt()));
                    int count=count(b,68);var entries=new ArrayList<Map<String,Object>>();
                    for(int n=0;n<count;n++) {
                        var entry=new LinkedHashMap<String,Object>();
                        entry.put("timestamp_seconds_since_2009",Integer.toUnsignedLong(b.getInt()));
                        for(String key:List.of("weather_type","wind_direction","solar_radiation","relative_humidity"))entry.put(key,b.getInt());
                        for(String key:List.of("temperature","perceived_temperature","dew_point","precipitation","wind_speed","barometric_pressure"))entry.put(key,number(b.getDouble()));
                        entries.add(entry);
                    }
                    data.put("entries",entries);
                }
                default -> throw new IllegalArgumentException("Unknown event type");
            }
            events.add(new Event(uuid,data,Arrays.copyOfRange(bytes,start,b.position())));
        }
        return events;
    }
    static int count(ByteBuffer b,int size) {int n=b.getInt();require(n>=0&&n<=b.remaining()/size,"Invalid entry count");return n;}
    static Object number(double v){return Double.isFinite(v)?v:Double.toString(v);}
    static String uuid(ByteBuffer b) {
        long first=Integer.toUnsignedLong(b.getInt());int second=Short.toUnsignedInt(b.getShort()),third=Short.toUnsignedInt(b.getShort());
        byte[] tail=new byte[8];b.get(tail);
        return String.format(Locale.ROOT,"%08x-%04x-%04x-%s",first,second,third,HexFormat.of().formatHex(tail));
    }
    static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
