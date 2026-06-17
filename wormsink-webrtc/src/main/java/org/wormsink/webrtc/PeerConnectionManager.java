package org.wormsink.webrtc;

import dev.onvoid.webrtc.*;
import org.wormsink.signaling.SignalingClient;
import org.wormsink.signaling.SessionState;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

public class PeerConnectionManager implements PeerConnectionObserver {

    private final String signalingUrl;
    private final String sessionCode;
    private final boolean isSender;
    private final WebRtcListener listener;

    private PeerConnectionFactory factory;
    private RTCPeerConnection peerConnection;
    private RTCDataChannel dataChannel;
    
    private final SignalingClient signalingClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final List<RTCIceCandidate> localCandidates = Collections.synchronizedList(new ArrayList<>());
    private int polledCandidateIndex = 0;
    private boolean isClosed = false;

    public PeerConnectionManager(String signalingUrl, String sessionCode, boolean isSender, WebRtcListener listener) {
        this.signalingUrl = signalingUrl;
        this.sessionCode = sessionCode;
        this.isSender = isSender;
        this.listener = listener;
        this.signalingClient = new SignalingClient(signalingUrl);
    }

    public void start() throws Exception {
        // Initialize Native WebRTC library
        this.factory = new PeerConnectionFactory();
        
        RTCConfiguration config = new RTCConfiguration();
        RTCIceServer stun1 = new RTCIceServer();
        stun1.urls.add("stun:stun.l.google.com:19302");
        RTCIceServer stun2 = new RTCIceServer();
        stun2.urls.add("stun:stun1.l.google.com:19302");
        config.iceServers.add(stun1);
        config.iceServers.add(stun2);

        this.peerConnection = factory.createPeerConnection(config, this);

        if (isSender) {
            // Sender creates the DataChannel
            RTCDataChannelInit dcConfig = new RTCDataChannelInit();
            dcConfig.ordered = true;
            dcConfig.maxPacketLifeTime = -1;
            dcConfig.maxRetransmits = -1;
            
            this.dataChannel = peerConnection.createDataChannel("wormsinks-data-channel", dcConfig);
            setupDataChannel(this.dataChannel);

            // Create Offer
            createOffer();
        } else {
            // Receiver polls for Offer
            pollForOffer();
        }

        // Start polling for remote ICE candidates
        startIceCandidatePolling();
    }

