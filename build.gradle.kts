import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("io.ktor.plugin") version "3.6.1" // aggiunge il task buildFatJar
    application
}

group = "dev.avvento"
version = "0.2.0"

application {
    mainClass.set("dev.avvento.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-cio")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    // Logger minimale: niente XML da leggere all'avvio, avvio piu' rapido.
    implementation("org.slf4j:slf4j-simple:2.0.20")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host")
}

// Compiliamo per Java 21: e' la versione che useremo nell'immagine Docker.
java {
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

tasks.test {
    useJUnitPlatform()
}
