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

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("wormsink")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("fatjar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "org.wormsink.cli.WormSinkCli"
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}
