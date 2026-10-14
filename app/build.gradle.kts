import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from /version.properties; CI can override it with -PappVersion=1.2.3 (used by the Release workflow).
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val appVersion: String = (findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() } ?: versionProps.getProperty("VERSION_NAME")
val appVersionParts: List<Int> = appVersion.substringBefore('-').split('.').map { it.toInt() }
require(appVersionParts.size == 3) { "VERSION_NAME must look like MAJOR.MINOR.PATCH, got '$appVersion'" }
val appVersionCode: Int = appVersionParts[0] * 10000 + appVersionParts[1] * 100 + appVersionParts[2]

// The one installable app: a start screen that opens the Controller or the Agent (both are library modules).
android {
    namespace = "com.remotecontrol.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.remotecontrol.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
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
