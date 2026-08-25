package com.example.assistant.feature.floating

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.assistant.AssistantApplication
import com.example.assistant.R
import com.example.assistant.core.speech.PanelVoiceController
import com.example.assistant.core.ui.RichMessageText
import com.example.assistant.core.vision.ImageUtils
import com.example.assistant.core.vision.ScreenSenseStarter
import com.example.assistant.di.AppContainer
import com.example.assistant.feature.chat.ChatUiMessage
import com.example.assistant.feature.chat.ChatViewModel
import com.example.assistant.feature.chat.MsgSegment
import com.example.assistant.ui.theme.AssistantTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 浮动界面（悬浮球展开的玻璃拟态面板，P6 核心）：
 * 透明 Activity + 独立 task（taskAffinity=""）——从任意 App 弹出，
 * 系统返回手势/返回键直接 finish，自动回到原 App（悬浮球由服务重新显示）。
 *
 * 视觉风格：深墨夜景底色（变暗层）+ 屏幕边缘缓慢游移的多色光晕
 * （glassmorphism 氛围光）+ 白色低透明玻璃组件（5-12% 白 + 1px 白描边 +
 * 大圆角 + 顶部内发光，参考 stylekit.top/zh/styles/glassmorphism）。
 *
 * 布局（无大框）：输出区（上，与 App 聊天页同一会话）→ 标题「随身助手」
 * （直接显示于背景）→ 四个功能气泡（识图/提醒/记录/对话，显示于背景上）
 * → 玻璃输入框 + 发送。
 *
 * 两种模式：
 * - MAIN：四气泡 + 对话输入
 * - SCREEN_SENSE：识图结果（小缩略图 + 提取文字/翻译/总结 + 继续对话）
 */
class FloatingPanelActivity : ComponentActivity() {

    /** 面板模式：MAIN=四功能气泡；SCREEN_SENSE=识图结果（缩略图+提取/翻译/总结） */
    enum class PanelMode { MAIN, SCREEN_SENSE }

    private val container: AppContainer
        get() = (application as AssistantApplication).container

