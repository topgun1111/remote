plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The one installable app: a start screen that opens the Controller or the Agent (both are library modules).
android {
    namespace = "com.remotecontrol.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.remotecontrol.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1100
        versionName = "0.11.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
    }
}

dependencies {
    implementation(project(":agent"))
    implementation(project(":controller"))
}
