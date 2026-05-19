// newtypesdk_android/app/src/main/java/com/newtype/sdkdemo/MainActivity.kt
package com.newtype.sdkdemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.newtype.sdkcore.CustomerLoginResponse
import com.newtype.sdkcore.NewTypeConfig
import com.newtype.sdkcore.NewTypeSessionClient
import com.newtype.sdkcore.SessionConnectionState
import com.newtype.sdkcore.SessionEvent
import com.newtype.sdkcore.SessionJoinRequest
import com.newtype.sdkcore.SessionPhase
import com.newtype.sdkcore.VadMode
import com.newtype.sdkcore.vad.VADPreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var client: NewTypeSessionClient? = null
    private var stateJob: Job? = null
    private var eventJob: Job? = null
    private var latestState: SessionConnectionState = SessionConnectionState()

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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            toast("需要麦克风权限")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        bindActions()
        ensureMicPermission()
    }

    private fun bindViews() {
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
        loginButton.setOnClickListener { loginCustomer() }
        joinButton.setOnClickListener { joinSession() }
        leaveButton.setOnClickListener { leaveSession() }
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
        vadPresetGroup.setOnCheckedChangeListener { _, _ ->
            client?.setVadPreset(getCurrentVadPreset())
            renderState(latestState)
        }
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
    }

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

    private fun buildConfig(): NewTypeConfig {
        return NewTypeConfig(apiBaseUrl = apiBaseUrlInput.text.toString().trim())
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
        lifecycleScope.launch {
            client?.leave("user-leave")
        }
    }

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

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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
        stateJob?.cancel()
        eventJob?.cancel()
        client?.close()
        client = null
        super.onDestroy()
    }
}

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

private fun CustomerAuthUser.displayLabel(): String {
    return displayName?.takeIf { it.isNotBlank() } ?: email
}

private fun CustomerAuthState.tokenPreview(): String {
    val head = token.take(8)
    val tail = token.takeLast(6)
    return if (token.length <= 18) token else "$head...$tail"
}
