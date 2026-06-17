package org.wormsink.transfer;

import dev.onvoid.webrtc.RTCDataChannelState;
import org.wormsink.core.*;
import org.wormsink.protocol.*;
import org.wormsink.webrtc.*;
import org.wormsink.signaling.SimpleJson;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class TransferEngine implements WebRtcListener {

    private PeerConnectionManager connectionManager;
    private ProgressListener progressListener;
    private ConnectionListener connectionListener;
    private TransferListener transferListener;

    private boolean isSender;
    private File file;
    private String destinationPath;
    private String stateFilePath;

    private int parallel = 4;
    private int chunkSize = ChunkingEngine.DEFAULT_CHUNK_SIZE;

    // Pre-computed file metadata (sender only) — computed before WebRTC starts
    private String precomputedFileHash;

    // State trackers
    private ResumeEngine.TransferState transferState;
    private final Set<Integer> requestedChunks = Collections.synchronizedSet(new HashSet<>());
    private final AtomicLong bytesTransferred = new AtomicLong(0);
    private long lastTime = System.currentTimeMillis();
    private long lastBytes = 0;

    private final CountDownLatch completeLatch = new CountDownLatch(1);
    private String errorMessage = null;
    // Set to true once the transfer finishes successfully so that subsequent
    // ICE/DataChannel CLOSED callbacks (triggered by our own close()) are ignored.
    private volatile boolean transferCompleted = false;

    // Dedicated single-thread executor for all disk I/O & protocol work on the receiver side.
    // Keeps native WebRTC callback threads free (they must not block).
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wormsink-io");
        t.setDaemon(true);
        return t;
    });

    // Receive buffer — only touched by the ioExecutor
    private final ByteBuffer protocolReceiveBuffer = ByteBuffer.allocate(32 * 1024 * 1024); // 32MB

    public void setParallel(int parallel) {
        this.parallel = parallel;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public void send(File file, String signalingUrl, String sessionCode,
                     ProgressListener pListener, ConnectionListener cListener,
                     TransferListener tListener) throws Exception {
        this.isSender = true;
        this.file = file;
        this.progressListener = pListener;
        this.connectionListener = cListener;
        this.transferListener = tListener;

        tListener.onTransferStarted(file.getName(), file.length(), sessionCode);
        cListener.onConnecting();

        // Pre-compute file hash HERE (off the WebRTC thread) before opening the connection.
        this.precomputedFileHash = ChunkingEngine.bytesToHex(ChunkingEngine.calculateFileSha256(file));

        this.connectionManager = new PeerConnectionManager(signalingUrl, sessionCode, true, this);
        this.connectionManager.start();

        // Wait until transfer completes or fails
        completeLatch.await();

        this.connectionManager.close();
        ioExecutor.shutdownNow();
        if (errorMessage != null) {
            tListener.onTransferFailed(errorMessage);
        } else {
            tListener.onTransferCompleted();
        }
    }

    public void receive(String sessionCode, String destinationPath, String signalingUrl,
                        ProgressListener pListener, ConnectionListener cListener,
                        TransferListener tListener) throws Exception {
        this.isSender = false;
        this.destinationPath = destinationPath;
        this.stateFilePath = destinationPath + ".state";
        this.progressListener = pListener;
        this.connectionListener = cListener;
        this.transferListener = tListener;

        cListener.onConnecting();

        this.connectionManager = new PeerConnectionManager(signalingUrl, sessionCode, false, this);
        this.connectionManager.start();

        // Wait until transfer completes or fails
        completeLatch.await();

        this.connectionManager.close();
        ioExecutor.shutdownNow();
        if (errorMessage != null) {
            tListener.onTransferFailed(errorMessage);
        } else {
            tListener.onTransferCompleted();
        }
    }

    @Override
    public void onConnectionStateChange(String state) {
        // Ignore all state changes once the transfer has completed successfully.
        // This prevents the ICE: CLOSED callback (fired by our own close()) from
        // overwriting a null errorMessage and making a success look like a failure.
        if (transferCompleted) return;
        if (state.contains("FAILED") || state.contains("CLOSED")) {
            if (errorMessage == null) {
                errorMessage = state;
            }
            completeLatch.countDown();
        }
    }

    @Override
    public void onDataChannelStateChange(RTCDataChannelState state) {
        if (state == RTCDataChannelState.OPEN) {
            connectionListener.onConnected();
            if (isSender) {
                // Send HELLO
                connectionManager.sendMessage(ProtocolSerializer.encodeHello().encode());
                // Send METADATA — hash was pre-computed before WebRTC started; no blocking here
                try {
                    String json = String.format(
                            "{\"fileName\":\"%s\",\"fileSize\":%d,\"chunkSize\":%d,\"totalChunks\":%d,\"fileHash\":\"%s\"}",
                            file.getName(), file.length(), chunkSize,
                            (int) Math.ceil((double) file.length() / chunkSize),
                            precomputedFileHash);
                    connectionManager.sendMessage(ProtocolSerializer.encodeMetadata(json).encode());
                } catch (Exception e) {
                    errorMessage = "Failed to build metadata: " + e.getMessage();
                    completeLatch.countDown();
                }
            } else {
                // Send HELLO back
                connectionManager.sendMessage(ProtocolSerializer.encodeHello().encode());
            }
        } else if (state == RTCDataChannelState.CLOSED) {
            connectionListener.onDisconnected();
            if (!transferCompleted && errorMessage == null) {
                errorMessage = "Data channel closed unexpectedly";
            }
            completeLatch.countDown();
        }
    }

    /**
     * Called on a native WebRTC thread. We MUST NOT block here.
     * Snapshot the incoming bytes and hand off to ioExecutor immediately.
     */
    @Override
    public void onMessageReceived(ByteBuffer data, boolean binary) {
        // Capture the incoming bytes before returning (the ByteBuffer may be reused by native code)
        byte[] snapshot = new byte[data.remaining()];
        data.get(snapshot);

        ioExecutor.submit(() -> {
            try {
                processIncomingBytes(snapshot);
            } catch (Exception e) {
                System.err.println("Exception in ioExecutor: " + e.getMessage());
                e.printStackTrace(System.err);
                if (errorMessage == null) {
                    errorMessage = e.getMessage();
                }
                completeLatch.countDown();
            }
        });
    }

    /**
     * Runs exclusively on ioExecutor — safe to do disk I/O here.
     */
    private void processIncomingBytes(byte[] snapshot) throws Exception {
        // Append to buffer
        if (protocolReceiveBuffer.remaining() < snapshot.length) {
            protocolReceiveBuffer.compact();
        }
        protocolReceiveBuffer.put(snapshot);
        protocolReceiveBuffer.flip();

        while (true) {
            ProtocolFrame frame = ProtocolFrame.decode(protocolReceiveBuffer);
            if (frame == null) break;
            handleProtocolFrame(frame);
        }
        protocolReceiveBuffer.compact();
    }

    private void handleProtocolFrame(ProtocolFrame frame) throws Exception {
        switch (frame.type) {
            case HELLO:
                // Handshake acknowledged — nothing to do
                break;

            case METADATA:
                if (!isSender) {
                    String json = ProtocolSerializer.decodeMetadata(frame.payload);
                    String fileHash   = SimpleJson.getField(json, "fileHash");
                    String fileName   = SimpleJson.getField(json, "fileName");
                    long   fileSize   = Long.parseLong(SimpleJson.getField(json, "fileSize"));
                    int totalChunks   = Integer.parseInt(SimpleJson.getField(json, "totalChunks"));
                    this.chunkSize    = Integer.parseInt(SimpleJson.getField(json, "chunkSize"));

                    // Resolve actual destination path:
                    // If the caller passed a directory (e.g. "."), use the real filename from metadata.
                    File destFile = new File(destinationPath);
                    if (destFile.isDirectory()) {
                        destFile = new File(destFile, fileName);
                        this.destinationPath = destFile.getPath();
                        this.stateFilePath   = this.destinationPath + ".state";
                    }

                    // Check for resumable state
                    this.transferState = ResumeEngine.loadState(stateFilePath);
                    if (this.transferState != null && this.transferState.fileHash.equals(fileHash)) {
                        // Resuming
                        transferListener.onTransferResumed(transferState.completedChunks.size() * (long) chunkSize);
                    } else {
                        // New transfer
                        this.transferState = new ResumeEngine.TransferState();
                        this.transferState.transferId      = UUID.randomUUID().toString();
                        this.transferState.fileHash        = fileHash;
                        this.transferState.fileName        = fileName;
                        this.transferState.fileSize        = fileSize;
                        this.transferState.totalChunks     = totalChunks;
                        this.transferState.destinationPath = destinationPath;

                        // Pre-allocate destination file
                        try (RandomAccessFile raf = new RandomAccessFile(destinationPath, "rw")) {
                            raf.setLength(fileSize);
                        }
                        ResumeEngine.saveState(transferState, stateFilePath);
                    }

                    transferListener.onTransferStarted(fileName, fileSize, transferState.transferId);
                    bytesTransferred.set(transferState.completedChunks.size() * (long) chunkSize);
                    requestMoreChunks();
                }
                break;

            case REQUEST_CHUNKS:
                if (isSender) {
                    int[] indices = ProtocolSerializer.decodeRequestChunks(frame.payload);
                    sendChunksAsync(indices);
                }
                break;

            case CHUNK:
                if (!isSender) {
                    ProtocolSerializer.ChunkData chunk = ProtocolSerializer.decodeChunk(frame.payload);

                    // Verify per-chunk hash
                    byte[] computedHash = ChunkingEngine.calculateSha256(chunk.data);
                    if (!Arrays.equals(computedHash, chunk.sha256)) {
                        // Hash mismatch — re-request this chunk
                        sendRequestChunks(new int[]{chunk.chunkIndex});
                        break;
                    }

                    // Write to disk (ioExecutor — safe to block)
                    try (RandomAccessFile raf = new RandomAccessFile(destinationPath, "rw");
                         FileChannel channel = raf.getChannel()) {
                        channel.write(ByteBuffer.wrap(chunk.data), chunk.offset);
                    }

                    // Update state
                    transferState.completedChunks.add(chunk.chunkIndex);
                    ResumeEngine.saveState(transferState, stateFilePath);

                    bytesTransferred.addAndGet(chunk.data.length);
                    reportProgress();

                    // ACK
                    connectionManager.sendMessage(ProtocolSerializer.encodeAck(chunk.chunkIndex, (byte) 0).encode());

                    if (transferState.completedChunks.size() == transferState.totalChunks) {
                        // Verify entire file
                        byte[] finalHash = ChunkingEngine.calculateFileSha256(new File(destinationPath));
                        if (ChunkingEngine.bytesToHex(finalHash).equals(transferState.fileHash)) {
                            transferCompleted = true;   // mark success BEFORE countDown
                            Files.deleteIfExists(Paths.get(stateFilePath));
                            connectionManager.sendMessage(ProtocolSerializer.encodeComplete().encode());
                            completeLatch.countDown();
                        } else {
                            errorMessage = "Whole file integrity verification failed";
                            completeLatch.countDown();
                        }
                    } else {
                        requestMoreChunks();
                    }
                }
                break;

            case ACK:
                if (isSender) {
                    ProtocolSerializer.AckData ack = ProtocolSerializer.decodeAck(frame.payload);
                    if (ack.status == 0) {
                        bytesTransferred.addAndGet(chunkSize);
                        reportProgress();
                    }
                }
                break;

            case COMPLETE:
                if (isSender) {
                    transferCompleted = true;   // mark success BEFORE countDown
                    completeLatch.countDown();
                }
                break;

            case ERROR:
                errorMessage = ProtocolSerializer.decodeError(frame.payload);
                completeLatch.countDown();
                break;

            case RESUME:
                if (isSender) {
                    ProtocolSerializer.ResumeData resume = ProtocolSerializer.decodeResume(frame.payload);
                    sendChunksAsync(resume.missingChunkIndices);
                }
                break;
        }
    }

    private void requestMoreChunks() {
        List<Integer> toRequest = new ArrayList<>();
        synchronized (requestedChunks) {
            for (int i = 0; i < transferState.totalChunks; i++) {
                if (!transferState.completedChunks.contains(i) && !requestedChunks.contains(i)) {
                    toRequest.add(i);
                    requestedChunks.add(i);
                    if (toRequest.size() >= parallel) break;
                }
            }
        }
        if (!toRequest.isEmpty()) {
            int[] indices = toRequest.stream().mapToInt(Integer::intValue).toArray();
            sendRequestChunks(indices);
        }
    }

    private void sendRequestChunks(int[] indices) {
        connectionManager.sendMessage(ProtocolSerializer.encodeRequestChunks(indices).encode());
    }

    private void sendChunksAsync(int[] indices) {
        CompletableFuture.runAsync(() -> {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 FileChannel channel = raf.getChannel()) {
                for (int index : indices) {
                    long offset = (long) index * chunkSize;
                    long size   = Math.min(chunkSize, file.length() - offset);
                    if (size <= 0) continue;

                    ByteBuffer buffer = ByteBuffer.allocate((int) size);
                    channel.read(buffer, offset);
                    byte[] chunkData = buffer.array();
                    byte[] sha256 = ChunkingEngine.calculateSha256(chunkData);

                    // Back-pressure: wait if the DataChannel send buffer is getting full
                    while (connectionManager.getBufferedAmount() > 2 * 1024 * 1024) { // 2 MB limit
                        Thread.sleep(10);
                    }

                    connectionManager.sendMessage(
                            ProtocolSerializer.encodeChunk(index, offset, (int) size, sha256, chunkData).encode());
                }
            } catch (Exception e) {
                System.err.println("sendChunksAsync error: " + e.getMessage());
                connectionManager.sendMessage(ProtocolSerializer.encodeError(
                        e.getMessage() != null ? e.getMessage() : "chunk send error").encode());
                if (errorMessage == null) errorMessage = e.getMessage();
                completeLatch.countDown();
            }
        });
    }

    private void reportProgress() {
        long now     = System.currentTimeMillis();
        long elapsed = now - lastTime;
        if (elapsed >= 1000) {
            long total   = isSender ? file.length() : transferState.fileSize;
            long current = bytesTransferred.get();
            long diff    = current - lastBytes;
            double rate  = (double) diff / (elapsed / 1000.0);
            long eta     = rate > 0 ? (long) ((total - current) / rate) : 0;

            progressListener.onProgress(current, total, rate, eta);

            lastTime  = now;
            lastBytes = current;
        }
    }
}