    /** 系统「自动旋转」开关监听（onStart 注册 / onStop 注销）：面板开着时切控制中心立刻生效 */
    private var rotateObserver: android.database.ContentObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 朝向总控：自动旋转开 = 跟随物理旋转；关 = 锁定「打开面板时刻前台应用的朝向」
        // （启动方把当时的 rotation 塞进 EXTRA_ORIENTATION；荣耀 ROM 的 sensor 不尊重旋转锁，
        //   必须手动锁，且 Activity 创建前系统就按 manifest 开始转，不能只读自己的 display）
        val preferredRotation = intent.getIntExtra(EXTRA_ORIENTATION, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
        com.example.assistant.core.OrientationUtils.applyPanelOrientation(this, preferredRotation)
        // 面板打开：悬浮球服务据此隐藏悬浮球（防截进截图 + 不遮挡面板）
        isPanelOpen = true
        container.panelState.value = AppContainer.PanelState.PANEL_OPEN
        enableEdgeToEdge()
        val mode = PanelMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: PanelMode.MAIN.name)
        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        // 悬浮球点击路径会带上 true；是否真的自动聆听还看设置开关（面板内判断）
        val autoVoiceRequested = intent.getBooleanExtra(EXTRA_AUTO_VOICE, false)
        setContent {
            AssistantTheme {
                FloatingPanelScreen(
                    vm = container.chatViewModel,
                    mode = mode,
                    imagePath = imagePath,
                    autoVoiceRequested = autoVoiceRequested,
                    onClose = { finishWithSlideOut() },
                    onScreenSense = { startScreenSenseCapture() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isPanelOpen = true
        // 实时跟随系统「自动旋转」开关（面板开着时拉控制中心切换，立刻生效）
        rotateObserver = com.example.assistant.core.OrientationUtils.registerAutoRotateObserver(this) {
            // 开关变化后重新应用朝向规则；锁定方向沿用启动时的记录
            val preferred = intent.getIntExtra(EXTRA_ORIENTATION, Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }
            com.example.assistant.core.OrientationUtils.applyPanelOrientation(this@FloatingPanelActivity, preferred)
        }
    }

    override fun onStop() {
        // 面板不可见（退出/被覆盖/系统回收）：立即标记关闭并把状态恢复为
        // HIDDEN——onDestroy 不一定执行（系统回收 Activity 时只有 onStop 保证回调），
        // 状态不恢复会导致悬浮球永久隐藏（用户遇到的 bug）
        isPanelOpen = false
        if (container.panelState.value == AppContainer.PanelState.PANEL_OPEN) {
            container.panelState.value = AppContainer.PanelState.HIDDEN
        }
        com.example.assistant.core.OrientationUtils.unregisterAutoRotateObserver(this, rotateObserver)
        rotateObserver = null
        super.onStop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 尝试「看穿模糊」（窗口后面内容模糊）：先试公开 FLAG_BLUR_BEHIND，
        // 再试隐藏 API setBlurBehindRadius 精细半径；都不支持就静默退化为纯变暗
        try {
            val lp = window.attributes
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            try {
                val method = lp.javaClass.getMethod("setBlurBehindRadius", Float::class.javaPrimitiveType)
                method.invoke(lp, 30f)
            } catch (_: Exception) {
            }
            window.attributes = lp
        } catch (_: Exception) {
        }
    }

    override fun onBackPressed() {
        // 系统返回手势/返回键：直接退出浮动界面回原 App（用户确认：不做「再次滑动提示」）
        finishWithSlideOut()
    }

    override fun onDestroy() {
        // 面板关闭（返回手势/关闭按钮）→ 悬浮球重现。
        // 注意：只恢复"面板自己置为 PANEL_OPEN"的状态——识图流程中
        // 面板 finish 时状态是 CAPTURING（截屏期悬浮球必须隐藏），
        // 若这里无条件重置为 HIDDEN 会导致悬浮球提前重现、被截进截图
        isPanelOpen = false
        if (container.panelState.value == AppContainer.PanelState.PANEL_OPEN) {
            container.panelState.value = AppContainer.PanelState.HIDDEN
        }
        com.example.assistant.core.OrientationUtils.unregisterAutoRotateObserver(this, rotateObserver)
        rotateObserver = null
        super.onDestroy()
    }

    /** 退出：下滑动画（作用于整个透明窗口）后 finish */
    private fun finishWithSlideOut() {
        finish()
        overridePendingTransition(0, R.anim.panel_slide_out)
    }

    /** 识图：面板收起 → 弹系统授权（独立 task 的权限 Activity）→ 截屏后回到本面板（识图模式） */
    private fun startScreenSenseCapture() {
        container.panelState.value = AppContainer.PanelState.CAPTURING
        ScreenSenseStarter.requestCapture(this)
        finishWithSlideOut()
    }

    companion object {
        private const val EXTRA_MODE = "panel_mode"
        private const val EXTRA_IMAGE_PATH = "image_path"
        /** 启动面板那一刻的屏幕朝向（Surface.ROTATION_*）：自动旋转关闭时锁定到它 */
        private const val EXTRA_ORIENTATION = "panel_orientation"
        /** 本次打开是否请求自动开始语音听写（悬浮球点击 = true；实际还受设置开关控制） */
        private const val EXTRA_AUTO_VOICE = "panel_auto_voice"

        /**
         * 面板是否真的在显示（onStart=true / onStop=false）。
         * 悬浮球服务用它做状态自愈：panelState 停在 PANEL_OPEN 但面板实际已关闭
         * （如系统回收 Activity 未回调 onDestroy）时，服务恢复显示悬浮球。
         */
        var isPanelOpen: Boolean = false
            private set

        /**
         * 打开浮动界面。自动旋转关闭时面板会锁定为「此刻的屏幕朝向」——
         * 即前台应用的朝向（悬浮球/截屏服务从服务侧读 WindowManager 默认显示的 rotation）。
         */
        fun intentFor(
            context: Context,
            mode: PanelMode,
            imagePath: String? = null,
            autoVoice: Boolean = false
        ): Intent =
            Intent(context, FloatingPanelActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_AUTO_VOICE, autoVoice)
                .apply {
                    if (imagePath != null) putExtra(EXTRA_IMAGE_PATH, imagePath)
                    // 记录打开时刻的真实屏幕方向（服务上下文的 display 不受 Activity 影响）
                    putExtra(
                        EXTRA_ORIENTATION,
                        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                            .defaultDisplay.rotation
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
    }
}

/** 浮动界面四个功能气泡（无选中 = 对话模式） */
private enum class QuickAction(val label: String, val accent: Color) {
    SCREEN_SENSE("识屏", Color(0xFF64B5F6)),   // 蓝
    REMINDER("提醒", Color(0xFFE4B863)),       // 香槟金（风格强调色）
    RECORD("记录", Color(0xFF81C784)),         // 绿
    CHAT("对话", Color(0xFFB0BEC5)),           // 蓝灰
}

/** 深墨夜景底色（glassmorphism 风格基底；识屏框选层 RegionPicker 复用同款背景） */
internal val NightDeep = Color(0xFF060A13)
internal val NightBase = Color(0xFF0B1322)

/** 浮动界面主界面：变暗 + 动态光晕 + 输出区 + 背景上的气泡与输入框 */
@Composable
private fun FloatingPanelScreen(
    vm: ChatViewModel,
    mode: FloatingPanelActivity.PanelMode,
    imagePath: String?,
    autoVoiceRequested: Boolean,
    onClose: () -> Unit,
    onScreenSense: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val container = app.container
    val scope = rememberCoroutineScope()

    val messages by vm.messages.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val speakingMsgId by vm.speakingMsgId.collectAsState()

    // 面板输入状态（面板自己的输入框，不碰聊天页输入框）
    var input by remember { mutableStateOf("") }
    // 方案B：悬浮球打开后自动聚焦输入框并弹键盘（引导用键盘上的麦克风语音输入）
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // 选中功能气泡（null = 对话模式；再点同一气泡回对话）
    var selectedMode by remember { mutableStateOf<QuickAction?>(null) }

    // 识图模式：截图缩略图 + 分析状态
    var analyzing by remember { mutableStateOf(false) }
    var thumbnail by remember(imagePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(imagePath) {
        thumbnail = withContext(Dispatchers.IO) {
            imagePath?.let { ImageUtils.decodeThumbnail(it) }
        }
    }

    // LLM 分类命中"识屏"（如"帮我看一下这个屏幕"）→ 直接走识图流程
    // （本地关键词之外的表达也能稳定触发，不依赖后台的 MainActivity）
    LaunchedEffect(Unit) {
        vm.screenSenseRequested.collect {
            onScreenSense()
        }
    }

    // ---- v1.5.x：语音听写（点悬浮球自动开始；识别完自动发送进面板对话） ----
    var listening by remember { mutableStateOf(false) }   // 正在聆听（麦克风开）
    var voicePartial by remember { mutableStateOf("") }   // 实时识别文字预览
    var voiceError by remember { mutableStateOf<String?>(null) } // 听写错误提示（几秒自动消失）
    val panelAutoVoice by vm.panelAutoVoiceEnabled.collectAsState()
    val voice = remember { PanelVoiceController(context) }
    DisposableEffect(Unit) { onDispose { voice.destroy() } }

    // 麦克风权限（RECORD_AUDIO，运行时申请一次）
    val recordAudioPermission = android.Manifest.permission.RECORD_AUDIO

    fun beginListening() {
        if (listening || isStreaming) return
        listening = true
        voicePartial = ""
        voice.start(
            onPartial = { voicePartial = it },
            onFinish = { result, error ->
                listening = false
                when {
                    // 说完 → 直接作为消息发进面板对话（用户确认的交互）
                    !result.isNullOrBlank() -> vm.quickSend(result)
                    error != null -> voiceError = error
                }
            }
        )
    }

    // 注意：局部函数须先声明后引用，permLauncher 放在 beginListening 之后
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginListening() else voiceError = "麦克风权限被拒绝，无法语音输入"
    }

    // 系统语音识别窗（兜底）：内联 SpeechRecognizer 无响应时自动切这个——
    // 荣耀自带语音服务的完整界面，可靠性高；结果回来照样自动发送
    val systemVoiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        listening = false
        val text = res.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull { !it.isNullOrBlank() }
        if (!text.isNullOrBlank()) vm.quickSend(text.trim())
        else voiceError = "没有识别到内容，可再试或改用打字"
    }
    voice.onStalled = {
        listening = false
        try {
            val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "请说出你的问题")
            }
            systemVoiceLauncher.launch(i)
        } catch (_: Exception) {
            // 本机连系统语音窗都没有（荣耀实测）：平滑降级到键盘语音
            voiceError = "本机不支持系统语音识别，已为你弹出键盘（可点键盘上的麦克风）"
            inputFocus.requestFocus()
            keyboard?.show()
        }
    }

    // 聆听中滑系统返回手势/按返回键 = 只停止聆听、留在面板（用户确认的交互）；
    // 非聆听时返回仍走原逻辑关面板（onBackPressed）
    BackHandler(enabled = listening) {
        voice.stop()
        listening = false
        voicePartial = ""
    }

    // 自动触发：悬浮球路径打开 → 等入场动画后：
    //   开关开 = 直接开始系统语音识别（适合支持的机型）；
    //   开关关（默认）= 方案B：自动聚焦输入框并弹出键盘，引导点键盘上的麦克风（输入法语音）
    LaunchedEffect(autoVoiceRequested) {
        if (!autoVoiceRequested) return@LaunchedEffect
        kotlinx.coroutines.delay(450) // 让面板先完成入场动画再动键盘/麦克风
        if (!panelAutoVoice) {
            // 方案B：弹键盘（仅主模式；识屏结果页不打扰）
            if (mode == FloatingPanelActivity.PanelMode.MAIN) {
                inputFocus.requestFocus()
                keyboard?.show()
            }
            return@LaunchedEffect
        }
        if (!voice.available) {
            voiceError = "本机没有可用的语音识别服务，已为你弹出键盘（可点键盘上的麦克风）"
            inputFocus.requestFocus()
            keyboard?.show()
            return@LaunchedEffect
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, recordAudioPermission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) beginListening() else permLauncher.launch(recordAudioPermission)
    }

    // 错误提示几秒后自动消失（荣耀 logcat 不可见，提示必须落在界面上）
    LaunchedEffect(voiceError) {
        if (voiceError != null) {
            kotlinx.coroutines.delay(4500)
            voiceError = null
        }
    }

    // 发送：按选中模式走对应逻辑（提醒/记录直接执行；无选中 = 正常对话，自动分类照旧）
    fun send() {
        val text = input.trim()
        if (text.isEmpty() || isStreaming) return

        // 识图模式：文字要求 + 截图**一起**发给视觉模型（与聊天页附件行为一致）
        if (mode == FloatingPanelActivity.PanelMode.SCREEN_SENSE && imagePath != null) {
            input = ""
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { ImageUtils.decodeThumbnail(imagePath!!) }
                val base64 = withContext(Dispatchers.IO) { ImageUtils.fileToBase64(imagePath!!) }
                vm.quickSendVision(text, base64, bmp)
            }
            return
        }

        // 直接对话时输入"识屏/识图"类指令 → 走识图流程（不依赖 MainActivity 中转）。
        // 与 IntentRouter 的 KEYWORDS_SCREEN 保持一致并补上"识图"；其他表达由
        // LLM 分类命中后经 screenSenseRequested 事件同样触发
        val screenKeywords = listOf("识屏", "识图", "截屏", "截个屏", "这个屏幕", "翻译这个")
        if (selectedMode == null && screenKeywords.any { text.contains(it) }) {
            input = ""
            onScreenSense()
            return
        }

        when (selectedMode) {
            // 识图不是"输入即执行"模式（点击即触发识屏），这里防御性忽略
            QuickAction.SCREEN_SENSE -> {}
            QuickAction.REMINDER -> vm.createReminderNow(text)
            QuickAction.RECORD -> vm.writeDiaryNow(text)
            QuickAction.CHAT, null -> vm.quickSend(text)
        }
        input = ""
    }

    // 识图模式：视觉模型分析（结果进聊天会话 → 输出区显示 + App 聊天记录留存一份，
    // 且聊天记录里能看到「截图 + 提示词」的完整一问一答，见 quickAnalyzeResult）
    fun analyze(key: String) {
        val path = imagePath ?: return
        if (analyzing) return
        analyzing = true
        scope.launch {
            val instruction = ScreenSenseStarter.instructionFor(key)
            val result = withContext(Dispatchers.IO) {
                try {
                    val base64 = ImageUtils.fileToBase64(path)
                    container.visionAnalyzer.analyze(base64, instruction)
                } catch (e: Exception) {
                    "⚠️ 识屏失败：${e.message}"
                }
            }
            vm.quickAnalyzeResult(path, instruction, result)
            analyzing = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 变暗层：深墨夜景色调（玻璃拟态基底，比纯黑更有层次）。
        // 荣耀不支持窗口级模糊，用「深色底 + 噪点蒙层 + 光晕」模拟毛玻璃氛围
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            NightDeep.copy(alpha = 0.88f),
                            NightBase.copy(alpha = 0.68f)
                        )
                    )
                )
        )
        // 噪点蒙层：细颗粒质感，消除纯色"塑料感"（glassmorphism 的 2.5% 噪点）
        NoiseLayer()
        // 屏幕边缘多色光晕（缓慢游移的氛围光）
        EdgeGlow()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()   // 顶部与状态栏不重叠
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // 输出区：面板上方的空白区域，显示最近会话（识图结果也会出现在这里）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                OutputArea(
                    messages = messages,
                    isStreaming = isStreaming,
                    speakingMsgId = speakingMsgId,
                    onSpeak = { vm.speakMessage(it) },
                    onEditResend = { msg -> vm.withdrawForEdit(msg.id)?.let { input = it } },
                    onCopy = { msg ->
                        clipboard?.setText(android.text.SpannableString(msg.text))
                    },
                    onRegenerate = { vm.regenerate(it) }
                )
            }

            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    if (mode == FloatingPanelActivity.PanelMode.SCREEN_SENSE) {
                        // ---- 识图模式：小缩略图 + 三个动作 ----
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 小缩略图：只让用户知道识别的是哪张图
                            thumbnail?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "屏幕截图",
                                    modifier = Modifier
                                        .height(72.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White.copy(alpha = 0.06f))
                                        .border(
                                            1.dp, Color.White.copy(alpha = 0.15f),
                                            RoundedCornerShape(12.dp)
                                        )
                                )
                            }
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "👁️ 已截屏，选择操作或直接输入问题",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onClose) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "关闭",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        ) {
                            listOf(
                                "extract" to (Icons.Filled.TextFields to "提取文字"),
                                "translate" to (Icons.Filled.Translate to "翻译"),
                                "describe" to (Icons.Filled.AutoAwesome to "总结内容")
                            ).forEach { (key, pair) ->
                                GlassActionButton(
                                    text = pair.second,
                                    icon = pair.first,
                                    enabled = !analyzing,
                                    onClick = { analyze(key) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (analyzing) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFFE4B863)
                                )
                                Spacer(Modifier.size(8.dp))
                                Text("分析中…", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    } else {
                        // ---- 主模式 ----
                        // 标题直接显示于背景（玻璃字效果：发光阴影）
                        Text(
                            "✨ 随身助手",
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            style = TextStyle(
                                shadow = Shadow(Color(0xFF000000), blurRadius = 16f)
                            )
                        )
                        Spacer(Modifier.height(12.dp))
                        // 四个功能气泡（显示于背景上，无大框）
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            QuickBubble(
                                modifier = Modifier.weight(1f),
                                action = QuickAction.SCREEN_SENSE,
                                icon = Icons.Filled.Screenshot,
                                selected = selectedMode == QuickAction.SCREEN_SENSE,
                                onClick = {
                                    // 已选中 → 取消回对话；未选中 → 选识图（立即触发识屏）
                                    if (selectedMode == QuickAction.SCREEN_SENSE) {
                                        selectedMode = null
                                    } else {
                                        selectedMode = QuickAction.SCREEN_SENSE
                                        onScreenSense()
                                    }
                                }
                            )
                            QuickBubble(
                                modifier = Modifier.weight(1f),
                                action = QuickAction.REMINDER,
                                icon = Icons.Filled.Alarm,
                                selected = selectedMode == QuickAction.REMINDER,
                                onClick = {
                                    selectedMode =
                                        if (selectedMode == QuickAction.REMINDER) null else QuickAction.REMINDER
                                }
                            )
                            QuickBubble(
                                modifier = Modifier.weight(1f),
                                action = QuickAction.RECORD,
                                icon = Icons.Filled.EditNote,
                                selected = selectedMode == QuickAction.RECORD,
                                onClick = {
                                    selectedMode =
                                        if (selectedMode == QuickAction.RECORD) null else QuickAction.RECORD
                                }
                            )
                            QuickBubble(
                                modifier = Modifier.weight(1f),
                                action = QuickAction.CHAT,
                                icon = Icons.Filled.ChatBubble,
                                selected = selectedMode == null,
                                onClick = { selectedMode = null }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // ---- 语音听写状态（聆听中 / 错误提示，玻璃条样式）----
    if (listening) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFE4B863).copy(alpha = 0.14f))
                .border(1.dp, Color(0xFFE4B863).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text("🎤", fontSize = 16.sp)
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "正在聆听…说完自动发送",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
                if (voicePartial.isNotBlank()) {
                    Text(
                        voicePartial,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 2
                    )
                }
            }
            Text(
                "取消",
                fontSize = 13.sp,
                color = Color(0xFFE4B863),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        voice.stop()
                        listening = false
                        voicePartial = ""
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
    }
    if (voiceError != null) {
        Text(
            "⚠️ $voiceError",
            fontSize = 12.sp,
            color = Color(0xFFEF9A9A),
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }

                    // ---- 玻璃输入行 ----
                    val placeholder = when (selectedMode) {
                        QuickAction.REMINDER -> "输入提醒内容，如：明天下午3点开会"
                        QuickAction.RECORD -> "输入要记录的内容…"
                        else -> "直接对话，如：查一下今天有什么新闻"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = {
                                Text(placeholder, fontSize = 13.sp, color = Color.White.copy(alpha = 0.4f))
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFE4B863),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedContainerColor = Color.White.copy(alpha = 0.08f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                                cursorColor = Color(0xFFE4B863),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.weight(1f).focusRequester(inputFocus)
                        )
                        Spacer(Modifier.size(10.dp))
                        // 发送按钮：香槟金强调色（风格唯一强调色）
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFFE4B863), Color(0xFFC8A25A))
                                    )
                                )
                                .shadow(8.dp, CircleShape)
                                .clickable(enabled = input.isNotBlank() && !isStreaming) { send() }
                        ) {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = "发送",
                                tint = NightDeep,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 输出区：最近会话消息列表（与 App 聊天页同一份数据） */
@Composable
private fun OutputArea(
    messages: List<ChatUiMessage>,
    isStreaming: Boolean,
    speakingMsgId: Long?,
    onSpeak: (ChatUiMessage) -> Unit,
    onEditResend: (ChatUiMessage) -> Unit,
    onCopy: (ChatUiMessage) -> Unit,
    onRegenerate: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    // 新消息/流式更新自动滚到底
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    val lastId = messages.lastOrNull()?.id
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 6.dp)) {
        if (messages.isEmpty()) {
            Text(
                if (isStreaming) "思考中…" else "选中下方功能，或直接开始对话",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp)
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(messages.takeLast(30)) { msg ->
                    MessageBubble(
                        msg = msg,
                        isLastAssistant = msg.role == "assistant" && msg.id == lastId,
                        speakingThis = speakingMsgId == msg.id,
                        onSpeak = { onSpeak(msg) },
                        showEditResend = msg.role == "user" &&
                            msg.id == messages.lastOrNull { it.role == "user" }?.id,
                        onEditResend = { onEditResend(msg) },
                        onCopy = { onCopy(msg) },
                        onRegenerate = { onRegenerate(msg.id) }
                    )
                }
            }
        }
    }
}

