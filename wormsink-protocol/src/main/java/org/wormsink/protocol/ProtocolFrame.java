package org.wormsink.protocol;

import java.nio.ByteBuffer;

public class ProtocolFrame {
    public final ProtocolMessage type;
    public final ByteBuffer payload;

    public ProtocolFrame(ProtocolMessage type, ByteBuffer payload) {
        this.type = type;
        this.payload = payload;
    }

    public static ProtocolFrame decode(ByteBuffer buffer) {
        if (buffer.remaining() < 5) {
            return null; // Need more data
        }
        buffer.mark();
        byte typeByte = buffer.get();
        int length = buffer.getInt();
        if (length < 0 || length > 16 * 1024 * 1024) { // Safety sanity check: max 16MB frame
            throw new IllegalArgumentException("Malformed frame: length is invalid: " + length);
        }
        if (buffer.remaining() < length) {
            buffer.reset();
            return null; // Need more data
        }
        byte[] payloadBytes = new byte[length];
        buffer.get(payloadBytes);
        return new ProtocolFrame(ProtocolMessage.fromByte(typeByte), ByteBuffer.wrap(payloadBytes));
    }

    public ByteBuffer encode() {
        int length = payload == null ? 0 : payload.remaining();
        ByteBuffer buffer = ByteBuffer.allocate(5 + length);
        buffer.put(type.getType());
        buffer.putInt(length);
        if (length > 0) {
            int pos = payload.position();
            buffer.put(payload);
            payload.position(pos); // restore position
        }
        buffer.flip();
        return buffer;
    }
}
