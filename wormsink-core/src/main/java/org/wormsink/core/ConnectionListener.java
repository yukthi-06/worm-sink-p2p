package org.wormsink.core;

public interface ConnectionListener {
    void onConnecting();
    void onConnected();
    void onDisconnected();
}