/** 单条消息气泡：用户右侧香槟金玻璃、助手左侧白色低透明玻璃（glassmorphism） */
@Composable
private fun MessageBubble(
    msg: ChatUiMessage,
    isLastAssistant: Boolean,
    speakingThis: Boolean,
    onSpeak: () -> Unit,
    showEditResend: Boolean,
    onEditResend: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit
) {
    // （onRegenerate 参数为 () -> Unit，外层已绑定 msg.id）
    val isUser = msg.role == "user"
    val bubbleBg: Brush = if (isUser) {
        Brush.linearGradient(
            listOf(Color(0xFFE4B863).copy(alpha = 0.30f), Color(0xFFC8A25A).copy(alpha = 0.16f))
        )
    } else {
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.05f))
        )
    }
    val shape = RoundedCornerShape(14.dp)
    val showActions = !msg.streaming && (msg.text.isNotEmpty() || msg.thinking.isNotEmpty())
    // 气泡 + 同一行的侧边操作按钮（不单独占一行）：
    // 用户消息图标在气泡左侧（行内最左），助手消息图标在气泡右侧（行内最右）；
    // 图标都靠屏幕中心侧，两边对称；气泡间距保持紧凑
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (isUser && showActions) {
            BubbleActions(
                isUser = true,
                showRegenerate = false,
                showSpeak = false,
                speakingThis = speakingThis,
                onSpeak = onSpeak,
                showEditResend = showEditResend,
                onEditResend = onEditResend,
                onCopy = onCopy,
                onRegenerate = onRegenerate
            )
        }
        Box(
            modifier = Modifier
                // 横向稍窄（0.9 权重），留出行内余量，玻璃气泡不撑满
                .weight(0.9f)
                .clip(shape)
                .background(bubbleBg)
                .border(
                    1.dp,
                    if (isUser) Color(0xFFE4B863).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.15f),
                    shape
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                if (msg.segments.isNotEmpty()) {
                    // 多轮工具回复：思考块/正文段/工具执行行按真实使用顺序渲染（与聊天页一致）
                    msg.segments.forEachIndexed { si, seg ->
                        when (seg) {
                            is MsgSegment.Think -> ThinkingBlock(
                                emoji = "💭",
                                text = seg.text,
                                showCursor = msg.streaming && si == msg.segments.lastIndex
                            )
                            is MsgSegment.Text -> if (seg.text.isNotEmpty()) {
                                SelectionContainer {
                                    RichMessageText(
                                        text = seg.text,
                                        streaming = msg.streaming && si == msg.segments.lastIndex,
                                        style = TextStyle(
                                            fontSize = 14.sp,
                                            lineHeight = 19.sp,
                                            color = Color.White.copy(alpha = 0.92f)
                                        )
                                    )
                                }
                            }
                            is MsgSegment.Tools -> ToolsStatusLine(seg.labels)
                        }
                        if (si != msg.segments.lastIndex) Spacer(Modifier.height(4.dp))
                    }
                } else {
                    // 单段消息：旧字段渲染（思考过程默认收起只显示摘要，点击展开/收起）
                    if (msg.thinking.isNotBlank()) {
                        ThinkingBlock(emoji = "💭", text = msg.thinking, showCursor = msg.streaming && msg.text.isEmpty())
                    }
                    // 富文本渲染：数学公式 + 基础 Markdown（与聊天页一致，同用 core.ui.RichMessageText）
                    SelectionContainer {
                        RichMessageText(
                            text = msg.text,
                            streaming = msg.streaming,
                            style = TextStyle(
                                fontSize = 14.sp,
                                lineHeight = 19.sp,
                                color = Color.White.copy(alpha = 0.92f)
                            )
                        )
                    }
                }
            }
        }
        if (!isUser && showActions) {
            BubbleActions(
                isUser = false,
                showRegenerate = isLastAssistant,
                // 助手消息都有朗读按钮（点正在读的停止，点别的切换朗读）
                showSpeak = true,
                speakingThis = speakingThis,
                onSpeak = onSpeak,
                showEditResend = false,
                onEditResend = onEditResend,
                onCopy = onCopy,
                onRegenerate = onRegenerate
            )
        }
    }
}

