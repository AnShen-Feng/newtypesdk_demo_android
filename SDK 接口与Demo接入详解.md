# NewType Android SDK 接口与 Demo 接入详解

本文只基于工程中的代码说明 SDK 的接入方式。客户工程师可以直接对照 demo 文件复制接入逻辑。

本文重点解释：

- Demo 如何引入 `newtypesdkcore-release.aar`
- Demo 如何创建 SDK client、登录、加入会话、监听状态、结束会话
- Demo 中用到的 SDK 接口和数据类分别承担什么职责
- Demo 中每个 UI 字段和具体代码块如何对应

## 1. Demo 文件索引

客户接入时建议按下面顺序阅读 demo 文件：

| 文件 | 作用 |
|------|------|
| `app/build.gradle.kts` | 引入 SDK AAR 和运行依赖。 |
| `app/src/main/AndroidManifest.xml` | 配置网络权限、麦克风权限、HTTP 明文流量。 |
| `app/src/main/res/xml/network_security_config.xml` | 允许局域网 HTTP 客户后端访问。 |
| `app/src/main/res/layout/activity_main.xml` | Demo 页面输入框、按钮、状态展示区域。 |
| `app/src/main/java/com/newtype/sdkdemo/MainActivity.kt` | SDK 调用主流程：登录、Join、VAD、状态监听、Leave、释放资源。 |
| `newtypesdkcore-release.aar` | NewType Android SDK 核心包。 |

## 2. Demo 的整体接入链路

Demo 采用的链路是：

```text
Android Demo App
  -> 客户后端 Customer Backend
  -> NewType backend
  -> 媒体服务器房间
  -> NewType room agent
```

Android 端只配置客户后端地址，例如：

```text
http://192.168.0.12:8090
```

Android 端不需要配置 NewType backend 地址，也不需要配置媒体服务器地址。媒体房间的 `url` 和 `token` 由客户后端在创建 session 后下发给 SDK。

Demo 中的完整流程：

```text
1. 用户填写 Customer Backend URL、邮箱、密码
2. 点击“登录客户后端”
3. SDK 调用 client.login(email, password)
4. Demo 保存登录返回的 customer token 和用户信息
5. 用户填写 childName、age、grade、topic
6. 点击 Join
7. Demo 创建 NewTypeSessionClient
8. Demo 设置 VAD 模式和 VAD 预设
9. Demo 调用 client.join(SessionJoinRequest)
10. SDK 连接媒体房间并发送 session.ready
11. Demo 通过 client.state 渲染连接状态、转录、AI 回复、总结
12. 用户点击 Leave 或页面销毁时释放资源
```

## 3. Gradle 接入

Demo 的依赖配置在 `app/build.gradle.kts`。

关键代码：

```kotlin
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(files("../newtypesdkcore-release.aar"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("io.livekit:livekit-android:2.24.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
}
```

依赖说明：

| 依赖 | 作用 |
|------|------|
| `newtypesdkcore-release.aar` | SDK 核心包，提供 `NewTypeSessionClient`、`NewTypeConfig`、`SessionJoinRequest` 等接口。 |
| `kotlinx-coroutines-android` | Demo 用 `lifecycleScope.launch` 调用 suspend 接口，并 collect Flow 状态。 |
| `kotlinx-serialization-json` | SDK 和后端 JSON 数据结构依赖。 |
| `okhttp` | SDK 网络请求依赖。 |
| `livekit-android` | SDK 内部连接媒体房间、发布麦克风音频。 |
| `onnxruntime-android` | SDK VAD 语音活动检测依赖。 |

Android 配置要求也在 `app/build.gradle.kts`：

```kotlin
android {
    namespace = "com.newtype.sdkdemo"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.newtype.sdkdemo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}
```

接入要求：

| 项目 | 要求 |
|------|------|
| minSdk | 24 或以上 |
| compileSdk | Demo 使用 36 |
| targetSdk | Demo 使用 36 |
| Java/Kotlin JVM target | 11 |

## 4. Manifest 和网络权限

Demo 的权限配置在 `app/src/main/AndroidManifest.xml`。

关键代码：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <application
        android:allowBackup="true"
        android:usesCleartextTraffic="true"
        android:networkSecurityConfig="@xml/network_security_config"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.NewTypeSdkDemo">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

字段说明：

| 配置 | 说明 |
|------|------|
| `INTERNET` | 访问客户后端、连接媒体房间。 |
| `RECORD_AUDIO` | 采集用户麦克风。 |
| `usesCleartextTraffic="true"` | 允许 HTTP 明文流量，适合局域网 demo。生产建议使用 HTTPS。 |
| `networkSecurityConfig` | 指向明文网络配置文件。 |

Demo 的明文网络配置在 `app/src/main/res/xml/network_security_config.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

如果客户后端使用 HTTPS，可以移除明文流量配置；如果客户后端使用局域网 HTTP，例如 `http://192.168.0.12:8090`，需要保留这两处配置。

