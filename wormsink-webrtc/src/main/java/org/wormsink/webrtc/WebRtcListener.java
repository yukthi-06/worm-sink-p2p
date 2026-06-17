package org.wormsink.webrtc;

import dev.onvoid.webrtc.RTCDataChannelState;
import java.nio.ByteBuffer;

public interface WebRtcListener {
    void onConnectionStateChange(String state);
    void onDataChannelStateChange(RTCDataChannelState state);
    void onMessageReceived(ByteBuffer data, boolean binary);
}
