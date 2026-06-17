package org.wormsink.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wormsink.core.HumanTransferCodeGenerator;
import org.wormsink.protocol.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class WormSinkTests {

    @Test
    public void testHumanTransferCodeGenerator() {
        String code = HumanTransferCodeGenerator.generateCode();
        assertNotNull(code);
        String[] parts = code.split("-");
        assertEquals(4, parts.length);
        for (String part : parts) {
            assertFalse(part.isEmpty());
            assertEquals(part, part.toLowerCase());
        }
    }

    @Test
    public void testProtocolFrame() {
        byte[] payload = "Hello WebRTC P2P!".getBytes(StandardCharsets.UTF_8);
        ProtocolFrame frame = new ProtocolFrame(ProtocolMessage.HELLO, ByteBuffer.wrap(payload));
        ByteBuffer encoded = frame.encode();

        ProtocolFrame decoded = ProtocolFrame.decode(encoded);
        assertNotNull(decoded);
        assertEquals(ProtocolMessage.HELLO, decoded.type);
        
        byte[] decodedPayload = new byte[decoded.payload.remaining()];
        decoded.payload.get(decodedPayload);
        assertArrayEquals(payload, decodedPayload);
    }

    @Test
    public void testProtocolSerializerChunk() {
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        byte[] sha256 = ChunkingEngine.calculateSha256(data);
        ProtocolFrame frame = ProtocolSerializer.encodeChunk(42, 1024L, 5, sha256, data);
        
        ProtocolFrame decodedFrame = ProtocolFrame.decode(frame.encode());
        assertNotNull(decodedFrame);
        assertEquals(ProtocolMessage.CHUNK, decodedFrame.type);

        ProtocolSerializer.ChunkData chunkData = ProtocolSerializer.decodeChunk(decodedFrame.payload);
        assertEquals(42, chunkData.chunkIndex);
        assertEquals(1024L, chunkData.offset);
        assertEquals(5, chunkData.size);
        assertArrayEquals(sha256, chunkData.sha256);
        assertArrayEquals(data, chunkData.data);
    }

    @Test
    public void testProtocolSerializerRequestChunks() {
        int[] indices = new int[]{1, 3, 5, 7, 9};
        ProtocolFrame frame = ProtocolSerializer.encodeRequestChunks(indices);
        ProtocolFrame decodedFrame = ProtocolFrame.decode(frame.encode());
        assertNotNull(decodedFrame);
        assertEquals(ProtocolMessage.REQUEST_CHUNKS, decodedFrame.type);

        int[] decodedIndices = ProtocolSerializer.decodeRequestChunks(decodedFrame.payload);
        assertArrayEquals(indices, decodedIndices);
    }

    @Test
    public void testResumeEngineState(@TempDir Path tempDir) throws IOException {
        Path stateFile = tempDir.resolve("test-transfer.state");
        ResumeEngine.TransferState state = new ResumeEngine.TransferState();
        state.transferId = "uuid-1234";
        state.fileHash = "abcde12345";
        state.fileName = "test.txt";
        state.fileSize = 1000L;
        state.totalChunks = 5;
        state.destinationPath = "/tmp/test.txt";
        state.completedChunks.add(0);
        state.completedChunks.add(2);

        ResumeEngine.saveState(state, stateFile.toString());
        assertTrue(Files.exists(stateFile));

        ResumeEngine.TransferState loaded = ResumeEngine.loadState(stateFile.toString());
        assertNotNull(loaded);
        assertEquals("uuid-1234", loaded.transferId);
        assertEquals("abcde12345", loaded.fileHash);
        assertEquals("test.txt", loaded.fileName);
        assertEquals(1000L, loaded.fileSize);
        assertEquals(5, loaded.totalChunks);
        assertEquals("/tmp/test.txt", loaded.destinationPath);
        assertEquals(2, loaded.completedChunks.size());
        assertTrue(loaded.completedChunks.contains(0));
        assertTrue(loaded.completedChunks.contains(2));
    }
}
