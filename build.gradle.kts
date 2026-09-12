plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)

    // Ktor Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // KotlinX
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Cloudstream API jar
    implementation(files("libs/cloudstream-api.jar"))
    implementation("com.github.Blatzar:NiceHttp:0.4.16") {
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "com.squareup.okhttp3")
    }

    // dex2jar (converts .cs3 DEX -> .jar for URLClassLoader)
    implementation(libs.dex2jar)
    implementation(libs.dex.tools)

    // ASM
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
    implementation(libs.asm.analysis)
    implementation(libs.asm.util)

    // Plugin runtime dependencies
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.jackson.module.kotlin)
    implementation("dev.whyoleg.cryptography:cryptography-core:0.4.0")
    implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.4.0")
    implementation("com.uwetrottmann.tmdb2:tmdb-java:2.9.0")
}

application {
    mainClass.set("com.cncverse.stremiobridge.server.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}


