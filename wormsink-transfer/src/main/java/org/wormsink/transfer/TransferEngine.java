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
    
    // State trackers
    private ResumeEngine.TransferState transferState;
    private final Set<Integer> requestedChunks = Collections.synchronizedSet(new HashSet<>());
    private final AtomicLong bytesTransferred = new AtomicLong(0);
    private long lastTime = System.currentTimeMillis();
    private long lastBytes = 0;
    
    private final CountDownLatch completeLatch = new CountDownLatch(1);
    private String errorMessage = null;

    // Receive buffer for protocol frames
    private final ByteBuffer protocolReceiveBuffer = ByteBuffer.allocate(32 * 1024 * 1024); // 32MB buffer

    public void setParallel(int parallel) {
        this.parallel = parallel;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public void send(File file, String signalingUrl, String sessionCode, ProgressListener pListener, ConnectionListener cListener, TransferListener tListener) throws Exception {
        this.isSender = true;
        this.file = file;
        this.progressListener = pListener;
        this.connectionListener = cListener;
        this.transferListener = tListener;

        tListener.onTransferStarted(file.getName(), file.length(), sessionCode);
        cListener.onConnecting();

        this.connectionManager = new PeerConnectionManager(signalingUrl, sessionCode, true, this);
        this.connectionManager.start();

        // Wait until transfer completes or fails
        completeLatch.await();
        
        this.connectionManager.close();
        if (errorMessage != null) {
            tListener.onTransferFailed(errorMessage);
        } else {
            tListener.onTransferCompleted();
        }
    }

    public void receive(String sessionCode, String destinationPath, String signalingUrl, ProgressListener pListener, ConnectionListener cListener, TransferListener tListener) throws Exception {
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
        if (errorMessage != null) {
            tListener.onTransferFailed(errorMessage);
        } else {
            tListener.onTransferCompleted();
        }
    }

    @Override
    public void onConnectionStateChange(String state) {
        if (state.contains("FAILED") || state.contains("CLOSED")) {
            errorMessage = state;
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
                // Send METADATA
                try {
                    String json = String.format("{\"fileName\":\"%s\",\"fileSize\":%d,\"chunkSize\":%d,\"totalChunks\":%d,\"fileHash\":\"%s\"}",
                            file.getName(), file.length(), chunkSize, (int) Math.ceil((double) file.length() / chunkSize),
                            ChunkingEngine.bytesToHex(ChunkingEngine.calculateFileSha256(file)));
                    connectionManager.sendMessage(ProtocolSerializer.encodeMetadata(json).encode());
                } catch (Exception e) {
                    errorMessage = "Failed to calculate file metadata: " + e.getMessage();
                    completeLatch.countDown();
                }
            } else {
                // Send HELLO back
                connectionManager.sendMessage(ProtocolSerializer.encodeHello().encode());
            }
        } else if (state == RTCDataChannelState.CLOSED) {
            connectionListener.onDisconnected();
            errorMessage = "Data channel closed unexpectedly";
            completeLatch.countDown();
        }
    }

    @Override
    public void onMessageReceived(ByteBuffer data, boolean binary) {
        // Append data to protocolReceiveBuffer
        if (protocolReceiveBuffer.remaining() < data.remaining()) {
            protocolReceiveBuffer.compact();
        }
        protocolReceiveBuffer.put(data);
        protocolReceiveBuffer.flip();

        while (true) {
            ProtocolFrame frame = ProtocolFrame.decode(protocolReceiveBuffer);
            if (frame == null) {
                break;
            }
            try {
                handleProtocolFrame(frame);
            } catch (Exception e) {
                errorMessage = e.getMessage();
                completeLatch.countDown();
                break;
            }
        }
        protocolReceiveBuffer.compact();
    }

    private void handleProtocolFrame(ProtocolFrame frame) throws Exception {
        switch (frame.type) {
            case HELLO:
                // Established
                break;
            case METADATA:
                if (!isSender) {
                    String json = ProtocolSerializer.decodeMetadata(frame.payload);
                    String fileHash = SimpleJson.getField(json, "fileHash");
                    String fileName = SimpleJson.getField(json, "fileName");
                    long fileSize = Long.parseLong(SimpleJson.getField(json, "fileSize"));
                    int totalChunks = Integer.parseInt(SimpleJson.getField(json, "totalChunks"));
                    this.chunkSize = Integer.parseInt(SimpleJson.getField(json, "chunkSize"));

                    // Check for resumable state
                    this.transferState = ResumeEngine.loadState(stateFilePath);
                    if (this.transferState != null && this.transferState.fileHash.equals(fileHash)) {
                        // Resuming transfer
                        transferListener.onTransferResumed(transferState.completedChunks.size() * (long) chunkSize);
                    } else {
                        // New transfer
                        this.transferState = new ResumeEngine.TransferState();
                        this.transferState.transferId = UUID.randomUUID().toString();
                        this.transferState.fileHash = fileHash;
                        this.transferState.fileName = fileName;
                        this.transferState.fileSize = fileSize;
                        this.transferState.totalChunks = totalChunks;
                        this.transferState.destinationPath = destinationPath;
                        
                        // Pre-allocate destination file space
                        try (RandomAccessFile raf = new RandomAccessFile(destinationPath, "rw")) {
                            raf.setLength(fileSize);
                        }
                        ResumeEngine.saveState(transferState, stateFilePath);
                    }

                    transferListener.onTransferStarted(fileName, fileSize, transferState.transferId);
                    bytesTransferred.set(transferState.completedChunks.size() * (long) chunkSize);

                    // Send RESUME or request chunks
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
                    // Verify hash
                    byte[] computedHash = ChunkingEngine.calculateSha256(chunk.data);
                    if (!Arrays.equals(computedHash, chunk.sha256)) {
                        // Hash mismatch, request again
                        sendRequestChunks(new int[]{chunk.chunkIndex});
                        break;
                    }
                    
                    // Write payload to disk at offset
                    try (RandomAccessFile raf = new RandomAccessFile(destinationPath, "rw");
                         FileChannel channel = raf.getChannel()) {
                        channel.write(ByteBuffer.wrap(chunk.data), chunk.offset);
                    }

                    // Save state
                    transferState.completedChunks.add(chunk.chunkIndex);
                    ResumeEngine.saveState(transferState, stateFilePath);

                    bytesTransferred.addAndGet(chunk.data.length);
                    reportProgress();

                    // Send ACK
                    connectionManager.sendMessage(ProtocolSerializer.encodeAck(chunk.chunkIndex, (byte) 0).encode());

                    if (transferState.completedChunks.size() == transferState.totalChunks) {
                        // Verify overall file
                        byte[] finalHash = ChunkingEngine.calculateFileSha256(new File(destinationPath));
                        if (ChunkingEngine.bytesToHex(finalHash).equals(transferState.fileHash)) {
                            // Complete!
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
                    if (toRequest.size() >= parallel) {
                        break;
                    }
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
                    long size = Math.min(chunkSize, file.length() - offset);
                    if (size <= 0) continue;

                    // Read sequential block
                    ByteBuffer buffer = ByteBuffer.allocate((int) size);
                    channel.read(buffer, offset);
                    byte[] data = buffer.array();

                    byte[] sha256 = ChunkingEngine.calculateSha256(data);
                    
                    // WebRTC flow control/backpressure
                    while (connectionManager.getBufferedAmount() > 4 * 1024 * 1024) { // 4MB Backpressure limit
                        Thread.sleep(20);
                    }

                    connectionManager.sendMessage(ProtocolSerializer.encodeChunk(index, offset, (int) size, sha256, data).encode());
                }
            } catch (Exception e) {
                connectionManager.sendMessage(ProtocolSerializer.encodeError(e.getMessage()).encode());
                errorMessage = e.getMessage();
                completeLatch.countDown();
            }
        });
    }

    private void reportProgress() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTime;
        if (elapsed >= 1000) {
            long total = isSender ? file.length() : transferState.fileSize;
            long current = bytesTransferred.get();
            long diff = current - lastBytes;
            double rate = (double) diff / (elapsed / 1000.0);
            long eta = rate > 0 ? (long) ((total - current) / rate) : 0;
            
            progressListener.onProgress(current, total, rate, eta);
            
            lastTime = now;
            lastBytes = current;
        }
    }
}
