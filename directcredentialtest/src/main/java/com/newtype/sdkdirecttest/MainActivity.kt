// newtypesdk_demo_android/directcredentialtest/src/main/java/com/newtype/sdkdirecttest/MainActivity.kt
package com.newtype.sdkdirecttest

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

class MainActivity : AppCompatActivity() {
    // 这个模块用于绕过客户后端，直接粘贴 NewTypeConnectionCredential 测试 SDK 连接。
    // 适合排查“后端已返回凭证，但 App/SDK 连接异常”的问题。
    private var client: NewTypeSessionClient? = null
    private var stateJob: Job? = null
    private var eventJob: Job? = null
    private var latestState: SessionConnectionState = SessionConnectionState()
    private var activeCredential: DirectCredentialInput? = null
    private var lastLoggedPhase: SessionPhase? = null

    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var summaryText: TextView
    private lateinit var joinButton: Button
    private lateinit var leaveButton: Button
    private lateinit var pttButton: Button
    private lateinit var interruptButton: Button
    private lateinit var vadModeGroup: RadioGroup
    private lateinit var vadModeOff: RadioButton
    private lateinit var vadModeSemiAuto: RadioButton
    private lateinit var vadModeFullAuto: RadioButton
    private lateinit var vadPresetGroup: RadioGroup
    private lateinit var vadPresetSensitive: RadioButton
    private lateinit var vadPresetNatural: RadioButton
    private lateinit var vadPresetChild: RadioButton
    private lateinit var sessionIdInput: EditText
    private lateinit var roomNameInput: EditText
    private lateinit var connectionUrlInput: EditText
    private lateinit var connectionTokenInput: EditText
    private lateinit var identityInput: EditText
    private lateinit var expiresInInput: EditText

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(TAG, "[NT-DIRECT][PERMISSION] RECORD_AUDIO result granted=$granted")
        if (!granted) {
            toast("需要麦克风权限")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "[NT-DIRECT][LIFECYCLE] onCreate")
        setContentView(R.layout.activity_main)
        bindViews()
        bindActions()
        ensureMicPermission()
        renderState(latestState)
    }

    private fun bindViews() {
        Log.d(TAG, "[NT-DIRECT][UI] bindViews")
        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        summaryText = findViewById(R.id.summaryText)
        joinButton = findViewById(R.id.joinButton)
        leaveButton = findViewById(R.id.leaveButton)
        pttButton = findViewById(R.id.pttButton)
        interruptButton = findViewById(R.id.interruptButton)
        vadModeGroup = findViewById(R.id.vadModeGroup)
        vadModeOff = findViewById(R.id.vadModeOff)
        vadModeSemiAuto = findViewById(R.id.vadModeSemiAuto)
        vadModeFullAuto = findViewById(R.id.vadModeFullAuto)
        vadPresetGroup = findViewById(R.id.vadPresetGroup)
        vadPresetSensitive = findViewById(R.id.vadPresetSensitive)
        vadPresetNatural = findViewById(R.id.vadPresetNatural)
        vadPresetChild = findViewById(R.id.vadPresetChild)
        sessionIdInput = findViewById(R.id.sessionIdInput)
        roomNameInput = findViewById(R.id.roomNameInput)
        connectionUrlInput = findViewById(R.id.connectionUrlInput)
        connectionTokenInput = findViewById(R.id.connectionTokenInput)
        identityInput = findViewById(R.id.identityInput)
        expiresInInput = findViewById(R.id.expiresInInput)
    }

    private fun bindActions() {
        Log.d(TAG, "[NT-DIRECT][UI] bindActions")
        // Connect：读取页面输入的连接凭证，直接传给 SDK，不请求任何客户后端。
        joinButton.setOnClickListener { connectSession() }
        // Leave：只断开 SDK 实时连接；此模块没有业务后端结束接口。
        leaveButton.setOnClickListener { leaveSession() }
        // Interrupt：主动打断当前 AI/TTS 回复，用于直接凭证场景验证中断能力。
        interruptButton.setOnClickListener {
            val activeClient = client ?: return@setOnClickListener
            Log.i(TAG, "[NT-DIRECT][INTERRUPT] click interrupt")
            lifecycleScope.launch { activeClient.interrupt() }
        }
        vadModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.vadModeOff -> VadMode.OFF
                R.id.vadModeSemiAuto -> VadMode.SEMI_AUTO
                R.id.vadModeFullAuto -> VadMode.FULL_AUTO
                else -> VadMode.FULL_AUTO
            }
            Log.i(TAG, "[NT-DIRECT][VAD] mode selected=$mode")
            client?.setVadMode(mode)
            renderState(latestState)
        }
        vadPresetGroup.setOnCheckedChangeListener { _, _ ->
            val preset = getCurrentVadPreset()
            Log.i(TAG, "[NT-DIRECT][VAD] preset selected=$preset")
            client?.setVadPreset(preset)
            renderState(latestState)
        }
        pttButton.setOnTouchListener { _, event ->
            val activeClient = client ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // PTT/半自动模式下，按下按钮开始采集一轮用户发言。
                    Log.i(TAG, "[NT-DIRECT][PTT] ACTION_DOWN startSpeaking")
                    lifecycleScope.launch { activeClient.startSpeaking() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 松手和取消都必须 stopSpeaking，防止一轮发言一直处于 recording。
                    Log.i(TAG, "[NT-DIRECT][PTT] ACTION_UP/CANCEL stopSpeaking action=${event.actionMasked}")
                    lifecycleScope.launch { activeClient.stopSpeaking() }
                    true
                }
                else -> false
            }
        }
    }

    private fun connectSession() {
        val credentialInput = readCredentialInput() ?: return
        Log.i(TAG, "[NT-DIRECT][CONNECT] start ${credentialInput.safeSummary()} mode=${getCurrentVadMode()} preset=${getCurrentVadPreset()}")
        Log.d(TAG, "[NT-DIRECT][CONNECT] close previous client if present hasClient=${client != null}")
        client?.close()
        Log.d(TAG, "[NT-DIRECT][CONNECT] creating SDK client")
        client = NewTypeSessionClient.create(this)
        val activeClient = client ?: return
        activeCredential = credentialInput
        // 先设置 VAD，再监听状态，最后 connect，便于看到完整连接过程日志。
        activeClient.setVadPreset(getCurrentVadPreset())
        activeClient.setVadMode(getCurrentVadMode())
        observeClient(activeClient)

        lifecycleScope.launch {
            runCatching {
                activeClient.connect(credentialInput.toSdkCredential())
            }.onSuccess {
                Log.i(TAG, "[NT-DIRECT][CONNECT] SDK connect call completed sessionId=${credentialInput.sessionId}")
            }.onFailure {
                Log.e(TAG, "[NT-DIRECT][CONNECT] failed message=${it.message}", it)
                toast("连接失败：${it.message.orEmpty()}")
            }
        }
    }

    private fun readCredentialInput(): DirectCredentialInput? {
        // 直接凭证测试要求字段完整；token 只做长度/脱敏日志，不打印明文。
        val sessionId = sessionIdInput.trimmedText()
        val roomName = roomNameInput.trimmedText()
        val connectionUrl = connectionUrlInput.trimmedText()
        val connectionToken = connectionTokenInput.text.toString().trim()
        val identity = identityInput.trimmedText()
        val expiresInText = expiresInInput.trimmedText()
        val expiresIn = if (expiresInText.isBlank()) {
            null
        } else {
            expiresInText.toLongOrNull() ?: return showInputError("expiresIn 必须是数字")
        }
        Log.d(TAG, "[NT-DIRECT][INPUT] read sessionId=$sessionId roomName=$roomName url=$connectionUrl identity=$identity token=${connectionToken.maskSecret()} expiresIn=$expiresIn")

        return when {
            sessionId.isBlank() -> showInputError("请输入 sessionId")
            roomName.isBlank() -> showInputError("请输入 roomName")
            connectionUrl.isBlank() -> showInputError("请输入 connectionUrl")
            connectionToken.isBlank() -> showInputError("请输入 connectionToken")
            identity.isBlank() -> showInputError("请输入 identity")
            else -> DirectCredentialInput(
                sessionId = sessionId,
                roomName = roomName,
                connectionUrl = connectionUrl,
                connectionToken = connectionToken,
                identity = identity,
                expiresIn = expiresIn,
            )
        }
    }

    private fun showInputError(message: String): DirectCredentialInput? {
        Log.w(TAG, "[NT-DIRECT][INPUT] $message")
        toast(message)
        return null
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
        val sessionId = activeCredential?.sessionId
        Log.i(TAG, "[NT-DIRECT][LEAVE] requested sessionId=$sessionId")
        lifecycleScope.launch {
            // Direct 测试模块没有业务后端，所以这里只调用 SDK disconnect。
            runCatching { client?.disconnect("user-leave") }
                .onSuccess { Log.i(TAG, "[NT-DIRECT][LEAVE] SDK disconnect completed") }
                .onFailure { Log.e(TAG, "[NT-DIRECT][LEAVE] SDK disconnect failed message=${it.message}", it) }
            activeCredential = null
        }
    }

    private fun observeClient(activeClient: NewTypeSessionClient) {
        Log.i(TAG, "[NT-DIRECT][OBSERVE] attach SDK state/events collectors")
        stateJob?.cancel()
        eventJob?.cancel()
        stateJob = lifecycleScope.launch {
            activeClient.state.collectLatest {
                if (lastLoggedPhase != it.phase) {
                    Log.i(TAG, "[NT-DIRECT][STATE-TRANSITION] ${lastLoggedPhase ?: "<initial>"} -> ${it.phase} sessionId=${it.sessionId}")
                    lastLoggedPhase = it.phase
                }
                Log.i(TAG, "[NT-DIRECT][STATE] phase=${it.phase} sessionId=${it.sessionId} participants=${it.participantCount} micReady=${it.micReady} recording=${it.recording} turnBusy=${it.turnBusy} agent=${it.agentStatus.phase} message=${it.agentStatus.message}")
                Log.d(TAG, "[NT-DIRECT][STATE-DETAIL] transcriptCount=${it.transcript.size} hasSummary=${it.summary != null} latestTranscript=${it.transcript.lastOrNull()?.debugSummary() ?: "-"}")
                renderState(it)
            }
        }
        eventJob = lifecycleScope.launch {
            activeClient.events.collectLatest { event ->
                when (event) {
                    is SessionEvent.Error -> {
                        Log.e(TAG, "[NT-DIRECT][EVENT] error message=${event.message}")
                        toast(event.message)
                    }
                    is SessionEvent.Info -> Log.i(TAG, "[NT-DIRECT][EVENT] info message=${event.message}")
                }
            }
        }
    }

    private fun renderState(state: SessionConnectionState) {
        latestState = state
        val credential = activeCredential
        // 页面状态面板用于现场排查：包含凭证摘要、SDK 状态、麦克风、录音和 Agent 入房情况。
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
            append(
                when (getCurrentVadMode()) {
                    VadMode.OFF -> "PTT 按下说话"
                    VadMode.SEMI_AUTO -> "VAD 半自动"
                    VadMode.FULL_AUTO -> "VAD 全自动"
                },
            )
            append("\npreset=")
            append(
                when (getCurrentVadPreset()) {
                    VADPreset.SENSITIVE -> "灵敏"
                    VADPreset.NATURAL -> "自然"
                    VADPreset.CHILD -> "儿童"
                },
            )
            append("\n\n=== 连接状态 ===")
            append("\n输入 sessionId：")
            append(credential?.sessionId ?: sessionIdInput.trimmedText().ifBlank { "-" })
            append("\n房间：")
            append(credential?.roomName ?: roomNameInput.trimmedText().ifBlank { "-" })
            append("\nIdentity：")
            append(credential?.identity ?: identityInput.trimmedText().ifBlank { "-" })
            append("\nURL：")
            append(credential?.connectionUrl ?: connectionUrlInput.trimmedText().ifBlank { "-" })
            append("\nToken：")
            append(credential?.connectionToken?.maskSecret() ?: connectionTokenInput.text.toString().trim().maskSecret())
            append("\n麦克风：${if (state.micReady) "就绪" else "未就绪"}")
            append("\n录音：${if (state.recording) "进行中" else "待机"}")
        }

        transcriptText.text = state.transcript
            .filter { it.text.isNotBlank() || it.meta.isNotBlank() }
            .joinToString("\n\n") { entry ->
                entry.toDisplayText()
            }
            .ifBlank { "暂无消息" }

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
        Log.d(TAG, "[NT-DIRECT][RENDER] phase=${state.phase.name}, participants=${state.participantCount}, agent=${state.agentStatus.phase.name}, transcript=${state.transcript.size}, hasSummary=${state.summary != null}")
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "[NT-DIRECT][PERMISSION] requesting RECORD_AUDIO")
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            Log.i(TAG, "[NT-DIRECT][PERMISSION] RECORD_AUDIO already granted")
        }
    }

    private fun updateActionButtons(state: SessionConnectionState?) {
        val safeState = state ?: SessionConnectionState()
        val connected = safeState.phase == SessionPhase.CONNECTED
        val connecting = safeState.phase == SessionPhase.CONNECTING
        val currentMode = getCurrentVadMode()
        joinButton.isEnabled = !connecting && !connected
        leaveButton.isEnabled = connected
        pttButton.isEnabled = connected && !safeState.turnBusy && currentMode != VadMode.FULL_AUTO
        interruptButton.isEnabled = connected && safeState.turnBusy
        vadModeGroup.isEnabled = true
        setCredentialInputsEnabled(!connecting && !connected)
        Log.d(TAG, "[NT-DIRECT][BUTTONS] phase=${safeState.phase} connected=$connected connecting=$connecting mode=$currentMode connect=${joinButton.isEnabled} leave=${leaveButton.isEnabled} ptt=${pttButton.isEnabled} interrupt=${interruptButton.isEnabled} inputs=${!connecting && !connected}")
    }

    private fun setCredentialInputsEnabled(enabled: Boolean) {
        sessionIdInput.isEnabled = enabled
        roomNameInput.isEnabled = enabled
        connectionUrlInput.isEnabled = enabled
        connectionTokenInput.isEnabled = enabled
        identityInput.isEnabled = enabled
        expiresInInput.isEnabled = enabled
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        Log.i(TAG, "[NT-DIRECT][LIFECYCLE] onDestroy sessionId=${activeCredential?.sessionId}")
        // Activity 销毁时 close SDK，确保释放麦克风、音频轨道和 VAD 资源。
        stateJob?.cancel()
        eventJob?.cancel()
        client?.close()
        client = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NewTypeDirectTest"
    }
}

