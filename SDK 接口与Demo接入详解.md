# NewType Android SDK 接口与 Demo 接入详解

本文基于当前 `newtypesdk_demo_android` 工程说明 SDK 的新接入方式。核心原则是：Demo App 自己请求客户后端，SDK 只接收客户后端返回的连接凭证并负责实时音频能力。

## 1. Demo 文件索引

| 文件 | 作用 |
|------|------|
| `app/build.gradle.kts` | 引入 SDK AAR、OkHttp、Serialization 和运行依赖。 |
| `app/src/main/AndroidManifest.xml` | 配置网络权限、麦克风权限、HTTP 明文流量。 |
| `app/src/main/res/xml/network_security_config.xml` | 允许局域网 HTTP 客户后端访问。 |
| `app/src/main/res/layout/activity_main.xml` | Demo 页面输入框、按钮、状态展示区域。 |
| `app/src/main/java/com/newtype/sdkdemo/MainActivity.kt` | App 请求客户后端、创建 SDK client、connect、VAD、状态监听、disconnect。 |
| `newtypesdkcore-release.aar` | NewType Android SDK 核心包。 |

## 2. 整体链路

```text
Android Demo App
  -> customer-backend-demo 或客户自己的后端
  -> NewType backend
  -> 实时音频会话

Android Demo App
  -> NewType SDK connect(NewTypeConnectionCredential)
  -> 实时音频会话、麦克风、VAD、状态流
```

SDK 不再请求客户后端，也不再包含 `login()`、`join()`、`SessionJoinRequest`。客户 App 可以自由定义自己的后端接口、鉴权方式和业务字段。

## 3. Gradle 接入

