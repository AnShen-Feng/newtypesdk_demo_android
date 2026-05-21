# NewType Android SDK 客户接入链路说明

本文面向客户 App 工程师，严格按照当前demo工程实现说明从登录客户后端、创建会话、获取媒体房间连接凭证、连接媒体房间、监听状态、发言控制到结束会话的完整接入链路。

核心边界：

- 客户 App 自己请求客户后端完成登录和业务会话创建。
- 客户后端返回媒体房间连接凭证给 App。
- App 将连接凭证传给 NewType SDK。
- NewType SDK 负责连接媒体房间、维护 WebSocket/实时音频链路、管理麦克风和 VAD、输出状态/转录/总结/错误。

当前 demo 的核心代码在 `app/src/main/java/com/newtype/sdkdemo/MainActivity.kt`，页面布局在 `app/src/main/res/layout/activity_main.xml`。

## 1. 客户需要先准备什么

### 1.1 Android 工程依赖

Demo 在 `app/build.gradle.kts` 中引入本地 AAR 和运行依赖：

```kotlin
dependencies {
    implementation(files("../newtypesdkcore-release.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("io.livekit:livekit-android:2.24.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
}
```

说明：

| 项目 | 说明 |
|------|------|
| `newtypesdkcore-release.aar` | NewType Android SDK 核心包，位于 demo 根目录。 |
| `kotlinx-serialization-json` | Demo App 请求客户后端时使用的 JSON 序列化库，可替换。 |
| `okhttp` | Demo App 请求客户后端时使用的 HTTP 客户端，可替换。 |
| `kotlinx-coroutines-android` | Demo 使用协程和 `lifecycleScope`。 |
| `onnxruntime-android` | SDK VAD 能力运行依赖。 |

### 1.2 系统权限和网络

`AndroidManifest.xml` 已配置：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

如果客户后端是局域网 HTTP，demo 使用：

```xml
<application
    android:usesCleartextTraffic="true"
    android:networkSecurityConfig="@xml/network_security_config">
</application>
```

生产环境建议使用 HTTPS；开发环境如果使用 HTTP，需要按业务域名配置明文流量策略。

Demo 启动时会请求麦克风权限：

```kotlin
private fun ensureMicPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
```

## 2. 整体链路

```text
1. App 展示客户后端地址、邮箱、密码等输入
2. App 调客户后端 POST /auth/login
3. App 保存客户 token 和用户信息
4. App 根据 token 调客户后端 POST /app/sessions 创建业务会话
5. 客户后端直接返回媒体房间凭证，或 App 再调 POST /app/sessions/{sessionId}/livekit-token 获取凭证
6. App 创建 NewTypeSessionClient
7. App 配置 VAD 模式和 VAD 预设
8. App collect SDK state 和 events
9. App 调 client.connect(NewTypeConnectionCredential)
10. SDK 连接媒体房间并维护实时链路
11. App 从 SDK state 中渲染连接阶段、参与者、麦克风、录音、转录、总结
12. 用户说话时 App 调 startSpeaking()/stopSpeaking()，全自动模式可不手动调用
13. 用户离开时 App 调 disconnect(reason) 并通知客户后端结束会话
14. Activity 销毁时 App 调 close() 释放资源
```

注意：当前 demo 不让 SDK 直接登录客户后端或创建业务 session。登录、创建 session、结束 session 都在 demo 的 `CustomerBackendApi` 中由 App 层完成。

## 3. 哪些后端接口可以由客户自定义

Demo 中的客户后端路径、请求字段和响应包装结构只是演示实现，客户正式接入时可以使用自己的后端接口定义。SDK 不关心客户后端的路径叫什么，也不关心客户 App 如何登录、如何创建业务订单或如何组织业务字段；SDK 只关心 App 最终传给 `client.connect(...)` 的连接凭证是否完整。

