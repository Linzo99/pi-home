plugins {
    id("com.android.application")
}

android {
    namespace = "com.sall.pitv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sall.pitv"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        create("pi") {
            storeFile = rootProject.file("pitv.keystore")
            storePassword = "pitvpitv"
            keyAlias = "pitv"
            keyPassword = "pitvpitv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("pi")
        }
        debug {
            signingConfig = signingConfigs.getByName("pi")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
}

dependencies {
    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    // geo-blocked channels through a proxy (HttpURLConnection has no per-request proxy)
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // AC3 / E-AC3 / DTS / MP2 audio, which Android doesn't decode and IPTV films often use
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
