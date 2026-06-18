plugins {
    java
}

allprojects {
    group = "org.wormsink"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        implementation("org.slf4j:slf4j-api:2.0.13")
        implementation("ch.qos.logback:logback-classic:1.5.6")
        implementation("org.slf4j:jul-to-slf4j:2.0.13")
        testImplementation(platform("org.junit:junit-bom:5.10.1"))
        testImplementation("org.junit.jupiter:junit-jupiter")
    }

    tasks.test {
        useJUnitPlatform()
    }
}
