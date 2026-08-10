plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 自用正式版：用 debug 签名（不依赖独立 keystore，安装升级与 debug 兼容）
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // 扩展图标（识图/提醒/记录等，P6 悬浮球浮动界面用）
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // 数据存储
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // Room 数据库
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // 后台任务（WorkManager：每日总结/周期轮询）
    implementation(libs.work.runtime.ktx)

    // 网络（OpenAI 兼容客户端）
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // 数学公式渲染（聊天消息 LaTeX → 位图）
    // jlatexmath 1.5 的 POM 声明 kotlin-stdlib:2.3.0，会覆盖项目 Kotlin 2.1.0 的 stdlib
    // （编译器读 2.3.0 元数据不兼容崩溃）；排除后由项目自带 2.1.0 stdlib 提供
    implementation(libs.jlatexmath) {
        exclude(group = "org.jetbrains.kotlin")
    }

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.compose.bom))
}
