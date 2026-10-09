import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val releaseKeyFile = rootProject.file("keystore.properties")
val releaseKey = Properties().apply {
    if (releaseKeyFile.exists()) releaseKeyFile.inputStream().use { load(it) }
}

android {
    namespace = "com.mihior.midash"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.mihior.midash"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    // Keep tracks seekable through AssetManager.openFd without loading MP3s into memory.
    androidResources {
        noCompress += "mp3"
        if (providers.gradleProperty("midash.excludeMusic").orNull == "true") {
            ignoreAssetsPattern = "*.mp3"
        }
    }

    signingConfigs {
        if (releaseKeyFile.exists()) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    testImplementation("org.json:json:20240303")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
