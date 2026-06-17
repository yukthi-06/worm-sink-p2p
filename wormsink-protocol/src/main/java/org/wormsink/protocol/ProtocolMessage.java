package org.wormsink.protocol;

public enum ProtocolMessage {
    HELLO((byte) 1),
    METADATA((byte) 2),
    REQUEST_CHUNKS((byte) 3),
    CHUNK((byte) 4),
    ACK((byte) 5),
    COMPLETE((byte) 6),
    ERROR((byte) 7),
    RESUME((byte) 8),
    HEARTBEAT((byte) 9);

    private final byte type;

    ProtocolMessage(byte type) {
        this.type = type;
    }

    public byte getType() {
        return type;
    }

    public static ProtocolMessage fromByte(byte b) {
        for (ProtocolMessage msg : values()) {
            if (msg.type == b) {
                return msg;
            }
        }
        throw new IllegalArgumentException("Unknown protocol message type: " + b);
    }
}