## 5. MainActivity 中用到的 SDK 类

Demo 在 `MainActivity.kt` 顶部引入了这些 SDK 类：

```kotlin
import com.newtype.sdkcore.CustomerLoginResponse
import com.newtype.sdkcore.NewTypeConfig
import com.newtype.sdkcore.NewTypeSessionClient
import com.newtype.sdkcore.SessionConnectionState
import com.newtype.sdkcore.SessionEvent
import com.newtype.sdkcore.SessionJoinRequest
import com.newtype.sdkcore.SessionPhase
import com.newtype.sdkcore.VadMode
import com.newtype.sdkcore.vad.VADPreset
```

这些类在 demo 中的作用如下：

| 类/枚举 | Demo 中的作用 |
|---------|---------------|
| `NewTypeConfig` | 配置客户后端地址。 |
| `NewTypeSessionClient` | SDK 主入口，负责登录、Join、Leave、发言控制、状态输出。 |
| `CustomerLoginResponse` | 登录客户后端的返回结果，包含 token 和用户信息。 |
| `SessionJoinRequest` | Join 时传给 SDK 的会话参数。 |
| `SessionConnectionState` | SDK 输出的完整会话状态，用于渲染 UI。 |
| `SessionEvent` | SDK 输出的一次性事件，例如错误 toast。 |
| `SessionPhase` | 连接阶段枚举，用于控制按钮可用性。 |
| `VadMode` | 发言控制模式：PTT、半自动、全自动。 |
| `VADPreset` | VAD 灵敏度预设：灵敏、自然、儿童。 |

## 6. Demo 中的成员变量

`MainActivity.kt` 中和 SDK 接入相关的状态：

```kotlin
private var client: NewTypeSessionClient? = null
private var stateJob: Job? = null
private var eventJob: Job? = null
private var latestState: SessionConnectionState = SessionConnectionState()
private var customerAuth: CustomerAuthState? = null
```

说明：

| 变量 | 说明 |
|------|------|
| `client` | 当前会话使用的 SDK client。 |
| `stateJob` | collect `client.state` 的协程 Job。 |
| `eventJob` | collect `client.events` 的协程 Job。 |
| `latestState` | 最近一次 SDK 状态，切换 VAD 或刷新按钮时使用。 |
| `customerAuth` | Demo 自己保存的登录态，包含 customer token 和用户信息。 |

Demo 自己定义了一个简化登录态：

```kotlin
private data class CustomerAuthState(
    val token: String,
    val expiresIn: Long,
    val user: CustomerAuthUser,
)

private data class CustomerAuthUser(
    val appUserId: String,
    val email: String,
    val displayName: String?,
)
```

把 SDK 登录返回转换为 demo 登录态：

```kotlin
private fun CustomerLoginResponse.toAuthState(): CustomerAuthState {
    return CustomerAuthState(
        token = token,
        expiresIn = expiresIn,
        user = CustomerAuthUser(
            appUserId = user.appUserId,
            email = user.email,
            displayName = user.displayName,
        ),
    )
}
```

显示用户名称：

```kotlin
private fun CustomerAuthUser.displayLabel(): String {
    return displayName?.takeIf { it.isNotBlank() } ?: email
}
```

Token 只显示前后几位，避免完整暴露：

```kotlin
private fun CustomerAuthState.tokenPreview(): String {
    val head = token.take(8)
    val tail = token.takeLast(6)
    return if (token.length <= 18) token else "$head...$tail"
}
```

## 7. 创建 SDK 配置：NewTypeConfig

Demo 中通过 `buildConfig()` 创建 SDK 配置：

```kotlin
private fun buildConfig(): NewTypeConfig {
    return NewTypeConfig(apiBaseUrl = apiBaseUrlInput.text.toString().trim())
}
```

这个配置来自页面输入框 `apiBaseUrlInput`。

布局中对应控件在 `app/src/main/res/layout/activity_main.xml`：

```xml
<EditText
    android:id="@+id/apiBaseUrlInput"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="8dp"
    android:hint="Customer Backend URL"
    android:text="http://192.168.0.12:8090" />
```

`NewTypeConfig` 在 demo 中只传了 `apiBaseUrl`：

```kotlin
NewTypeConfig(apiBaseUrl = "http://192.168.0.12:8090")
```

客户后端如果使用默认路径，这样即可。默认路径由 SDK 约定：

| 用途 | 默认路径 |
|------|----------|
| 登录客户后端 | `/auth/login` |
| 创建 session | `/app/sessions` |
| 获取媒体房间 token | `/app/sessions/{sessionId}/livekit-token` |

如果客户后端路径不同，可以在创建 `NewTypeConfig` 时覆盖路径：

```kotlin
val config = NewTypeConfig(
    apiBaseUrl = "https://customer-api.example.com",
    authEndpointPath = "/api/auth/login",
    sessionEndpointPath = "/api/app/sessions",
    liveKitTokenEndpointPathTemplate = "/api/app/sessions/{sessionId}/livekit-token",
)
```

