package com.example.assistant.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.assistant.core.agent.Agent
import com.example.assistant.core.agent.Agent.AgentResult
import com.example.assistant.core.agent.AssistantIntent
import com.example.assistant.core.agent.IntentRouter
import com.example.assistant.core.agent.Session
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.speech.TtsManager
import com.example.assistant.core.storage.ConversationLog
import com.example.assistant.core.storage.SettingsStore
import com.example.assistant.core.vision.ImageUtils
import com.example.assistant.core.vision.ScreenSenseController
import com.example.assistant.core.vision.VisionAnalyzer
import com.example.assistant.data.db.entity.parseDiaryTags
import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.repo.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 聊天界面的一条消息 */
data class ChatUiMessage(
    val id: Long,
    val role: String,        // "user" | "assistant"
    val text: String,
    /** 推理模型的思考过程（独立于正式回答展示，带"思考过程"标注） */
    val thinking: String = "",
    val streaming: Boolean = false,
    /** 消息附带的图片缩略图（识屏截图 / 上传的图片），空表示无图 */
    val image: Bitmap? = null,
    /** 分段内容（按真实时序：思考块/正文段/工具执行行）；非空时优先于 text/thinking 渲染 */
    val segments: List<MsgSegment> = emptyList()
)

/**
 * 助手消息的一个片段（多轮工具回复按真实使用顺序排列）：
 * 思考块与正文段各自成段、工具执行行夹在中间，界面按列表顺序原样渲染。
 */
sealed interface MsgSegment {
    /** 推理模型的一段思考过程（可折叠显示） */
    data class Think(val text: String) : MsgSegment

    /** 一段正文（流式文本；轮次结束时由 Agent 剥掉调用标记后的干净正文） */
    data class Text(val text: String) : MsgSegment

    /** 一次工具执行批（气泡里的「🔧 …」状态行） */
    data class Tools(val labels: List<String>) : MsgSegment
}

/**
 * 消息的可朗读正文：分段消息只取正文段（跳过思考块/工具行）；
 * 普通消息直接用 text。TTS 朗读与 speak 工具共用此提取。
 */
fun ChatUiMessage.spokenBody(): String =
    if (segments.isEmpty()) text
    else segments.filterIsInstance<MsgSegment.Text>()
        .joinToString("\n") { it.text }
        .ifBlank { text }

/** 待发送附件：缩略图（附件栏显示）+ base64（发送时给视觉模型） */
data class PendingImage(
    val thumbnail: Bitmap,
    val base64: String
)

/** 一轮流式回复的产出：最终回答 + 工具信息（写回会话历史 / 记录兜底用） */
private data class StreamOutcome(
    val answer: String,
    /** 工具中间轮记录：(模型输出原文, 回传的结果消息)；未触发工具时为空 */
    val exchanges: List<Pair<String, String>> = emptyList(),
    /** 成功执行过的工具名（write_diary 兜底判断用） */
    val toolNames: List<String> = emptyList()
)

/**
 * 聊天核心逻辑（进程级共享单例，AppContainer 创建）：
 * 聊天页与浮动界面共用同一份会话与消息列表。
 * 主模型统一调度架构：是否调用工具、调用哪个全部由主聊天模型在回复中决定
 * （提醒/记录/记忆/监控/搜索/读网页/识屏），这里只负责路由、渲染与会话维护。
 */
