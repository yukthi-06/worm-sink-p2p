package org.wormsink.core;

public interface ProgressListener {
    void onProgress(long bytesTransferred, long totalBytes, double rateBytesPerSecond, long etaSeconds);
}
