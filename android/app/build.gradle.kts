import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: create android/keystore.properties (never commit it) with
// storeFile=/path/to/upload-key.jks, storePassword=..., keyAlias=..., keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.phonedrop.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.osbendes.phonedrop"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.4"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("upload") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("upload")
            else signingConfigs.getByName("debug")
        }
    }
    // The Google libraries ship strings for ~80 languages; the app has two
    androidResources {
        localeFilters += listOf("en", "tr")
    }
    // The language can be chosen in the app, so a Play install must carry both languages
    bundle {
        language {
            enableSplit = false
        }
    }
    packaging {
        resources {
            // Only kotlin-reflect reads these, and the app doesn't use it
            excludes += listOf("kotlin/**", "META-INF/*.version", "META-INF/*.kotlin_module", "DebugProbesKt.bin")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // In-app QR scanner from Google Play services (no camera permission needed)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // QR code for the Mac app's download link
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