| Demo 内容 | 客户是否可自定义 | SDK 是否直接依赖 | 说明 |
|-----------|------------------|------------------|------|
| `POST /auth/login` | 可以 | 不依赖 | 客户可使用已有登录体系、手机号登录、OAuth、匿名登录等；只要 App 能拿到后续调用客户后端所需的认证信息。 |
| 登录请求 `email/password` | 可以 | 不依赖 | Demo 只是用邮箱密码演示，生产可替换为客户自己的登录参数。 |
| 登录响应 `token/tokenType/expiresIn/user` | 可以 | 不直接依赖 | Demo 用这些字段保存登录态和展示 UI；SDK 不读取登录响应。 |
| `POST /app/sessions` | 可以 | 不依赖路径 | 客户可改成自己的创建会话接口；App 只需要最终拿到媒体房间连接凭证。 |
| 创建会话请求字段 | 可以 | 不依赖 | `appUserId`、`externalSessionId`、`childName`、`age`、`grade`、`topic`、`interests`、`identity` 是 demo 示例字段，客户可增删或改名。 |
| `POST /app/sessions/{sessionId}/livekit-token` | 可以 | 不依赖路径 | Demo 在创建会话响应没有直接返回凭证时才请求该接口；客户也可以在创建会话接口中一次性返回凭证。 |
| `POST /app/sessions/{sessionId}/end` | 可以 | 不依赖 | 业务结束上报由 App 自己调客户后端；SDK 只负责断开实时会话。 |
| `NewTypeConnectionCredential` | 不可缺字段 | 直接依赖 | 这是 SDK 的连接入参，字段名和含义必须按 SDK 要求构造。 |

客户后端必须最终给 App 提供这些 SDK 连接字段：

| SDK 字段 | 必须 | 说明 |
|----------|------|------|
| `sessionId` | 是 | NewType 会话 ID，用于 SDK 状态、信令和结束流程关联。 |
| `roomName` | 是 | 媒体房间名称。 |
| `connectionUrl` | 是 | 媒体服务器 WebSocket 地址。 |
| `connectionToken` | 是 | App 进入媒体房间的连接凭证。 |
| `identity` | 是 | 当前用户在媒体房间中的身份。 |
| `expiresIn` | 否 | 凭证有效期，demo 透传给 SDK；没有时可传 `null`。 |

也就是说，客户可以完全替换 `CustomerBackendApi`、接口路径、请求/响应 data class 和鉴权方式，但不能省略 `connect(...)` 所需的 `sessionId`、`roomName`、`connectionUrl`、`connectionToken`、`identity`。

## 4. 登录客户后端

UI 控件与代码对应：

| UI | 字段/方法 | 说明 |
|----|-----------|------|
| Customer Backend URL | `apiBaseUrlInput` | 客户后端地址。 |
| 登录邮箱 | `loginEmailInput` | 登录邮箱。 |
| 登录密码 | `loginPasswordInput` | 登录密码。 |
| 登录客户后端 | `loginButton` / `loginCustomer()` | 触发客户后端登录。 |
| 用户信息 | `userInfoText` | 展示登录用户和 token 预览。 |

登录按钮绑定：

```kotlin
private fun bindActions() {
    loginButton.setOnClickListener { loginCustomer() }
}
```

登录实现：

```kotlin
private fun loginCustomer() {
    val backend = buildCustomerBackendApi()
    val email = loginEmailInput.text.toString().trim()
    val password = loginPasswordInput.text.toString()
    if (email.isBlank() || password.isBlank()) {
        toast("请输入登录邮箱和密码")
        return
    }
    loginButton.isEnabled = false
    loginButton.text = "登录中..."
    lifecycleScope.launch {
        runCatching {
            backend.login(email, password)
        }.onSuccess { response ->
            customerAuth = response.toAuthState()
            if (!response.user.displayName.isNullOrBlank()) {
                childNameInput.setText(response.user.displayName)
            }
            renderCustomerAuth()
            updateActionButtons(latestState)
            toast("登录成功")
        }.onFailure {
            customerAuth = null
            renderCustomerAuth()
            updateActionButtons(latestState)
            toast("登录失败：${it.message.orEmpty()}")
        }
    }
}
```

