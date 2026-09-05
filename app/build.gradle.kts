plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tagcopy.shopeecapture"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tagcopy.shopeecapture"
        minSdk = 30
        targetSdk = 34
        versionCode = 40
        versionName = "1.048"
    }

    // 【2026-09-05新增】固定簽章設定，解決每次GitHub Actions重新編譯apk都用Gradle預設
    // debug key（每次隨機產生）導致簽章不同、只能先解除安裝再重裝、權限跟無障礙服務
    // 授權全部要重設的問題。keystore本身不會進repo（public repo放金鑰極度危險），
    // 是CI跑的時候從GitHub Secrets（KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS/
    // KEY_PASSWORD）動態還原成檔案，透過環境變數KEYSTORE_PATH等傳進來（見
    // .github/workflows/build.yml的「Decode keystore」步驟）。
    // 本機（非CI）如果沒有設定這些環境變數，storeFile等於null，理論上會導致
    // debug/release這兩個buildType沒辦法真的簽出apk——但這個專案一律只透過
    // GitHub Actions編譯，不會在本機用Android Studio建置，所以先不處理「本機也要
    // 能建置」這個情境，之後真的有需要再補退回預設debug簽章的判斷邏輯。
    signingConfigs {
        create("ci") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 【2026-09-05新增】CI跑的是assembleDebug（不是assembleRelease），所以固定
            // 簽章要套用在debug這個buildType上，套在release上不會有作用。
            signingConfig = signingConfigs.getByName("ci")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ci")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
