# NewType Android SDK 接入指引

## 简介

NewType Android SDK 用于在 Android 应用中接入儿童英语口语陪练会话。SDK 负责登录客户后端、创建会话、获取媒体房间入房凭证、连接房间、管理麦克风与 VAD，并通过状态流输出转录、AI 回复、会话总结和错误事件。

当前推荐链路：

```text
Android App -> 客户后端 / customer-backend-demo -> NewType backend -> 媒体服务器
```

Android 端不要直连 NewType backend，也不要配置媒体服务器 URL。媒体服务器 URL 和 token 由客户后端按 session 下发。

## 环境要求

| 项目 | 要求 |
|------|------|
| Android SDK | API 24 及以上 |
| compileSdk | API 36 |
| Java | Java 11+ |
| Kotlin | 1.9+ |

必需权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

如果客户后端使用局域网 HTTP，例如 `http://192.168.0.12:8090`，宿主 App 需要允许明文流量：

```xml
<application
    android:usesCleartextTraffic="true"
    android:networkSecurityConfig="@xml/network_security_config">
</application>
```

`res/xml/network_security_config.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

Gradle 依赖：

```kotlin
dependencies {
    implementation(files("../newtypesdkcore-release.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.livekit:livekit-android:2.24.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
}
```

## 快速开始

```kotlin
val client = NewTypeSessionClient.create(
    context = this,
    config = NewTypeConfig(apiBaseUrl = "http://192.168.0.12:8090"),
)

val login = client.login(
    email = "demo@example.com",
    password = "demo-password-change-me",
)

client.setVadMode(VadMode.FULL_AUTO)
client.setVadPreset(VADPreset.NATURAL)

client.join(
    SessionJoinRequest(
        customerToken = login.token,
        appUserId = login.user.appUserId,
        childName = login.user.displayName ?: "Leo",
        age = "9",
        grade = "Grade 3",
        topic = "speaking",
        identity = login.user.displayName ?: "android-child",
    ),
)
```

监听状态和事件：

```kotlin
stateJob = lifecycleScope.launch {
    client.state.collectLatest { state ->
        renderState(state)
    }
}

eventJob = lifecycleScope.launch {
    client.events.collectLatest { event ->
        when (event) {
            is SessionEvent.Error -> showError(event.message)
            is SessionEvent.Info -> Unit
        }
    }
}
```

结束会话：

```kotlin
client.leave("user-leave")
client.close()
```

## 推荐接入流程

```text
1. 创建 NewTypeConfig 和 NewTypeSessionClient
2. 调用 client.login(email, password)
3. 展示 login.user 的用户信息
4. 用户确认 childName / age / grade / topic
5. 调用 client.join(SessionJoinRequest)
6. 监听 client.state 渲染连接状态、转录和总结
7. 按 VAD 模式控制 startSpeaking()/stopSpeaking()
8. 结束时调用 leave()，页面销毁时调用 close()
```

## SDK 接口总览

| 接口 | 作用 | 主要输入 | 主要输出 |
|------|------|----------|----------|
| `NewTypeConfig(...)` | 配置客户后端地址和路径 | 客户后端 URL、可选 token、路径 | 配置对象 |
| `NewTypeSessionClient.create(...)` | 创建 SDK 客户端 | `Context`、`NewTypeConfig` | `NewTypeSessionClient` |
| `client.login(...)` | 登录客户后端 | email、password | `CustomerLoginResponse` |
| `client.join(...)` | 创建会话并加入媒体房间 | `SessionJoinRequest` | 无直接返回，结果走 `state/events` |
| `client.leave(...)` | 结束并离开会话 | reason | 无 |
| `client.startSpeaking()` | 开始一轮发言 | 无 | 无，状态走 `state` |
| `client.stopSpeaking()` | 结束一轮发言 | 无 | 无，状态走 `state` |
| `client.setVadMode(...)` | 设置发言控制模式 | `VadMode` | 无 |
| `client.setVadPreset(...)` | 设置 VAD 预设 | `VADPreset` | 无 |
| `client.setVadOptions(...)` | 设置自定义 VAD 参数 | `VADOptions` | 无 |
| `client.getVadPresetOrNull()` | 获取当前 VAD 预设 | 无 | `VADPreset?` |
| `client.getVadOptions()` | 获取当前 VAD 参数 | 无 | `VADOptions` |
| `client.initializeVAD()` | 手动初始化 VAD | 无 | `Result<Unit>` |
| `client.startVAD()` | 手动启动 VAD 检测 | 无 | 无 |
| `client.stopVAD()` | 手动停止 VAD 检测 | 无 | 无 |
| `client.close()` | 释放 SDK 资源 | 无 | 无 |
| `client.state` | 监听会话状态 | Flow collect | `SessionConnectionState` |
| `client.events` | 监听一次性事件 | Flow collect | `SessionEvent` |

## API 详细说明

### NewTypeConfig

作用：配置 SDK 访问客户后端的地址和接口路径。

```kotlin
data class NewTypeConfig(
    val apiBaseUrl: String,
    val customerAuthToken: String? = null,
    val authEndpointPath: String = "/auth/login",
    val sessionEndpointPath: String = "/app/sessions",
    val liveKitTokenEndpointPathTemplate: String = "/app/sessions/{sessionId}/livekit-token",
)
```

| 字段 | 类型 | 必填 | 默认值 | 含义 |
|------|------|------|--------|------|
| `apiBaseUrl` | `String` | 是 | 无 | 客户后端基础地址，例如 `http://192.168.0.12:8090`。 |
| `customerAuthToken` | `String?` | 否 | `null` | 已有 customer JWT。传入后 `join()` 可直接使用。 |
| `authEndpointPath` | `String` | 否 | `/auth/login` | 客户后端登录路径。 |
| `sessionEndpointPath` | `String` | 否 | `/app/sessions` | 客户后端会话基础路径。 |
| `liveKitTokenEndpointPathTemplate` | `String` | 否 | `/app/sessions/{sessionId}/livekit-token` | 获取媒体服务器 token 的路径模板。 |

注意：这里不配置媒体服务器 URL。媒体服务器 URL 必须由客户后端返回。

### NewTypeSessionClient.create

作用：创建 SDK 主客户端。

```kotlin
fun create(context: Context, config: NewTypeConfig): NewTypeSessionClient
```

| 参数 | 类型 | 含义 |
|------|------|------|
| `context` | `Context` | Android Context，SDK 内部会使用 `applicationContext`。 |
| `config` | `NewTypeConfig` | SDK 配置对象。 |

返回：`NewTypeSessionClient`。

### client.login

作用：登录客户后端，获取 customer JWT 和客户侧用户信息。推荐先登录，再手动调用 `join()`。

```kotlin
suspend fun login(email: String, password: String): CustomerLoginResponse
```

入参：

| 参数 | 类型 | 含义 |
|------|------|------|
| `email` | `String` | 登录邮箱，SDK 会自动 `trim()`。 |
| `password` | `String` | 登录密码。 |

出参：

```kotlin
@Serializable
data class CustomerLoginResponse(
    val token: String,
    val tokenType: String,
    val expiresIn: Long,
    val user: CustomerLoginUser,
)

@Serializable
data class CustomerLoginUser(
    val appUserId: String,
    val email: String,
    val displayName: String? = null,
    val created: Boolean = false,
)
```

| 字段 | 含义 |
|------|------|
| `token` | customer JWT，后续创建 session 使用。 |
| `tokenType` | token 类型，通常为 `Bearer`。 |
| `expiresIn` | token 有效期，当前 demo 语义为秒。 |
| `user.appUserId` | 客户侧用户 ID。 |
| `user.email` | 用户邮箱。 |
| `user.displayName` | 用户显示名，可作为默认 `childName` 和 `identity`。 |
| `user.created` | 是否本次登录创建了新用户。 |

异常：网络失败、HTTP 非 2xx、响应 JSON 不匹配时会抛出异常。

### client.join

作用：创建 NewType 会话、获取媒体服务器 token、连接媒体房间，并发送 `session.ready`。

```kotlin
suspend fun join(request: SessionJoinRequest)
```

入参：

```kotlin
data class SessionJoinRequest(
    val customerToken: String? = null,
    val email: String? = null,
    val password: String? = null,
    val appUserId: String? = null,
    val externalSessionId: String? = null,
    val childName: String,
    val age: String,
    val grade: String,
    val topic: String = "Open conversation",
    val interests: List<String> = emptyList(),
    val identity: String? = null,
)
```

| 字段 | 类型 | 建议 | 含义 |
|------|------|------|------|
| `customerToken` | `String?` | 推荐填写 | `login()` 返回的 customer JWT。 |
| `email` | `String?` | 不推荐 | 无 token 时可隐式登录。生产建议先调用 `login()`。 |
| `password` | `String?` | 不推荐 | 隐式登录密码。 |
| `appUserId` | `String?` | 推荐填写 | 客户侧用户 ID。 |
| `externalSessionId` | `String?` | 可选 | 客户业务系统自己的会话 ID。 |
| `childName` | `String` | 必填 | 孩子姓名或显示名。空字符串会作为 `null` 传给后端。 |
| `age` | `String` | 必填 | 年龄，例如 `9`。空字符串会作为 `null` 传给后端。 |
| `grade` | `String` | 必填 | 年级，例如 `Grade 3`。空字符串会作为 `null` 传给后端。 |
| `topic` | `String` | 推荐填写 | 产品/场景/会话主题。Web demo 常用 `speaking`。 |
| `interests` | `List<String>` | 可选 | 兴趣标签。 |
| `identity` | `String?` | 可选 | 媒体房间 participant identity。 |

认证解析优先级：

```text
request.customerToken -> config.customerAuthToken -> request.email + request.password
```

执行过程：

1. `POST /app/sessions` 创建 session。
2. `POST /app/sessions/{sessionId}/livekit-token` 获取媒体服务器 URL/token。
3. 连接媒体服务器。
4. 发布 `session.ready` 到 `newtype.control.v1`。
5. 通过 `state` 输出连接状态和会话数据。

返回：无直接返回值。成功或失败通过 `state` 和 `events` 观察。

### client.leave

作用：发送结束信令、通知客户后端结束 session，并断开媒体房间。

```kotlin
suspend fun leave(reason: String)
```

| 参数 | 类型 | 含义 |
|------|------|------|
| `reason` | `String` | 离开原因，例如 `user-leave`。 |

返回：无。

### client.startSpeaking

作用：开始一轮用户发言。

```kotlin
suspend fun startSpeaking()
```

| 当前模式 | 行为 |
|----------|------|
| `VadMode.OFF` | PTT 模式，打开麦克风并发送 `turn.start`。 |
| `VadMode.SEMI_AUTO` | 开始半自动 VAD 检测，检测到语音后发送 `turn.start`。 |
| `VadMode.FULL_AUTO` | 通常无需手动调用。 |

返回：无。状态通过 `state.recording` 和 `state.agentStatus` 输出。

### client.stopSpeaking

作用：结束一轮用户发言。

```kotlin
suspend fun stopSpeaking()
```

| 当前模式 | 行为 |
|----------|------|
| `VadMode.OFF` | PTT 模式，发送 `turn.stop` 并关闭麦克风。 |
| `VadMode.SEMI_AUTO` | 停止半自动 VAD 并发送 `turn.stop`。 |
| `VadMode.FULL_AUTO` | 通常无需手动调用。 |

返回：无。状态通过 `state.recording`、`state.turnBusy` 输出。

### client.setVadMode

作用：设置发言控制模式。

```kotlin
fun setVadMode(mode: VadMode)
```

```kotlin
enum class VadMode {
    OFF,
    SEMI_AUTO,
    FULL_AUTO,
}
```

| 值 | 含义 | 适用场景 |
|----|------|----------|
| `OFF` | 关闭 VAD，使用 PTT。 | 需要手动控制开始/结束。 |
| `SEMI_AUTO` | 手动启动一轮，VAD 检测开始，手动结束。 | 噪声环境或需要手动收口。 |
| `FULL_AUTO` | VAD 自动检测开始和结束。 | 自然对话。 |

### client.setVadPreset

作用：设置内置 VAD 灵敏度预设。

```kotlin
fun setVadPreset(preset: VADPreset)
```

```kotlin
enum class VADPreset {
    SENSITIVE,
    NATURAL,
    CHILD,
}
```

| 值 | 含义 |
|----|------|
| `SENSITIVE` | 更容易触发，适合安静环境和轻声说话。 |
| `NATURAL` | 平衡灵敏度，默认推荐。 |
| `CHILD` | 对儿童声音和停顿更宽容。 |

### client.setVadOptions

作用：设置自定义 VAD 参数。调用后当前 preset 会变为 `null`。

```kotlin
fun setVadOptions(options: VADOptions)
```

```kotlin
data class VADOptions(
    val sampleRate: Int = 16000,
    val positiveSpeechThreshold: Float = 0.5f,
    val negativeSpeechThreshold: Float = 0.35f,
    val minSpeechFramesMs: Int = 100,
    val minSilenceFramesMs: Int = 500,
    val speechPadMs: Int = 100,
    val speechPreRollMs: Int = 400,
)
```

| 字段 | 含义 |
|------|------|
| `sampleRate` | VAD 目标采样率，SDK 内部标准化为 `16000`。 |
| `positiveSpeechThreshold` | 开始说话阈值，越低越容易触发。 |
| `negativeSpeechThreshold` | 停止说话阈值。 |
| `minSpeechFramesMs` | 最短有效语音时长。 |
| `minSilenceFramesMs` | 判定一轮结束所需静音时长。 |
| `speechPadMs` | 尾部保留音频时长。 |
| `speechPreRollMs` | 起始前预录音时长，避免吞字。 |

### client.getVadPresetOrNull

作用：获取当前 VAD 预设。

```kotlin
fun getVadPresetOrNull(): VADPreset?
```

返回：`VADPreset` 表示使用内置预设；`null` 表示使用自定义 `VADOptions`。

### client.getVadOptions

作用：获取当前生效的 VAD 参数。

```kotlin
fun getVadOptions(): VADOptions
```

返回：当前 `VADOptions`。

### client.initializeVAD

作用：手动初始化 VAD 模型。通常不需要业务层调用，SDK 在需要时会自动初始化。

```kotlin
suspend fun initializeVAD(): Result<Unit>
```

返回：成功为 `Result.success(Unit)`，失败为 `Result.failure(Throwable)`。

### client.startVAD / client.stopVAD

作用：高级接口，手动启停 VAD 检测。一般推荐使用 `setVadMode()`、`startSpeaking()`、`stopSpeaking()`。

```kotlin
fun startVAD()
fun stopVAD()
```

### client.setVadEnabled

作用：旧版兼容接口，已废弃。

```kotlin
@Deprecated("Use setVadMode instead")
fun setVadEnabled(enabled: Boolean)
```

等价于：

```kotlin
setVadMode(if (enabled) VadMode.FULL_AUTO else VadMode.OFF)
```

### client.close

作用：释放 SDK 资源。

```kotlin
fun close()
```

行为：停止 VAD、释放 ONNX / VAD 资源、断开媒体房间、取消内部协程。应在 Activity / Fragment 销毁时调用。

## 状态和事件模型

### client.state

```kotlin
val state: StateFlow<SessionConnectionState>
```

`SessionConnectionState`：

| 字段 | 类型 | 含义 |
|------|------|------|
| `phase` | `SessionPhase` | 当前连接阶段。 |
| `participantCount` | `Int` | 媒体房间参与者数量。 |
| `sessionId` | `String?` | 当前 NewType session ID。 |
| `transcript` | `List<TranscriptEntry>` | 对话转录列表。 |
| `summary` | `SessionSummary?` | 会话总结。 |
| `context` | `SessionContextSummary` | 当前会话上下文摘要。 |
| `agentStatus` | `AgentStatus` | Agent 状态和提示文案。 |
| `turnBusy` | `Boolean` | AI/后端是否正在处理当前 turn。 |
| `micReady` | `Boolean` | 麦克风是否已初始化可用。 |
| `recording` | `Boolean` | 当前是否正在采集一轮用户发言。 |
| `customerToken` | `String?` | 当前 session 使用的 customer JWT，仅用于 SDK 内部结束 session。 |

### SessionPhase

```kotlin
enum class SessionPhase {
    IDLE,
    REQUESTING_TOKEN,
    CONNECTING,
    CONNECTED,
    LEAVING,
    ERROR,
}
```

| 值 | 含义 |
|----|------|
| `IDLE` | 空闲或已断开。 |
| `REQUESTING_TOKEN` | 正在创建 session / 获取媒体服务器 token。 |
| `CONNECTING` | 正在连接媒体服务器。 |
| `CONNECTED` | 已连接并可对话。 |
| `LEAVING` | 正在离开。 |
| `ERROR` | 发生错误。 |

### AgentStatus / AgentPhase

```kotlin
@Serializable
data class AgentStatus(
    val phase: AgentPhase,
    val message: String,
)
```

| `AgentPhase` | 含义 |
|--------------|------|
| `WAITING` | 等待用户或等待下一步。 |
| `OPENING` | Agent 正在或已经发送开场。 |
| `LISTENING` | 正在听用户说话。 |
| `PROCESSING` | 正在处理用户发言或生成回复。 |
| `CLOSING` | 正在收尾或生成总结。 |
| `ERROR` | Agent 或链路发生错误。 |

### TranscriptEntry

| 字段 | 类型 | 含义 |
|------|------|------|
| `id` | `String` | 本地生成的转录条目 ID。 |
| `speaker` | `String` | `child` 或 `ai`。 |
| `text` | `String` | 文本内容。 |
| `meta` | `String` | 补充信息，例如 IPA 或纠错候选。 |
| `streaming` | `Boolean` | 是否为 AI 流式回复中的临时文本。 |

### SessionSummary

| 字段 | 类型 | 含义 |
|------|------|------|
| `summary` | `String` | 本次会话一句话总结。 |
| `didWell` | `String` | 正向反馈。 |
| `learnedSentences` | `List<String>` | 本次学到的可复用句子。 |
| `oneTip` | `String` | 一个轻量建议。 |
| `nextTopic` | `String` | 下次建议话题。 |
| `pronunciationFocus` | `String` | 发音关注点。 |

### client.events

```kotlin
val events: SharedFlow<SessionEvent>
```

```kotlin
sealed class SessionEvent {
    data class Info(val message: String) : SessionEvent()
    data class Error(val message: String) : SessionEvent()
}
```

| 事件 | 含义 |
|------|------|
| `Info` | 一次性提示事件，当前 SDK 较少使用。 |
| `Error` | 一次性错误事件，可用于 toast 或错误弹窗。 |

## 数据模型参考

### SessionRecord

`SessionRecord` 是客户后端 / NewType backend 返回的会话记录，SDK 主要在内部使用，业务侧可用于理解 session 数据结构。

| 字段 | 类型 | 含义 |
|------|------|------|
| `sessionId` | `String` | 会话 ID。 |
| `roomName` | `String` | 媒体房间名称。 |
| `customerId` | `String` | 客户 ID。 |
| `productId` | `String` | 产品 ID。 |
| `externalUserId` | `String` | 客户侧映射后的用户 ID。 |
| `externalSessionId` | `String?` | 客户侧会话 ID。 |
| `status` | `SessionStatus` | `ACTIVE` 或 `ENDED`。 |
| `childName` / `age` / `grade` | `String?` | 孩子基础信息。 |
| `topic` | `String` | 产品/会话主题。 |
| `interests` | `List<String>` | 兴趣标签。 |
| `turns` | `List<StoredTurn>` | 历史 turn。 |
| `summary` | `SessionSummary?` | 总结。 |
| `profileMemory` | `StudentProfileMemory?` | 用户画像记忆。 |
| `sessionMemory` | `PersistentSessionMemory?` | 学习记忆。 |
| `agentMemory` | `AgentMemorySnapshot?` | Agent 配置快照。 |

### StoredTurn

| 字段 | 类型 | 含义 |
|------|------|------|
| `id` | `String` | turn ID。 |
| `speaker` | `Speaker` | `CHILD` 或 `AI`。 |
| `text` | `String` | turn 文本。 |
| `language` | `LanguageMode` | `EN` / `ZH` / `MIXED` / `UNKNOWN`。 |
| `createdAt` | `String` | 创建时间。 |
| `asr` | `AsrResult?` | ASR 结果。 |
| `pronunciation` | `PronunciationAssessment?` | 发音分析。 |
| `audioPath` | `String?` | 服务端归档音频路径。 |

## 常见问题

### 连接客户后端失败

检查：

1. `apiBaseUrl` 是否指向客户后端，例如 `http://192.168.0.12:8090`。
2. 真机和后端机器是否在同一局域网。
3. 客户后端是否监听 `0.0.0.0:8090`，而不是只监听 `localhost`。
4. Android 是否允许 HTTP 明文流量。
5. 手机浏览器是否能打开客户后端健康检查地址。

### Join 后没有声音或没有转录

检查：

1. 麦克风权限是否授予。
2. `state.participantCount` 是否大于 1，表示 Agent 已入房。
3. `state.agentStatus.message` 是否有错误提示。
4. VAD 模式是否符合预期。PTT 模式需要按住按钮调用 `startSpeaking()` / `stopSpeaking()`。

### Topic 应该填什么

`topic` 是产品。若与 Web demo 对齐，建议填 `speaking`。之后有别的产品，就填对应的产品ID。

### 生产环境是否可以使用 email/password 隐式登录

不推荐。生产建议由业务 App 自己完成登录，然后将 customer JWT 传给 SDK：

```kotlin
SessionJoinRequest(customerToken = token, ...)
```

或：

```kotlin
NewTypeConfig(apiBaseUrl = baseUrl, customerAuthToken = token)
```

## 版本信息

- SDK 版本：1.0.2
- 更新日期：2026-05-19