客户后端登录接口：

> 可自定义：`/auth/login`、请求体和响应体都是 demo 为了展示完整流程而定义的示例。客户正式接入时可以使用自己的登录接口，甚至可以不在此页面登录，而是直接复用业务 App 已有登录态。SDK 不直接调用该接口。

```text
POST /auth/login
Content-Type: application/json
Accept: application/json
```

请求体：

```json
{
  "email": "demo@example.com",
  "password": "demo-password-change-me"
}
```

Demo 需要的响应字段：

```json
{
  "token": "customer-token",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "appUserId": "user-123",
    "email": "demo@example.com",
    "displayName": "Leo",
    "created": false
  }
}
```

响应模型：

```kotlin
@Serializable
private data class CustomerLoginResponse(
    val token: String,
    val tokenType: String,
    val expiresIn: Long,
    val user: CustomerLoginUser,
)

@Serializable
private data class CustomerLoginUser(
    val appUserId: String,
    val email: String,
    val displayName: String? = null,
    val created: Boolean = false,
)
```

Demo 保存的登录态：

```kotlin
private data class CustomerAuthState(
    val token: String,
    val expiresIn: Long,
    val user: CustomerAuthUser,
)
```

当前 demo 在创建 session 时使用 `auth.token`，请求头中拼成：

```text
Authorization: Bearer <customer-token>
```

这里的 `customer-token` 只用于 App 请求客户自己的后端。SDK 不读取该 token；SDK 只使用后面构造出来的媒体房间连接凭证。

## 5. 创建会话并获取媒体房间连接凭证

用户点击 Connect 后进入：

```kotlin
joinButton.setOnClickListener { connectSession() }
```

Connect 前 demo 会做这些事：

1. 检查是否已登录。
2. 关闭旧 client：`client?.close()`。
3. 创建新的 SDK client：`NewTypeSessionClient.create(this)`。
4. 设置 VAD 预设和模式。
5. 开始 collect SDK 状态和事件。
6. 调客户后端创建会话并拿到连接凭证。
7. 调 SDK `connect(...)` 进入媒体房间。

核心代码：

```kotlin
private fun connectSession() {
    val auth = customerAuth
    if (auth == null) {
        toast("请先登录客户后端")
        return
    }
    client?.close()
    client = NewTypeSessionClient.create(this)
    val activeClient = client ?: return
    activeClient.setVadPreset(getCurrentVadPreset())
    activeClient.setVadMode(getCurrentVadMode())
    observeClient(activeClient)

    val backend = buildCustomerBackendApi()
    val request = StartRealtimeSessionRequest(
        appUserId = auth.user.appUserId,
        externalSessionId = null,
        childName = childNameInput.text.toString().trim().ifBlank { null },
        age = ageInput.text.toString().trim().ifBlank { null },
        grade = gradeInput.text.toString().trim().ifBlank { null },
        topic = topicInput.text.toString().trim().ifBlank { "Open conversation" },
        interests = emptyList(),
        identity = childNameInput.text.toString().trim().ifBlank { "android-child" },
    )
    lifecycleScope.launch {
        runCatching {
            val credential = backend.startRealtimeSession(auth.token, request)
            activeSessionId = credential.sessionId
            activeClient.connect(credential.toSdkCredential())
        }.onFailure {
            toast("连接失败：${it.message.orEmpty()}")
        }
    }
}
```

### 5.1 创建会话请求

接口：

> 可自定义：`/app/sessions` 是 demo 的客户后端示例路径。客户可以改成自己的创建会话接口，也可以把创建订单、选择课程、风控校验、创建 NewType 会话等逻辑合并在客户自己的后端流程中。SDK 不直接调用该接口。

```text
POST /app/sessions
Authorization: Bearer <customer-token>
Content-Type: application/json
Accept: application/json
```

Demo 请求模型如下，正式接入时可替换为客户自己的请求模型：

