plugins {
    id("com.android.application")
}

android {
    namespace = "com.aliya.hyfilemgr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aliya.hyfilemgr"
        minSdk = 21
        targetSdk = 34
        // 版本号唯一来源：本文件
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ── 签名配置：复用 HY_VQ 的发布密钥 ──
    // 密钥不在仓库内，从工作区 etc/keys 读取；缺失则回退 debug 签名（不影响本地编译）。
    signingConfigs {
        create("release") {
            val ksDir = System.getenv("HY_KEYS_DIR") ?: "/sdcard/Android/haoyou781/etc/keys"
            val ks = file("$ksDir/hyvq-release.jks")
            val passFile = file("$ksDir/.storepass")
            if (ks.exists() && passFile.exists()) {
                storeFile = ks
                storePassword = passFile.readText().trim()
                keyAlias = "hyvq"
                keyPassword = passFile.readText().trim()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // debug 也用发布密钥：避免 DSH 更新重建 debug.keystore 后
            // 每次安装都报 INSTALL_FAILED_UPDATE_INCOMPATIBLE
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // 文件管理内置播放器（从 HY_VQ 剥离）
    implementation("androidx.media3:media3-exoplayer:1.1.1")
    implementation("androidx.media3:media3-ui:1.1.1")

    // 播放器网络数据源（PlayerCore 需要）
    implementation("androidx.media3:media3-datasource-okhttp:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // ZIP 加密解出（从 HY_VQ 剥离）
    implementation("net.lingala.zip4j:zip4j:2.11.5")
}
