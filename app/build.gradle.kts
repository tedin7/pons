import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Screenshot tests of the launcher: gradle :app:verifyPaparazziDebug (recordPaparazziDebug to update).
    id("app.cash.paparazzi")
}

// Components come from the GitHub release; a debug build can use a local mirror
// instead: put pons.devMirror=http://<host>:<port>/ in local.properties (not committed).
val releaseComponents = "https://github.com/tedin7/pons/releases/download/components/"
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val devMirror: String = localProps.getProperty("pons.devMirror") ?: releaseComponents

// Release signing, only where the key lives (local.properties, not committed):
// pons.signing.storeFile=<keystore>, pons.signing.passwordFile=<file holding the password>.
val signingStore = localProps.getProperty("pons.signing.storeFile")?.let(::file)
val signingPassword = localProps.getProperty("pons.signing.passwordFile")?.let { file(it).readText().trim() }

android {
    namespace = "io.github.pons"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.pons"
        minSdk = 33
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (signingStore != null && signingPassword != null) {
            create("release") {
                storeFile = signingStore
                storePassword = signingPassword
                keyAlias = "pons"
                keyPassword = signingPassword
            }
        }
    }

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
            signingConfig = signingConfigs.findByName("release")
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