## 8. 登录客户后端：client.login

### 8.1 UI 输入

Demo 的登录输入框在 `activity_main.xml`：

```xml
<EditText
    android:id="@+id/loginEmailInput"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="8dp"
    android:hint="登录邮箱"
    android:inputType="textEmailAddress"
    android:text="demo@example.com" />

<EditText
    android:id="@+id/loginPasswordInput"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="8dp"
    android:hint="登录密码"
    android:inputType="textPassword"
    android:text="demo-password-change-me" />

<Button
    android:id="@+id/loginButton"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="8dp"
    android:text="登录客户后端" />
```

按钮事件绑定在 `bindActions()`：

```kotlin
private fun bindActions() {
    loginButton.setOnClickListener { loginCustomer() }
    joinButton.setOnClickListener { joinSession() }
    leaveButton.setOnClickListener { leaveSession() }
    ...
}
```

### 8.2 登录实现

Demo 登录逻辑在 `loginCustomer()`：

```kotlin
private fun loginCustomer() {
    val config = buildConfig()
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
            val loginClient = NewTypeSessionClient.create(this@MainActivity, config)
            loginClient.login(email, password).also {
                loginClient.close()
            }
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

调用点解释：

| 代码 | 说明 |
|------|------|
| `val config = buildConfig()` | 从页面读取客户后端地址。 |
| `NewTypeSessionClient.create(this@MainActivity, config)` | 创建临时 SDK client。 |
| `loginClient.login(email, password)` | 调用客户后端登录接口。 |
| `loginClient.close()` | 登录完成后释放临时 client。 |
| `customerAuth = response.toAuthState()` | 保存 token 和用户信息，后续 Join 使用。 |
| `childNameInput.setText(response.user.displayName)` | 如果登录返回显示名，自动填到孩子名称。 |
| `renderCustomerAuth()` | 刷新登录态 UI。 |
| `updateActionButtons(latestState)` | 登录成功后启用 Join。 |

### 8.3 登录返回：CustomerLoginResponse

Demo 中没有直接声明 `CustomerLoginResponse` 的结构，但使用了这些字段：

```kotlin
private fun CustomerLoginResponse.toAuthState(): CustomerAuthState {
    return CustomerAuthState(
        token = token,
        expiresIn = expiresIn,
        user = CustomerAuthUser(
            appUserId = user.appUserId,
            email = user.email,
            displayName = user.displayName,
        ),
    )
}
```

由 demo 可见，登录返回至少包含：

| 字段 | Demo 用途 |
|------|-----------|
| `token` | 保存到 `customerAuth.token`，Join 时传给 `SessionJoinRequest.customerToken`。 |
| `expiresIn` | 保存到 demo 登录态，表示 token 有效期。 |
| `user.appUserId` | Join 时传给 `SessionJoinRequest.appUserId`。 |
| `user.email` | 没有 displayName 时作为 UI 展示名。 |
| `user.displayName` | 自动填入 `childNameInput`，也可作为孩子显示名。 |

登录成功后 UI 渲染代码：

```kotlin
private fun renderCustomerAuth() {
    val auth = customerAuth
    userInfoText.text = if (auth == null) {
        "未登录"
    } else {
        buildString {
            append("已登录：")
            append(auth.user.displayLabel())
            append("\n用户 ID：")
            append(auth.user.appUserId)
            append("\nToken：")
            append(auth.tokenPreview())
        }
    }
    loginButton.text = if (auth == null) "登录客户后端" else "重新登录客户后端"
}
```

## 9. 加入会话：client.join

### 9.1 UI 输入

Demo 中 Join 需要这些输入：

```xml
<EditText
    android:id="@+id/topicInput"
    android:hint="Topic"
    android:text="Self introduction and hobbies" />

<EditText
    android:id="@+id/childNameInput"
    android:hint="Child Name"
    android:text="Leo" />

<EditText
    android:id="@+id/ageInput"
    android:hint="Age"
    android:text="9" />

<EditText
    android:id="@+id/gradeInput"
    android:hint="Grade"
    android:text="Grade 3" />

<Button
    android:id="@+id/joinButton"
    android:enabled="false"
    android:text="Join" />
