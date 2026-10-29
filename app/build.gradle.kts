plugins {
    id("com.android.application")
}

// whisper.cpp and llama.cpp are git submodules (both share one ggml, taken from llama.cpp)
val thirdParty = rootProject.file("third_party").absolutePath

android {
    namespace = "com.dbuezas.traductor"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.dbuezas.traductor"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
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

    buildTypes {
        release {
            isMinifyEnabled = false
            // the standard debug key (created by Android tooling where missing, e.g. in CI)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