class ChatViewModel(
    private val context: Context,
    private val agent: Agent,
    private val intentRouter: IntentRouter,
    private val settingsStore: SettingsStore,
    private val diaryRepository: DiaryRepository,
    private val memoryRepository: MemoryRepository,
    private val visionAnalyzer: VisionAnalyzer,
    private val screenSenseController: ScreenSenseController,
    private val conversationLog: ConversationLog,
    private val ttsManager: TtsManager
) {

    /** 协程域：进程级共享，用 SupervisorJob 防止单个任务失败影响其他任务 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    /** 是否正在 TTS 朗读（气泡喇叭按钮高亮/停止用） */
    val ttsSpeaking: StateFlow<Boolean> get() = ttsManager.speaking

    /** 当前正在朗读的消息 id（null = 没在读）：点同一条停止、点别的切换的依据 */
    private val _speakingMsgId = MutableStateFlow<Long?>(null)
    val speakingMsgId: StateFlow<Long?> = _speakingMsgId

    init {
        // 朗读自然结束/被打断时清掉「正在读」标记，喇叭按钮回到待朗读样式
        scope.launch {
            ttsManager.speaking.collect { speaking ->
                if (!speaking) _speakingMsgId.value = null
            }
        }
    }

    /** 浮动面板「点悬浮球自动开始听写」开关（面板订阅决定是否自动聆听） */
    val panelAutoVoiceEnabled: StateFlow<Boolean> = settingsStore.panelAutoVoiceEnabled
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), true)

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 待发送图片附件（分享/上传后等待用户输入文字，一起发送） */
    private val _pendingImage = MutableStateFlow<PendingImage?>(null)
    val pendingImage: StateFlow<PendingImage?> = _pendingImage

    private val session = Session()
    private var counter = 0L

    /** 带图用户消息的原图 base64（编辑重发时恢复进附件栏用）；超 40 条丢最旧 */
    private val imageBase64ByMsgId = object : LinkedHashMap<Long, String>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>): Boolean = size > 40
    }

    /**
     * 识屏流程被触发的事件：浮动界面订阅它直接走自己的识图流程。
     * 关键词直连与 screen_sense 工具两条路径统一经 controller.requests 转发到这里，
     * 面板场景 MainActivity 在后台收不到授权请求事件，必须由面板自己触发。
     */
    private val _screenSenseRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val screenSenseRequested: SharedFlow<Unit> = _screenSenseRequested

    /** 助手消息 id → 触发它的请求消息（重新生成用） */
    private val regenerateMessages = mutableMapOf<Long, List<ChatMessage>>()

    init {
        // 识屏结果 → 追加消息 + 截图进附件栏（小窗「在 App 中继续」）
        scope.launch {
            screenSenseController.results.collect { result ->
                if (result.imagePath.isNotEmpty()) {
                    addFileAttachment(result.imagePath)
                }
                if (result.resultText.isNotBlank()) {
                    append(ChatUiMessage(counter++, "assistant", result.resultText))
                    session.addAssistant(result.resultText)
                }
            }
        }
        // 识屏请求 → 通知浮动面板（关键词路径与工具路径统一经此转发）
        scope.launch {
            screenSenseController.requests.collect { _screenSenseRequested.tryEmit(Unit) }
        }
        // 外部分享图片 → 附件栏（不自动分析，等用户输入要求一起发送）
        scope.launch {
            screenSenseController.imageShares.collect { share ->
                loadImageToPending(share.uri)
            }
        }
        // 外部分享文本 → 预填输入框（用户确认后发送）
        scope.launch {
            screenSenseController.textShares.collect { text ->
                _inputText.value = text
            }
        }
        // 设置页「聊天上下文长度」实时生效
        scope.launch {
            settingsStore.conversationMaxTurns.collect { session.setMaxTurns(it) }
        }
    }

    fun setInput(text: String) {
        _inputText.value = text
    }

    /** 聊天上传/分享图片 → 读图、缩放、进入附件栏 */
    fun setPendingImageFromUri(uri: Uri) = loadImageToPending(uri)

    fun removePendingImage() {
        _pendingImage.value = null
    }

    private fun loadImageToPending(uri: Uri) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageUtils.readUriBitmap(context, uri)
            }
            if (bitmap == null) {
                _error.value = "无法读取这张图片，请换一张试试"
                return@launch
            }
            _pendingImage.value = PendingImage(
                thumbnail = ImageUtils.thumbnail(bitmap),
                base64 = ImageUtils.bitmapToBase64(bitmap)
            )
        }
    }

    /** 本地截图文件加入附件栏（识屏小窗「在 App 中继续」），与分享/上传图片同一交互 */
    private fun addFileAttachment(path: String) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    android.graphics.BitmapFactory.decodeFile(path)
                } catch (_: Exception) {
                    null
                }
            }
            if (bitmap == null) {
                _error.value = "无法读取截图，请重新识屏"
                return@launch
            }
            _pendingImage.value = PendingImage(
                thumbnail = ImageUtils.thumbnail(bitmap),
                base64 = ImageUtils.bitmapToBase64(bitmap)
            )
        }
    }

    fun send() {
        val text = _inputText.value.trim()
        val image = _pendingImage.value
        if (text.isEmpty() && image == null) return
        if (_isStreaming.value) return
        _inputText.value = ""
        _pendingImage.value = null
        scope.launch {
            _isStreaming.value = true
            _error.value = null
            if (image != null) {
                sendWithImage(text, image)
            } else {
                sendText(text)
            }
            _isStreaming.value = false
        }
    }

    /**
     * 朗读一条消息（气泡喇叭按钮）：点「正在读的这条」= 停止；点别的 = 切换朗读新内容。
     * 内容取正文字段（分段消息只拼正文段，跳过思考块与工具状态行）。
     */
    fun speakMessage(msg: ChatUiMessage) {
        if (_speakingMsgId.value == msg.id && ttsManager.speaking.value) {
            // 点的是正在读的这条 → 停止
            ttsManager.stop()
            _speakingMsgId.value = null
            return
        }
        _speakingMsgId.value = msg.id
        ttsManager.speak(msg.spokenBody())
    }

    /**
     * 浮动界面对话入口：与聊天页 send() 行为一致（主模型工具回路流式回复），
     * 但不碰输入框/附件栏状态（面板有自己的输入框）。
     */
    fun quickSend(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _isStreaming.value) return
        scope.launch {
            _isStreaming.value = true
            _error.value = null
            sendText(t)
            _isStreaming.value = false
        }
    }

    /**
     * 浮动界面「提醒」模式：统一走对话回路——主模型经 set_reminder 工具完成
     * （自动补"提醒"前缀提高语义清晰度；解析质量与聊天路径完全一致）。
     */
    fun createReminderNow(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _isStreaming.value) return
        conversationLog.log(t)
        scope.launch {
            _isStreaming.value = true
            _error.value = null
            val normalized = if (t.startsWith("提醒")) t else "提醒我$t"
            sendText(normalized)
            _isStreaming.value = false
        }
    }

    /**
     * 浮动界面识图模式对话：文字要求 + 截图**一起**发给视觉模型（流式）。
     * 图片不重发进会话（占位文本），后续追问基于回复文本走普通聊天。
     */
    fun quickSendVision(text: String, imageBase64: String, thumbnail: Bitmap?) {
        val t = text.trim()
        if (t.isEmpty() || _isStreaming.value) return
        conversationLog.log(t)
        scope.launch {
            _isStreaming.value = true
            _error.value = null
            val placeholder = if (t.isNotEmpty()) "[📷 屏幕截图]\n$t" else "[📷 屏幕截图]"
            session.addUser(placeholder)
            val visionMsgId = counter++
            _messages.update { it + ChatUiMessage(visionMsgId, "user", t, image = thumbnail) }
            imageBase64ByMsgId[visionMsgId] = imageBase64
            if (visionAnalyzer.visionProfile() == null) {
                append(ChatUiMessage(counter++, "assistant", VisionAnalyzer.GUIDE_TEXT))
                session.addAssistant(VisionAnalyzer.GUIDE_TEXT)
            } else {
                val streamingId = counter++
                append(ChatUiMessage(streamingId, "assistant", "", streaming = true))
                val instruction = t.ifBlank { "请描述这张图片" }
                val answer = streamVisionReply(imageBase64, instruction, streamingId)
                session.addAssistant(answer)
                // 视觉回复完成后：记录类请求走静默工具回路整理入库（失败自动兜底原文）
                handleVisionRecordInBackground(t, answer, imageBase64)
            }
            _isStreaming.value = false
        }
    }

    /**
     * 浮动界面识图按钮分析完成：把「截图 + 提示词」作为用户消息、
     * 分析结果作为助手消息一起进聊天记录。
     */
    fun quickAnalyzeResult(imagePath: String, instruction: String, resultText: String) {
        if (instruction.isNotBlank()) conversationLog.log(instruction)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    android.graphics.BitmapFactory.decodeFile(imagePath)
                } catch (_: Exception) {
                    null
                }
            }
            val thumbnail = bmp?.let { ImageUtils.thumbnail(it) }
            val placeholder = if (instruction.isNotEmpty()) "[📷 屏幕截图]\n$instruction" else "[📷 屏幕截图]"
            session.addUser(placeholder)
            _messages.update { it + ChatUiMessage(counter++, "user", instruction, image = thumbnail) }
            append(ChatUiMessage(counter++, "assistant", resultText))
            session.addAssistant(resultText)
        }
    }

    /** 浮动界面「记录」模式：文本直接写入默认日记本（不经模型），并给出反馈消息 */
    fun writeDiaryNow(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        conversationLog.log(t)
        scope.launch {
            writeDiary(t)
            val hint = "📔 已记入日记本"
            append(ChatUiMessage(counter++, "assistant", hint))
            session.addAssistant(hint)
        }
    }

    /** 普通文字消息：路由 + 主模型工具回路流式回复 */
    private suspend fun sendText(text: String) {
        // 秘密功能：记录用户发出的内容（数字分身素材）
        conversationLog.log(text)
        session.addUser(text)
        _messages.update { it + ChatUiMessage(counter++, "user", text) }

        // 长期记忆注入 + 日记标签词汇表（易变上下文，供 write_diary 选标签）
        val memoryText = memoryRepository.memoryContextText()
        val diaryTags = parseDiaryTags(settingsStore.diaryTagsCsv.first())
        when (val result = agent.route(text, memoryText = memoryText, history = session.all, diaryTags = diaryTags)) {
            is AgentResult.Command -> {
                // 目前只有识屏关键词直连会走到这里
                val hint = executeCommand(result.intent)
                append(ChatUiMessage(counter++, "assistant", hint))
                session.addAssistant(hint)
            }
            is AgentResult.Error -> {
                val msg = "⚠️ ${result.message}"
                append(ChatUiMessage(counter++, "assistant", msg))
                session.addAssistant(msg)
            }
            is AgentResult.ChatRequested -> {
                val streamingId = counter++
                // 记住请求消息，供"重新生成"复用
                regenerateMessages[streamingId] = result.messages
                append(ChatUiMessage(streamingId, "assistant", "", streaming = true))
                val outcome = streamReply(result.messages, streamingId)
                // 工具中间轮写回会话历史：后续追问时模型能看到调用过什么、拿到过什么结果
                outcome.exchanges.forEach { (request, resultsMsg) ->
                    session.addAssistant(request)
                    session.addUser(resultsMsg)
                }
                session.addAssistant(outcome.answer)
                // 记录兜底："记录…"类请求但模型没调 write_diary → 静默存原文（记录不能丢）
                if (!outcome.toolNames.contains("write_diary") && intentRouter.looksLikeDiaryRequest(text)) {
                    writeDiary(text)
                }
            }
        }
    }

    /**
     * 附件消息：文字要求 + 图片一起发给「识屏」视觉模型（流式）。
     * 图片不重发进会话（占位文本），后续追问基于回复文本走普通聊天。
     */
    private suspend fun sendWithImage(text: String, image: PendingImage) {
        val placeholder = if (text.isNotEmpty()) "[📷 用户发送了一张图片]\n$text" else "[📷 用户发送了一张图片]"
        session.addUser(placeholder)
        val newId = counter++
        _messages.update { it + ChatUiMessage(newId, "user", text, image = image.thumbnail) }
        imageBase64ByMsgId[newId] = image.base64
        // 未配置视觉模型 → 明确引导，不发起无意义的调用（记录意图仍照常入日记）
        if (visionAnalyzer.visionProfile() == null) {
            append(ChatUiMessage(counter++, "assistant", VisionAnalyzer.GUIDE_TEXT))
            session.addAssistant(VisionAnalyzer.GUIDE_TEXT)
            handleVisionRecordInBackground(text, "", image.base64)
            return
        }
        val streamingId = counter++
        append(ChatUiMessage(streamingId, "assistant", "", streaming = true))
        val instruction = text.ifBlank { "请描述这张图片" }
        val answer = streamVisionReply(image.base64, instruction, streamingId)
        session.addAssistant(answer)
        // 视觉回复完成后：记录类请求走静默工具回路整理入库（失败自动兜底原文）
        handleVisionRecordInBackground(text, answer, image.base64)
    }

    /**
     * 视觉消息的记录处理：先落图片文件，再跑一个**静默工具回路**——把用户要求+视觉分析
     * 结果作为素材交给主模型，由它正常调 write_diary 整理正文/选标签/带图片路径入库
     * （顺带也能 write_memory）。失败或模型没写 → 兜底原文+图直接入日记（记录不能丢）。
     */
    private fun handleVisionRecordInBackground(userText: String, visionAnswer: String, imageBase64: String) {
        if (userText.isBlank()) return
        if (!intentRouter.looksLikeDiaryRequest(userText)) return
        scope.launch {
            // 图片先落盘（write_diary 的 image_paths 参数直接引用该路径）
            val imagePath = withContext(Dispatchers.IO) {
                ImageUtils.decodeBase64Bitmap(imageBase64)?.let { bmp ->
                    ImageUtils.saveToFilesDir(
                        context, ImageUtils.scaleBitmap(bmp), "diary_${System.currentTimeMillis()}.jpg"
                    )
                }
            }
            val memoryText = memoryRepository.memoryContextText()
            val diaryTags = parseDiaryTags(settingsStore.diaryTagsCsv.first())
            val task = buildString {
                append("[后台任务] 用户刚发送了一张图片并对它说：「${userText}」，其中包含记录日记的意愿。")
                append("\n视觉模型对该图片的分析结果：\n").append(visionAnswer.take(1500))
                if (imagePath != null) {
                    append("\n图片已保存到本地路径：").append(imagePath)
                }
                append("\n请整理成一条简洁日记并用 write_diary 写入（有图片路径就放进 image_paths 参数；")
                append("对话里有值得长期记住的信息也可用 write_memory）。完成后只需简短确认。")
            }
            val result = agent.silentReply(task, memoryText, diaryTags)
            if (!result.ok || !result.toolNames.contains("write_diary")) {
                // 兜底：原文+图直接入日记（记录不能丢）
                writeDiary(userText, imagePaths = listOfNotNull(imagePath))
            }
        }
    }

    /**
     * 收集一轮完整回复（含主模型驱动的工具循环），实时更新消息文本。
     * - Delta：流式增量追加到气泡基线之后
     * - RoundSettled：本轮正文并入基线（纯调用轮为空=重置流式区）
     * - ToolsRunning：气泡切到「🔧 …」执行状态
     * - Final：替换为最终回答
     */
    private suspend fun streamReply(messages: List<ChatMessage>, messageId: Long): StreamOutcome {
        var base = ""      // 已定格正文（各轮保留正文的累加；维护 text 字段供复制/滚动）
        var roundAcc = ""  // 本轮流式文本累计
        var textThisRound = false  // 本轮是否流出过正文（RoundSettled 时定位要清洗的段）
        var exchanges: List<Pair<String, String>> = emptyList()
        var toolNames: List<String> = emptyList()
        var finalAnswer: String? = null
        // 分段时间线：思考块/正文段/工具执行行按真实顺序排列，界面原样渲染
        val segments = mutableListOf<MsgSegment>()

        fun appendThink(delta: String) {
            if (delta.isEmpty()) return
            val last = segments.lastOrNull()
            if (last is MsgSegment.Think) segments[segments.lastIndex] = last.copy(text = last.text + delta)
            else segments += MsgSegment.Think(delta)
        }

        fun appendText(delta: String) {
            if (delta.isEmpty()) return
            textThisRound = true
            val last = segments.lastOrNull()
            if (last is MsgSegment.Text) segments[segments.lastIndex] = last.copy(text = last.text + delta)
            else segments += MsgSegment.Text(delta)
        }

        fun snapshot(textShown: String) {
            updateMessage(messageId) {
                it.copy(text = textShown, segments = segments.toList())
            }
        }

        try {
            agent.chatReplyFlow(messages).collect { ev ->
                when (ev) {
                    is Agent.ReplyEvent.Delta -> {
                        appendThink(ev.thinking)
                        appendText(ev.text)
                        roundAcc += ev.text
                        val shown = (if (base.isEmpty()) "" else "$base\n\n") + roundAcc
                        snapshot(shown)
                    }
                    is Agent.ReplyEvent.RoundSettled -> {
                        base = when {
                            base.isEmpty() -> ev.prose
                            ev.prose.isEmpty() -> base
                            else -> "$base\n\n${ev.prose}"
                        }
                        // 本轮流出的正文段：替换为剥掉调用标记后的干净正文（纯调用轮没有正文段）
                        if (textThisRound) {
                            val li = segments.indexOfLast { it is MsgSegment.Text }
                            if (li >= 0) {
                                if (ev.prose.isEmpty()) segments.removeAt(li)
                                else segments[li] = MsgSegment.Text(ev.prose)
                            }
                        }
                        textThisRound = false
                        roundAcc = ""
                        snapshot(base)
                    }
                    is Agent.ReplyEvent.ToolsRunning -> {
                        segments += MsgSegment.Tools(ev.labels)
                        val status = "🔧 " + ev.labels.joinToString("、") + " …"
                        snapshot(if (base.isEmpty()) status else "$base\n\n$status")
                    }
                    is Agent.ReplyEvent.Final -> {
                        exchanges = ev.exchanges
                        toolNames = ev.toolNames
                        finalAnswer = ev.answer
                        snapshot(ev.answer)
                    }
                }
            }
            updateMessage(messageId) { it.copy(streaming = false) }
        } catch (e: Exception) {
            val tail = "[出错：${e.message}]"
            appendText(tail)
            snapshot((base + "\n\n" + roundAcc).trim())
            updateMessage(messageId) { it.copy(streaming = false) }
        }
        // 最终回答以 Final 事件为准；异常中断时退回已定格正文+本轮累计
        val answer = finalAnswer ?: (base + "\n\n" + roundAcc).trim()
        return StreamOutcome(
            answer = answer,
            exchanges = exchanges,
            toolNames = toolNames
        )
    }

    /** 视觉模型流式回复（附件图片），失败信息附到消息尾部 */
    private suspend fun streamVisionReply(imageBase64: String, instruction: String, messageId: Long): String {
        var acc = ""
        try {
            visionAnalyzer.analyzeStream(imageBase64, instruction).collect { chunk ->
                val delta = chunk.choices.firstOrNull()?.delta
                val text = delta?.textContent.orEmpty()
                if (text.isNotEmpty()) {
                    acc += text
                    updateMessage(messageId) { it.copy(text = acc) }
                }
            }
            // 兜底：流式结束仍无内容（模型返回空/思考吃光配额等），给出明确提示
            if (acc.isBlank()) {
                acc = "（模型没有返回内容，可能思考过程占满输出长度。可关闭思考或更换视觉模型后重试）"
                updateMessage(messageId) { it.copy(text = acc) }
            }
            updateMessage(messageId) { it.copy(streaming = false) }
        } catch (e: Exception) {
            val tail = "\n\n[识屏出错：${e.message}]"
            acc += tail
            updateMessage(messageId) { it.copy(text = acc, streaming = false) }
        }
        return acc
    }

    /**
     * 重新生成某条助手回复：清空该条内容，用记忆的请求参数重新流式生成。
     * 只对最近一次回复有意义（messages 是触发时的快照）。
     */
    fun regenerate(messageId: Long) {
        if (_isStreaming.value) return
        val messages = regenerateMessages[messageId] ?: return
        scope.launch {
            _isStreaming.value = true
            _error.value = null
            // 清空该条并重新流式（分段时间线一并清空重建）
            updateMessage(messageId) { it.copy(text = "", thinking = "", streaming = true, segments = emptyList()) }
            val outcome = streamReply(messages, messageId)
            // 会话尾部同步替换为该新回复（中间工具轮已在上次回复时入历史，这里只换最后的回答）
            session.replaceLastAssistant(outcome.answer)
            _isStreaming.value = false
        }
    }

    /**
     * 编辑重发：撤回「最后一条用户消息」——界面与会话历史同步删掉它及其后的助手回复，
     * 返回原文供调用方填回输入框；用户改完再发送即全新一轮，上下文与界面保持一致。
     * 带图消息：restoreImage=true 时把原图恢复进附件栏（聊天页）；面板无附件栏传 false。
     * 返回 null = 不可撤回（正在流式 / 该条不是最后的用户消息）。
     */
    fun withdrawForEdit(messageId: Long, restoreImage: Boolean = false): String? {
        if (_isStreaming.value) return null
        val list = _messages.value
        val lastUser = list.lastOrNull { it.role == "user" } ?: return null
        if (lastUser.id != messageId) return null
        val idx = list.indexOf(lastUser)
        _messages.update { it.take(idx) }
        session.removeLastTurn()
        if (lastUser.image != null) {
            val b64 = imageBase64ByMsgId.remove(lastUser.id)
            if (restoreImage && b64 != null) {
                _pendingImage.value = PendingImage(lastUser.image, b64)
            }
        }
        return lastUser.text
    }

    fun clearConversation() {
        session.clear()
        regenerateMessages.clear()
        imageBase64ByMsgId.clear()
        _messages.value = emptyList()
    }

    private fun append(msg: ChatUiMessage) {
        _messages.update { it + msg }
    }

    private fun updateMessage(id: Long, transform: (ChatUiMessage) -> ChatUiMessage) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /**
     * 执行命令类意图（目前仅识屏关键词直连）。
     * 浮动面板通知由 init 里对 controller.requests 的统一转发完成，此处不再手动发事件。
     */
    private suspend fun executeCommand(intent: AssistantIntent): String = when (intent) {
        is AssistantIntent.ScreenSense -> {
            // 请求 MainActivity 弹 MediaProjection 授权（聊天页场景 MainActivity 在前台）；
            // 浮动面板经 screenSenseRequested 事件自行触发识图流程
            screenSenseController.requestScreenSense(intent.action)
            "👁️ 正在准备识屏…\n请在系统弹出的窗口中点「允许」，截屏后结果会自动显示"
        }
    }

    /** 聊天同时记录：写入默认「日记」本（启动时已种子创建，这里防御） */
    private suspend fun writeDiary(
        content: String,
        imagePaths: List<String> = emptyList()
    ) {
        val book = diaryRepository.defaultBook() ?: return
        diaryRepository.addEntry(book.id, content, source = "chat", imagePaths = imagePaths)
    }


    /** 后台静默抽取长期记忆的旧入口已删除：对话内记忆改由主模型 write_memory 工具完成 */
}