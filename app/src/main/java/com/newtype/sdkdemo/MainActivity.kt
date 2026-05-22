// newtypesdk_android/app/src/main/java/com/newtype/sdkdemo/MainActivity.kt
package com.newtype.sdkdemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.newtype.sdkcore.NewTypeConnectionCredential
import com.newtype.sdkcore.NewTypeSessionClient
import com.newtype.sdkcore.SessionConnectionState
import com.newtype.sdkcore.SessionEvent
import com.newtype.sdkcore.SessionPhase
import com.newtype.sdkcore.VadMode
import com.newtype.sdkcore.vad.VADPreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {
    // SDK 客户端只负责实时音频房间连接、麦克风、VAD、状态流和事件流。
    // 登录客户后端、创建业务会话、结束业务会话都放在 Demo App 层完成，便于客户替换成自己的后端。
    private var client: NewTypeSessionClient? = null
    private var stateJob: Job? = null
    private var eventJob: Job? = null
    private var latestState: SessionConnectionState = SessionConnectionState()
    private var lastLoggedPhase: SessionPhase? = null

    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var summaryText: TextView
    private lateinit var loginButton: Button
    private lateinit var joinButton: Button
    private lateinit var leaveButton: Button
    private lateinit var pttButton: Button
    private lateinit var vadModeGroup: RadioGroup
    private lateinit var vadModeOff: RadioButton
    private lateinit var vadModeSemiAuto: RadioButton
    private lateinit var vadModeFullAuto: RadioButton
    private lateinit var vadPresetGroup: RadioGroup
    private lateinit var vadPresetSensitive: RadioButton
    private lateinit var vadPresetNatural: RadioButton
    private lateinit var vadPresetChild: RadioButton

    private lateinit var apiBaseUrlInput: EditText
    private lateinit var loginEmailInput: EditText
    private lateinit var loginPasswordInput: EditText
    private lateinit var userInfoText: TextView
    private lateinit var topicInput: EditText
    private lateinit var childNameInput: EditText
    private lateinit var ageInput: EditText
    private lateinit var gradeInput: EditText
    private var customerAuth: CustomerAuthState? = null
    private var activeSessionId: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(TAG, "[NT-DEMO][PERMISSION] RECORD_AUDIO result granted=$granted")
        if (!granted) {
            toast("需要麦克风权限")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "[NT-DEMO][LIFECYCLE] onCreate")
        setContentView(R.layout.activity_main)
        bindViews()
        bindActions()
        ensureMicPermission()
    }

    private fun bindViews() {
        Log.d(TAG, "[NT-DEMO][UI] bindViews")
        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        summaryText = findViewById(R.id.summaryText)
        loginButton = findViewById(R.id.loginButton)
        joinButton = findViewById(R.id.joinButton)
        leaveButton = findViewById(R.id.leaveButton)
        pttButton = findViewById(R.id.pttButton)
        vadModeGroup = findViewById(R.id.vadModeGroup)
        vadModeOff = findViewById(R.id.vadModeOff)
        vadModeSemiAuto = findViewById(R.id.vadModeSemiAuto)
        vadModeFullAuto = findViewById(R.id.vadModeFullAuto)
        vadPresetGroup = findViewById(R.id.vadPresetGroup)
        vadPresetSensitive = findViewById(R.id.vadPresetSensitive)
        vadPresetNatural = findViewById(R.id.vadPresetNatural)
        vadPresetChild = findViewById(R.id.vadPresetChild)
        apiBaseUrlInput = findViewById(R.id.apiBaseUrlInput)
        loginEmailInput = findViewById(R.id.loginEmailInput)
        loginPasswordInput = findViewById(R.id.loginPasswordInput)
        userInfoText = findViewById(R.id.userInfoText)
        topicInput = findViewById(R.id.topicInput)
        childNameInput = findViewById(R.id.childNameInput)
        ageInput = findViewById(R.id.ageInput)
        gradeInput = findViewById(R.id.gradeInput)
    }

    private fun bindActions() {
        Log.d(TAG, "[NT-DEMO][UI] bindActions")
        // 登录客户后端：这里只拿客户侧 token，SDK 不会读取这个 token。
        loginButton.setOnClickListener { loginCustomer() }
        // Connect：先向客户后端换取媒体房间凭证，再把凭证交给 SDK。
        joinButton.setOnClickListener { connectSession() }
        // Leave：先断开 SDK 实时连接，再按业务需要通知客户后端结束 session。
        leaveButton.setOnClickListener { leaveSession() }
        vadModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.vadModeOff -> VadMode.OFF
                R.id.vadModeSemiAuto -> VadMode.SEMI_AUTO
                R.id.vadModeFullAuto -> VadMode.FULL_AUTO
                else -> VadMode.FULL_AUTO
            }
            Log.i(TAG, "[NT-DEMO][VAD] mode selected=$mode")
            client?.setVadMode(mode)
            renderState(latestState)
        }
        vadPresetGroup.setOnCheckedChangeListener { _, _ ->
            val preset = getCurrentVadPreset()
            Log.i(TAG, "[NT-DEMO][VAD] preset selected=$preset")
            client?.setVadPreset(preset)
            renderState(latestState)
        }
        pttButton.setOnTouchListener { _, event ->
            val activeClient = client ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // PTT 模式下按下即开始一轮用户发言；半自动模式也可用它手动触发。
                    Log.i(TAG, "[NT-DEMO][PTT] ACTION_DOWN startSpeaking")
                    lifecycleScope.launch { activeClient.startSpeaking() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 松手或手势取消都要 stopSpeaking，避免麦克风采集停不下来。
                    Log.i(TAG, "[NT-DEMO][PTT] ACTION_UP/CANCEL stopSpeaking action=${event.actionMasked}")
                    lifecycleScope.launch { activeClient.stopSpeaking() }
                    true
                }
                else -> false
            }
        }
    }

    private fun loginCustomer() {
        val backend = buildCustomerBackendApi()
        val email = loginEmailInput.text.toString().trim()
        val password = loginPasswordInput.text.toString()
        if (email.isBlank() || password.isBlank()) {
            Log.w(TAG, "[NT-DEMO][AUTH] login blocked: email/password blank")
            toast("请输入登录邮箱和密码")
            return
        }
        Log.i(TAG, "[NT-DEMO][AUTH] login start baseUrl=${apiBaseUrlInput.text.toString().trim()} email=$email")
        Log.d(TAG, "[NT-DEMO][AUTH] login form emailLength=${email.length} passwordLength=${password.length}")
        loginButton.isEnabled = false
        loginButton.text = "登录中..."
        lifecycleScope.launch {
            runCatching {
                backend.login(email, password)
            }.onSuccess { response ->
                Log.i(TAG, "[NT-DEMO][AUTH] login success userId=${response.user.appUserId} email=${response.user.email} token=${response.token.maskSecret()} expiresIn=${response.expiresIn}")
                customerAuth = response.toAuthState()
                if (!response.user.displayName.isNullOrBlank()) {
                    childNameInput.setText(response.user.displayName)
                }
                renderCustomerAuth()
                updateActionButtons(latestState)
                toast("登录成功")
            }.onFailure {
                Log.e(TAG, "[NT-DEMO][AUTH] login failed message=${it.message}", it)
                customerAuth = null
                renderCustomerAuth()
                updateActionButtons(latestState)
                toast("登录失败：${it.message.orEmpty()}")
            }
        }
    }

    private fun connectSession() {
        val auth = customerAuth
        if (auth == null) {
            Log.w(TAG, "[NT-DEMO][CONNECT] blocked: customerAuth=null")
            toast("请先登录客户后端")
            return
        }
        Log.i(TAG, "[NT-DEMO][CONNECT] start userId=${auth.user.appUserId} baseUrl=${apiBaseUrlInput.text.toString().trim()} mode=${getCurrentVadMode()} preset=${getCurrentVadPreset()}")
        Log.d(TAG, "[NT-DEMO][CONNECT] closing previous SDK client if present hasClient=${client != null}")
        client?.close()
        Log.d(TAG, "[NT-DEMO][CONNECT] creating SDK client")
        client = NewTypeSessionClient.create(this)
        val activeClient = client ?: return
        // 建议先配置 VAD 和监听 state/events，再 connect。这样连接过程中的状态变化不会漏掉。
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
        Log.i(TAG, "[NT-DEMO][BACKEND] start session request appUserId=${request.appUserId} topic=${request.topic} childName=${request.childName} identity=${request.identity}")
        Log.d(TAG, "[NT-DEMO][BACKEND] start session request detail externalSessionId=${request.externalSessionId} age=${request.age} grade=${request.grade} interests=${request.interests.joinToString()}")
        lifecycleScope.launch {
            runCatching {
                val credential = backend.startRealtimeSession(auth.token, request)
                Log.i(TAG, "[NT-DEMO][BACKEND] credential received ${credential.safeSummary()}")
                activeSessionId = credential.sessionId
                // 只有这里的 NewTypeConnectionCredential 会传给 SDK；客户后端 token 不会传入 SDK。
                Log.i(TAG, "[NT-DEMO][CONNECT] calling SDK connect sessionId=${credential.sessionId}")
                activeClient.connect(credential.toSdkCredential())
                Log.i(TAG, "[NT-DEMO][CONNECT] SDK connect call completed sessionId=${credential.sessionId}")
            }.onFailure {
                Log.e(TAG, "[NT-DEMO][CONNECT] failed message=${it.message}", it)
                toast("连接失败：${it.message.orEmpty()}")
            }
        }
    }

    private fun buildCustomerBackendApi(): CustomerBackendApi {
        Log.d(TAG, "[NT-DEMO][BACKEND] build api baseUrl=${apiBaseUrlInput.text.toString().trim()}")
        return CustomerBackendApi(apiBaseUrl = apiBaseUrlInput.text.toString().trim())
    }

    private fun getCurrentVadMode(): VadMode {
        return when (vadModeGroup.checkedRadioButtonId) {
            R.id.vadModeOff -> VadMode.OFF
            R.id.vadModeSemiAuto -> VadMode.SEMI_AUTO
            R.id.vadModeFullAuto -> VadMode.FULL_AUTO
            else -> VadMode.FULL_AUTO
        }
    }

    private fun getCurrentVadPreset(): VADPreset {
        return when (vadPresetGroup.checkedRadioButtonId) {
            R.id.vadPresetSensitive -> VADPreset.SENSITIVE
            R.id.vadPresetChild -> VADPreset.CHILD
            R.id.vadPresetNatural -> VADPreset.NATURAL
            else -> VADPreset.NATURAL
        }
    }

    private fun leaveSession() {
        val sessionId = activeSessionId
        val auth = customerAuth
        val backend = buildCustomerBackendApi()
        Log.i(TAG, "[NT-DEMO][LEAVE] requested sessionId=$sessionId hasAuth=${auth != null}")
        lifecycleScope.launch {
            // SDK disconnect 只负责离开实时音频房间；业务结束上报由 App 层自行调用客户后端。
            runCatching { client?.disconnect("user-leave") }
                .onSuccess { Log.i(TAG, "[NT-DEMO][LEAVE] SDK disconnect completed") }
                .onFailure { Log.e(TAG, "[NT-DEMO][LEAVE] SDK disconnect failed message=${it.message}", it) }
            if (!sessionId.isNullOrBlank() && auth != null) {
                runCatching { backend.endRealtimeSession(auth.token, sessionId) }
                    .onSuccess { Log.i(TAG, "[NT-DEMO][BACKEND] end session success sessionId=$sessionId") }
                    .onFailure { Log.e(TAG, "[NT-DEMO][BACKEND] end session failed sessionId=$sessionId message=${it.message}", it) }
            }
            activeSessionId = null
        }
    }

    private fun observeClient(activeClient: NewTypeSessionClient) {
        Log.i(TAG, "[NT-DEMO][OBSERVE] attach SDK state/events collectors")
        stateJob?.cancel()
        eventJob?.cancel()
        stateJob = lifecycleScope.launch {
            activeClient.state.collectLatest {
                if (lastLoggedPhase != it.phase) {
                    Log.i(TAG, "[NT-DEMO][STATE-TRANSITION] ${lastLoggedPhase ?: "<initial>"} -> ${it.phase} sessionId=${it.sessionId}")
                    lastLoggedPhase = it.phase
                }
                Log.i(TAG, "[NT-DEMO][STATE] phase=${it.phase} sessionId=${it.sessionId} participants=${it.participantCount} micReady=${it.micReady} recording=${it.recording} turnBusy=${it.turnBusy} agent=${it.agentStatus.phase} message=${it.agentStatus.message}")
                Log.d(TAG, "[NT-DEMO][STATE-DETAIL] transcriptCount=${it.transcript.size} hasSummary=${it.summary != null} latestTranscript=${it.transcript.lastOrNull()?.safeSummary() ?: "-"}")
                renderState(it)
            }
        }
        eventJob = lifecycleScope.launch {
            activeClient.events.collectLatest { event ->
                when (event) {
                    is SessionEvent.Error -> {
                        Log.e(TAG, "[NT-DEMO][EVENT] error message=${event.message}")
                        toast(event.message)
                    }
                    is SessionEvent.Info -> Log.i(TAG, "[NT-DEMO][EVENT] info message=${event.message}")
                }
            }
        }
    }

    private fun renderState(state: SessionConnectionState) {
        latestState = state
        // statusText 是排查现场的主面板：把连接阶段、Agent 状态、麦克风、录音等关键字段直接展示出来。
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
            append(
                when (getCurrentVadPreset()) {
                    VADPreset.SENSITIVE -> "灵敏"
                    VADPreset.NATURAL -> "自然"
                    VADPreset.CHILD -> "儿童"
                },
            )
            append("\n\n=== 连接状态 ===")
            append("\n主题：${topicInput.text}")
            append("\n客户后端：${apiBaseUrlInput.text}")
            append("\n用户：")
            append(customerAuth?.user?.displayLabel() ?: "未登录")
            append("\n麦克风：${if (state.micReady) "就绪" else "未就绪"}")
            append("\n录音：${if (state.recording) "进行中" else "待机"}")
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

        Log.d(TAG, "[NT-DEMO][RENDER] phase=${state.phase.name}, participants=${state.participantCount}, agent=${state.agentStatus.phase.name}, transcript=${state.transcript.size}, hasSummary=${state.summary != null}")
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "[NT-DEMO][PERMISSION] requesting RECORD_AUDIO")
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            Log.i(TAG, "[NT-DEMO][PERMISSION] RECORD_AUDIO already granted")
        }
    }

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
        Log.d(TAG, "[NT-DEMO][BUTTONS] phase=${safeState.phase} loggedIn=$loggedIn connected=$connected mode=$currentMode login=${loginButton.isEnabled} connect=${joinButton.isEnabled} leave=${leaveButton.isEnabled} ptt=${pttButton.isEnabled}")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

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

    override fun onDestroy() {
        Log.i(TAG, "[NT-DEMO][LIFECYCLE] onDestroy sessionId=$activeSessionId")
        // Activity 销毁时必须 close，释放麦克风、音频链路、VAD 等 SDK 内部资源。
        stateJob?.cancel()
        eventJob?.cancel()
        client?.close()
        client = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NewTypeDemo"
    }
}