```

Join 按钮默认 disabled，登录成功后才会启用。

### 9.2 Join 实现

Demo Join 逻辑在 `joinSession()`：

```kotlin
private fun joinSession() {
    val auth = customerAuth
    if (auth == null) {
        toast("请先登录客户后端")
        return
    }
    val config = buildConfig()
    client?.close()
    client = NewTypeSessionClient.create(this, config)
    val activeClient = client ?: return
    activeClient.setVadPreset(getCurrentVadPreset())
    activeClient.setVadMode(getCurrentVadMode())
    observeClient(activeClient)
    val request = SessionJoinRequest(
        customerToken = auth.token,
        appUserId = auth.user.appUserId,
        childName = childNameInput.text.toString().trim(),
        age = ageInput.text.toString().trim(),
        grade = gradeInput.text.toString().trim(),
        topic = topicInput.text.toString().trim().ifBlank { "Open conversation" },
        identity = childNameInput.text.toString().trim().ifBlank { "android-child" },
    )
    lifecycleScope.launch {
        runCatching {
            client?.join(request)
        }.onFailure {
            toast("加入失败：${it.message.orEmpty()}")
        }
    }
}
```

调用点解释：

| 代码 | 说明 |
|------|------|
| `val auth = customerAuth` | Join 必须先登录，拿到 token。 |
| `client?.close()` | 如果已有旧 client，先释放。 |
| `NewTypeSessionClient.create(this, config)` | 创建本次会话使用的 SDK client。 |
| `setVadPreset(getCurrentVadPreset())` | 设置 VAD 预设。 |
| `setVadMode(getCurrentVadMode())` | 设置发言控制模式。 |
| `observeClient(activeClient)` | 先监听状态，再 Join，避免漏掉连接过程状态。 |
| `SessionJoinRequest(...)` | 组装会话请求。 |
| `client?.join(request)` | 创建会话、获取媒体 token、连接房间。 |

### 9.3 SessionJoinRequest 字段说明

Demo 中实际使用的字段：

```kotlin
val request = SessionJoinRequest(
    customerToken = auth.token,
    appUserId = auth.user.appUserId,
    childName = childNameInput.text.toString().trim(),
    age = ageInput.text.toString().trim(),
    grade = gradeInput.text.toString().trim(),
    topic = topicInput.text.toString().trim().ifBlank { "Open conversation" },
    identity = childNameInput.text.toString().trim().ifBlank { "android-child" },
)
```

字段解释：

| 字段 | Demo 来源 | 说明 |
|------|-----------|------|
| `customerToken` | `auth.token` | 登录客户后端返回的 token，Join 必填。 |
| `appUserId` | `auth.user.appUserId` | 客户侧用户 ID，建议传。 |
| `childName` | `childNameInput` | 孩子姓名或昵称。 |
| `age` | `ageInput` | 年龄。 |
| `grade` | `gradeInput` | 年级。 |
| `topic` | `topicInput` | 会话主题；空时默认 `Open conversation`。 |
| `identity` | `childNameInput` 或 `android-child` | 媒体房间里的用户身份。 |

`SessionJoinRequest` 还支持 demo 未使用但客户可使用的字段：

| 字段 | 说明 |
|------|------|
| `externalSessionId` | 客户业务系统自己的 session ID，便于和业务订单、课堂、练习记录关联。 |
| `interests` | 孩子兴趣标签，例如 `listOf("football", "drawing")`。 |
| `email` / `password` | 可用于无 token 时隐式登录；生产环境不推荐，建议业务 App 自己登录后传 `customerToken`。 |

客户可扩展示例：

```kotlin
val request = SessionJoinRequest(
    customerToken = auth.token,
    appUserId = auth.user.appUserId,
    externalSessionId = "classroom-session-20260520-001",
    childName = "Leo",
    age = "9",
    grade = "Grade 3",
    topic = "Self introduction and hobbies",
    interests = listOf("football", "drawing"),
    identity = "child-${auth.user.appUserId}",
)
```

## 10. 监听 SDK 状态：client.state 和 client.events

Demo 在 Join 前调用 `observeClient(activeClient)`：

```kotlin
observeClient(activeClient)
```

完整实现：

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

说明：

| Flow | Demo 处理方式 | 适合用途 |
|------|---------------|----------|
| `client.state` | 持续 collect，并调用 `renderState()` | 渲染连接状态、转录、总结、按钮状态。 |
| `client.events` | collect 一次性事件 | toast、弹窗、错误提示。 |

`SessionEvent` 在 demo 中只处理了错误：

```kotlin
when (event) {
    is SessionEvent.Error -> toast(event.message)
    is SessionEvent.Info -> Unit
}
```

## 11. 渲染连接状态：SessionConnectionState

Demo 的 `renderState()` 接收 `SessionConnectionState`：

```kotlin
private fun renderState(state: SessionConnectionState) {
    latestState = state
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
        append(if (state.participantCount > 1) "(Agent 已入房 ✅)" else "(等待 Agent 入房...)")
        append("\nsession=")
        append(state.sessionId ?: "-")
        append("\nmode=")
        append(when (getCurrentVadMode()) {
            VadMode.OFF -> "PTT 按下说话"
            VadMode.SEMI_AUTO -> "VAD 半自动"
            VadMode.FULL_AUTO -> "VAD 全自动"
        })
        append("\npreset=")
        append(
            when (getCurrentVadPreset()) {
                VADPreset.SENSITIVE -> "灵敏"
                VADPreset.NATURAL -> "自然"
                VADPreset.CHILD -> "儿童"
            },
        )
        append("\n\n=== 连接状态 ===")
        append("\n主题：${topicInput.text}")
        append("\nAPI: ${apiBaseUrlInput.text}")
        append("\n用户：")
        append(customerAuth?.user?.displayLabel() ?: "未登录")
        append("\n麦克风：${if (state.micReady) "就绪 ✅" else "未就绪"}")
        append("\n录音：${if (state.recording) "进行中 🎤" else "待机"}")
    }

    transcriptText.text = state.transcript.joinToString("\n\n") { entry ->
        val role = if (entry.speaker == "ai") "AI" else "Child"
        val tail = if (entry.meta.isBlank()) "" else "\n${entry.meta}"
        "$role: ${entry.text}$tail"
    }.ifBlank { "暂无消息" }

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

    updateActionButtons(state)

    android.util.Log.d("MainActivity", "Room state: phase=${state.phase.name}, participants=${state.participantCount}, agent=${state.agentStatus.phase.name}")
}
```

Demo 中用到的 `SessionConnectionState` 字段：

| 字段 | Demo 用途 |
|------|-----------|
| `phase` | 展示连接阶段，控制按钮可用性。 |
| `agentStatus.phase` | 展示 Agent 当前阶段。 |
| `agentStatus.message` | 展示 Agent 当前提示文案。 |
| `participantCount` | 判断 Agent 是否入房，大于 1 显示“Agent 已入房”。 |
| `sessionId` | 展示当前 session ID。 |
| `micReady` | 展示麦克风是否就绪。 |
| `recording` | 展示当前是否正在录音。 |
| `transcript` | 渲染孩子转录和 AI 回复。 |
| `summary` | 渲染会话总结。 |
| `turnBusy` | 在 `updateActionButtons()` 中禁用 PTT，避免重复提交。 |

## 12. 渲染转录：TranscriptEntry

Demo 通过 `state.transcript` 渲染消息列表：

```kotlin
transcriptText.text = state.transcript.joinToString("\n\n") { entry ->
    val role = if (entry.speaker == "ai") "AI" else "Child"
    val tail = if (entry.meta.isBlank()) "" else "\n${entry.meta}"
    "$role: ${entry.text}$tail"
}.ifBlank { "暂无消息" }
```

由 demo 可见每条 transcript 至少包含：

| 字段 | Demo 用途 |
|------|-----------|
| `speaker` | 判断显示 `AI` 还是 `Child`。 |
| `text` | 显示消息正文。 |
| `meta` | 显示 IPA、纠错等补充信息。 |
| `streaming` | SDK 内部用于区分 AI 流式回复；demo 不单独展示，但会看到 AI 文本逐步增长。 |

业务 App 可以用 RecyclerView/Compose LazyColumn 渲染 `state.transcript`。

## 13. 渲染总结：SessionSummary

Demo 通过 `state.summary` 渲染总结：

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

Demo 中使用的 summary 字段：

| 字段 | 说明 |
|------|------|
| `summary` | 本次会话总结。 |
| `didWell` | 孩子做得好的地方。 |
| `oneTip` | 一个轻量建议。 |
| `nextTopic` | 下次话题。 |
| `pronunciationFocus` | 发音关注点。 |

`SessionSummary` 还包含 `learnedSentences`，demo 当前没有展示，但客户 App 建议展示为“今天学会的句子”：

```kotlin
state.summary?.let { summary ->
    learnedSentencesText.text = summary.learnedSentences.joinToString("\n") { sentence ->
        "• $sentence"
    }
}
```

## 14. 控制按钮状态：SessionPhase 和 turnBusy

Demo 通过 `updateActionButtons()` 控制按钮：

```kotlin
private fun updateActionButtons(state: SessionConnectionState?) {
    val safeState = state ?: SessionConnectionState()
    val connected = safeState.phase == SessionPhase.CONNECTED
    val currentMode = getCurrentVadMode()
    val loggedIn = customerAuth != null
    loginButton.isEnabled = !connected && safeState.phase != SessionPhase.REQUESTING_TOKEN && safeState.phase != SessionPhase.CONNECTING
    leaveButton.isEnabled = connected
    joinButton.isEnabled = loggedIn && safeState.phase != SessionPhase.REQUESTING_TOKEN && safeState.phase != SessionPhase.CONNECTING && !connected
    pttButton.isEnabled = connected && !safeState.turnBusy && currentMode != VadMode.FULL_AUTO
    vadModeGroup.isEnabled = true
}
```

逻辑解释：

| 控件 | 启用条件 |
|------|----------|
| 登录按钮 | 未连接，且不在请求 token / 连接中。 |
| Leave | 已连接。 |
| Join | 已登录，未连接，且不在请求 token / 连接中。 |
| PTT | 已连接，后端不忙，且当前不是全自动模式。 |

`SessionPhase` 在 demo 中用于判断是否连接：

```kotlin
val connected = safeState.phase == SessionPhase.CONNECTED
```

常见阶段：

| 阶段 | UI 建议 |
|------|---------|
| `IDLE` | 可登录、可 Join。 |
| `REQUESTING_TOKEN` | 禁用登录和 Join，显示加载。 |
| `CONNECTING` | 禁用登录和 Join，显示连接中。 |
| `CONNECTED` | 启用 Leave，可按模式说话。 |
| `LEAVING` | 显示离开中。 |
| `ERROR` | 展示错误，可允许重新登录/Join。 |

## 15. VAD 模式和发言控制

Demo 支持三种模式：

| UI 文案 | SDK 枚举 | 行为 |
|---------|----------|------|
| PTT | `VadMode.OFF` | 按住按钮说话，松开结束。 |
| 半自动 | `VadMode.SEMI_AUTO` | 手动启动，VAD 判断语音开始/结束。 |
| 全自动 | `VadMode.FULL_AUTO` | VAD 自动检测语音开始/结束。 |

### 15.1 VAD Mode UI

布局：

```xml
<RadioGroup
    android:id="@+id/vadModeGroup"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal">

    <RadioButton
        android:id="@+id/vadModeOff"
        android:text="PTT" />

    <RadioButton
        android:id="@+id/vadModeSemiAuto"
        android:text="半自动" />

    <RadioButton
        android:id="@+id/vadModeFullAuto"
        android:checked="true"
        android:text="全自动" />
