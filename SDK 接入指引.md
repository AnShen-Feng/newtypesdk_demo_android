# NewType Android SDK 接入指引

## 目录

- [简介](#简介)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [SDK 架构说明](#sdk-架构说明)
- [核心概念](#核心概念)
- [接入步骤](#接入步骤)
- [API 参考](#api-参考)
- [常见问题](#常见问题)

---

## 简介

NewType Android SDK 是一款用于实时语音交互的软件开发工具包，专为儿童教育场景设计。SDK 提供了以下核心功能：

- **实时语音对话**：通过 WebRTC 技术实现低延迟的双向语音通信
- **智能语音活动检测（VAD）**：自动检测用户说话状态，支持多种检测模式
- **会话转录**：实时将语音转换为文字，记录对话内容
- **会话总结**：AI 自动生成会话反馈和改进建议

---

## 环境要求

### 最低系统要求

| 项目 | 要求 |
|------|------|
| Android SDK | API 24 (Android 7.0) 及以上 |
| 编译 SDK | API 36 |
| Java 版本 | Java 11 |
| Kotlin 版本 | 1.9+ |

### 必需权限

在 `AndroidManifest.xml` 中声明以下权限：

```xml
<!-- 网络权限：用于与后端服务通信 -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- 录音权限：用于采集用户语音 -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

### 依赖项

SDK 需要以下第三方库支持：

```kotlin
// Kotlin 协程 - 用于异步编程
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

// OkHttp - 用于网络请求
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

// LiveKit - WebRTC 实时通信
implementation("io.livekit:livekit-android:2.24.0")

// ONNX Runtime - 机器学习推理（VAD）
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

// Kotlinx Serialization - JSON 处理
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
```

---

## 快速开始

### 步骤 1：添加 SDK 依赖

将 `newtypesdkcore-release.aar` 文件放入项目目录，在 `app/build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation(files("../newtypesdkcore-release.aar"))
}
```

### 步骤 2：初始化 SDK

在 Activity 中创建并配置 `NewTypeSessionClient`：

```kotlin
// 创建 SDK 配置
val config = NewTypeConfig(
    apiBaseUrl = "https://newtype.squady.app:11000",
    defaultLiveKitUrl = "wss://livekit.squady.app:11000",
    tokenEndpointPath = "/api/livekit/token",
)

// 创建会话客户端
val client = NewTypeSessionClient.create(this, config)
```

### 步骤 3：加入会话

```kotlin
// 创建加入请求
val request = SessionJoinRequest(
    childName = "Leo",
    age = "9",
    grade = "Grade 3",
    roomName = "speaking-demo",
    identity = "leo-student",
)

// 加入房间
lifecycleScope.launch {
    client.join(request)
}
```

### 步骤 4：监听状态变化

```kotlin
lifecycleScope.launch {
    client.state.collectLatest { state ->
        // 处理状态更新
        when (state.phase) {
            SessionPhase.CONNECTED -> {
                // 已连接，可以开始对话
            }
            SessionPhase.DISCONNECTED -> {
                // 已断开连接
            }
            else -> {}
        }
    }
}
```

### 步骤 5：离开会话

```kotlin
lifecycleScope.launch {
    client.leave("user-leave")
    client.close()
}
```

---

## SDK 架构说明

### 核心组件

```
┌─────────────────────────────────────────────────────────┐
│                    应用层 (Your App)                     │
├─────────────────────────────────────────────────────────┤
│                  NewTypeSessionClient                   │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │  连接管理   │  │  音频处理    │  │  状态管理     │  │
│  └─────────────┘  └──────────────┘  └───────────────┘  │
├─────────────────────────────────────────────────────────┤
│                    底层依赖                              │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │   LiveKit   │  │  ONNX RT    │  │    OkHttp     │  │
│  │  (WebRTC)   │  │   (VAD)     │  │   (Network)   │  │
│  └─────────────┘  └──────────────┘  └───────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 数据流

```
用户语音 → 麦克风采集 → VAD 检测 → 音频编码 → LiveKit → 服务端
                                              ↓
用户界面 ← 状态更新 ← 事件处理 ← 音频解码 ← LiveKit ← AI 语音
```

---

## 核心概念

### SessionPhase（会话阶段）

| 阶段 | 说明 |
|------|------|
| `IDLE` | 空闲状态，未发起连接 |
| `CONNECTING` | 正在连接中 |
| `CONNECTED` | 已连接到房间 |
| `DISCONNECTED` | 已断开连接 |

### VadMode（VAD 模式）

| 模式 | 说明 | 适用场景 |
|------|------|----------|
| `OFF` | 关闭 VAD，使用 PTT 手动模式 | 需要精确控制发言时机 |
| `SEMI_AUTO` | 半自动：VAD 检测开始，手动结束 | 需要控制发言结束时机 |
| `FULL_AUTO` | 全自动：VAD 自动检测和结束 | 自然对话场景 |

### VADPreset（VAD 预设）

| 预设 | 说明 | 适用场景 |
|------|------|----------|
| `SENSITIVE` | 灵敏模式，易触发检测 | 安静环境、轻声说话 |
| `NATURAL` | 自然模式，平衡灵敏度 | 一般环境（默认） |
| `CHILD` | 儿童模式，针对儿童优化 | 儿童用户场景 |

### SessionConnectionState（会话连接状态）

主要属性：

| 属性 | 类型 | 说明 |
|------|------|------|
| `phase` | SessionPhase | 当前会话阶段 |
| `agentStatus` | AgentStatus | Agent 状态信息 |
| `participantCount` | Int | 房间参与者数量 |
| `sessionId` | String? | 会话唯一标识 |
| `micReady` | Boolean | 麦克风是否就绪 |
| `recording` | Boolean | 是否正在录音 |
| `turnBusy` | Boolean | 当前是否被占用（AI 正在说话） |
| `transcript` | List<TranscriptEntry> | 对话转录列表 |
| `summary` | SessionSummary? | 会话总结 |

---

## 接入步骤

### 详细配置流程

#### 1. 项目配置

在 `settings.gradle.kts` 中确保包含以下仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

#### 2. 权限处理

在 Activity 中请求麦克风权限：

```kotlin
private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted ->
    if (!granted) {
        // 处理权限拒绝
        Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_SHORT).show()
    }
}

private fun ensureMicPermission() {
    if (ContextCompat.checkSelfPermission(
            this, 
            Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
```

#### 3. 生命周期管理

正确处理 Activity 生命周期，避免资源泄漏：

```kotlin
class MainActivity : AppCompatActivity() {
    private var client: NewTypeSessionClient? = null
    private var stateJob: Job? = null
    private var eventJob: Job? = null

    override fun onDestroy() {
        // 取消所有监听协程
        stateJob?.cancel()
        eventJob?.cancel()
        
        // 关闭 SDK 客户端，释放资源
        client?.close()
        client = null
        
        super.onDestroy()
    }
}
```

#### 4. 状态监听

监听 SDK 状态和事件：

```kotlin
private fun observeClient(activeClient: NewTypeSessionClient) {
    // 监听状态变化
    stateJob = lifecycleScope.launch {
        activeClient.state.collectLatest { state ->
            renderState(state)
        }
    }
    
    // 监听事件
    eventJob = lifecycleScope.launch {
        activeClient.events.collectLatest { event ->
            when (event) {
                is SessionEvent.Error -> {
                    // 处理错误事件
                    toast(event.message)
                }
                is SessionEvent.Info -> {
                    // 处理信息事件
                }
            }
        }
    }
}
```

---

## API 参考

### NewTypeConfig

SDK 配置类，用于初始化 `NewTypeSessionClient`。

```kotlin
data class NewTypeConfig(
    val apiBaseUrl: String,        // API 基础地址
    val defaultLiveKitUrl: String, // LiveKit WebSocket 地址
    val tokenEndpointPath: String, // Token 获取端点路径
)
```

### NewTypeSessionClient

核心会话客户端类，提供所有 SDK 功能。

#### 工厂方法

```kotlin
companion object {
    fun create(context: Context, config: NewTypeConfig): NewTypeSessionClient
}
```

#### 主要方法

| 方法 | 说明 |
|------|------|
| `suspend fun join(request: SessionJoinRequest)` | 加入会话房间 |
| `suspend fun leave(reason: String)` | 离开会话房间 |
| `fun close()` | 关闭客户端，释放资源 |
| `suspend fun startSpeaking()` | 开始说话（PTT 模式） |
| `suspend fun stopSpeaking()` | 停止说话（PTT 模式） |
| `fun setVadMode(mode: VadMode)` | 设置 VAD 模式 |
| `fun setVadPreset(preset: VADPreset)` | 设置 VAD 预设 |

#### 状态流

```kotlin
val state: StateFlow<SessionConnectionState>  // 状态流
val events: Flow<SessionEvent>                // 事件流
```

### SessionJoinRequest

加入会话请求参数。

```kotlin
data class SessionJoinRequest(
    val childName: String,   // 儿童姓名
    val age: String,         // 年龄
    val grade: String,       // 年级
    val roomName: String,    // 房间名称
    val identity: String,    // 用户唯一标识
)
```

### SessionEvent

会话事件基类。

```kotlin
sealed class SessionEvent {
    data class Error(val message: String) : SessionEvent()
    data class Info(val message: String) : SessionEvent()
}
```

---

## 常见问题

### Q1: 连接失败怎么办？

**检查项：**
1. 确认 `apiBaseUrl` 和 `liveKitUrl` 配置正确
2. 检查网络连接状态
3. 确认 `tokenEndpointPath` 路径正确
4. 查看 Logcat 日志获取详细错误信息

### Q2: VAD 检测不灵敏？

**解决方案：**
1. 切换到 `SENSITIVE` 预设
2. 检查麦克风权限是否已授权
3. 确认麦克风硬件正常工作
4. 在安静环境下测试

### Q3: 如何切换 VAD 模式？

```kotlin
// 切换到 PTT 模式
client.setVadMode(VadMode.OFF)

// 切换到半自动模式
client.setVadMode(VadMode.SEMI_AUTO)

// 切换到全自动模式
client.setVadMode(VadMode.FULL_AUTO)
```

### Q4: 如何处理内存泄漏？

确保在 `onDestroy` 中正确清理资源：

```kotlin
override fun onDestroy() {
    stateJob?.cancel()
    eventJob?.cancel()
    client?.close()
    client = null
    super.onDestroy()
}
```

### Q5: 支持后台运行吗？

SDK 设计为前台运行。如需后台运行，需要：
1. 申请前台服务权限
2. 创建前台服务保持连接
3. 注意电池优化策略

---

## 技术支持

如有问题，请联系技术支持团队或提交 Issue。

**版本信息：**
- SDK 版本：1.0.0
- 更新日期：2026-05-11
