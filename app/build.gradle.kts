plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.sdarquilab.subvivo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sdarquilab.subvivo"
        minSdk = 29 // 다른 앱 소리 받기(AudioPlaybackCapture)는 안드로이드 10부터
        targetSdk = 34 // 35는 안드로이드 15에서 전체 화면(edge-to-edge)을 강제하므로 34로 둠
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // 서명 키는 저장소에 넣지 않는다. GitHub Actions가 처음 한 번 만들어 Actions 캐시에만 보관하고,
    // 빌드할 때 환경 변수로 넘겨준다 (업데이트 설치가 이어지도록 같은 키 유지).
    val signingStore = System.getenv("SIGNING_STORE")
    val signingPass = System.getenv("SIGNING_PASS")
    signingConfigs {
        create("release") {
            if (signingStore != null && signingPass != null) {
                storeFile = file(signingStore)
                storePassword = signingPass
                keyAlias = "subvivo"
                keyPassword = signingPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingStore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    androidResources {
        noCompress += listOf("onnx")
    }
}