```kotlin
@Serializable
private data class StartRealtimeSessionRequest(
    val appUserId: String,
    val externalSessionId: String? = null,
    val childName: String? = null,
    val age: String? = null,
    val grade: String? = null,
    val topic: String,
    val interests: List<String> = emptyList(),
    val identity: String,
)
```

字段说明：

| 字段 | Demo 来源 | 说明 |
|------|-----------|------|
| `appUserId` | 登录返回的 `user.appUserId` | 客户侧用户 ID。 |
| `externalSessionId` | demo 当前传 `null` | 客户业务会话 ID；客户可按业务传入。 |
| `childName` | Child Name | 孩子姓名或昵称，空字符串转 `null`。 |
| `age` | Age | 年龄，空字符串转 `null`。 |
| `grade` | Grade | 年级，空字符串转 `null`。 |
| `topic` | Topic | 主题，空时 demo 默认 `Open conversation`。 |
| `interests` | demo 固定空数组 | 兴趣标签，客户可按业务传。 |
| `identity` | `childName` 或 `android-child` | 当前用户在媒体房间中的身份。 |

这些创建会话请求字段都属于客户 App 与客户后端之间的业务协议，不是 SDK 强制字段。客户可按自己的业务传 `lessonId`、`courseId`、`childId`、订单号、设备信息、风控字段等。客户后端需要基于这些业务字段创建 NewType 会话，并把 SDK 连接所需字段返回给 App。

### 5.2 创建会话响应

Demo 支持两种返回方式。

> 可自定义：创建会话响应的外层结构也可以由客户自定义。Demo 使用 `session + realtime` 或 `session + userToken` 只是为了展示两种常见方式。客户后端可以一次性返回 SDK 连接字段，也可以分两步返回；App 只需要最终映射出 `NewTypeConnectionCredential`。

方式 A：`POST /app/sessions` 直接返回媒体房间连接信息：

```json
{
  "session": {
    "sessionId": "session-123",
    "roomName": "room-123"
  },
  "realtime": {
    "token": "media-room-token",
    "url": "wss://media.example.com",
    "identity": "Leo",
    "roomName": "room-123",
    "expiresIn": 3600
  }
}
```

方式 B：`POST /app/sessions` 返回 `userToken`，App 再请求媒体房间连接凭证：

```json
{
  "session": {
    "sessionId": "session-123",
    "roomName": "room-123"
  },
  "userToken": {
    "token": "customer-user-token",
    "tokenType": "Bearer",
    "expiresIn": 3600
  }
}
```

然后 demo 调用：

```text
POST /app/sessions/{sessionId}/livekit-token
Authorization: Bearer <customer-token>
Content-Type: application/json
Accept: application/json
```

请求体：

```json
{
  "userToken": "customer-user-token"
}
```

响应体：

```json
{
  "token": "media-room-token",
  "url": "wss://media.example.com",
  "identity": "Leo",
  "roomName": "room-123",
  "expiresIn": 3600
}
```

## 6. 传给 SDK 的连接凭证

Demo 把客户后端返回转换为 SDK 入参：

```kotlin
private fun RealtimeConnectionCredentialResponse.toSdkCredential(): NewTypeConnectionCredential {
    return NewTypeConnectionCredential(
        sessionId = sessionId,
        roomName = roomName,
        connectionUrl = connectionUrl,
        connectionToken = connectionToken,
        identity = identity,
        expiresIn = expiresIn,
    )
}
```

然后连接：

```kotlin
activeClient.connect(credential.toSdkCredential())
```

SDK 需要的字段：

| 字段 | 必须 | Demo 来源 | 说明 |
|------|------|-----------|------|
| `sessionId` | 是 | `session.sessionId` | NewType 会话 ID，SDK 用它关联状态、信令和结束流程。 |
| `roomName` | 是 | 凭证或 `session.roomName` | 媒体房间名称，SDK 用它进入正确房间。 |
| `connectionUrl` | 是 | 凭证 `url` | 媒体服务器 WebSocket 地址，SDK 用它建立实时连接。 |
| `connectionToken` | 是 | 凭证 `token` | 进入媒体房间的连接凭证，SDK 用它完成鉴权。 |
| `identity` | 是 | 凭证 `identity` | 当前用户在媒体房间中的身份，需与后端签发凭证时一致。 |
| `expiresIn` | 否 | 凭证 `expiresIn` | 凭证有效期，demo 透传给 SDK；没有时可传 `null`。 |

