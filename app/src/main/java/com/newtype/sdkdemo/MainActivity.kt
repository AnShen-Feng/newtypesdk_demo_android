// newtypesdk_android/app/src/main/java/com/newtype/sdkdemo/MainActivity.kt
// ============================================================================
// NewType Android SDK Demo - 主活动（Main Activity）
// ============================================================================
// 此文件是应用的主界面，演示如何集成和使用 NewType SDK
// 主要功能：
// 1. 配置 NewType SDK 连接参数（API 地址、LiveKit URL、Token 端点等）
// 2. 加入/离开语音会话房间
// 3. 实时显示会话状态、转录文本和会话总结
// 4. 支持 VAD（语音活动检测）模式切换和预设选择
// 5. 支持 PTT（Push-to-Talk）按下说话模式
// ============================================================================
package com.newtype.sdkdemo

// ----------------------------------------------------------------------------
// Android 系统导入
// ----------------------------------------------------------------------------
import android.Manifest                                    // 权限常量定义
import android.content.pm.PackageManager                   // 包管理器，用于权限检查
import android.os.Bundle                                   // Activity 状态保存
import android.view.MotionEvent                            // 触摸事件处理
import android.widget.Button                               // 按钮控件
import android.widget.EditText                             // 文本输入框
import android.widget.RadioButton                          // 单选按钮
import android.widget.RadioGroup                           // 单选按钮组
import android.widget.TextView                             // 文本显示控件
import android.widget.Toast                                // 短消息提示
import androidx.activity.result.contract.ActivityResultContracts  // 权限请求合约
import androidx.appcompat.app.AppCompatActivity            // AppCompat 基类 Activity
import androidx.core.content.ContextCompat                 // 兼容性工具类

// ----------------------------------------------------------------------------
// 生命周期和协程导入
// ----------------------------------------------------------------------------
import androidx.lifecycle.lifecycleScope                 // 生命周期作用域，用于自动取消协程
import kotlinx.coroutines.Job                              // 协程作业引用
import kotlinx.coroutines.flow.collectLatest              // Flow 收集操作符

// ----------------------------------------------------------------------------
// NewType SDK 核心导入
// ----------------------------------------------------------------------------
import com.newtype.sdkcore.NewTypeConfig                   // SDK 配置类
import com.newtype.sdkcore.NewTypeSessionClient            // 会话客户端（核心类）
import com.newtype.sdkcore.SessionConnectionState          // 会话连接状态
import com.newtype.sdkcore.SessionEvent                    // 会话事件基类
import com.newtype.sdkcore.SessionJoinRequest              // 加入会话请求
import com.newtype.sdkcore.SessionPhase                    // 会话阶段枚举
import com.newtype.sdkcore.VadMode                         // VAD 模式枚举
import com.newtype.sdkcore.vad.VADPreset                   // VAD 预设枚举

import kotlinx.coroutines.launch                           // 启动协程

// ============================================================================
// MainActivity - 主活动类
// ============================================================================
// 作为应用的主界面，负责：
// 1. 初始化和管理 NewTypeSessionClient 实例
// 2. 绑定 UI 控件和处理用户交互
// 3. 监听和显示会话状态变化
// 4. 处理权限请求
// ============================================================================
class MainActivity : AppCompatActivity() {
    // -------------------------------------------------------------------------
    // SDK 客户端和协程管理
    // -------------------------------------------------------------------------
    // NewType 会话客户端实例 - 核心 SDK 对象，用于所有会话操作
    private var client: NewTypeSessionClient? = null
    
    // 状态监听协程作业引用 - 用于取消状态监听
    private var stateJob: Job? = null
    
    // 事件监听协程作业引用 - 用于取消事件监听
    private var eventJob: Job? = null
    
    // 最新会话状态缓存 - 用于在 UI 更新时获取最新状态
    private var latestState: SessionConnectionState = SessionConnectionState()

