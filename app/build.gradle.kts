import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Components come from the GitHub release; a debug build can use a local mirror
// instead: put pons.devMirror=http://<host>:<port>/ in local.properties (not committed).
val releaseComponents = "https://github.com/tedin7/pons/releases/download/components/"
val devMirror: String = Properties().run {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
    getProperty("pons.devMirror") ?: releaseComponents
}

android {
    namespace = "io.github.pons"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.pons"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "COMPONENTS_BASE_URL", "\"$devMirror\"")
        }
        release {
            buildConfigField("String", "COMPONENTS_BASE_URL", "\"$releaseComponents\"")
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