</RadioGroup>
```

读取当前模式：

```kotlin
private fun getCurrentVadMode(): VadMode {
    return when (vadModeGroup.checkedRadioButtonId) {
        R.id.vadModeOff -> VadMode.OFF
        R.id.vadModeSemiAuto -> VadMode.SEMI_AUTO
        R.id.vadModeFullAuto -> VadMode.FULL_AUTO
        else -> VadMode.FULL_AUTO
    }
}
```

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

### 15.2 VAD Preset UI

Demo 支持三种 VAD 预设：

| UI 文案 | SDK 枚举 | 建议场景 |
|---------|----------|----------|
| 灵敏 | `VADPreset.SENSITIVE` | 安静环境、声音较小。 |
| 自然 | `VADPreset.NATURAL` | 默认推荐。 |
| 儿童 | `VADPreset.CHILD` | 儿童声音、停顿较长的场景。 |

布局：

```xml
<RadioGroup
    android:id="@+id/vadPresetGroup"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal">

    <RadioButton
        android:id="@+id/vadPresetSensitive"
        android:text="灵敏" />

    <RadioButton
        android:id="@+id/vadPresetNatural"
        android:checked="true"
        android:text="自然" />

    <RadioButton
        android:id="@+id/vadPresetChild"
        android:text="儿童" />
