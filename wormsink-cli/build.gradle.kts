plugins {
    application
}

dependencies {
    implementation(project(":wormsink-core"))
    implementation(project(":wormsink-protocol"))
    implementation(project(":wormsink-signaling"))
    implementation(project(":wormsink-webrtc"))
    implementation(project(":wormsink-transfer"))
}

application {
    mainClass.set("org.wormsink.cli.WormSinkCli")
}
