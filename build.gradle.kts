// newtypesdk_demo_android/build.gradle.kts
// ============================================================================
// 项目级构建配置文件（Project-level Build Configuration）
// ============================================================================
// 此文件是整个 Gradle 项目的根配置文件，用于定义项目级别的插件和配置
// 主要作用：
// 1. 声明 Android 应用程序插件和 Kotlin Android 插件
// 2. 使用 `apply false` 表示这些插件仅在子模块中应用，而不是在此处应用
// 3. 插件版本通过 libs.versions.toml 进行版本目录管理
// ============================================================================
plugins {
    // 声明 Android 应用程序插件（应用于 app 模块）
    // 该插件提供 Android 应用程序构建所需的所有功能和任务
    alias(libs.plugins.android.application) apply false
    
    // 声明 Kotlin Android 插件（应用于 app 模块）
    // 该插件启用 Kotlin 语言支持，使项目能够编译 Kotlin 代码
    alias(libs.plugins.kotlin.android) apply false

    // 声明 Kotlin Serialization 插件（应用于 app 模块）
    // Demo 使用类型安全的 @Serializable 数据类请求客户后端
    alias(libs.plugins.kotlin.serialization) apply false
}
