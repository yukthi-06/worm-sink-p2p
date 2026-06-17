package org.wormsink.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ProtocolSerializer {

    public static ProtocolFrame encodeHello() {
        return new ProtocolFrame(ProtocolMessage.HELLO, null);
    }

    public static ProtocolFrame encodeHeartbeat() {
        return new ProtocolFrame(ProtocolMessage.HEARTBEAT, null);
    }

    public static ProtocolFrame encodeComplete() {
        return new ProtocolFrame(ProtocolMessage.COMPLETE, null);
    }

    public static ProtocolFrame encodeError(String reason) {
        byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
        return new ProtocolFrame(ProtocolMessage.ERROR, ByteBuffer.wrap(bytes));
    }

    public static String decodeError(ByteBuffer payload) {
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static ProtocolFrame encodeMetadata(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new ProtocolFrame(ProtocolMessage.METADATA, ByteBuffer.wrap(bytes));
    }

    public static String decodeMetadata(ByteBuffer payload) {
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static ProtocolFrame encodeRequestChunks(int[] chunkIndices) {
        ByteBuffer payload = ByteBuffer.allocate(4 + chunkIndices.length * 4);
        payload.putInt(chunkIndices.length);
        for (int index : chunkIndices) {
            payload.putInt(index);
        }
        payload.flip();
        return new ProtocolFrame(ProtocolMessage.REQUEST_CHUNKS, payload);
    }

    public static int[] decodeRequestChunks(ByteBuffer payload) {
        int count = payload.getInt();
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = payload.getInt();
        }
        return indices;
    }

    public static ProtocolFrame encodeAck(int chunkIndex, byte status) {
        ByteBuffer payload = ByteBuffer.allocate(5);
        payload.putInt(chunkIndex);
        payload.put(status);
        payload.flip();
        return new ProtocolFrame(ProtocolMessage.ACK, payload);
    }

    public static class AckData {
        public final int chunkIndex;
        public final byte status;

        public AckData(int chunkIndex, byte status) {
            this.chunkIndex = chunkIndex;
            this.status = status;
        }
    }

    public static AckData decodeAck(ByteBuffer payload) {
        int chunkIndex = payload.getInt();
        byte status = payload.get();
        return new AckData(chunkIndex, status);
    }

    public static ProtocolFrame encodeResume(String transferId, String fileHash, int[] missingChunkIndices) {
        byte[] idBytes = transferId.getBytes(StandardCharsets.UTF_8);
        byte[] hashBytes = fileHash.getBytes(StandardCharsets.UTF_8);
        
        ByteBuffer payload = ByteBuffer.allocate(2 + idBytes.length + 2 + hashBytes.length + 4 + missingChunkIndices.length * 4);
        payload.putShort((short) idBytes.length);
        payload.put(idBytes);
        payload.putShort((short) hashBytes.length);
        payload.put(hashBytes);
        
        payload.putInt(missingChunkIndices.length);
        for (int index : missingChunkIndices) {
            payload.putInt(index);
        }
        
        payload.flip();
        return new ProtocolFrame(ProtocolMessage.RESUME, payload);
    }

    public static class ResumeData {
        public final String transferId;
        public final String fileHash;
        public final int[] missingChunkIndices;

        public ResumeData(String transferId, String fileHash, int[] missingChunkIndices) {
            this.transferId = transferId;
            this.fileHash = fileHash;
            this.missingChunkIndices = missingChunkIndices;
        }
    }

    public static ResumeData decodeResume(ByteBuffer payload) {
        int idLen = Short.toUnsignedInt(payload.getShort());
        byte[] idBytes = new byte[idLen];
        payload.get(idBytes);
        String transferId = new String(idBytes, StandardCharsets.UTF_8);

        int hashLen = Short.toUnsignedInt(payload.getShort());
        byte[] hashBytes = new byte[hashLen];
        payload.get(hashBytes);
        String fileHash = new String(hashBytes, StandardCharsets.UTF_8);

        int count = payload.getInt();
        int[] missingChunkIndices = new int[count];
        for (int i = 0; i < count; i++) {
            missingChunkIndices[i] = payload.getInt();
        }

        return new ResumeData(transferId, fileHash, missingChunkIndices);
    }

    public static ProtocolFrame encodeChunk(int chunkIndex, long offset, int size, byte[] sha256, byte[] data) {
        ByteBuffer payload = ByteBuffer.allocate(4 + 8 + 4 + 32 + data.length);
        payload.putInt(chunkIndex);
        payload.putLong(offset);
        payload.putInt(size);
        payload.put(sha256);
        payload.put(data);
        payload.flip();
        return new ProtocolFrame(ProtocolMessage.CHUNK, payload);
    }

    public static class ChunkData {
        public final int chunkIndex;
        public final long offset;
        public final int size;
        public final byte[] sha256;
        public final byte[] data;

        public ChunkData(int chunkIndex, long offset, int size, byte[] sha256, byte[] data) {
            this.chunkIndex = chunkIndex;
            this.offset = offset;
            this.size = size;
            this.sha256 = sha256;
            this.data = data;
        }
    }

    public static ChunkData decodeChunk(ByteBuffer payload) {
        int chunkIndex = payload.getInt();
        long offset = payload.getLong();
        int size = payload.getInt();
        byte[] sha256 = new byte[32];
        payload.get(sha256);
        byte[] data = new byte[payload.remaining()];
        payload.get(data);
        return new ChunkData(chunkIndex, offset, size, sha256, data);
    }
}
