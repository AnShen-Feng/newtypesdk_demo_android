// newtypesdk_demo_android/app/build.gradle.kts
// ============================================================================
// 应用模块构建配置文件（App Module Build Configuration）
// ============================================================================
// 此文件是 app 模块的构建配置文件，用于定义：
// 1. 应用插件（Android Application 和 Kotlin Android）
// 2. Android 特定配置（SDK 版本、包名、编译选项等）
// 3. 项目依赖（第三方库、本地 AAR 文件等）
// ============================================================================

// ----------------------------------------------------------------------------
// 插件声明
// ----------------------------------------------------------------------------
// 应用 Android 应用程序插件 - 提供 Android 应用构建功能
plugins {
    alias(libs.plugins.android.application)
    // 应用 Kotlin Android 插件 - 启用 Kotlin 语言编译支持
    alias(libs.plugins.kotlin.android)
    // 应用 Kotlin Serialization 插件 - Demo 使用类型安全 JSON 模型请求客户后端
    alias(libs.plugins.kotlin.serialization)
}

// ----------------------------------------------------------------------------
// Android 配置块
// ----------------------------------------------------------------------------
// 定义 Android 应用程序的所有构建相关配置
android {
    // 设置应用的包名命名空间（用于 R 文件生成和代码引用）
    // 此命名空间将作为应用的 Java/Kotlin 包名
    namespace = "com.newtype.sdkdemo"
    
    // 编译 SDK 版本配置
    compileSdk {
        // 设置编译时使用的 Android SDK API 级别为 36
        // release() 函数确保使用正式发布的 SDK 版本
        version = release(36)
    }

    // 默认配置块 - 应用于所有构建变体（Build Variants）
    defaultConfig {
        // 应用的唯一标识符（包名），在 Google Play 中必须唯一
        applicationId = "com.newtype.sdkdemo"
        
        // 最低支持的 Android SDK 版本（Android 7.0）
        // 低于此版本的设备无法安装此应用
        minSdk = 24
        
        // 目标 SDK 版本，表示应用已针对此版本进行测试和优化
        targetSdk = 36
        
        // 内部版本号，每次发布更新时必须递增
        versionCode = 1
        
        // 对外显示的版本号，用户可见
        versionName = "1.0"
        
        // 设置 instrumentation 测试运行器
        // 用于运行 Android 单元测试和 UI 测试
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 构建类型配置 - 定义不同构建变体的行为
    buildTypes {
        // release 构建类型配置（发布版本）
        release {
            // 是否启用代码混淆和缩减
            // false 表示不启用，发布时建议设为 true 以减小 APK 体积
            isMinifyEnabled = false
            
            // ProGuard 配置文件
            // 第一个参数使用默认的 ProGuard 配置文件
            // 第二个参数是项目自定义的 ProGuard 规则文件
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Java 编译选项配置
    compileOptions {
        // 设置 Java 源代码兼容性版本为 Java 11
        sourceCompatibility = JavaVersion.VERSION_11
        // 设置 Java 字节码目标版本为 Java 11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Kotlin 编译选项配置
    kotlinOptions {
        // 设置 Kotlin 编译生成的 JVM 字节码版本为 11
        // 必须与 Java 编译选项保持一致
        jvmTarget = "11"
    }
}

// ----------------------------------------------------------------------------
// 依赖配置块
// ----------------------------------------------------------------------------
// 定义项目所需的所有外部依赖和本地库
dependencies {
    // AndroidX Core KTX - 提供 Kotlin 扩展函数，简化 Android 开发
    implementation(libs.androidx.core.ktx)
    
    // AndroidX AppCompat - 提供向后兼容的 Android 支持库
    implementation(libs.androidx.appcompat)
    
    // Material Components - Google Material Design 组件库
    implementation(libs.material)
    
    // AndroidX Activity - 提供 Activity 相关的 Kotlin 扩展
    implementation(libs.androidx.activity)
    
    // AndroidX ConstraintLayout - 强大的布局库，支持复杂 UI 设计
    implementation(libs.androidx.constraintlayout)
    
    // AndroidX Lifecycle Runtime KTX - 生命周期感知的 Kotlin 扩展
    implementation(libs.androidx.lifecycle.runtime.ktx)
    
    // 本地 AAR 文件依赖 - NewType SDK 核心库
    // 该文件位于项目根目录，包含 NewType SDK 的所有核心功能
    implementation(files("../newtypesdkcore-release.aar"))

    // Kotlinx Serialization JSON - Kotlin 官方 JSON 序列化/反序列化库
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    
    // Kotlinx Coroutines Android - Kotlin 协程库，用于异步编程
    // 提供结构化并发和协程上下文管理
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    
    // OkHttp - 高效的 HTTP 客户端库
    // 用于网络请求、连接池管理、缓存等
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // OkHttp Logging Interceptor - OkHttp 日志拦截器
    // 用于在开发阶段打印 HTTP 请求和响应日志
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // LiveKit Android - WebRTC 实时通信 SDK
    // 用于音视频通话、实时数据传输等功能
    implementation("io.livekit:livekit-android:2.24.0")
    
    // ONNX Runtime Android - 机器学习推理引擎
    // 用于在移动端运行 ONNX 格式的机器学习模型（如 VAD 语音活动检测）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // JUnit - Java 单元测试框架
    testImplementation(libs.junit)
    
    // AndroidX JUnit - Android 测试扩展
    androidTestImplementation(libs.androidx.junit)
    
    // Espresso - Android UI 测试框架
    androidTestImplementation(libs.androidx.espresso.core)
}
