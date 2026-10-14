plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.remotecontrol.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.remotecontrol.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1001
        versionName = "0.10.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":shared"))
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}