    private void createOffer() {
        RTCOfferOptions options = new RTCOfferOptions();
        peerConnection.createOffer(options, new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription desc) {
                peerConnection.setLocalDescription(desc, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        try {
                            signalingClient.sendOffer(sessionCode, desc.sdp);
                            // Now start polling for Answer
                            pollForAnswer();
                        } catch (Exception e) {
                            listener.onConnectionStateChange("FAILED: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onFailure(String err) {
                        listener.onConnectionStateChange("FAILED to set local description: " + err);
                    }
                });
            }

            @Override
            public void onFailure(String err) {
                listener.onConnectionStateChange("FAILED to create offer: " + err);
            }
        });
    }

    private void pollForOffer() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (isClosed) return;
            try {
                SessionState session = signalingClient.getSession(sessionCode);
                if (session != null && session.offer != null) {
                    // Stop polling
                    throw new CancellationException("Got offer");
                }
            } catch (CancellationException e) {
                try {
                    SessionState session = signalingClient.getSession(sessionCode);
                    setRemoteOfferAndCreateAnswer(session.offer);
                } catch (Exception ex) {
                    listener.onConnectionStateChange("FAILED to handle remote offer: " + ex.getMessage());
                }
                throw e; // stops scheduler task
            } catch (Exception e) {
                // ignore and retry
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void setRemoteOfferAndCreateAnswer(String sdp) {
        RTCSessionDescription remoteDesc = new RTCSessionDescription(RTCSdpType.OFFER, sdp);
        peerConnection.setRemoteDescription(remoteDesc, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                RTCAnswerOptions options = new RTCAnswerOptions();
                peerConnection.createAnswer(options, new CreateSessionDescriptionObserver() {
                    @Override
                    public void onSuccess(RTCSessionDescription localDesc) {
                        peerConnection.setLocalDescription(localDesc, new SetSessionDescriptionObserver() {
                            @Override
                            public void onSuccess() {
                                try {
                                    signalingClient.sendAnswer(sessionCode, localDesc.sdp);
                                } catch (Exception e) {
                                    listener.onConnectionStateChange("FAILED to send answer: " + e.getMessage());
                                }
                            }

                            @Override
                            public void onFailure(String err) {
                                listener.onConnectionStateChange("FAILED to set local answer: " + err);
                            }
                        });
                    }

                    @Override
                    public void onFailure(String err) {
                        listener.onConnectionStateChange("FAILED to create answer: " + err);
                    }
                });
            }

            @Override
            public void onFailure(String err) {
                listener.onConnectionStateChange("FAILED to set remote offer: " + err);
            }
        });
    }

    private void pollForAnswer() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (isClosed) return;
            try {
                SessionState session = signalingClient.getSession(sessionCode);
                if (session != null && session.answer != null) {
                    throw new CancellationException("Got answer");
                }
            } catch (CancellationException e) {
                try {
                    SessionState session = signalingClient.getSession(sessionCode);
                    RTCSessionDescription remoteDesc = new RTCSessionDescription(RTCSdpType.ANSWER, session.answer);
                    peerConnection.setRemoteDescription(remoteDesc, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            // Remote description set successfully
                        }

                        @Override
                        public void onFailure(String err) {
                            listener.onConnectionStateChange("FAILED to set remote answer: " + err);
                        }
                    });
                } catch (Exception ex) {
                    listener.onConnectionStateChange("FAILED to handle remote answer: " + ex.getMessage());
                }
                throw e; // stops scheduling task
            } catch (Exception e) {
                // ignore
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void startIceCandidatePolling() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (isClosed) return;
            try {
                SessionState session = signalingClient.getSession(sessionCode);
                if (session != null && session.candidates != null) {
                    while (polledCandidateIndex < session.candidates.size()) {
                        SessionState.CandidateData cd = session.candidates.get(polledCandidateIndex++);
                        RTCIceCandidate candidate = new RTCIceCandidate(cd.sdpMid, cd.sdpMLineIndex, cd.candidate);
                        peerConnection.addIceCandidate(candidate);
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void setupDataChannel(RTCDataChannel dc) {
        dc.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
                // Not used
            }

            @Override
            public void onStateChange() {
                listener.onDataChannelStateChange(dc.getState());
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                // Copy the buffer content to process it safely asynchronously
                ByteBuffer dataCopy = ByteBuffer.allocate(buffer.data.remaining());
                dataCopy.put(buffer.data);
                dataCopy.flip();
                listener.onMessageReceived(dataCopy, buffer.binary);
            }
        });
    }

    public void sendMessage(ByteBuffer data) {
        if (dataChannel != null && dataChannel.getState() == RTCDataChannelState.OPEN) {
            try {
                RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(data, true); // Send binary custom packets
                dataChannel.send(buffer);
            } catch (Exception e) {
                listener.onConnectionStateChange("FAILED to send message: " + e.getMessage());
            }
        }
    }

    public long getBufferedAmount() {
        return dataChannel != null ? dataChannel.getBufferedAmount() : 0;
    }

    public RTCDataChannelState getDataChannelState() {
        return dataChannel != null ? dataChannel.getState() : RTCDataChannelState.CLOSED;
    }

    public void close() {
        isClosed = true;
        scheduler.shutdownNow();
        if (dataChannel != null) {
            try {
                dataChannel.close();
            } catch (Exception e) {}
        }
        if (peerConnection != null) {
            try {
                peerConnection.close();
            } catch (Exception e) {}
        }
        if (factory != null) {
            try {
                factory.dispose();
            } catch (Exception e) {}
        }
    }

    // --- PeerConnectionObserver Interface Callbacks ---

    @Override
    public void onSignalingChange(RTCSignalingState state) {
    }

    @Override
    public void onIceConnectionChange(RTCIceConnectionState state) {
        listener.onConnectionStateChange("ICE: " + state.name());
    }

    @Override
    public void onIceConnectionReceivingChange(boolean receiving) {
    }

    @Override
    public void onIceGatheringChange(RTCIceGatheringState state) {
    }

    @Override
    public void onIceCandidate(RTCIceCandidate candidate) {
        try {
            signalingClient.sendCandidate(sessionCode, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex);
        } catch (Exception e) {
            // Queue and retry
            localCandidates.add(candidate);
        }
    }

    @Override
    public void onIceCandidatesRemoved(RTCIceCandidate[] candidates) {
    }

    @Override
    public void onIceCandidateError(RTCPeerConnectionIceErrorEvent event) {
    }

    @Override
    public void onDataChannel(RTCDataChannel dataChannel) {
        if (!isSender) {
            this.dataChannel = dataChannel;
            setupDataChannel(dataChannel);
            listener.onDataChannelStateChange(dataChannel.getState());
        }
    }

    @Override
    public void onRenegotiationNeeded() {
    }

    @Override
    public void onConnectionChange(RTCPeerConnectionState state) {
        listener.onConnectionStateChange("CONNECTION: " + state.name());
    }

    @Override
    public void onTrack(RTCRtpTransceiver transceiver) {
    }
}