Demo 使用 Kotlin Serialization 插件，因为 `MainActivity.kt` 中的客户后端请求模型是类型安全 `@Serializable` data class。

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
```

关键依赖：

```kotlin
dependencies {
    implementation(files("../newtypesdkcore-release.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.livekit:livekit-android:2.24.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
}
```

`okhttp` 和 `kotlinx-serialization-json` 是 Demo App 请求 `customer-backend-demo` 使用的示例依赖；客户正式接入时可以换成自己的网络层。

## 4. MainActivity 中用到的 SDK 类

```kotlin
import com.newtype.sdkcore.NewTypeConnectionCredential
import com.newtype.sdkcore.NewTypeSessionClient
import com.newtype.sdkcore.SessionConnectionState
import com.newtype.sdkcore.SessionEvent
import com.newtype.sdkcore.SessionPhase
import com.newtype.sdkcore.VadMode
import com.newtype.sdkcore.vad.VADPreset
```

| 类/枚举 | Demo 中的作用 |
|---------|---------------|
| `NewTypeSessionClient` | SDK 主入口，负责 connect、disconnect、发言控制、状态输出。 |
| `NewTypeConnectionCredential` | App 从客户后端拿到后传给 SDK 的实时会话连接凭证。 |
| `SessionConnectionState` | SDK 输出的完整会话状态，用于渲染 UI。 |
| `SessionEvent` | SDK 输出的一次性事件，例如错误 toast。 |
| `SessionPhase` | 连接阶段枚举，用于控制按钮可用性。 |
| `VadMode` | 发言控制模式：PTT、半自动、全自动。 |
| `VADPreset` | VAD 灵敏度预设：灵敏、自然、儿童。 |

## 5. Demo App 自己请求客户后端

`MainActivity.kt` 内部的 `CustomerBackendApi` 是 Demo 层代码，不属于 SDK。它当前适配 `customer-backend-demo`：

```text
POST /auth/login
POST /app/sessions
POST /app/sessions/{sessionId}/livekit-token
POST /app/sessions/{sessionId}/end
```

Demo 使用这些类型安全模型请求后端：

```kotlin
@Serializable
private data class CustomerLoginRequest(
    val email: String,
    val password: String,
)

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

客户正式接入时，只需要把 `CustomerBackendApi` 和这些请求/响应 data class 替换成自己的接口即可，SDK 无需修改。

## 6. 登录客户后端

Demo 登录发生在 App 层：

```kotlin
private fun loginCustomer() {
    val backend = buildCustomerBackendApi()
    val email = loginEmailInput.text.toString().trim()
    val password = loginPasswordInput.text.toString()

    lifecycleScope.launch {
        runCatching {
            backend.login(email, password)
        }.onSuccess { response ->
            customerAuth = response.toAuthState()
            renderCustomerAuth()
            updateActionButtons(latestState)
        }
    }
}
```

注意：这里不是 SDK 的 `client.login()`。SDK 不再提供登录客户后端的方法。

## 7. 获取连接凭证并 connect

Demo 的连接流程在 `connectSession()`：

```kotlin
private fun connectSession() {
    val auth = customerAuth ?: return

    client?.close()
    client = NewTypeSessionClient.create(this)
    val activeClient = client ?: return

    activeClient.setVadPreset(getCurrentVadPreset())
    activeClient.setVadMode(getCurrentVadMode())
    observeClient(activeClient)

    val request = StartRealtimeSessionRequest(
        appUserId = auth.user.appUserId,
        childName = childNameInput.text.toString().trim().ifBlank { null },
        age = ageInput.text.toString().trim().ifBlank { null },
        grade = gradeInput.text.toString().trim().ifBlank { null },
        topic = topicInput.text.toString().trim().ifBlank { "Open conversation" },
        identity = childNameInput.text.toString().trim().ifBlank { "android-child" },
    )

    lifecycleScope.launch {
        val credential = backend.startRealtimeSession(auth.token, request)
        activeSessionId = credential.sessionId
        activeClient.connect(credential.toSdkCredential())
    }
}
```

`toSdkCredential()` 把客户后端响应转换为 SDK 凭证：

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

## 8. SDK connect 输入

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

App 只要能从自己的后端拿到这些字段，就可以调用 SDK。客户后端接口路径和请求字段不受 SDK 限制。

## 9. 监听 SDK 状态和事件

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

## 10. VAD 和 PTT 控制

VAD 模式切换仍由 SDK 负责：

```kotlin
client?.setVadMode(mode)
client?.setVadPreset(getCurrentVadPreset())
```

PTT 按钮仍然调用 SDK 的发言控制：

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

## 11. 结束会话

Demo 中 SDK 只负责断开实时会话，业务结束上报由 App 自己请求客户后端：

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

页面销毁时释放 SDK：

```kotlin
override fun onDestroy() {
    stateJob?.cancel()
    eventJob?.cancel()
    client?.close()
    client = null
    super.onDestroy()
}
```

## 12. Demo 页面字段与代码对应表

| UI 控件 | id | 代码位置 | 作用 |
|---------|----|----------|------|
| Customer Backend URL | `apiBaseUrlInput` | `buildCustomerBackendApi()` | Demo App 请求客户后端。 |
| 登录邮箱 | `loginEmailInput` | `loginCustomer()` | 传给 Demo App 的客户后端登录接口。 |
| 登录密码 | `loginPasswordInput` | `loginCustomer()` | 传给 Demo App 的客户后端登录接口。 |
| 登录客户后端 | `loginButton` | `bindActions()` / `loginCustomer()` | 触发 App 层登录。 |
| Topic | `topicInput` | `connectSession()` | 传给 App 层客户后端请求模型。 |
| Child Name | `childNameInput` | `connectSession()` | 传给 App 层客户后端请求模型和 identity 默认值。 |
| Age | `ageInput` | `connectSession()` | 传给 App 层客户后端请求模型。 |
| Grade | `gradeInput` | `connectSession()` | 传给 App 层客户后端请求模型。 |
| Connect | `joinButton` | `connectSession()` | 获取连接凭证并调用 SDK connect。 |
| Leave | `leaveButton` | `leaveSession()` | SDK disconnect，并由 App 层通知客户后端结束。 |
| VAD Mode | `vadModeGroup` | `getCurrentVadMode()` / `setVadMode()` | 切换 PTT、半自动、全自动。 |
| VAD Preset | `vadPresetGroup` | `getCurrentVadPreset()` / `setVadPreset()` | 切换灵敏、自然、儿童。 |
| Hold to Talk | `pttButton` | `startSpeaking()` / `stopSpeaking()` | 手动发言控制。 |
| 状态文本 | `statusText` | `renderState()` | 展示连接和 Agent 状态。 |
| Transcript | `transcriptText` | `renderState()` | 展示转录和 AI 回复。 |
| Summary | `summaryText` | `renderState()` | 展示会话总结。 |

## 13. 生产接入建议

1. 客户 App 自己登录自己的账号体系，SDK 不参与业务登录。
2. 客户 App 自己调用客户后端创建会话，可传任意业务字段。
3. 客户后端返回 `NewTypeConnectionCredential` 所需字段给 App。
4. App 调用 `client.connect(...)` 后，SDK 负责实时音频、VAD、状态和事件。
5. 用户结束时调用 `client.disconnect(...)`，业务结束上报由 App 自己调客户后端。
6. 页面销毁时调用 `client.close()`。

## 14. 最小交付清单

客户 App 接入时至少需要完成：

- 在 Gradle 中引入 `newtypesdkcore-release.aar`。
- 添加 `INTERNET` 和 `RECORD_AUDIO` 权限。
- 如果客户后端是 HTTP，配置明文流量。
- 创建 `NewTypeSessionClient`。
- 自己请求客户后端，获取实时会话连接凭证。
- 构造 `NewTypeConnectionCredential`。
- 调用 `client.connect(credential)`。
- collect `client.state` 渲染连接状态、对话转录、会话总结。
- collect `client.events` 展示错误事件。
- 根据产品体验设置 `VadMode` 和 `VADPreset`。
- 用户结束时调用 `disconnect()`。
- 页面销毁时调用 `close()`。