客户 App 正式接入时可以替换自己的网络层、接口路径和响应结构，只要最终能构造出这个连接凭证并调用 `connect(...)`。如果客户后端返回字段名不同，例如 `wsUrl`、`accessToken`、`room`，App 层需要映射为 SDK 要求的 `connectionUrl`、`connectionToken`、`roomName`。

最小可用响应示例，客户后端字段名可不同，但语义必须完整：

```json
{
  "sessionId": "session-123",
  "roomName": "room-123",
  "connectionUrl": "wss://media.example.com",
  "connectionToken": "media-room-token",
  "identity": "Leo",
  "expiresIn": 3600
}
```

## 7. SDK client 生命周期

Demo 中的生命周期策略：

| 场景 | 代码 | 说明 |
|------|------|------|
| Connect 前 | `client?.close()` | 关闭旧 client，避免复用旧房间/旧状态。 |
| Connect 时 | `NewTypeSessionClient.create(this)` | 创建新的 SDK client。 |
| Connect 前 | `setVadPreset(...)` / `setVadMode(...)` | 连接前先设置发言控制。 |
| Connect 前 | `observeClient(activeClient)` | 先 collect 状态和事件，再 connect。 |
| Leave 时 | `client?.disconnect("user-leave")` | SDK 断开当前媒体房间。 |
| Leave 后 | `backend.endRealtimeSession(...)` | App 通知客户后端业务会话结束。 |
| Activity 销毁 | `client?.close()` | 释放 SDK 资源。 |

`onDestroy()` 中的释放逻辑：

```kotlin
override fun onDestroy() {
    stateJob?.cancel()
    eventJob?.cancel()
    client?.close()
    client = null
    super.onDestroy()
}
```

## 8. 监听 SDK 状态和事件

Demo 使用 Flow 监听：

```kotlin
private fun observeClient(activeClient: NewTypeSessionClient) {
    stateJob?.cancel()
    eventJob?.cancel()
    stateJob = lifecycleScope.launch {
        activeClient.state.collectLatest { renderState(it) }
    }
    eventJob = lifecycleScope.launch {
        activeClient.events.collectLatest { event ->
            when (event) {
                is SessionEvent.Error -> toast(event.message)
                is SessionEvent.Info -> Unit
            }
        }
    }
}
```

建议客户产品优先使用：

- `client.state`：渲染 UI 的主入口，包含连接状态、参与者、麦克风、录音、转录、总结。
- `client.events`：展示一次性错误事件，例如 toast 或弹窗。

## 9. 状态字段怎么用

Demo 使用的 `SessionConnectionState` 字段：

| 字段 | Demo 用途 | 客户接入建议 |
|------|-----------|--------------|
| `phase` | 控制 Login/Connect/Leave/PTT 按钮 | UI 主状态机。 |
| `agentStatus.phase` | 展示 Agent 阶段 | 告诉用户当前是等待、开场、聆听、处理或收尾。 |
| `agentStatus.message` | 展示提示文案 | 可直接作为状态提示。 |
| `participantCount` | 判断 Agent 是否入房 | 大于 1 时 demo 显示“Agent 已入房”。 |
| `sessionId` | 展示当前 session | 作为业务排查 ID。 |
| `micReady` | 展示麦克风是否就绪 | 未就绪时提示授权或重试。 |
| `recording` | 展示是否录音中 | 控制录音动效。 |
| `turnBusy` | 禁用 PTT | 后端正在处理上一轮时避免重复提交。 |
| `transcript` | 渲染对话文本 | 展示孩子转录和 AI 回复。 |
| `summary` | 渲染会话总结 | 结束后展示学习反馈。 |