    // -------------------------------------------------------------------------
    // UI 控件引用 - 状态显示区域
    // -------------------------------------------------------------------------
    // 状态文本 - 显示当前会话阶段、Agent 状态、参与者数量等
    private lateinit var statusText: TextView
    
    // 转录文本 - 显示 AI 和儿童的对话转录内容
    private lateinit var transcriptText: TextView
    
    // 总结文本 - 显示会话总结和反馈
    private lateinit var summaryText: TextView

    // -------------------------------------------------------------------------
    // UI 控件引用 - 操作按钮
    // -------------------------------------------------------------------------
    // 加入按钮 - 发起加入会话请求
    private lateinit var joinButton: Button
    
    // 离开按钮 - 离开当前会话
    private lateinit var leaveButton: Button
    
    // PTT 按钮 - 按下说话（Push-to-Talk）
    private lateinit var pttButton: Button

    // -------------------------------------------------------------------------
    // UI 控件引用 - VAD 模式选择
    // -------------------------------------------------------------------------
    // VAD 模式单选组
    private lateinit var vadModeGroup: RadioGroup
    // VAD 关闭模式（纯 PTT 模式）
    private lateinit var vadModeOff: RadioButton
    // VAD 半自动模式（自动检测开始，手动结束）
    private lateinit var vadModeSemiAuto: RadioButton
    // VAD 全自动模式（自动检测和结束）
    private lateinit var vadModeFullAuto: RadioButton

    // -------------------------------------------------------------------------
    // UI 控件引用 - VAD 预设选择
    // -------------------------------------------------------------------------
    // VAD 预设单选组
    private lateinit var vadPresetGroup: RadioGroup
    // 灵敏预设 - 更容易触发语音检测
    private lateinit var vadPresetSensitive: RadioButton
    // 自然预设 - 平衡的检测灵敏度
    private lateinit var vadPresetNatural: RadioButton
    // 儿童预设 - 针对儿童语音优化的检测参数
    private lateinit var vadPresetChild: RadioButton

    // -------------------------------------------------------------------------
    // UI 控件引用 - 配置输入框
    // -------------------------------------------------------------------------
    // API 基础地址输入框 - NewType 后端 API 地址
    private lateinit var apiBaseUrlInput: EditText
    
    // LiveKit URL 输入框 - WebRTC 信令服务器地址
    private lateinit var liveKitUrlInput: EditText
    
    // Token 端点路径输入框 - 获取访问令牌的路径
    private lateinit var tokenEndpointInput: EditText
    
    // 房间名称输入框 - 要加入的会话房间名
    private lateinit var roomNameInput: EditText
    
    // 儿童姓名输入框 - 用户身份标识
    private lateinit var childNameInput: EditText
    
    // 年龄输入框 - 用户年龄信息
    private lateinit var ageInput: EditText
    
    // 年级输入框 - 用户年级信息
    private lateinit var gradeInput: EditText

