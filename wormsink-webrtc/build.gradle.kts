dependencies {
    implementation(project(":wormsink-core"))
    implementation(project(":wormsink-protocol"))
    implementation(project(":wormsink-signaling"))
    
    // WebRTC dependency
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
}
