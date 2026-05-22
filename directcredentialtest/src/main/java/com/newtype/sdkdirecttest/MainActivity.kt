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
    private var client: NewTypeSessionClient? = null
    private var stateJob: Job? = null
    private var eventJob: Job? = null
    private var latestState: SessionConnectionState = SessionConnectionState()
    private var activeCredential: DirectCredentialInput? = null

    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var summaryText: TextView
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
    private lateinit var sessionIdInput: EditText
    private lateinit var roomNameInput: EditText
    private lateinit var connectionUrlInput: EditText
    private lateinit var connectionTokenInput: EditText
    private lateinit var identityInput: EditText
    private lateinit var expiresInInput: EditText

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
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
        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        summaryText = findViewById(R.id.summaryText)
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
        sessionIdInput = findViewById(R.id.sessionIdInput)
        roomNameInput = findViewById(R.id.roomNameInput)
        connectionUrlInput = findViewById(R.id.connectionUrlInput)
        connectionTokenInput = findViewById(R.id.connectionTokenInput)
        identityInput = findViewById(R.id.identityInput)
        expiresInInput = findViewById(R.id.expiresInInput)
    }

    private fun bindActions() {
        joinButton.setOnClickListener { connectSession() }
        leaveButton.setOnClickListener { leaveSession() }
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
                    Log.i(TAG, "[NT-DIRECT][PTT] ACTION_DOWN startSpeaking")
                    lifecycleScope.launch { activeClient.startSpeaking() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
        client?.close()
        client = NewTypeSessionClient.create(this)
        val activeClient = client ?: return
        activeCredential = credentialInput
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
                Log.i(TAG, "[NT-DIRECT][STATE] phase=${it.phase} sessionId=${it.sessionId} participants=${it.participantCount} micReady=${it.micReady} recording=${it.recording} turnBusy=${it.turnBusy} agent=${it.agentStatus.phase} message=${it.agentStatus.message}")
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
        vadModeGroup.isEnabled = true
        setCredentialInputsEnabled(!connecting && !connected)
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

private fun EditText.trimmedText(): String = text.toString().trim()

private fun String.maskSecret(): String {
    if (isBlank()) return "<blank>"
    return if (length <= 16) "<len=$length>" else "${take(8)}...${takeLast(6)}(len=$length)"
}
