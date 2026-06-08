# NewType Android SDK 接入指引

## 简介

NewType Android SDK 用于在 Android 应用中接入儿童英语口语陪练会话。当前版本的职责边界是：客户 App 自己调用客户后端，拿到实时会话连接凭证后传给 SDK；SDK 只负责实时音频连接、麦克风、VAD、发言控制、状态流和事件流。

```text
Android App -> 客户自己的后端 -> NewType backend -> 实时音频会话
Android App -> NewType SDK connect(连接凭证) -> 实时音频会话
```

SDK 不再替 App 登录客户后端、不再替 App 创建 session、也不再写死客户后端接口路径。

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

如果客户后端使用局域网 HTTP，宿主 App 需要允许明文流量：

```xml
<application
    android:usesCleartextTraffic="true"
    android:networkSecurityConfig="@xml/network_security_config">
</application>
```

## Gradle 依赖

```kotlin
dependencies {
    implementation(files("../newtypesdkcore-release.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Demo App 请求客户后端示例，可替换
    implementation("io.livekit:livekit-android:2.24.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
}
```

`okhttp` 和 `kotlinx-serialization-json` 是 Demo App 请求 `customer-backend-demo` 的示例依赖，不是 SDK 要求客户必须使用的后端请求方式。

## 推荐接入流程

```text
1. App 自己完成登录或使用已有业务登录态
2. App 调客户自己的后端接口，提交 lessonId / childId / courseId / topic 等任意业务字段
3. 客户后端创建 NewType 会话并返回实时会话连接凭证
4. App 创建 NewTypeSessionClient
5. App 设置 VAD 模式和 VAD 预设
6. App 调 client.connect(NewTypeConnectionCredential)
7. App collect client.state 和 client.events 渲染 UI
8. 如需主动打断当前 AI/TTS 回复，可调用 client.interrupt()
9. 用户结束时调用 client.disconnect(reason)，页面销毁时调用 client.close()
10. 如需业务结束上报，App 自己调用客户后端结束接口
```

## 快速开始

```kotlin
val backendCredential = customerApi.startSpeakingSession(
    lessonId = lessonId,
    childId = childId,
    courseId = courseId,
    topic = "speaking",
)

val client = NewTypeSessionClient.create(context = this)

client.setVadMode(VadMode.FULL_AUTO)
client.setVadPreset(VADPreset.CHILD)

client.connect(
    NewTypeConnectionCredential(
        sessionId = backendCredential.sessionId,
        roomName = backendCredential.roomName,
        connectionUrl = backendCredential.connectionUrl,
        connectionToken = backendCredential.connectionToken,
        identity = backendCredential.identity,
        expiresIn = backendCredential.expiresIn,
    ),
)
```

监听状态和事件：

```kotlin
stateJob = lifecycleScope.launch {
    client.state.collectLatest { state -> renderState(state) }
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
client.disconnect("user-leave")
client.close()
customerApi.endSpeakingSession(sessionId) // 如需业务上报，由 App 自己调用
```

主动打断当前 AI/TTS 回复：

```kotlin
client.interrupt()
```

## SDK 接口总览

| 接口 | 作用 | 主要输入 | 主要输出 |
|------|------|----------|----------|
| `NewTypeSessionClient.create(...)` | 创建 SDK 客户端 | `Context`、可选 `NewTypeConfig` | `NewTypeSessionClient` |
| `client.connect(...)` | 使用 App 已获取的连接凭证进入实时会话 | `NewTypeConnectionCredential` | 无直接返回，结果走 `state/events` |
| `client.disconnect(...)` | 发送结束信令并断开实时会话 | reason | 无 |
| `client.interrupt()` | 主动打断当前 AI/TTS 回复 | 无 | 无 |
| `client.startSpeaking()` | 开始一轮发言 | 无 | 无，状态走 `state` |
| `client.stopSpeaking()` | 结束一轮发言 | 无 | 无，状态走 `state` |
| `client.setVadMode(...)` | 设置发言控制模式 | `VadMode` | 无 |
| `client.setVadPreset(...)` | 设置 VAD 预设 | `VADPreset` | 无 |
| `client.setVadOptions(...)` | 设置自定义 VAD 参数 | `VADOptions` | 无 |
| `client.initializeVAD()` | 手动初始化 VAD | 无 | `Result<Unit>` |
| `client.close()` | 释放 SDK 资源 | 无 | 无 |
| `client.state` | 监听会话状态 | Flow collect | `SessionConnectionState` |
| `client.events` | 监听一次性事件 | Flow collect | `SessionEvent` |