    // -------------------------------------------------------------------------
    // 权限请求处理器
    // -------------------------------------------------------------------------
    // 注册麦克风权限请求结果处理器
    // 使用 ActivityResultContracts.RequestPermission 处理运行时权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),  // 请求单个权限的合约
    ) { granted ->
        // 权限请求结果回调
        // granted: true 表示用户授予权限，false 表示拒绝
        if (!granted) {
            // 如果用户拒绝麦克风权限，显示提示信息
            toast("需要麦克风权限")
        }
    }

    // -------------------------------------------------------------------------
    // Activity 生命周期方法
    // -------------------------------------------------------------------------
    /**
     * Activity 创建回调
     * 在 Activity 首次创建时调用，用于初始化界面和设置
     * @param savedInstanceState 之前保存的状态数据（如果有）
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        // 调用父类 onCreate 方法，完成 Activity 基本初始化
        super.onCreate(savedInstanceState)
        
        // 设置 Activity 的布局文件为 activity_main.xml
        // 该布局定义了所有 UI 控件的排列和属性
        setContentView(R.layout.activity_main)
        
        // 绑定所有 UI 控件引用
        // 通过 findViewById 获取布局中定义的控件实例
        bindViews()
        
        // 绑定所有控件的事件监听器
        // 设置按钮点击、触摸等交互行为
        bindActions()
        
        // 确保已获取麦克风权限
        // 如果未授权则请求运行时权限
        ensureMicPermission()
    }

    // -------------------------------------------------------------------------
    // 私有辅助方法 - UI 绑定
    // -------------------------------------------------------------------------
    /**
     * 绑定所有 UI 控件引用
     * 通过 findViewById 将布局中的控件与 Kotlin 变量关联
     */
    private fun bindViews() {
        // 绑定状态显示控件
        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        summaryText = findViewById(R.id.summaryText)
        
        // 绑定操作按钮
        joinButton = findViewById(R.id.joinButton)
        leaveButton = findViewById(R.id.leaveButton)
        pttButton = findViewById(R.id.pttButton)
        
        // 绑定 VAD 模式选择控件
        vadModeGroup = findViewById(R.id.vadModeGroup)
        vadModeOff = findViewById(R.id.vadModeOff)
        vadModeSemiAuto = findViewById(R.id.vadModeSemiAuto)
        vadModeFullAuto = findViewById(R.id.vadModeFullAuto)
        
        // 绑定 VAD 预设选择控件
        vadPresetGroup = findViewById(R.id.vadPresetGroup)
        vadPresetSensitive = findViewById(R.id.vadPresetSensitive)
        vadPresetNatural = findViewById(R.id.vadPresetNatural)
        vadPresetChild = findViewById(R.id.vadPresetChild)
        
        // 绑定配置输入框
        apiBaseUrlInput = findViewById(R.id.apiBaseUrlInput)
        liveKitUrlInput = findViewById(R.id.liveKitUrlInput)
        tokenEndpointInput = findViewById(R.id.tokenEndpointInput)
        roomNameInput = findViewById(R.id.roomNameInput)
        childNameInput = findViewById(R.id.childNameInput)
        ageInput = findViewById(R.id.ageInput)
        gradeInput = findViewById(R.id.gradeInput)
    }

    /**
     * 绑定所有控件的事件监听器
     * 设置用户交互的响应逻辑
     */
    private fun bindActions() {
        // 加入按钮点击事件 - 发起加入会话请求
        joinButton.setOnClickListener { joinSession() }
        
        // 离开按钮点击事件 - 离开当前会话
        leaveButton.setOnClickListener { leaveSession() }
        
        // VAD 模式切换监听
        // 当用户选择不同的 VAD 模式时，实时更新 SDK 配置
        vadModeGroup.setOnCheckedChangeListener { _, checkedId ->
            // 根据选中的单选按钮 ID 确定 VAD 模式
            val mode = when (checkedId) {
                R.id.vadModeOff -> VadMode.OFF           // 关闭 VAD，使用 PTT 模式
                R.id.vadModeSemiAuto -> VadMode.SEMI_AUTO // 半自动模式
                R.id.vadModeFullAuto -> VadMode.FULL_AUTO // 全自动模式
                else -> VadMode.FULL_AUTO                // 默认全自动
            }
            // 调用 SDK 方法设置新的 VAD 模式
            client?.setVadMode(mode)
            // 重新渲染状态显示，更新 UI 上的模式信息
            renderState(latestState)
        }
        
        // VAD 预设切换监听
        // 当用户选择不同的 VAD 预设时，更新 SDK 的检测参数
        vadPresetGroup.setOnCheckedChangeListener { _, _ ->
            // 获取当前选中的 VAD 预设并应用到 SDK
            client?.setVadPreset(getCurrentVadPreset())
            // 重新渲染状态显示，更新 UI 上的预设信息
            renderState(latestState)
        }
        
        // PTT 按钮触摸事件处理
        // 实现"按下说话，松开停止"的功能
        pttButton.setOnTouchListener { _, event ->
            // 获取当前活跃的客户端实例，如果为 null 则不处理
            val activeClient = client ?: return@setOnTouchListener false
            
            // 根据触摸事件类型执行不同操作
            when (event.actionMasked) {
                // 手指按下事件 - 开始说话
                MotionEvent.ACTION_DOWN -> {
                    // 启动协程调用 SDK 的开始说话方法
                    lifecycleScope.launch { activeClient.startSpeaking() }
                    // 返回 true 表示已处理此事件
                    true
                }
                // 手指抬起或取消事件 - 停止说话
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 启动协程调用 SDK 的停止说话方法
                    lifecycleScope.launch { activeClient.stopSpeaking() }
                    // 返回 true 表示已处理此事件
                    true
                }
                // 其他触摸事件（如移动）- 不处理
                else -> false
            }
        }
    }

    // -------------------------------------------------------------------------
    // 会话管理方法
    // -------------------------------------------------------------------------
    /**
     * 加入会话
     * 创建并配置 NewTypeSessionClient，发起加入房间请求
     * 此方法会：
     * 1. 从输入框读取配置参数
     * 2. 创建新的 SDK 客户端实例
     * 3. 设置 VAD 模式和预设
     * 4. 开始监听状态和事件
     * 5. 发起加入房间请求
     */
    private fun joinSession() {
        // 从 UI 输入框读取配置参数，创建 NewTypeConfig 配置对象
        val config = NewTypeConfig(
            // API 基础地址 - NewType 后端服务的 URL
            apiBaseUrl = apiBaseUrlInput.text.toString().trim(),
            // 默认 LiveKit URL - WebRTC 信令服务器地址
            defaultLiveKitUrl = liveKitUrlInput.text.toString().trim(),
            // Token 端点路径 - 用于获取访问令牌的 API 路径
            tokenEndpointPath = tokenEndpointInput.text.toString().trim(),
        )
        
        // 关闭已存在的客户端实例（如果有），释放资源
        client?.close()
        
        // 创建新的 NewTypeSessionClient 实例
        // create 方法是工厂方法，负责初始化和配置 SDK 客户端
        client = NewTypeSessionClient.create(this, config)
        
        // 获取新创建的客户端实例，如果为 null 则直接返回
        val activeClient = client ?: return
        
        // 设置 VAD 预设 - 根据用户选择的预设配置语音检测参数
        activeClient.setVadPreset(getCurrentVadPreset())
        
        // 设置 VAD 模式 - 根据用户选择的模式配置语音检测行为
        activeClient.setVadMode(getCurrentVadMode())
        
        // 开始监听客户端的状态和事件变化
        observeClient(activeClient)
        
        // 创建加入会话请求对象
        // 包含用户信息和房间信息
        val request = SessionJoinRequest(
            // 儿童姓名 - 用于标识用户身份
            childName = childNameInput.text.toString().trim(),
            // 年龄 - 用户年龄信息，可能用于调整 AI 交互策略
            age = ageInput.text.toString().trim(),
            // 年级 - 用户年级信息，可能用于调整内容难度
            grade = gradeInput.text.toString().trim(),
            // 房间名称 - 要加入的会话房间名
            roomName = roomNameInput.text.toString().trim(),
            // 身份标识 - 用户在房间中的唯一标识
            // 如果姓名为空，则使用默认标识"android-child"
            identity = childNameInput.text.toString().trim().ifBlank { "android-child" },
        )
        
        // 启动协程执行加入操作（异步）
        lifecycleScope.launch {
            // 使用 runCatching 捕获可能的异常
            runCatching {
                // 调用 SDK 的 join 方法加入房间
                client?.join(request)
            }.onFailure {
                // 如果加入失败，显示错误提示
                toast("加入失败：${it.message.orEmpty()}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // VAD 配置获取方法
    // -------------------------------------------------------------------------
    /**
     * 获取当前选中的 VAD 模式
     * @return VadMode 枚举值，表示当前的语音活动检测模式
     */
    private fun getCurrentVadMode(): VadMode {
        // 根据单选按钮组的选中状态返回对应的 VAD 模式
        return when (vadModeGroup.checkedRadioButtonId) {
            R.id.vadModeOff -> VadMode.OFF           // 关闭 VAD，使用 PTT 手动模式
            R.id.vadModeSemiAuto -> VadMode.SEMI_AUTO // 半自动：自动检测开始，手动结束
            R.id.vadModeFullAuto -> VadMode.FULL_AUTO // 全自动：自动检测和结束
            else -> VadMode.FULL_AUTO                // 默认值：全自动模式
        }
    }

    /**
     * 获取当前选中的 VAD 预设
     * @return VADPreset 枚举值，表示当前的语音检测灵敏度预设
     */
    private fun getCurrentVadPreset(): VADPreset {
        // 根据单选按钮组的选中状态返回对应的 VAD 预设
        return when (vadPresetGroup.checkedRadioButtonId) {
            R.id.vadPresetSensitive -> VADPreset.SENSITIVE // 灵敏模式：更容易触发检测
            R.id.vadPresetChild -> VADPreset.CHILD         // 儿童模式：针对儿童语音优化
            R.id.vadPresetNatural -> VADPreset.NATURAL     // 自然模式：平衡的灵敏度
            else -> VADPreset.NATURAL                      // 默认值：自然模式
        }
    }

    /**
     * 离开会话
     * 调用 SDK 的 leave 方法主动离开房间
     * 参数"user-leave"是离开原因，用于日志和状态追踪
     */
    private fun leaveSession() {
        // 启动协程执行离开操作（异步）
        lifecycleScope.launch {
            // 调用 SDK 的 leave 方法，传入离开原因
            client?.leave("user-leave")
        }
    }

    // -------------------------------------------------------------------------
    // 客户端状态监听方法
    // -------------------------------------------------------------------------
    /**
     * 监听客户端状态和事件变化
     * @param activeClient 要监听的 NewTypeSessionClient 实例
     * 
     * 此方法会：
     * 1. 取消之前的监听协程（如果有）
     * 2. 启动新的状态监听协程，实时响应状态变化
     * 3. 启动新的事件监听协程，处理 SDK 事件
     */
    private fun observeClient(activeClient: NewTypeSessionClient) {
        // 取消之前的状态监听协程（如果有），避免重复监听
        stateJob?.cancel()
        
        // 取消之前的事件监听协程（如果有），避免重复监听
        eventJob?.cancel()
        
        // 启动新的状态监听协程
        stateJob = lifecycleScope.launch {
            // 使用 collectLatest 收集状态 Flow
            // 每次状态变化时自动调用 renderState 更新 UI
            activeClient.state.collectLatest { renderState(it) }
        }
        
        // 启动新的事件监听协程
        eventJob = lifecycleScope.launch {
            // 使用 collectLatest 收集事件 Flow
            activeClient.events.collectLatest { event ->
                // 根据事件类型进行处理
                when (event) {
                    // 错误事件 - 显示错误消息提示
                    is SessionEvent.Error -> toast(event.message)
                    // 信息事件 - 无需特殊处理，静默忽略
                    is SessionEvent.Info -> Unit
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 状态渲染和 UI 更新方法
    // -------------------------------------------------------------------------
    /**
     * 渲染会话状态到 UI
     * @param state 当前的 SessionConnectionState 状态对象
     * 
     * 此方法是核心 UI 更新逻辑，负责：
     * 1. 更新状态文本（会话阶段、Agent 状态、参与者数量等）
     * 2. 更新转录文本（显示 AI 和儿童的对话内容）
     * 3. 更新总结文本（显示会话总结和反馈）
     * 4. 更新操作按钮的可用状态
     * 5. 记录日志用于调试
     */
    private fun renderState(state: SessionConnectionState) {
        // 缓存最新状态，供其他方法使用
        latestState = state
        
        // 构建状态文本内容，显示详细的连接信息
        statusText.text = buildString {
            // 会话阶段（IDLE/CONNECTING/CONNECTED/DISCONNECTED）
            append("phase=")
            append(state.phase.name)
            
            // Agent 状态（阶段和消息）
            append("\nagent=")
            append(state.agentStatus.phase.name)
            append(" ")
            append(state.agentStatus.message)
            
            // 参与者数量和 Agent 入房状态
            append("\nparticipants=")
            append(state.participantCount)
            append(" ")
            // 判断 Agent 是否已入房（参与者数>1 表示 Agent 已在房间中）
            append(if (state.participantCount > 1) "(Agent 已入房 ✅)" else "(等待 Agent 入房...)")
            
            // 会话 ID
            append("\nsession=")
            append(state.sessionId ?: "-")
            
            // 当前 VAD 模式
            append("\nmode=")
            append(when (getCurrentVadMode()) {
                VadMode.OFF -> "PTT 按下说话"      // 手动模式
                VadMode.SEMI_AUTO -> "VAD 半自动"   // 半自动模式
                VadMode.FULL_AUTO -> "VAD 全自动"   // 全自动模式
            })
            
            // 当前 VAD 预设
            append("\npreset=")
            append(
                when (getCurrentVadPreset()) {
                    VADPreset.SENSITIVE -> "灵敏"   // 灵敏模式
                    VADPreset.NATURAL -> "自然"     // 自然模式
                    VADPreset.CHILD -> "儿童"       // 儿童模式
                },
            )
            
            // 分隔线和连接状态详情
            append("\n\n=== 连接状态 ===")
            append("\n房间：${roomNameInput.text}")           // 当前房间名
            append("\nLiveKit: ${liveKitUrlInput.text}")     // LiveKit 服务器地址
            append("\nAPI: ${apiBaseUrlInput.text}")         // API 服务器地址
            append("\n麦克风：${if (state.micReady) "就绪 ✅" else "未就绪"}")  // 麦克风就绪状态
            append("\n录音：${if (state.recording) "进行中 🎤" else "待机"}")    // 录音状态
        }
        
        // 渲染转录文本 - 显示 AI 和儿童的对话内容
        // 使用 joinToString 将转录条目列表拼接成字符串
        transcriptText.text = state.transcript.joinToString("\n\n") { entry ->
            // 根据说话者标识确定角色名称
            val role = if (entry.speaker == "ai") "AI" else "Child"
            // 如果有元数据（如时间戳、置信度等），附加在文本后面
            val tail = if (entry.meta.isBlank()) "" else "\n${entry.meta}"
            // 格式化输出："角色：文本内容\n元数据"
            "$role: ${entry.text}$tail"
        }.ifBlank { "暂无消息" }  // 如果转录为空，显示默认提示
        
        // 渲染总结文本 - 显示会话总结和反馈
        // 使用安全调用操作符处理可能为 null 的总结
        summaryText.text = state.summary?.let { summary ->
            // 如果总结存在，构建详细的总结内容
            buildString {
                append(summary.summary)              // 主要总结内容
                append("\n\nDid well: ")             // 做得好的地方
                append(summary.didWell)
                append("\nTip: ")                    // 改进建议
                append(summary.oneTip)
                append("\nNext: ")                   // 下一个话题建议
                append(summary.nextTopic)
                append("\nPronunciation: ")          // 发音重点关注
                append(summary.pronunciationFocus)
            }
        } ?: "暂无总结"  // 如果总结为 null，显示默认提示
        
        // 根据当前状态更新操作按钮的可用状态
        updateActionButtons(state)
        
        // 记录日志到 Logcat，用于调试和问题排查
        android.util.Log.d("MainActivity", "Room state: phase=${state.phase.name}, participants=${state.participantCount}, agent=${state.agentStatus.phase.name}")
    }

    // -------------------------------------------------------------------------
    // 权限管理方法
    // -------------------------------------------------------------------------
    /**
     * 确保已获取麦克风权限
     * 检查 RECORD_AUDIO 权限，如果未授权则发起请求
     * 
     * Android 6.0+ 需要在运行时请求危险权限
     * 麦克风权限属于危险权限，必须用户明确授权
     */
    private fun ensureMicPermission() {
        // 检查是否已授予录音权限
        // ContextCompat.checkSelfPermission 返回权限授予状态
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // 权限未授予，启动权限请求
            // 使用之前注册的 permissionLauncher 发起请求
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        // 如果权限已授予，则无需操作，直接继续
    }

    // -------------------------------------------------------------------------
    // UI 状态更新方法
    // -------------------------------------------------------------------------
    /**
     * 根据会话状态更新操作按钮的可用状态
     * @param state 当前会话状态，可为 null
     * 
     * 按钮状态逻辑：
     * - Leave 按钮：仅在已连接状态可用
     * - PTT 按钮：仅在已连接、非忙碌且非全自动模式时可用
     * - VAD 模式组：始终可用
     */
    private fun updateActionButtons(state: SessionConnectionState?) {
        // 安全处理：如果状态为 null，使用空状态对象
        val safeState = state ?: SessionConnectionState()
        
        // 判断是否已连接到房间
        // 只有 CONNECTED 阶段才表示真正连接成功
        val connected = safeState.phase == SessionPhase.CONNECTED
        
        // 获取当前选中的 VAD 模式
        val currentMode = getCurrentVadMode()
        
        // Leave 按钮：仅在已连接状态可用
        // 未连接时禁止点击，避免无效操作
        leaveButton.isEnabled = connected
        
        // PTT 按钮：需要满足三个条件才可用
        // 1. 已连接到房间
        // 2. 当前不是忙碌状态（turnBusy 表示 AI 正在说话）
        // 3. 当前模式不是全自动模式（全自动模式下无需手动说话）
        pttButton.isEnabled = connected && !safeState.turnBusy && currentMode != VadMode.FULL_AUTO
        
        // VAD 模式组：始终可用，允许用户随时切换模式
        vadModeGroup.isEnabled = true
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------
    /**
     * 显示 Toast 短消息
     * @param message 要显示的消息内容
     * 
     * Toast 是 Android 的轻量级消息提示控件
     * 会自动在屏幕底部显示并消失，无需用户关闭
     */
    private fun toast(message: String) {
        // 创建并显示 Toast 消息
        // Toast.LENGTH_SHORT 表示显示较短时间（约 2 秒）
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------------------------
    // Activity 生命周期方法 - 销毁
    // -------------------------------------------------------------------------
    /**
     * Activity 销毁回调
     * 在 Activity 即将被销毁时调用，用于清理资源
     * 
     * 重要清理工作：
     * 1. 取消所有监听协程，避免内存泄漏
     * 2. 关闭 SDK 客户端，释放网络和音频资源
     * 3. 调用父类 onDestroy 完成清理
     */
    override fun onDestroy() {
        // 取消状态监听协程
        // 防止协程继续运行导致内存泄漏
        stateJob?.cancel()
        
        // 取消事件监听协程
        // 防止协程继续运行导致内存泄漏
        eventJob?.cancel()
        
        // 关闭 SDK 客户端
        // 释放网络连接、音频流、麦克风等资源
        client?.close()
        
        // 清空客户端引用
        // 避免持有已关闭对象的引用
        client = null
        
        // 调用父类 onDestroy 方法
        // 完成 Activity 的标准清理流程
        super.onDestroy()
    }
}