private data class DirectCredentialInput(
    val sessionId: String,
    val roomName: String,
    val connectionUrl: String,
    val connectionToken: String,
    val identity: String,
    val expiresIn: Long?,
)

private fun DirectCredentialInput.toSdkCredential(): NewTypeConnectionCredential {
    return NewTypeConnectionCredential(
        sessionId = sessionId,
        roomName = roomName,
        connectionUrl = connectionUrl,
        connectionToken = connectionToken,
        identity = identity,
        expiresIn = expiresIn,
    )
}

private fun DirectCredentialInput.safeSummary(): String {
    return "sessionId=$sessionId roomName=$roomName url=$connectionUrl identity=$identity token=${connectionToken.maskSecret()} expiresIn=$expiresIn"
}

private fun com.newtype.sdkcore.TranscriptEntry.toDisplayText(): String {
    val role = when (speaker.lowercase()) {
        "ai" -> "AI"
        "child" -> "Child"
        else -> speaker.ifBlank { "Unknown" }
    }
    val streamingSuffix = if (streaming) " [streaming]" else ""
    val metaSuffix = if (meta.isBlank()) "" else "\n$meta"
    val content = text.ifBlank { "[无文本内容]" }
    return "$role: $content$streamingSuffix$metaSuffix"
}

private fun com.newtype.sdkcore.TranscriptEntry.debugSummary(): String {
    return "speaker=$speaker, text=${text.replace('\n', ' ').ifBlank { "[无文本内容]" }}, meta=${meta.replace('\n', ' ')}, streaming=$streaming"
        .take(500)
}

private fun EditText.trimmedText(): String = text.toString().trim()

private fun String.maskSecret(): String {
    if (isBlank()) return "<blank>"
    return if (length <= 16) "<len=$length>" else "${take(8)}...${takeLast(6)}(len=$length)"
}
