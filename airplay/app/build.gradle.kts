plugins {
    id("com.android.application")
}

android {
    namespace = "com.sall.piairplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sall.piairplay"
        // java-airplay uses Java 17+ library APIs such as HexFormat
        minSdk = 34
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
    }

    signingConfigs {
        create("pi") {
            storeFile = rootProject.file("piairplay.keystore")
            storePassword = "piairplay"
            keyAlias = "piairplay"
            keyPassword = "piairplay"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("pi")
        }
        debug {
            signingConfig = signingConfigs.getByName("pi")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets {
        getByName("main") {
            java.srcDirs(
                "src/main/java",
                "../third_party/java-airplay/protocol",
                "../third_party/java-airplay/server",
            )
            // FairPlay tables, read with Class.getResource("/table_s1")
            resources.srcDirs("../third_party/java-airplay/protocol-resources")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/native-image/**",
                "META-INF/versions/**",
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    val netty = "4.2.19.Final"
    implementation("io.netty:netty-buffer:$netty")
    implementation("io.netty:netty-transport:$netty")
    implementation("io.netty:netty-handler:$netty")
    implementation("io.netty:netty-codec-http:$netty")
    // referenced by the control server; Epoll.isAvailable() is false on Android, so NIO is used
    implementation("io.netty:netty-transport-classes-epoll:$netty")
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("com.googlecode.plist:dd-plist:1.30")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.whispersystems:curve25519-java:0.5.0")
    implementation("io.lindstrom:m3u8-parser:0.30")
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")
}
