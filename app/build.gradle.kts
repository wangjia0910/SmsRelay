import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 读取 local.properties 里的 release 签名；未配置则回退到 Android 默认 debug 密钥，
// 保证 `assembleRelease` 始终能产出可安装的 apk（自用足够，只是不能上架分发）。
fun releaseSigning(rootDir: File): Map<String, String>? {
    val f = File(rootDir, "local.properties")
    if (!f.exists()) return null
    val p = java.util.Properties()
    f.inputStream().use { p.load(it) }
    val store = p.getProperty("RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() } ?: return null
    return mapOf(
        "storeFile" to store,
        "storePassword" to (p.getProperty("RELEASE_STORE_PASSWORD") ?: ""),
        "keyAlias" to (p.getProperty("RELEASE_KEY_ALIAS") ?: "smsrelay"),
        "keyPassword" to (p.getProperty("RELEASE_KEY_PASSWORD") ?: "")
    )
}

android {
    namespace = "com.lazy.smsrelay"
    // compileSdk 锁 36：截至 2026-08-31，官方 SDK 稳定仓库只有 platform-36，
    // android-37(API 37) 尚未进入稳定源，compileSdk=37 在本地与 CI 都无法编译。
    // 运行时在 Android 17 设备上以 targetSdk=36 兼容行为运行，且不会触发 OTP 3 小时延迟。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lazy.smsrelay"
        minSdk = 26
        // ============================================================
        // 关键决策：targetSdk 锁死在 36（Android 16），不要升到 37！
        // 原因：Android 17 / API 37 起，非「默认短信应用」且非「SMS Retriever
        // 用户」的应用，读取含 OTP 的短信会被延迟 3 小时，SMS_RECEIVED_ACTION
        // 广播同样被扣押 —— 转发验证码这个核心场景会直接失效。
        // 迁移到 37 的前提见 docs/HYPEROS4-适配手册.md 第八章。
        // ============================================================
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        resourceConfigurations += setOf("zh-rCN", "en")
    }

    signingConfigs {
        create("release") {
            val s = releaseSigning(rootDir)
            if (s != null) {
                storeFile = rootProject.file(s["storeFile"]!!)
                storePassword = s["storePassword"]
                keyAlias = s["keyAlias"]
                keyPassword = s["keyPassword"]
            } else {
                // 回退到 AGP 自动生成的 debug 密钥（~/.android/debug.keystore）
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 未配置签名时自动回退 debug 密钥，保证一定能出可安装 apk
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // -------- AndroidX 基础 --------
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // -------- Compose --------
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // -------- 后台任务 --------
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // -------- 网络 / 序列化 --------
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