/** 可折叠思考块（分段时间线用，浮动界面配色；showCursor=流式进行中且是最后一个片段） */
@Composable
private fun ThinkingBlock(emoji: String, text: String, showCursor: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp)
    ) {
        Text(
            if (expanded) "$emoji 思考过程（点击收起）" else "$emoji 思考过程（点击展开）",
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.45f)
        )
        val shown = if (expanded) text + if (showCursor) "▍" else ""
        else text.take(60) + (if (text.length > 60) "…" else "")
        SelectionContainer {
            Text(
                shown,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.45f)
            )
        }
    }
}

/** 工具执行状态行（气泡内的「🔧 …」小字） */
@Composable
private fun ToolsStatusLine(labels: List<String>) {
    Text(
        "🔧 " + labels.joinToString("、"),
        fontSize = 12.sp,
        color = Color.White.copy(alpha = 0.55f)
    )
}

/** 气泡侧边的操作按钮列（贴底部）：复制；编辑重发（最后一条用户消息）；朗读；重做 */
@Composable
private fun BubbleActions(
    isUser: Boolean,
    showRegenerate: Boolean,
    showSpeak: Boolean,
    speakingThis: Boolean,
    onSpeak: () -> Unit,
    showEditResend: Boolean,
    onEditResend: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit
) {
    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        IconButton(onClick = onCopy, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "复制",
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(13.dp)
            )
        }
        // 编辑重发（仅最后一条文字用户消息）：撤回该轮，文字回输入框改完再发
        if (isUser && showEditResend) {
            IconButton(onClick = onEditResend, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "编辑重发",
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        // 朗读按钮：金色高亮 = 正在朗读这条（再点停止）；灰色 = 待朗读
        if (showSpeak) {
            IconButton(onClick = onSpeak, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Filled.VolumeUp,
                    contentDescription = if (speakingThis) "停止朗读" else "朗读",
                    tint = if (speakingThis) Color(0xFFE4B863) else Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        if (showRegenerate) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "重做",
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * 噪点蒙层：确定性伪随机细颗粒（无状态、不闪烁），
 * 蒙在变暗层上模拟毛玻璃的细腻质感。
 * internal：识屏框选层（RegionPickerActivity）复用同款背景。
 */
@Composable
internal fun NoiseLayer() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        for (i in 0 until 500) {
            val x = fract(sin(i * 127.1f) * 43758.5453f) * w
            val y = fract(sin(i * 311.7f) * 12543.123f) * h
            val a = fract(sin(i * 74.7f) * 26913.123f)
            drawCircle(
                color = Color.White.copy(alpha = 0.012f + a * 0.02f),
                radius = 0.8f,
                center = Offset(x, y)
            )
        }
    }
}

/** 小数部分（确定性伪随机用） */
private fun fract(v: Float): Float = v - kotlin.math.floor(v)

/** 光斑运动方式：顺时针/逆时针沿边缘游走，或 Lissajous 曲线在屏幕内穿越 */
private enum class GlowMotion { CLOCKWISE, COUNTER, CROSS }

/** 单个光斑的规格：颜色 / 亮度 / 大小 / 速度 / 相位 / 运动方式（各不相同 → 随机感）。
 * 设计原则：**小光斑比大光斑更亮**（小斑 alpha 更高） */
private data class GlowSpec(
    val color: Color,
    val alpha: Float,
    val radiusFactor: Float,
    val speed: Float,
    val offset: Float,
    val motion: GlowMotion
)

/**
 * 屏幕边缘多色光晕：多个**大小不同、亮度不同、运动各异**的 radial 光斑。
 * - 大光斑偏暗（氛围铺底），小光斑偏亮（点睛）
 * - 运动：顺时针 / 逆时针沿边缘游走 + 屏幕内 Lissajous 曲线穿越（不贴边）
 * - 亮度呼吸 + 向内漂移，营造自然氛围光
 * internal：识屏框选层（RegionPickerActivity）复用同款背景。
 */
@Composable
internal fun EdgeGlow() {
    val glows = listOf(
        GlowSpec(Color(0xFF64B5F6), 0.30f, 0.36f, 1.0f, 0.00f, GlowMotion.CLOCKWISE), // 蓝，大，顺
        GlowSpec(Color(0xFF4DD0E1), 0.33f, 0.22f, 1.7f, 0.13f, GlowMotion.CLOCKWISE), // 青，中，顺
        GlowSpec(Color(0xFF9575CD), 0.32f, 0.32f, 0.8f, 0.31f, GlowMotion.COUNTER),   // 紫，大，逆
        GlowSpec(Color(0xFFE4B863), 0.40f, 0.13f, 2.3f, 0.42f, GlowMotion.CROSS),     // 金，小，穿越
        GlowSpec(Color(0xFF82B1FF), 0.44f, 0.10f, 3.1f, 0.55f, GlowMotion.CROSS),     // 亮蓝，最小，穿越快
        GlowSpec(Color(0xFF80CBC4), 0.35f, 0.19f, 1.3f, 0.68f, GlowMotion.COUNTER),   // 青绿，中，逆
        GlowSpec(Color(0xFFCE93D8), 0.38f, 0.27f, 0.6f, 0.79f, GlowMotion.CROSS),     // 淡紫，中大，穿越慢
    )
    val infinite = rememberInfiniteTransition(label = "glow")
    // 全局慢相位（24 秒一圈）；每个光斑乘自己的速度 → 相对运动各不相同
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)),
        label = "glowPhase"
    )
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val perimeter = 2f * (w + h)
        glows.forEachIndexed { i, g ->
            val t = (phase * g.speed + g.offset) % 1f
            val finalCenter = when (g.motion) {
                GlowMotion.CLOCKWISE -> {
                    // 顺时针沿边缘游走 + 向内部漂移（不完全贴边）
                    var d = t * perimeter
                    val (center, inwardDir) = when {
                        d < w -> Offset(d, 0f) to Offset(0f, 1f)
                        d < w + h -> Offset(w, d - w) to Offset(-1f, 0f)
                        d < 2f * w + h -> Offset(w - (d - w - h), h) to Offset(0f, -1f)
                        else -> Offset(0f, h - (d - 2f * w - h)) to Offset(1f, 0f)
                    }
                    val drift = (0.05f + 0.07f * sin(t * 6.28f * 3f + i * 1.7f)) * w
                    center + inwardDir * drift
                }
                GlowMotion.COUNTER -> {
                    // 逆时针：边缘进度反向
                    var d = (1f - t) * perimeter
                    val (center, inwardDir) = when {
                        d < w -> Offset(d, 0f) to Offset(0f, 1f)
                        d < w + h -> Offset(w, d - w) to Offset(-1f, 0f)
                        d < 2f * w + h -> Offset(w - (d - w - h), h) to Offset(0f, -1f)
                        else -> Offset(0f, h - (d - 2f * w - h)) to Offset(1f, 0f)
                    }
                    val drift = (0.06f + 0.08f * sin(t * 6.28f * 2.5f + i * 2.1f)) * w
                    center + inwardDir * drift
                }
                GlowMotion.CROSS -> {
                    // Lissajous 曲线：在屏幕内大范围穿越（不同频率比 → 不同轨迹）
                    val ang = t * 6.28f * 2f + g.offset * 6.28f
                    val cx = w * (0.5f + 0.40f * sin(ang * 1.3f))
                    val cy = h * (0.5f + 0.40f * sin(ang * 0.9f + g.offset * 4f))
                    Offset(cx, cy)
                }
            }
            // 亮度呼吸：随移动轻微起伏
            val alphaMod = 0.78f + 0.22f * sin(t * 6.28f * 2f + i)
            val radius = g.radiusFactor * w
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(g.color.copy(alpha = g.alpha * alphaMod), Color.Transparent),
                    center = finalCenter,
                    radius = radius
                ),
                radius = radius,
                center = finalCenter
            )
        }
        // 中心淡淡一圈冷光（整体氛围）
        val cx = w / 2f
        val cy = h / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF4DD0E1).copy(alpha = 0.10f), Color.Transparent),
                center = Offset(cx, cy),
                radius = w * 0.45f
            ),
            radius = w * 0.45f,
            center = Offset(cx, cy)
        )
    }
}

/** 功能气泡：小尺寸玻璃拟态（白色低透明底 + 白描边 + 功能色选中高亮），显示于背景上 */
@Composable
private fun QuickBubble(
    modifier: Modifier = Modifier,
    action: QuickAction,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val bg = if (selected) {
        Brush.linearGradient(
            listOf(action.accent.copy(alpha = 0.32f), action.accent.copy(alpha = 0.16f))
        )
    } else {
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.05f))
        )
    }
    val borderColor = if (selected) action.accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.18f)
    val contentColor = if (selected) action.accent else Color.White.copy(alpha = 0.8f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .shadow(10.dp, shape, ambientColor = NightDeep.copy(alpha = 0.6f))
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp)
    ) {
        Icon(icon, contentDescription = action.label, tint = contentColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(action.label, color = contentColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** 玻璃动作按钮（识图模式三按钮）：白色低透明玻璃 + 白描边 + 图标 */
@Composable
private fun GlassActionButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(if (enabled) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = if (enabled) 0.85f else 0.4f)
            )
        }
    }
}