private class CustomerBackendApi(
    private val apiBaseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun login(email: String, password: String): CustomerLoginResponse {
        val request = CustomerLoginRequest(email = email.trim(), password = password)
        Log.i(TAG, "[NT-DEMO][HTTP] POST /auth/login email=${request.email}")
        Log.d(TAG, "[NT-DEMO][HTTP] login payload email=${request.email} passwordLength=${request.password.length}")
        val response = execute(
            Request.Builder()
                .url(buildUrl("/auth/login"))
                .post(json.encodeToString(CustomerLoginRequest.serializer(), request).toJsonBody())
                .header("Accept", "application/json")
                .build(),
        )
        Log.d(TAG, "[NT-DEMO][HTTP] /auth/login responseBytes=${response.length}")
        return json.decodeFromString(CustomerLoginResponse.serializer(), response)
    }

    suspend fun startRealtimeSession(
        customerToken: String,
        request: StartRealtimeSessionRequest,
    ): RealtimeConnectionCredentialResponse {
        Log.i(TAG, "[NT-DEMO][HTTP] POST /app/sessions appUserId=${request.appUserId} topic=${request.topic} token=${customerToken.maskSecret()}")
        val sessionResponse = execute(
            authorizedBuilder("/app/sessions", customerToken)
                .post(json.encodeToString(StartRealtimeSessionRequest.serializer(), request).toJsonBody())
                .build(),
        )
        Log.d(TAG, "[NT-DEMO][HTTP] /app/sessions responseBytes=${sessionResponse.length}")
        val session = json.decodeFromString(StartRealtimeSessionResponse.serializer(), sessionResponse)
        Log.i(TAG, "[NT-DEMO][HTTP] session created sessionId=${session.session.sessionId} roomName=${session.session.roomName} hasRealtime=${session.realtime != null} hasLivekit=${session.livekit != null} hasUserToken=${session.userToken != null}")
        Log.d(TAG, "[NT-DEMO][HTTP] session response tokenType=${session.userToken?.tokenType ?: "-"} userTokenExpiresIn=${session.userToken?.expiresIn ?: -1}")
        val credential = session.realtime ?: session.livekit ?: requestRealtimeCredential(
            customerToken = customerToken,
            sessionId = session.session.sessionId,
            userToken = session.userToken?.token ?: throw IllegalStateException("customer backend response missing userToken"),
        )
        return RealtimeConnectionCredentialResponse(
            sessionId = session.session.sessionId,
            roomName = credential.roomName.ifBlank { session.session.roomName },
            connectionUrl = credential.url,
            connectionToken = credential.token,
            identity = credential.identity,
            expiresIn = credential.expiresIn,
        )
    }

    private suspend fun requestRealtimeCredential(
        customerToken: String,
        sessionId: String,
        userToken: String,
    ): CustomerRealtimeCredentialResponse {
        // 兼容两种后端返回方式：如果创建 session 时没有直接返回实时凭证，则用 userToken 再换一次 LiveKit token。
        Log.i(TAG, "[NT-DEMO][HTTP] POST /app/sessions/{sessionId}/livekit-token sessionId=$sessionId userToken=${userToken.maskSecret()}")
        val credentialResponse = execute(
            authorizedBuilder("/app/sessions/${sessionId.urlEncode()}/livekit-token", customerToken)
                .post(json.encodeToString(ConnectionCredentialRequest.serializer(), ConnectionCredentialRequest(userToken = userToken)).toJsonBody())
                .build(),
        )
        Log.d(TAG, "[NT-DEMO][HTTP] livekit-token responseBytes=${credentialResponse.length}")
        return json.decodeFromString(CustomerRealtimeCredentialResponse.serializer(), credentialResponse)
    }

    suspend fun endRealtimeSession(customerToken: String, sessionId: String) {
        Log.i(TAG, "[NT-DEMO][HTTP] POST /app/sessions/{sessionId}/end sessionId=$sessionId token=${customerToken.maskSecret()}")
        execute(
            authorizedBuilder("/app/sessions/${sessionId.urlEncode()}/end", customerToken)
                .post("{}".toJsonBody())
                .build(),
        )
    }

    private fun authorizedBuilder(path: String, bearerToken: String): Request.Builder {
        return Request.Builder()
            .url(buildUrl(path))
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
    }

    private suspend fun execute(request: Request): String {
        return suspendCancellableCoroutine { continuation ->
            val startedAtMs = System.currentTimeMillis()
            Log.i(TAG, "[NT-DEMO][HTTP] request ${request.method} ${request.url}")
            Log.d(TAG, "[NT-DEMO][HTTP] request headers hasAuth=${request.header("Authorization") != null} accept=${request.header("Accept")}")
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    val elapsedMs = System.currentTimeMillis() - startedAtMs
                    Log.e(TAG, "[NT-DEMO][HTTP] failure ${request.method} ${request.url} elapsedMs=$elapsedMs message=${e.message}", e)
                    if (!continuation.isCancelled) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use { res ->
                        val body = res.body?.string().orEmpty()
                        val elapsedMs = System.currentTimeMillis() - startedAtMs
                        Log.i(TAG, "[NT-DEMO][HTTP] response ${request.method} ${request.url} code=${res.code} success=${res.isSuccessful} bytes=${body.length} elapsedMs=$elapsedMs")
                        if (!res.isSuccessful) {
                            Log.w(TAG, "[NT-DEMO][HTTP] error body preview=${body.take(300)}")
                            continuation.resumeWith(Result.failure(IllegalStateException("HTTP ${res.code}: $body")))
                            return
                        }
                        continuation.resumeWith(Result.success(body))
                    }
                }
            })
        }
    }

    private fun buildUrl(path: String): String {
        val normalizedBase = apiBaseUrl.trim().trimEnd('/')
        val normalizedPath = path.trim().let { if (it.startsWith("/")) it else "/$it" }
        Log.d(TAG, "[NT-DEMO][HTTP] buildUrl base=$normalizedBase path=$normalizedPath")
        return "$normalizedBase$normalizedPath"
    }

    companion object {
        private const val TAG = "NewTypeDemo"
    }
}

