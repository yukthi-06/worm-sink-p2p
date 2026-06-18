package org.wormsink.transfer;

import dev.onvoid.webrtc.RTCDataChannelState;
import org.wormsink.core.*;
import org.wormsink.protocol.*;
import org.wormsink.webrtc.*;
import org.wormsink.signaling.SignalingClient;
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

/**
 * Manages one complete file transfer (send or receive).
 *
 * Key design decisions
 * ─────────────────────
 * • All disk I/O runs on ioExecutor (single daemon thread) — never on native WebRTC callbacks.
 * • Sender keeps the session alive and reconnects automatically if a receiver dies mid-transfer.
 * • Sender sends HEARTBEAT every 2 s; receiver terminates within 8 s of sender death instead
 *   of draining the full SCTP receive buffer (which can be hundreds of MB).
 * • HEARTBEAT frames are also injected inline into the chunk-send loop every 2 s so that
 *   a saturated DataChannel does not starve the scheduler-based heartbeats.
 * • The receiver resets its heartbeat timer on every CHUNK frame received — proof the sender
 *   is alive even if an explicit HEARTBEAT is delayed by back-pressure.
 */
public class TransferEngine implements WebRtcListener {

    // ── Heartbeat timing ──────────────────────────────────────────────────────
    private static final long HEARTBEAT_INTERVAL_MS    = 2_000;   // sender send interval
    private static final long HEARTBEAT_TIMEOUT_MS     = 8_000;   // receiver gives up after this
    private static final long CONNECT_TIMEOUT_MS       = 60_000;  // receiver sessionLatch timeout
    private static final long ICE_DISCONNECTED_GRACE_MS = 15_000; // receiver ICE-limbo timeout

    // ── Injected state ────────────────────────────────────────────────────────
    private PeerConnectionManager connectionManager;
    private ProgressListener progressListener;
    private ConnectionListener connectionListener;
    private TransferListener transferListener;

    private boolean isSender;
    private File file;
    private String signalingUrl;
    private String sessionCode;
    private String destinationPath;
    private String stateFilePath;

    private int parallel = 4;
    private int chunkSize = ChunkingEngine.DEFAULT_CHUNK_SIZE;

    // Pre-computed (sender only) — done before WebRTC starts so no blocking on native threads
    private String precomputedFileHash;

    // ── Transfer state ────────────────────────────────────────────────────────
    private ResumeEngine.TransferState transferState;
    private final Set<Integer> requestedChunks = Collections.synchronizedSet(new HashSet<>());
    private final AtomicLong bytesTransferred = new AtomicLong(0);
    private long lastTime  = System.currentTimeMillis();
    private long lastBytes = 0;

    // ── Synchronization ───────────────────────────────────────────────────────
    /**
     * Per-connection-attempt latch. Replaced each time the sender reconnects.
     * Counted down when the current attempt either succeeds or fails.
     */
    private volatile CountDownLatch sessionLatch = new CountDownLatch(1);
    private volatile CountDownLatch connectionLatch = new CountDownLatch(1);

    private void releaseLatches() {
        sessionLatch.countDown();
        if (connectionLatch != null) {
            connectionLatch.countDown();
        }
    }

    /** Whether the OVERALL transfer (across all reconnect attempts) has completed. */
    private volatile boolean transferCompleted = false;

    /**
     * Set when a connection attempt fails in a way that allows the SENDER to reconnect
     * (e.g., receiver died). If true, the send() loop retries instead of propagating the error.
     */
    private volatile boolean receiverDisconnected = false;

    /** Error message for the current connection attempt (cleared on each reconnect). */
    private volatile String sessionError = null;

