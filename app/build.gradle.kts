plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.violet.box"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.violet.box"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 代码收缩 + 资源收缩：APK 13.1MB -> 3.4MB
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 离线重打包：无原作者签名密钥，改用 debug 签名以便安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = false
    }

    androidResources {
        localeFilters += listOf("zh", "en")
    }

    packaging {
        resources {
            // commons-codec 传递依赖带入的语音匹配数据，项目未使用
            excludes += "org/apache/commons/codec/language/**"
            // kotlinx-coroutines 调试探针，release 无用
            excludes += "DebugProbesKt.bin"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.activity.compose)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.backdrop)
    implementation(libs.capsule)
    implementation(libs.kotlinx.coroutines.android)

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("commons-io:commons-io:2.18.0")

    // Shizuku：以 shell 权限执行传感器限制命令（安全页摇一摇广告防护）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
