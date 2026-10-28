plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.pinguplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.pinguplay"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            // Dev mirror on the build machine, reachable over Tailscale.
            buildConfigField("String", "COMPONENTS_BASE_URL", "\"http://mirror.example:8765/\"")
        }
        release {
            buildConfigField("String", "COMPONENTS_BASE_URL",
                "\"https://github.com/pinguplay/pinguplay/releases/download/components/\"")
        }
    }

    // proot and its loader are shipped as lib*.so so Android extracts them into
    // nativeLibraryDir, the only app location where executing files is allowed.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        noCompress += "xz"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}
