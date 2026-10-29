plugins {
    id("com.android.application")
}

import java.util.Properties

// whisper.cpp and llama.cpp are git submodules (both share one ggml, taken from llama.cpp)
val thirdParty = rootProject.file("third_party").absolutePath

// The app's own signing key, so every build (mine and CI's) can update the installed app.
// CI gets it from secrets through env vars; locally it lives outside the repo, in ~/.android.
val home = System.getProperty("user.home")
val keystoreFile = file(System.getenv("TRADUCTOR_KEYSTORE") ?: "$home/.android/traductor-release.jks")
val keystorePassword = System.getenv("TRADUCTOR_KEYSTORE_PASSWORD") ?: Properties().run {
    val f = file("$home/.android/traductor-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    getProperty("storePassword")
}

// CI numbers each build, so a newer APK installs as an update
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.dbuezas.traductor"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.dbuezas.traductor"
        minSdk = 28
        targetSdk = 37
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DLLAMA_DIR=$thirdParty/llama.cpp",
                    "-DWHISPER_DIR=$thirdParty/whisper.cpp",
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    signingConfigs {
        if (keystoreFile.exists() && keystorePassword != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "traductor"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // without the app's key (e.g. a fork), fall back to the standard debug key
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