    // ── Executors ─────────────────────────────────────────────────────────────
    /** Single daemon thread for all disk I/O and protocol framing. */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wormsink-io");
        t.setDaemon(true);
        return t;
    });

    /** Heartbeat scheduler — daemon so it doesn't block JVM exit. */
    private ScheduledExecutorService heartbeatScheduler;

    // ── Receive buffer (ioExecutor only) ──────────────────────────────────────
    private final ByteBuffer protocolReceiveBuffer = ByteBuffer.allocate(32 * 1024 * 1024);

    // ── Heartbeat tracking (receiver) ─────────────────────────────────────────
    private volatile long lastHeartbeatReceivedMs;
    private volatile ScheduledFuture<?> senderHeartbeatTask;
    private volatile ScheduledFuture<?> senderWatchdogTask;
    private volatile ScheduledFuture<?> receiverWatchdogTask;

    // ── ICE DISCONNECTED grace timer (receiver) ───────────────────────────────
    /**
     * One-shot timer started when the receiver sees ICE: DISCONNECTED before the data
     * channel ever opened. If no improvement within ICE_DISCONNECTED_GRACE_MS, we
     * force-count-down the latch so the receiver doesn't hang forever in ICE limbo
     * (the typical symptom when it latched on to a stale offer from the old session).
     */
    private volatile ScheduledFuture<?> iceDisconnectedTimer;
    /** Flipped to true the first time the data channel reaches OPEN. */
    private volatile boolean dataChannelEverOpened = false;

    // =========================================================================
    // Public API
    // =========================================================================

    public void setParallel(int parallel) { this.parallel = parallel; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    /**
     * Send a file. The sender stays alive and automatically reconnects if the receiver
     * disconnects mid-transfer, posting a fresh WebRTC offer with the same session code.
     */
    public void send(File file, String signalingUrl, String sessionCode,
                     ProgressListener pListener, ConnectionListener cListener,
                     TransferListener tListener) throws Exception {
        this.isSender      = true;
        this.file          = file;
        this.signalingUrl  = signalingUrl;
        this.sessionCode   = sessionCode;
        this.progressListener   = pListener;
        this.connectionListener = cListener;
        this.transferListener   = tListener;

        tListener.onTransferStarted(file.getName(), file.length(), sessionCode);
        cListener.onConnecting();

        // Pre-compute hash once (heavy; must not run on a native WebRTC callback thread).
        this.precomputedFileHash = ChunkingEngine.bytesToHex(ChunkingEngine.calculateFileSha256(file));

        // Initialize the scheduler once for the lifetime of this send operation
        this.heartbeatScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "wormsink-scheduler");
            t.setDaemon(true);
            return t;
        });

        // ── Main send loop: retry after receiver disconnects ──────────────────
        try {
            while (!transferCompleted) {
                receiverDisconnected = false;
                sessionError         = null;
                sessionLatch         = new CountDownLatch(1);
                connectionLatch      = new CountDownLatch(1);
                requestedChunks.clear();

                connectionManager = new PeerConnectionManager(signalingUrl, sessionCode, true, this);
                connectionManager.start();

                // Block until the current connection attempt ends
                sessionLatch.await();

                stopHeartbeat();
                connectionManager.close();

                if (transferCompleted) break;

                if (receiverDisconnected) {
                    System.out.println("\nReceiver disconnected. Resetting signaling and waiting for new receiver...");
                    // NOTE: resetSession was already called asynchronously the moment receiver
                    // disconnect was detected (see onConnectionStateChange / onDataChannelStateChange).
                    // We do NOT call it again here to avoid a redundant HTTP round-trip.
                    cListener.onConnecting();
                    // Short pause to let ICE fully tear down before we create a new peer connection
                    Thread.sleep(1_000);
                } else {
                    // Fatal error for this session
                    break;
                }
            }
        } finally {
            if (heartbeatScheduler != null) {
                heartbeatScheduler.shutdownNow();
            }
            ioExecutor.shutdownNow();
        }

        if (transferCompleted) {
            tListener.onTransferCompleted();
        } else {
            tListener.onTransferFailed(sessionError != null ? sessionError : "Unknown error");
        }
    }

    /** Receive a file. */
    public void receive(String sessionCode, String destinationPath, String signalingUrl,
                        ProgressListener pListener, ConnectionListener cListener,
                        TransferListener tListener) throws Exception {
        this.isSender           = false;
        this.signalingUrl       = signalingUrl;
        this.sessionCode        = sessionCode;
        this.destinationPath    = destinationPath;
        this.stateFilePath      = destinationPath + ".state";
        this.progressListener   = pListener;
        this.connectionListener = cListener;
        this.transferListener   = tListener;

        cListener.onConnecting();

        // Initialize the scheduler once for the lifetime of this receive operation
        this.heartbeatScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "wormsink-scheduler");
            t.setDaemon(true);
            return t;
        });

        this.connectionLatch = new CountDownLatch(1);

        try {
            connectionManager = new PeerConnectionManager(signalingUrl, sessionCode, false, this);
            connectionManager.start();

            // Wait for connection to establish
            boolean released = connectionLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!released && sessionError == null) {
                sessionError = "Connection timed out after " + (CONNECT_TIMEOUT_MS / 1000)
                        + "s — sender may not be ready yet, please retry";
            }

            if (sessionError == null) {
                // Wait indefinitely for transfer to finish
                sessionLatch.await();
            }
        } finally {
            cancelIceGraceTimer();
            stopHeartbeat();
            if (heartbeatScheduler != null) {
                heartbeatScheduler.shutdownNow();
            }
            connectionManager.close();
            ioExecutor.shutdownNow();
        }

        if (transferCompleted) {
            tListener.onTransferCompleted();
        } else {
            tListener.onTransferFailed(sessionError != null ? sessionError : "Unknown error");
        }
    }

    // =========================================================================
    // WebRtcListener callbacks (called on native threads — MUST NOT block)
    // =========================================================================

    @Override
    public void onConnectionStateChange(String state) {
        if (transferCompleted) return;

        boolean isFailed = state.contains("FAILED");
        boolean isClosed = state.contains("CLOSED");
        boolean isSenderDisconnected = isSender && state.contains("DISCONNECTED");

        if (isFailed || isClosed || isSenderDisconnected) {
            cancelIceGraceTimer(); // no longer needed if we're definitively done
            if (isSender && (isFailed || isSenderDisconnected)) {
                // Receiver died/disconnected. Mark for reconnect and
                // immediately reset the signaling session so a restarting receiver
                // cannot pick up our stale offer while the old connection is still
                // closing (which takes ~3-4 s).  Fire-and-forget — the send() loop
                // will NOT call resetSession() a second time.
                receiverDisconnected = true;
                final String code = sessionCode;
                final String url  = signalingUrl;
                CompletableFuture.runAsync(() -> {
                    try {
                        new SignalingClient(url).resetSession(code);
                    } catch (Exception e) {
                        System.err.println("Warning: early signaling reset failed: " + e.getMessage());
                    }
                });
            } else {
                if (sessionError == null) sessionError = state;
            }
            releaseLatches();

        } else if (!isSender && state.contains("DISCONNECTED") && !dataChannelEverOpened) {
            // FIX 3: Receiver sees ICE DISCONNECTED before the data channel ever opened.
            // This happens when we latched onto a stale offer from the previous session:
            // the sender is partially alive so STUN probes half-succeed, keeping us in
            // DISCONNECTED limbo rather than cleanly reaching FAILED.
            // Start a grace timer; if we haven't improved within the grace period, give up.
            scheduleIceGraceTimer();
        }
    }

    @Override
    public void onDataChannelStateChange(RTCDataChannelState state) {
        if (state == RTCDataChannelState.OPEN) {
            cancelIceGraceTimer(); // successfully connected — cancel any pending grace timer
            dataChannelEverOpened = true;
            connectionListener.onConnected();
            if (isSender) {
                startSenderHeartbeat();
                startSenderHeartbeatWatch();
                // Send HELLO + METADATA immediately; hash was pre-computed — no blocking
                connectionManager.sendMessage(ProtocolSerializer.encodeHello().encode());
                try {
                    String json = String.format(
                            "{\"fileName\":\"%s\",\"fileSize\":%d,\"chunkSize\":%d,\"totalChunks\":%d,\"fileHash\":\"%s\"}",
                            file.getName(), file.length(), chunkSize,
                            (int) Math.ceil((double) file.length() / chunkSize),
                            precomputedFileHash);
                    connectionManager.sendMessage(ProtocolSerializer.encodeMetadata(json).encode());
                } catch (Exception e) {
                    sessionError = "Failed to build metadata: " + e.getMessage();
                    releaseLatches();
                }
            } else {
                connectionManager.sendMessage(ProtocolSerializer.encodeHello().encode());
                startReceiverHeartbeatWatch();
                if (connectionLatch != null) {
                    connectionLatch.countDown();
                }
            }
        } else if (state == RTCDataChannelState.CLOSED) {
            connectionListener.onDisconnected();
            if (!transferCompleted) {
                if (isSender) {
                    // Receiver died via data channel close. Reset signaling immediately
                    // (same early-reset pattern as the ICE FAILED path).
                    receiverDisconnected = true;
                    final String code = sessionCode;
                    final String url  = signalingUrl;
                    CompletableFuture.runAsync(() -> {
                        try {
                            new SignalingClient(url).resetSession(code);
                        } catch (Exception e) {
                            System.err.println("Warning: early signaling reset (DC closed) failed: " + e.getMessage());
                        }
                    });
                } else if (sessionError == null) {
                    sessionError = "Data channel closed unexpectedly";
                }
                releaseLatches();
            }
        }
    }

    /**
     * Called on a native WebRTC thread. MUST NOT block.
     * Snapshot bytes and hand off to ioExecutor.
     */
    @Override
    public void onMessageReceived(ByteBuffer data, boolean binary) {
        byte[] snapshot = new byte[data.remaining()];
        data.get(snapshot);
        lastHeartbeatReceivedMs = System.currentTimeMillis();

        ioExecutor.submit(() -> {
            try {
                processIncomingBytes(snapshot);
            } catch (Exception e) {
                System.err.println("Exception in ioExecutor: " + e.getMessage());
                e.printStackTrace(System.err);
                if (sessionError == null) sessionError = e.getMessage();
                releaseLatches();
            }
        });
    }

    // =========================================================================
    // Heartbeat helpers
    // =========================================================================

    private void startSenderHeartbeat() {
        senderHeartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!transferCompleted && connectionManager.getDataChannelState() == RTCDataChannelState.OPEN) {
                connectionManager.sendMessage(ProtocolSerializer.encodeHeartbeat().encode());
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void startSenderHeartbeatWatch() {
        lastHeartbeatReceivedMs = System.currentTimeMillis();
        senderWatchdogTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (transferCompleted) return;
            long silence = System.currentTimeMillis() - lastHeartbeatReceivedMs;
            if (silence > HEARTBEAT_TIMEOUT_MS) {
                System.err.println("\nNo heartbeat/message from receiver for " + (silence / 1000) + "s — receiver died.");
                receiverDisconnected = true;
                if (sessionError == null) sessionError = "Receiver heartbeat timeout";

                final String code = sessionCode;
                final String url  = signalingUrl;
                CompletableFuture.runAsync(() -> {
                    try {
                        new SignalingClient(url).resetSession(code);
                    } catch (Exception e) {
                        System.err.println("Warning: early signaling reset (timeout) failed: " + e.getMessage());
                    }
                });

                releaseLatches();
                stopHeartbeat();
            }
        }, HEARTBEAT_TIMEOUT_MS, 2_000, TimeUnit.MILLISECONDS);
    }

    private void startReceiverHeartbeatWatch() {
        lastHeartbeatReceivedMs = System.currentTimeMillis();
        // Check every 2 s; timeout after HEARTBEAT_TIMEOUT_MS of silence
        receiverWatchdogTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (transferCompleted) return;
            long silence = System.currentTimeMillis() - lastHeartbeatReceivedMs;
            if (silence > HEARTBEAT_TIMEOUT_MS) {
                System.err.println("\nNo heartbeat from sender for " + (silence / 1000) + "s — sender died.");
                if (sessionError == null) sessionError = "Sender heartbeat timeout";
                releaseLatches();
                stopHeartbeat();
            }
        }, HEARTBEAT_TIMEOUT_MS, 2_000, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (senderHeartbeatTask != null) {
            senderHeartbeatTask.cancel(false);
            senderHeartbeatTask = null;
        }
        if (senderWatchdogTask != null) {
            senderWatchdogTask.cancel(false);
            senderWatchdogTask = null;
        }
        if (receiverWatchdogTask != null) {
            receiverWatchdogTask.cancel(false);
            receiverWatchdogTask = null;
        }
        cancelIceGraceTimer();
    }

    // ── ICE DISCONNECTED grace timer helpers ──────────────────────────────────

    /**
     * Schedules a one-shot timer that forces the latch down after
     * ICE_DISCONNECTED_GRACE_MS if ICE never progresses beyond DISCONNECTED.
     * Idempotent — a second call while a timer is already pending is a no-op.
     */
    private void scheduleIceGraceTimer() {
        if (iceDisconnectedTimer != null && !iceDisconnectedTimer.isDone()) return;
        ScheduledExecutorService exec = heartbeatScheduler;
        if (exec != null && !exec.isShutdown()) {
            iceDisconnectedTimer = exec.schedule(() -> {
                if (!transferCompleted && sessionError == null) {
                    System.err.println("\nICE DISCONNECTED grace period expired (" +
                            (ICE_DISCONNECTED_GRACE_MS / 1000) + "s) — stale session, giving up.");
                    sessionError = "ICE stuck in DISCONNECTED (stale offer from previous session)";
                    releaseLatches();
                }
            }, ICE_DISCONNECTED_GRACE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Cancels any pending ICE grace timer. Safe to call multiple times. */
    private void cancelIceGraceTimer() {
        if (iceDisconnectedTimer != null) {
            iceDisconnectedTimer.cancel(false);
            iceDisconnectedTimer = null;
        }
    }

    // =========================================================================
    // Protocol framing (ioExecutor only)
    // =========================================================================

    private void processIncomingBytes(byte[] snapshot) throws Exception {
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
                break; // handshake ack — nothing to do

            case HEARTBEAT:
                if (!isSender) {
                    lastHeartbeatReceivedMs = System.currentTimeMillis();
                    connectionManager.sendMessage(ProtocolSerializer.encodeHeartbeat().encode());
                } else {
                    lastHeartbeatReceivedMs = System.currentTimeMillis();
                }
                break;

            case METADATA:
                if (!isSender) {
                    String json      = ProtocolSerializer.decodeMetadata(frame.payload);
                    String fileHash  = SimpleJson.getField(json, "fileHash");
                    String fileName  = SimpleJson.getField(json, "fileName");
                    long   fileSize  = Long.parseLong(SimpleJson.getField(json, "fileSize"));
                    int totalChunks  = Integer.parseInt(SimpleJson.getField(json, "totalChunks"));
                    this.chunkSize   = Integer.parseInt(SimpleJson.getField(json, "chunkSize"));

                    // Resolve directory → actual filename
                    File destFile = new File(destinationPath);
                    if (destFile.isDirectory()) {
                        destFile = new File(destFile, fileName);
                        this.destinationPath = destFile.getPath();
                        this.stateFilePath   = this.destinationPath + ".state";
                    }

                    // Resume or fresh start
                    this.transferState = ResumeEngine.loadState(stateFilePath);
                    boolean isResume = false;
                    if (this.transferState != null && this.transferState.fileHash.equals(fileHash)) {
                        transferListener.onTransferResumed(
                                transferState.completedChunks.size() * (long) chunkSize);
                        isResume = true;
                    } else {
                        this.transferState = new ResumeEngine.TransferState();
                        this.transferState.transferId      = UUID.randomUUID().toString();
                        this.transferState.fileHash        = fileHash;
                        this.transferState.fileName        = fileName;
                        this.transferState.fileSize        = fileSize;
                        this.transferState.totalChunks     = totalChunks;
                        this.transferState.destinationPath = destinationPath;

                        try (RandomAccessFile raf = new RandomAccessFile(destinationPath, "rw")) {
                            raf.setLength(fileSize);
                        }
                        ResumeEngine.saveState(transferState, stateFilePath);
                    }

                    transferListener.onTransferStarted(fileName, fileSize, transferState.transferId);
                    bytesTransferred.set(transferState.completedChunks.size() * (long) chunkSize);
                    if (transferState.completedChunks.size() == transferState.totalChunks) {
                        byte[] finalHash = ChunkingEngine.calculateFileSha256(new File(destinationPath));
                        if (ChunkingEngine.bytesToHex(finalHash).equals(transferState.fileHash)) {
                            transferCompleted = true;
                            Files.deleteIfExists(Paths.get(stateFilePath));
                            connectionManager.sendMessage(ProtocolSerializer.encodeComplete().encode());
                            releaseLatches();
                        } else {
                            sessionError = "Whole-file integrity verification failed";
                            releaseLatches();
                        }
                    } else if (isResume) {
                        List<Integer> missing = new ArrayList<>();
                        for (int i = 0; i < transferState.totalChunks; i++) {
                            if (!transferState.completedChunks.contains(i)) {
                                missing.add(i);
                            }
                        }
                        int[] missingIndices = missing.stream().mapToInt(Integer::intValue).toArray();
                        connectionManager.sendMessage(ProtocolSerializer.encodeResume(
                                transferState.transferId, transferState.fileHash, missingIndices).encode());
                    } else {
                        requestMoreChunks();
                    }
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

                    // Any incoming chunk proves the sender is alive — reset heartbeat timer.
                    // This prevents a false heartbeat timeout when the DataChannel is so
                    // saturated with chunk data that explicit HEARTBEAT frames are delayed.
                    lastHeartbeatReceivedMs = System.currentTimeMillis();

                    // Per-chunk hash verification
                    byte[] computedHash = ChunkingEngine.calculateSha256(chunk.data);
                    if (!Arrays.equals(computedHash, chunk.sha256)) {
                        sendRequestChunks(new int[]{chunk.chunkIndex});
                        break;
                    }

                    // Write to disk
                    try (RandomAccessFile raf = new RandomAccessFile(destinationPath, "rw");
                         FileChannel channel = raf.getChannel()) {
                        channel.write(ByteBuffer.wrap(chunk.data), chunk.offset);
                    }

                    transferState.completedChunks.add(chunk.chunkIndex);
                    ResumeEngine.saveState(transferState, stateFilePath);
                    bytesTransferred.addAndGet(chunk.data.length);
                    reportProgress();

                    connectionManager.sendMessage(
                            ProtocolSerializer.encodeAck(chunk.chunkIndex, (byte) 0).encode());

                    if (transferState.completedChunks.size() == transferState.totalChunks) {
                        byte[] finalHash = ChunkingEngine.calculateFileSha256(new File(destinationPath));
                        if (ChunkingEngine.bytesToHex(finalHash).equals(transferState.fileHash)) {
                            transferCompleted = true;
                            Files.deleteIfExists(Paths.get(stateFilePath));
                            connectionManager.sendMessage(ProtocolSerializer.encodeComplete().encode());
                            releaseLatches();
                        } else {
                            sessionError = "Whole-file integrity verification failed";
                            releaseLatches();
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
                    transferCompleted = true;
                    releaseLatches();
                }
                break;

            case ERROR:
                sessionError = ProtocolSerializer.decodeError(frame.payload);
                releaseLatches();
                break;

            case RESUME:
                if (isSender) {
                    ProtocolSerializer.ResumeData resume = ProtocolSerializer.decodeResume(frame.payload);
                    int totalChunks = (int) Math.ceil((double) file.length() / chunkSize);
                    int completedCount = totalChunks - resume.missingChunkIndices.length;
                    bytesTransferred.set(completedCount * (long) chunkSize);
                    reportProgress();
                    sendChunksAsync(resume.missingChunkIndices);
                }
                break;
        }
    }

    // =========================================================================
    // Chunk sending helpers
    // =========================================================================

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
            sendRequestChunks(toRequest.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    private void sendRequestChunks(int[] indices) {
        connectionManager.sendMessage(ProtocolSerializer.encodeRequestChunks(indices).encode());
    }

    private void sendChunksAsync(int[] indices) {
        CompletableFuture.runAsync(() -> {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 FileChannel channel = raf.getChannel()) {
                // Track when we last injected an inline heartbeat so that the receiver
                // never goes silent for > HEARTBEAT_INTERVAL_MS even while we are
                // saturating the DataChannel with chunk data.
                long lastInlineHeartbeatMs = System.currentTimeMillis();

                for (int index : indices) {
                    long offset = (long) index * chunkSize;
                    long size   = Math.min(chunkSize, file.length() - offset);
                    if (size <= 0) continue;

                    ByteBuffer buffer = ByteBuffer.allocate((int) size);
                    channel.read(buffer, offset);
                    byte[] chunkData = buffer.array();
                    byte[] sha256    = ChunkingEngine.calculateSha256(chunkData);

                    // Back-pressure: wait if the DataChannel send buffer is getting full
                    while (connectionManager.getBufferedAmount() > 2 * 1024 * 1024) {
                        Thread.sleep(10);
                    }

                    // Inline heartbeat: inject a HEARTBEAT before the chunk if we have
                    // been silent for HEARTBEAT_INTERVAL_MS. This keeps the receiver's
                    // watchdog alive when the DataChannel is fully saturated and the
                    // scheduler-based heartbeat cannot get a send slot.
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastInlineHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
                        if (connectionManager.getDataChannelState() == RTCDataChannelState.OPEN) {
                            connectionManager.sendMessage(ProtocolSerializer.encodeHeartbeat().encode());
                        }
                        lastInlineHeartbeatMs = nowMs;
                    }

                    connectionManager.sendMessage(
                            ProtocolSerializer.encodeChunk(index, offset, (int) size, sha256, chunkData).encode());
                }
            } catch (Exception e) {
                System.err.println("sendChunksAsync error: " + e.getMessage());
                try {
                    connectionManager.sendMessage(
                            ProtocolSerializer.encodeError(
                                    e.getMessage() != null ? e.getMessage() : "chunk send error").encode());
                } catch (Exception ignored) {}
                if (sessionError == null) sessionError = e.getMessage();
                releaseLatches();
            }
        });
    }

    // =========================================================================
    // Progress reporting
    // =========================================================================

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
