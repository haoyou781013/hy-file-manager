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
        versionCode = 3
        versionName = "0.2.0-beta.2"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // 文件管理内置播放器（从 HY_VQ 剥离）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // 播放器网络数据源（PlayerCore 需要）
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // ZIP 加密解出（从 HY_VQ 剥离）
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    // TAR / TAR.GZ / GZ 真实支持（zip4j 只覆盖 ZIPC/7Z）
    implementation("org.apache.commons:commons-compress:1.20")
    // commons-compress 1.20 把 xz / zstd 设为 optional，不会传递引入。
    // 缺这两个会在 tar.xz / tar.zst 分支抛 NoClassDefFoundError。
    implementation("org.tukaani:xz:1.9")
    implementation("com.github.luben:zstd-jni:1.5.5-11@aar")
    // RAR 解压（RAR 压缩格式专有，无开源压缩实现）
    implementation("com.github.junrar:junrar:7.5.5")
}