</RadioGroup>
```

读取当前预设：

```kotlin
private fun getCurrentVadPreset(): VADPreset {
    return when (vadPresetGroup.checkedRadioButtonId) {
        R.id.vadPresetSensitive -> VADPreset.SENSITIVE
        R.id.vadPresetChild -> VADPreset.CHILD
        R.id.vadPresetNatural -> VADPreset.NATURAL
        else -> VADPreset.NATURAL
    }
}
```

切换预设：

```kotlin
vadPresetGroup.setOnCheckedChangeListener { _, _ ->
    client?.setVadPreset(getCurrentVadPreset())
    renderState(latestState)
}
```

Join 前也会设置一次：

```kotlin
activeClient.setVadPreset(getCurrentVadPreset())
activeClient.setVadMode(getCurrentVadMode())
```

### 15.3 PTT 按钮

布局：

```xml
<Button
    android:id="@+id/pttButton"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="8dp"
    android:enabled="false"
    android:text="Hold to Talk" />
```

触摸事件：

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

不同模式下建议：

| 模式 | 是否需要 PTT 按钮 |
|------|-------------------|
| `VadMode.OFF` | 需要，按下开始、松开结束。 |
| `VadMode.SEMI_AUTO` | 可用，按下开始半自动监听，松开/取消结束。 |
| `VadMode.FULL_AUTO` | 不需要，demo 会禁用 PTT 按钮。 |

## 16. 结束会话和释放资源

### 16.1 用户主动结束：leave

Demo 的 Leave 按钮绑定：

```kotlin
leaveButton.setOnClickListener { leaveSession() }
```

实现：

```kotlin
private fun leaveSession() {
    lifecycleScope.launch {
        client?.leave("user-leave")
    }
}
```

说明：

- `leave("user-leave")` 会结束当前 session 并断开媒体房间。
- `reason` 可以按业务自定义，例如 `user-leave`、`timeout`、`page-close`。

### 16.2 页面销毁：close

Demo 在 `onDestroy()` 中释放资源：

```kotlin
override fun onDestroy() {
    stateJob?.cancel()
    eventJob?.cancel()
    client?.close()
    client = null
    super.onDestroy()
}
```

说明：

| 代码 | 说明 |
|------|------|
| `stateJob?.cancel()` | 停止监听状态。 |
| `eventJob?.cancel()` | 停止监听事件。 |
| `client?.close()` | 释放 SDK 内部资源、断开连接。 |
| `client = null` | 清空引用。 |

建议客户 App：

- 用户主动离开会话时调用 `leave()`。
- Activity/Fragment/ViewModel 销毁时调用 `close()`。
- `close()` 后不要继续复用同一个 client，需要重新创建。

## 17. Demo 页面字段与代码对应表

| UI 控件 | id | 代码位置 | 作用 |
|---------|----|----------|------|
| Customer Backend URL | `apiBaseUrlInput` | `buildConfig()` | 创建 `NewTypeConfig`。 |
| 登录邮箱 | `loginEmailInput` | `loginCustomer()` | 传给 `client.login()`。 |
| 登录密码 | `loginPasswordInput` | `loginCustomer()` | 传给 `client.login()`。 |
| 登录客户后端 | `loginButton` | `bindActions()` / `loginCustomer()` | 触发登录。 |
| 用户信息 | `userInfoText` | `renderCustomerAuth()` | 展示登录用户和 token 预览。 |
| Topic | `topicInput` | `joinSession()` | 传给 `SessionJoinRequest.topic`。 |
| Child Name | `childNameInput` | `joinSession()` | 传给 `childName` 和 `identity`。 |
| Age | `ageInput` | `joinSession()` | 传给 `SessionJoinRequest.age`。 |
| Grade | `gradeInput` | `joinSession()` | 传给 `SessionJoinRequest.grade`。 |
| Join | `joinButton` | `joinSession()` | 创建并加入会话。 |
| Leave | `leaveButton` | `leaveSession()` | 结束会话。 |
| VAD Mode | `vadModeGroup` | `getCurrentVadMode()` / `setVadMode()` | 切换 PTT、半自动、全自动。 |
| VAD Preset | `vadPresetGroup` | `getCurrentVadPreset()` / `setVadPreset()` | 切换灵敏、自然、儿童。 |
| Hold to Talk | `pttButton` | `startSpeaking()` / `stopSpeaking()` | 手动发言控制。 |
| 状态文本 | `statusText` | `renderState()` | 展示连接和 Agent 状态。 |
| Transcript | `transcriptText` | `renderState()` | 展示转录和 AI 回复。 |
| Summary | `summaryText` | `renderState()` | 展示会话总结。 |

## 18. 客户后端接口返回约定

虽然 demo 不直接实现客户后端，但从 demo 的 SDK 调用可以看出客户后端需要支持以下能力。

### 18.1 登录接口

Demo 调用：

```kotlin
loginClient.login(email, password)
```

默认路径：

```text
POST /auth/login
```

Demo 使用登录返回中的字段：

```kotlin
token
expiresIn
user.appUserId
user.email
user.displayName
```

推荐返回示例：

```json
{
  "token": "customer-jwt",
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

### 18.2 创建 Session 接口

Demo 调用：

```kotlin
client?.join(request)
```

SDK 会在 Join 内部请求客户后端创建 session。默认路径：

```text
POST /app/sessions
Authorization: Bearer <customerToken>
```

请求中的业务字段来自 `SessionJoinRequest`：

```text
appUserId
externalSessionId
childName
age
grade
topic
interests
identity
```

### 18.3 获取媒体房间 Token 接口

Join 内部还会请求媒体房间 token。默认路径：

```text
POST /app/sessions/{sessionId}/livekit-token
Authorization: Bearer <customerToken>
```

客户后端需要返回媒体房间连接信息，关键字段包括：

| 字段 | 说明 |
|------|------|
| `token` | 媒体房间入房 token。 |
| `url` | 媒体房间 WebSocket 地址，例如 `wss://media.example.com`。 |
| `identity` | 当前用户身份。 |
| `roomName` | 房间名。 |
| `expiresIn` | token 有效期。 |

### 18.4 结束 Session 接口

Demo 调用：

```kotlin
client?.leave("user-leave")
```

SDK 会通知客户后端结束 session。默认路径：

```text
POST /app/sessions/{sessionId}/end
Authorization: Bearer <customerToken>
```

## 19. 常见问题排查

### 19.1 Join 按钮不可用

Demo 中 Join 按钮启用条件：

```kotlin
val loggedIn = customerAuth != null
joinButton.isEnabled = loggedIn && safeState.phase != SessionPhase.REQUESTING_TOKEN && safeState.phase != SessionPhase.CONNECTING && !connected
```

因此必须先登录客户后端，登录成功后 `customerAuth != null`，Join 才会启用。

### 19.2 登录客户后端失败

检查：

1. `apiBaseUrlInput` 是否填写客户后端地址。
2. 真机不要填 `localhost`，应填电脑/服务器在局域网中的 IP。
3. 客户后端是否监听 `0.0.0.0`。
4. 如果是 HTTP，是否保留 `usesCleartextTraffic=true` 和 `network_security_config`。
5. 手机浏览器是否能访问客户后端健康检查地址。
6. 邮箱和密码是否符合客户后端 demo 账号要求。

### 19.3 已连接但没有 AI 回复

检查：

1. `statusText` 中 `phase` 是否为 `CONNECTED`。
2. `participants` 是否大于 1。大于 1 表示 Agent 已入房。
3. `agentStatus.message` 是否提示错误。
4. 是否授权麦克风权限。
5. 如果当前是 PTT 模式，是否按下并松开了 Hold to Talk。
6. 如果当前是全自动模式，是否尝试切换 `VADPreset.CHILD`。
7. 客户后端返回的媒体房间 `url` 和 `token` 是否正确。

### 19.4 PTT 按钮不可用

Demo 中 PTT 按钮启用条件：

```kotlin
pttButton.isEnabled = connected && !safeState.turnBusy && currentMode != VadMode.FULL_AUTO
```

因此以下情况会禁用：

- 未连接成功。
- 后端正在处理上一轮，即 `turnBusy=true`。
- 当前是 `VadMode.FULL_AUTO`。

### 19.5 VAD 太早截断孩子说话

Demo 可切到“儿童”预设：

```kotlin
client?.setVadPreset(VADPreset.CHILD)
```

UI 操作是在 VAD Preset 中选择“儿童”。

### 19.6 退出页面后仍占用麦克风

确认 `onDestroy()` 中调用了：

```kotlin
client?.close()
```

如果业务使用 Fragment 或 ViewModel，也需要在对应生命周期里释放 SDK client。

## 20. 客户 App 最小接入代码

下面是一份基于 demo 写法整理的最小接入骨架：

```kotlin
class SpeakingActivity : AppCompatActivity() {
    private var client: NewTypeSessionClient? = null
    private var stateJob: Job? = null
    private var eventJob: Job? = null
    private var login: CustomerLoginResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = NewTypeConfig(apiBaseUrl = "https://customer-api.example.com")
        client = NewTypeSessionClient.create(this, config)

        stateJob = lifecycleScope.launch {
            client!!.state.collectLatest { state ->
                renderState(state)
            }
        }

        eventJob = lifecycleScope.launch {
            client!!.events.collectLatest { event ->
                when (event) {
                    is SessionEvent.Error -> showError(event.message)
                    is SessionEvent.Info -> Unit
                }
            }
        }

        lifecycleScope.launch {
            val response = client!!.login(
                email = "demo@example.com",
                password = "demo-password-change-me",
            )
            login = response

            client!!.setVadMode(VadMode.FULL_AUTO)
            client!!.setVadPreset(VADPreset.CHILD)

            client!!.join(
                SessionJoinRequest(
                    customerToken = response.token,
                    appUserId = response.user.appUserId,
                    childName = response.user.displayName ?: "Leo",
                    age = "9",
                    grade = "Grade 3",
                    topic = "Self introduction and hobbies",
                    identity = "child-${response.user.appUserId}",
                ),
            )
        }
    }

    private fun renderState(state: SessionConnectionState) {
        // state.phase: 连接阶段
        // state.agentStatus.message: 当前提示文案
        // state.transcript: 对话转录和 AI 回复
        // state.summary: 会话总结
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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

如果客户产品使用 PTT 模式：

```kotlin
client!!.setVadMode(VadMode.OFF)

holdToTalkButton.setOnTouchListener { _, event ->
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            lifecycleScope.launch { client?.startSpeaking() }
            true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            lifecycleScope.launch { client?.stopSpeaking() }
            true
        }
        else -> false
    }
}
```

## 21. 生产接入建议

1. 生产环境建议业务 App 自己完成登录，然后把 customer token 传给 SDK，不建议在 SDK 内保存固定账号密码。
2. `apiBaseUrl` 生产环境建议使用 HTTPS。
3. 不要在 UI 或日志中输出完整 token，demo 中只展示 `tokenPreview()`。
4. 先调用 `observeClient(activeClient)` 再调用 `join(request)`，避免漏掉早期状态。
5. UI 应基于 `state.phase`、`state.turnBusy`、`state.recording` 控制按钮。
6. 儿童自然对话建议默认 `VadMode.FULL_AUTO + VADPreset.CHILD`。
7. 现场演示或噪声环境建议使用 `VadMode.OFF` PTT。
8. 用户主动结束调用 `leave()`，页面销毁调用 `close()`。

## 22. 最小交付清单

客户 App 接入时至少需要完成：

- 在 Gradle 中引入 `newtypesdkcore-release.aar`。
- 添加 `INTERNET` 和 `RECORD_AUDIO` 权限。
- 如果客户后端是 HTTP，配置明文流量。
- 创建 `NewTypeConfig(apiBaseUrl = ...)`。
- 创建 `NewTypeSessionClient`。
- 调用 `client.login(email, password)` 或由业务登录后提供 customer token。
- 构造 `SessionJoinRequest`。
- 调用 `client.join(request)`。
- collect `client.state` 渲染连接状态、对话转录、会话总结。
- collect `client.events` 展示错误事件。
- 根据产品体验设置 `VadMode` 和 `VADPreset`。
- 用户结束时调用 `leave()`。
- 页面销毁时调用 `close()`。
