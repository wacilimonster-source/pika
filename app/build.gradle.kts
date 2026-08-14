plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pika"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pika"
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "1.5.16"
    }

    signingConfigs {
        // 使用 Android 默认 debug keystore 签名 release，
        // 与已安装 App 同签名，保证应用内更新可覆盖安装。
        // 注意：默认 debug keystore 的别名/密码为公开默认值，非保密信息。
        create("release") {
            storeFile = file("C:/Users/wacil/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    // BouncyCastle 的 JAR 含多版本(Multi-Release)资源，Android 不需要，排除避免打包冲突
    packaging {
        resources {
            excludes += "/META-INF/versions/**"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    // 自定义 TLS：BouncyCastle 纯 Java 栈，绕过 Cloudflare 对 BoringSSL 的指纹拦截
    implementation(libs.bcprov)
    implementation(libs.bctls)
    implementation(libs.bcutil)
    debugImplementation(libs.androidx.ui.tooling)
}