SDK 不提供 `login()`、`join()`、`SessionJoinRequest`，也不持有客户后端 base URL。

## NewTypeConnectionCredential

```kotlin
data class NewTypeConnectionCredential(
    val sessionId: String,
    val roomName: String,
    val connectionUrl: String,
    val connectionToken: String,
    val identity: String,
    val expiresIn: Long? = null,
)
```

| 字段 | 含义 |
|------|------|
| `sessionId` | NewType 会话 ID。 |
| `roomName` | 实时会话房间名。 |
| `connectionUrl` | 实时会话连接地址，由客户后端返回给 App。 |
| `connectionToken` | 实时会话连接凭证，由客户后端返回给 App。 |
| `identity` | 当前用户在实时会话中的身份。 |
| `expiresIn` | 凭证有效期，单位秒，可选。 |

## 状态和事件模型

`SessionConnectionState` 常用字段：

| 字段 | 含义 |
|------|------|
| `phase` | 当前连接阶段：`IDLE`、`CONNECTING`、`CONNECTED`、`LEAVING`、`ERROR`。 |
| `participantCount` | 实时会话参与者数量。 |
| `sessionId` | 当前 NewType session ID。 |
| `transcript` | 对话转录列表。 |
| `summary` | 会话总结。 |
| `agentStatus` | Agent 状态和提示文案。 |
| `turnBusy` | AI/后端是否正在处理当前 turn。 |
| `micReady` | 麦克风是否已初始化可用。 |
| `recording` | 当前是否正在采集一轮用户发言。 |

## Demo 说明

本 Demo 为了展示完整接入流程，在 App 层实现了 `CustomerBackendApi`：

```text
POST /auth/login
POST /app/sessions
POST /app/sessions/{sessionId}/livekit-token
POST /app/sessions/{sessionId}/end
```

这些路径只属于 Demo App 对 `customer-backend-demo` 的示例适配，不属于 SDK API。客户正式接入时应替换为自己的后端接口和类型安全请求模型。

当前工程包含两个 demo：

- `app/`：完整客户后端接入示例，演示登录、创建会话、connect、interrupt、disconnect
- `directcredentialtest/`：直接粘贴 `NewTypeConnectionCredential` 的排障示例，演示跳过客户后端直接验证 SDK 连接和 interrupt

## 常见问题

### SDK 是否会请求客户后端？

不会。SDK 只接收 App 传入的 `NewTypeConnectionCredential`，不会请求客户后端，也不会写死客户后端路径。

### 客户如何传自己的业务字段？

客户 App 自己调用客户后端，因此可以自由传入 `lessonId`、`childId`、`courseId`、订单号、设备信息、风控字段、埋点字段等。SDK 不参与这些字段定义。

### 连接后没有声音或没有转录

检查麦克风权限、`state.phase == CONNECTED`、`state.participantCount > 1`、`state.agentStatus.message`，以及 VAD/PTT 模式是否符合预期。

### 如何主动打断 AI 回复

当 `state.phase == CONNECTED` 且 `state.turnBusy == true` 时，可调用 `client.interrupt()`；当前 demo 的两个页面都提供了 `Interrupt` 按钮，并且只会在可打断时启用。

## 版本信息

- SDK 版本：1.0.3
- 更新日期：2026-05-21