Demo 状态渲染：

```kotlin
statusText.text = buildString {
    append("phase=")
    append(state.phase.name)
    append("\nagent=")
    append(state.agentStatus.phase.name)
    append(" ")
    append(state.agentStatus.message)
    append("\nparticipants=")
    append(state.participantCount)
    append(" ")
    append(if (state.participantCount > 1) "(Agent 已入房)" else "(等待 Agent 入房...)")
    append("\nsession=")
    append(state.sessionId ?: "-")
    append("\nmode=")
    append(when (getCurrentVadMode()) {
        VadMode.OFF -> "PTT 按下说话"
        VadMode.SEMI_AUTO -> "VAD 半自动"
        VadMode.FULL_AUTO -> "VAD 全自动"
    })
    append("\npreset=")
    append(when (getCurrentVadPreset()) {
        VADPreset.SENSITIVE -> "灵敏"
        VADPreset.NATURAL -> "自然"
        VADPreset.CHILD -> "儿童"
    })
    append("\n\n=== 连接状态 ===")
    append("\n主题：${topicInput.text}")
    append("\n客户后端：${apiBaseUrlInput.text}")
    append("\n用户：")
    append(customerAuth?.user?.displayLabel() ?: "未登录")
    append("\n麦克风：${if (state.micReady) "就绪" else "未就绪"}")
    append("\n录音：${if (state.recording) "进行中" else "待机"}")
}
```

常用阶段：

| `SessionPhase` | 说明 | UI 建议 |
|----------------|------|---------|
| `IDLE` | 空闲或已断开 | 可登录、可 Connect。 |
| `CONNECTING` | 正在连接媒体房间 | 禁用 Connect，显示连接中。 |
| `CONNECTED` | 已连接 | 启用 Leave，按模式允许说话。 |
| `LEAVING` | 正在离开 | 禁用 Leave，显示离开中。 |
| `ERROR` | 错误 | 展示错误，允许用户重试。 |

Demo 按钮控制逻辑：

```kotlin
private fun updateActionButtons(state: SessionConnectionState?) {
    val safeState = state ?: SessionConnectionState()
    val connected = safeState.phase == SessionPhase.CONNECTED
    val currentMode = getCurrentVadMode()
    val loggedIn = customerAuth != null
    loginButton.isEnabled = !connected && safeState.phase != SessionPhase.CONNECTING
    leaveButton.isEnabled = connected
    joinButton.isEnabled = loggedIn && safeState.phase != SessionPhase.CONNECTING && !connected
    pttButton.isEnabled = connected && !safeState.turnBusy && currentMode != VadMode.FULL_AUTO
    vadModeGroup.isEnabled = true
}
```

## 10. 转录和总结

转录渲染：

```kotlin
transcriptText.text = state.transcript.joinToString("\n\n") { entry ->
    val role = if (entry.speaker == "ai") "AI" else "Child"
    val tail = if (entry.meta.isBlank()) "" else "\n${entry.meta}"
    "$role: ${entry.text}$tail"
}.ifBlank { "暂无消息" }
```

转录字段：

| 字段 | 说明 |
|------|------|
| `speaker` | demo 中按字符串判断，`ai` 显示为 AI，其他显示为 Child。 |
| `text` | 文本内容。 |
| `meta` | 补充信息，例如发音或纠错信息。 |

总结渲染：

```kotlin
summaryText.text = state.summary?.let { summary ->
    buildString {
        append(summary.summary)
        append("\n\nDid well: ")
        append(summary.didWell)
        append("\nTip: ")
        append(summary.oneTip)
        append("\nNext: ")
        append(summary.nextTopic)
        append("\nPronunciation: ")
        append(summary.pronunciationFocus)
    }
} ?: "暂无总结"
```

总结字段：

| 字段 | 说明 |
|------|------|
| `summary` | 本次会话总结。 |
| `didWell` | 做得好的地方。 |
| `oneTip` | 一个建议。 |
| `nextTopic` | 下次话题。 |
| `pronunciationFocus` | 发音关注点。 |

