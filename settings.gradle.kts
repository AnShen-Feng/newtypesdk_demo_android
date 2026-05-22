// newtypesdk_demo_android/settings.gradle.kts
// ============================================================================
// Gradle 设置配置文件（Gradle Settings Configuration）
// ============================================================================
// 此文件是 Gradle 项目的入口配置文件，用于定义：
// 1. 插件管理仓库（pluginManagement）- 决定从哪里下载 Gradle 插件
// 2. 依赖解析管理（dependencyResolutionManagement）- 决定从哪里下载项目依赖
// 3. 项目结构定义（rootProject.name 和 include）
// ============================================================================

// ----------------------------------------------------------------------------
// 插件管理配置
// ----------------------------------------------------------------------------
// 定义 Gradle 插件的下载来源仓库
pluginManagement {
    repositories {
        // Google 仓库 - 包含 Android 相关的 Gradle 插件
        google {
            content {
                // 限制此仓库仅用于下载指定组织下的插件和依赖
                // 这样可以加速构建并避免仓库冲突
                includeGroupByRegex("com\\.android.*")  // Android 相关
                includeGroupByRegex("com\\.google.*")  // Google 相关
                includeGroupByRegex("androidx.*")      // AndroidX 相关
            }
        }
        // Maven Central - 主要的开源库仓库
        mavenCentral()
        // Gradle Plugin Portal - Gradle 官方插件仓库
        gradlePluginPortal()
    }
}

// ----------------------------------------------------------------------------
// 依赖解析管理配置
// ----------------------------------------------------------------------------
// 定义项目所有模块的依赖下载来源仓库
dependencyResolutionManagement {
    // 设置依赖解析模式为 FAIL_ON_PROJECT_REPOS
    // 此模式禁止在模块级别的 build.gradle 中定义仓库，强制统一在此处管理
    // 好处：确保所有模块使用相同的仓库配置，避免依赖来源不一致的问题
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google 仓库 - 包含 AndroidX、Material Components 等 Google 官方库
        google()
        // Maven Central - 主要的开源库仓库，包含大多数第三方库
        mavenCentral()
        // JitPack 仓库 - 用于从 GitHub 等项目获取依赖
        // 常用于获取未在 Maven Central 发布的开源库
        maven { url = uri("https://jitpack.io") }
    }
}

// ----------------------------------------------------------------------------
// 项目结构定义
// ----------------------------------------------------------------------------
// 设置根项目的名称，该名称将用于生成 APK 文件名等
rootProject.name = "newtypesdk_demo_android"

// 包含 app 模块
// Gradle 会查找名为 "app" 的子目录并将其作为模块引入项目
include(":app")

// 包含直接输入 NewTypeConnectionCredential 的独立测试应用模块
include(":directcredentialtest")
