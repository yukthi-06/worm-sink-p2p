package org.wormsink.core;

public interface TransferListener {
    void onTransferStarted(String fileName, long fileSize, String transferId);
    void onTransferCompleted();
    void onTransferFailed(String errorReason);
    void onTransferResumed(long bytesAlreadyTransferred);
}