## 11. VAD 模式和发言控制

Demo 支持三种模式：

| UI | SDK 枚举 | 行为 |
|----|----------|------|
| PTT | `VadMode.OFF` | 按住按钮开始说话，松开结束。 |
| 半自动 | `VadMode.SEMI_AUTO` | 手动启动一轮，VAD 辅助判断。 |
| 全自动 | `VadMode.FULL_AUTO` | SDK 自动检测开始和结束，demo 禁用 PTT 按钮。 |

切换模式：

```kotlin
vadModeGroup.setOnCheckedChangeListener { _, checkedId ->
    val mode = when (checkedId) {
        R.id.vadModeOff -> VadMode.OFF
        R.id.vadModeSemiAuto -> VadMode.SEMI_AUTO
        R.id.vadModeFullAuto -> VadMode.FULL_AUTO
        else -> VadMode.FULL_AUTO
    }
    client?.setVadMode(mode)
    renderState(latestState)
}
```

预设：

| UI | SDK 枚举 | 建议场景 |
|----|----------|----------|
| 灵敏 | `VADPreset.SENSITIVE` | 安静环境、轻声说话。 |
| 自然 | `VADPreset.NATURAL` | 默认平衡方案。 |
| 儿童 | `VADPreset.CHILD` | 儿童语音和停顿较多场景。 |

切换预设：

```kotlin
vadPresetGroup.setOnCheckedChangeListener { _, _ ->
    client?.setVadPreset(getCurrentVadPreset())
    renderState(latestState)
}
```

PTT 按钮：

```kotlin
pttButton.setOnTouchListener { _, event ->
    val activeClient = client ?: return@setOnTouchListener false
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            lifecycleScope.launch { activeClient.startSpeaking() }
            true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            lifecycleScope.launch { activeClient.stopSpeaking() }
            true
        }
        else -> false
    }
}
```

客户 UI 建议：

- 只有 `phase == CONNECTED` 时允许说话。
- `turnBusy == true` 时禁用 PTT，避免重复提交。
- `VadMode.FULL_AUTO` 时不展示或禁用 PTT。
- `recording == true` 时展示录音动效。

## 12. 结束会话

用户点击 Leave：

```kotlin
leaveButton.setOnClickListener { leaveSession() }
```

Demo 实现：

```kotlin
private fun leaveSession() {
    val sessionId = activeSessionId
    val auth = customerAuth
    val backend = buildCustomerBackendApi()
    lifecycleScope.launch {
        runCatching { client?.disconnect("user-leave") }
        if (!sessionId.isNullOrBlank() && auth != null) {
            runCatching { backend.endRealtimeSession(auth.token, sessionId) }
        }
        activeSessionId = null
    }
}
```

这里分两步：

1. `client?.disconnect("user-leave")`：SDK 断开当前媒体房间。
2. `POST /app/sessions/{sessionId}/end`：App 通知客户后端业务会话结束。

结束接口：

```text
POST /app/sessions/{sessionId}/end
Authorization: Bearer <customer-token>
Content-Type: application/json
Accept: application/json
```

请求体：

```json
{}
```

## 13. 最小接入代码骨架