@Serializable
private data class CustomerLoginRequest(
    val email: String,
    val password: String,
)

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

@Serializable
private data class StartRealtimeSessionResponse(
    val session: CustomerSessionRecord,
    val userToken: CustomerUserToken? = null,
    val livekit: CustomerRealtimeCredentialResponse? = null,
    val realtime: CustomerRealtimeCredentialResponse? = null,
)

@Serializable
private data class CustomerSessionRecord(
    val sessionId: String,
    val roomName: String,
)

@Serializable
private data class CustomerUserToken(
    val token: String,
    val tokenType: String,
    val expiresIn: Long,
)

@Serializable
private data class ConnectionCredentialRequest(
    val userToken: String,
)

@Serializable
private data class RealtimeConnectionCredentialResponse(
    val sessionId: String = "",
    val roomName: String = "",
    val connectionUrl: String,
    val connectionToken: String,
    val identity: String,
    val expiresIn: Long? = null,
)

@Serializable
private data class CustomerRealtimeCredentialResponse(
    val token: String,
    val url: String,
    val identity: String,
    val roomName: String,
    val expiresIn: Long? = null,
)

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

private fun CustomerAuthUser.displayLabel(): String {
    return displayName?.takeIf { it.isNotBlank() } ?: email
}

private fun CustomerAuthState.tokenPreview(): String {
    val head = token.take(8)
    val tail = token.takeLast(6)
    return if (token.length <= 18) token else "$head...$tail"
}

private fun RealtimeConnectionCredentialResponse.safeSummary(): String {
    return "sessionId=$sessionId roomName=$roomName url=$connectionUrl identity=$identity token=${connectionToken.maskSecret()} expiresIn=$expiresIn"
}

private fun Any.safeSummary(): String {
    return toString().replace('\n', ' ').take(180)
}

private fun String.maskSecret(): String {
    if (isBlank()) return "<blank>"
    return if (length <= 16) "<len=$length>" else "${take(8)}...${takeLast(6)}(len=$length)"
}

private fun String.toJsonBody() = toRequestBody("application/json".toMediaType())

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
