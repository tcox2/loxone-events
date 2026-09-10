package loxone;

import java.nio.*;

/** WebSocket fragmentation is assembled before these application messages arrive. */
final class Framing {
    record Message(int type,byte[] payload) {}
    static final int MAX_BYTES=64*1024*1024;
    private int type=-1,length;
    Message binary(byte[] bytes) {
        if(type>=0)return payload(bytes);
        Events.require(bytes.length==8 && bytes[0]==3,"Expected an eight-byte Loxone message header");
        int identifier=Byte.toUnsignedInt(bytes[1]);
        long size=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,4,4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        Events.require(size<=MAX_BYTES,"Loxone message exceeds configured maximum");
        if((bytes[2]&1)!=0)return null; // Exact header follows an estimated header.
        if(identifier==5||identifier==6)return new Message(identifier,new byte[0]);
        type=identifier;length=(int)size;
        if(length==0)return payload(new byte[0]);
        return null;
    }
    Message text(byte[] bytes) {
        Events.require(type==0,"Unexpected text message without text header");return payload(bytes);
    }
    private Message payload(byte[] bytes) {
        Events.require(bytes.length==length,"Loxone payload length differs from header");
        var message=new Message(type,bytes);type=-1;return message;
    }
}