```kotlin
class SpeakingActivity : AppCompatActivity() {
    private var client: NewTypeSessionClient? = null
    private var stateJob: Job? = null
    private var eventJob: Job? = null
    private var activeSessionId: String? = null
    private var customerToken: String? = null

    private fun start() {
        val backend = CustomerBackendApi(apiBaseUrl = "http://10.0.2.2:8090")
        lifecycleScope.launch {
            runCatching {
                val login = backend.login("demo@example.com", "demo-password-change-me")
                customerToken = login.token

                val activeClient = NewTypeSessionClient.create(this@SpeakingActivity)
                client = activeClient
                activeClient.setVadMode(VadMode.FULL_AUTO)
                activeClient.setVadPreset(VADPreset.CHILD)

                stateJob = lifecycleScope.launch {
                    activeClient.state.collectLatest { state ->
                        // 根据 state.phase / state.participantCount / state.transcript / state.summary 渲染 UI
                    }
                }
                eventJob = lifecycleScope.launch {
                    activeClient.events.collectLatest { event ->
                        if (event is SessionEvent.Error) {
                            Toast.makeText(this@SpeakingActivity, event.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val credential = backend.startRealtimeSession(
                    customerToken = login.token,
                    request = StartRealtimeSessionRequest(
                        appUserId = login.user.appUserId,
                        childName = login.user.displayName ?: "Leo",
                        age = "9",
                        grade = "Grade 3",
                        topic = "speaking",
                        identity = login.user.displayName ?: "android-child",
                    ),
                )
                activeSessionId = credential.sessionId
                activeClient.connect(credential.toSdkCredential())
            }.onFailure { error ->
                Toast.makeText(this@SpeakingActivity, error.message.orEmpty(), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stop() {
        val sessionId = activeSessionId
        val token = customerToken
        val backend = CustomerBackendApi(apiBaseUrl = "http://10.0.2.2:8090")
        lifecycleScope.launch {
            runCatching { client?.disconnect("user-leave") }
            if (!sessionId.isNullOrBlank() && !token.isNullOrBlank()) {
                runCatching { backend.endRealtimeSession(token, sessionId) }
            }
            client?.close()
            client = null
            activeSessionId = null
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        eventJob?.cancel()
        client?.close()
        client = null
        super.onDestroy()
    }
}
```

> 上面的 `CustomerBackendApi`、请求/响应模型请参考 demo 的 `MainActivity.kt`。客户正式接入时可替换为自己的网络层。

## 14. 接入检查清单

- 已引入 `newtypesdkcore-release.aar`。
- 已配置 `INTERNET` 和 `RECORD_AUDIO` 权限。
- HTTP 调试环境已配置明文流量策略，生产使用 HTTPS。
- App 已完成客户自己的登录流程；如使用 demo 后端，可请求 `POST /auth/login` 并保存客户 token。
- App 已完成客户自己的创建会话流程；如使用 demo 后端，可请求 `POST /app/sessions` 创建业务会话。
- 客户后端已返回 SDK 必须字段：`sessionId`、`roomName`、`connectionUrl`、`connectionToken`、`identity`。
- 如客户后端字段名不同，App 已把它们映射为 `NewTypeConnectionCredential`；`expiresIn` 可选。
- 已先 collect `client.state` / `client.events`，再调用 `connect(...)`。
- UI 已基于 `phase`、`participantCount`、`micReady`、`recording`、`turnBusy` 控制展示和按钮。
- 已根据产品场景设置 `VadMode` 和 `VADPreset`。
- 用户离开时调用 `disconnect(reason)`，Activity 销毁时调用 `close()`。

## 15. 常见问题

### 真机连不上客户后端

- Android 模拟器访问电脑本机服务通常使用 `http://10.0.2.2:<port>`，真机需要使用局域网 IP。
- 客户后端需监听 `0.0.0.0`。
- HTTP 调试需要允许明文流量。
- 可先用手机浏览器访问客户后端健康检查地址。

### Connect 后没有 Agent 或没有回复

- 查看 `phase` 是否为 `CONNECTED`。
- 查看 `participantCount` 是否大于 1。
- 查看 `agentStatus.message` 或 `SessionEvent.Error`。
- 查看麦克风权限和 `micReady`。
- PTT 模式下确认按下和松开都调用了 SDK。
- 儿童停顿较长时建议使用 `VADPreset.CHILD`。

### PTT 不可点

Demo 的启用条件是：

```kotlin
connected && !safeState.turnBusy && currentMode != VadMode.FULL_AUTO
```

如果正在连接、上一轮还在处理、或当前为全自动模式，PTT 会被禁用。

### 退出页面后仍占用麦克风

确认 Activity 销毁、重新 Connect 前或业务退出时调用：

```kotlin
client?.close()
```
