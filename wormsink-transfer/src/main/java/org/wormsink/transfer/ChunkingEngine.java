package org.wormsink.transfer;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;

public class ChunkingEngine {
    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024; // 64KB — must stay below WebRTC SCTP 256KB message limit

    public static byte[] calculateSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] calculateFileSha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel channel = raf.getChannel()) {
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024); // Use 64KB buffers for fast scanning
            while (channel.read(buffer) > 0) {
                buffer.flip();
                md.update(buffer);
                buffer.clear();
            }
        }
        return md.digest();
    }

    public static byte[] calculateFolderSha256(List<File> files) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        for (File file : files) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 FileChannel channel = raf.getChannel()) {
                while (channel.read(buffer) > 0) {
                    buffer.flip();
                    md.update(buffer);
                    buffer.clear();
                }
            }
        }
        return md.digest();
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
