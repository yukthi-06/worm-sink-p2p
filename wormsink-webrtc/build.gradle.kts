dependencies {
    implementation(project(":wormsink-core"))
    implementation(project(":wormsink-protocol"))
    implementation(project(":wormsink-signaling"))
    
    // WebRTC dependency
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:windows-x86_64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:linux-x86_64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:macos-x86_64")
}
