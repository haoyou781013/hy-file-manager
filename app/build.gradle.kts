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

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}
