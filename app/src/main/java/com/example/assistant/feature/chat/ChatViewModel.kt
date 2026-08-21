package com.example.assistant.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.assistant.core.agent.Agent
import com.example.assistant.core.agent.Agent.AgentResult
import com.example.assistant.core.agent.AssistantIntent
import com.example.assistant.core.agent.DiarySummarizer
import com.example.assistant.core.agent.EventExtractor
import com.example.assistant.core.agent.IntentRouter
import com.example.assistant.core.agent.MemoryExtractor
import com.example.assistant.core.agent.ReminderTimeParser
import com.example.assistant.core.agent.Session
import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.storage.ConversationLog
import com.example.assistant.core.storage.SettingsStore
import com.example.assistant.core.vision.ImageUtils
import com.example.assistant.core.vision.ScreenSenseController
import com.example.assistant.core.vision.VisionAnalyzer
import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.repo.EventRepository
import com.example.assistant.data.repo.MemoryRepository
import com.example.assistant.data.repo.ReminderRepository
import com.example.assistant.data.db.entity.parseDiaryTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 聊天界面的一条消息 */
data class ChatUiMessage(
    val id: Long,
    val role: String,        // "user" | "assistant"
    val text: String,
    /** 推理模型的思考过程（独立于正式回答展示，带"思考过程"标注） */
    val thinking: String = "",
    val streaming: Boolean = false,
    /** 消息附带的图片缩略图（识屏截图 / 上传的图片），空表示无图 */
    val image: Bitmap? = null
)

/** 待发送附件：缩略图（附件栏显示）+ base64（发送时给视觉模型） */
data class PendingImage(
    val thumbnail: Bitmap,
    val base64: String
)

/**
 * 聊天核心逻辑（进程级共享单例，AppContainer 创建）：
 * 聊天页与浮动界面（悬浮球展开的面板）共用同一份会话与消息列表——
 * 浮动界面的对话/识屏结果自动留存到 App 聊天记录。
 * 不是 ViewModel：生命周期 = 进程（协程域 [scope] 自管，进程退出才结束）。
 */
class ChatViewModel(
    private val context: Context,
    private val agent: Agent,
    private val intentRouter: IntentRouter,
    private val diarySummarizer: DiarySummarizer,
    private val settingsStore: SettingsStore,
    private val diaryRepository: DiaryRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryExtractor: MemoryExtractor,
    private val reminderRepository: ReminderRepository,
    private val reminderTimeParser: ReminderTimeParser,
    private val reminderScheduler: ReminderScheduler,
    private val eventRepository: EventRepository,
    private val eventExtractor: EventExtractor,
    private val visionAnalyzer: VisionAnalyzer,
    private val screenSenseController: ScreenSenseController,
    private val conversationLog: ConversationLog
) {

    /** 协程域：进程级共享，用 SupervisorJob 防止单个任务失败影响其他任务 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 待发送图片附件（分享/上传后等待用户输入文字，一起发送） */
    private val _pendingImage = MutableStateFlow<PendingImage?>(null)
    val pendingImage: StateFlow<PendingImage?> = _pendingImage

    private val session = Session()
    private var counter = 0L

    /**
     * 识屏指令被真正执行的事件（LLM 分类命中"识屏"时也会走到 executeCommand）：
     * 浮动界面订阅它直接触发自己的识图流程（面板场景 MainActivity 在后台，
     * 原来的 requestScreenSense 事件它收不到，导致 LLM 中转的识屏无后续）。
     */
    private val _screenSenseRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val screenSenseRequested: SharedFlow<Unit> = _screenSenseRequested

    /** 助手消息 id → 触发它的请求消息（重新生成用） */
    private val regenerateMessages = mutableMapOf<Long, List<ChatMessage>>()

    init {
        // 识屏小窗「在 App 中继续」：截图加入附件栏（等用户输入命令，
        // 文字+图片一起发给视觉模型）；小窗里已分析的结果作为一条助手消息保留
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
        // 设置页「聊天上下文长度」实时生效（DataStore 首帧即发当前值，
        // Session.setMaxTurns 会立即按新阈值截断已有历史）
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
     * 浮动界面对话入口（悬浮球展开面板的输入框）：
     * 与聊天页 send() 行为一致（路由/记忆注入/流式回复），
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

    /** 浮动界面「提醒」模式：文本直接创建提醒（自动补"提醒我"前缀提高解析成功率） */
    fun createReminderNow(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _isStreaming.value) return
        conversationLog.log(t)
        scope.launch {
            _isStreaming.value = true
            val normalized = if (t.startsWith("提醒")) t else "提醒我$t"
            val hint = createReminder(AssistantIntent.SetReminder(title = "", timeText = normalized))
            append(ChatUiMessage(counter++, "assistant", hint))
            session.addAssistant(hint)
            _isStreaming.value = false
        }
    }

    /**
     * 浮动界面识图模式对话：文字要求 + 截图**一起**发给视觉模型
     * （与聊天页附件行为一致：不重发图片进会话，用占位文本，后续追问走普通聊天）。
     * 文字为记录意图时，后台把图片+总结文字一并存入日记（与聊天附件一致）。
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
            _messages.update { it + ChatUiMessage(counter++, "user", t, image = thumbnail) }
            if (visionAnalyzer.visionProfile() == null) {
                append(ChatUiMessage(counter++, "assistant", VisionAnalyzer.GUIDE_TEXT))
                session.addAssistant(VisionAnalyzer.GUIDE_TEXT)
            } else {
                val streamingId = counter++
                append(ChatUiMessage(streamingId, "assistant", "", streaming = true))
                val instruction = t.ifBlank { "请描述这张图片" }
                val answer = streamVisionReply(imageBase64, instruction, streamingId)
                session.addAssistant(answer)
            }
            // 记录意图（关键词/LLM 判断）：视觉回复完成后，后台把图片+总结文字一并存入日记
            detectRecordAndSave(t, imageBase64)
            _isStreaming.value = false
        }
    }

    /**
     * 浮动界面识图按钮分析完成：把「截图 + 提示词」作为用户消息、
     * 分析结果作为助手消息**一起进聊天记录**（与聊天附件行为一致，
     * 输出区/聊天页都能看到完整一问一答）。
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

    /** 浮动界面「记录」模式：文本直接写入默认日记本，并给出反馈消息 */
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

    /** 普通文字消息：搜索判断 + 路由 + 流式回复（原有逻辑） */
    private suspend fun sendText(text: String) {
        // 秘密功能：记录用户发出的内容（数字分身素材）
        conversationLog.log(text)
        session.addUser(text)
        _messages.update { it + ChatUiMessage(counter++, "user", text) }

        // 带上长期记忆 + 会话历史（含当前消息）：多轮上下文 + 记忆注入
        val memoryText = memoryRepository.memoryContextText()
        when (val result = agent.route(text, memoryText = memoryText, history = session.all)) {
            is AgentResult.Command -> {
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
                // 所有对话都后台做记忆抽取（importance 过滤兜底）——
                // 防止"你要记得"这类不带"记录"关键词但值得记住的信息被漏掉
                extractMemoryInBackground(text)
                val streamingId = counter++
                // 记住请求消息，供"重新生成"复用
                regenerateMessages[streamingId] = result.messages
                append(ChatUiMessage(streamingId, "assistant", "", streaming = true))
                val answer = streamReply(result.messages, streamingId)
                session.addAssistant(answer)
                // 记录类请求：**回复完成后**后台用「最近几轮对话 + 本次助手回复」
                // LLM 总结入日记——总结模型能看到主聊天模型的回复与上下文，
                // 避免孤立总结丢上下文（如「记录一下刚才说的方案」单独看没有信息）；
                // 总结失败回退原文，记录不丢失
                result.recordHint?.let { writeDiaryInBackground() }
            }
        }
    }

    /**
     * 附件消息：文字要求 + 图片**一起**发给「识屏」视觉模型（流式）。
     * 图片不重发进会话（占位文本），后续追问基于回复文本走普通聊天。
     * 若文字是记录意图（关键词/LLM 判断），后台把图片+总结文字一并存入日记
     * （视觉分析照常进行，用户确认：记录 + 照常分析）。
     */
    private suspend fun sendWithImage(text: String, image: PendingImage) {
        val placeholder = if (text.isNotEmpty()) "[📷 用户发送了一张图片]\n$text" else "[📷 用户发送了一张图片]"
        session.addUser(placeholder)
        _messages.update { it + ChatUiMessage(counter++, "user", text, image = image.thumbnail) }
        // 未配置视觉模型 → 明确引导，不发起无意义的调用（记录意图仍照常入日记）
        if (visionAnalyzer.visionProfile() == null) {
            append(ChatUiMessage(counter++, "assistant", VisionAnalyzer.GUIDE_TEXT))
            session.addAssistant(VisionAnalyzer.GUIDE_TEXT)
            detectRecordAndSave(text, image.base64)
            return
        }
        val streamingId = counter++
        append(ChatUiMessage(streamingId, "assistant", "", streaming = true))
        val instruction = text.ifBlank { "请描述这张图片" }
        val answer = streamVisionReply(image.base64, instruction, streamingId)
        session.addAssistant(answer)
        // 记录意图（关键词/LLM 判断）：视觉回复完成后，后台把图片+总结文字一并存入日记
        detectRecordAndSave(text, image.base64)
    }

    /** 记录意图检测 + 图片入日记（聊天附件与悬浮球识图共用；后台执行，不拖慢视觉回复） */
    private fun detectRecordAndSave(text: String, imageBase64: String) {
        if (text.isBlank()) return
        scope.launch {
            val isRecord = intentRouter.keywordRoute(text) is AssistantIntent.RecordDiary ||
                intentRouter.llmClassify(text) is AssistantIntent.RecordDiary
            if (isRecord) saveDiaryWithImageInBackground(imageBase64)
        }
    }

    /**
     * 收集流式回复，实时更新消息文本，返回最终内容。
     * 思考过程与正式回答分开积累、同时展示（思考部分带标注）。
     */
    private suspend fun streamReply(messages: List<ChatMessage>, messageId: Long): String {
        var acc = ""
        var thinking = ""
        try {
            agent.chatStream(messages).collect { chunk ->
                val delta = chunk.choices.firstOrNull()?.delta
                val text = delta?.textContent.orEmpty()
                val think = delta?.reasoningContent.orEmpty()
                if (text.isNotEmpty()) acc += text
                if (think.isNotEmpty()) thinking += think
                updateMessage(messageId) { it.copy(text = acc, thinking = thinking) }
            }
            updateMessage(messageId) { it.copy(streaming = false) }
        } catch (e: Exception) {
            val tail = "\n\n[出错：${e.message}]"
            acc += tail
            updateMessage(messageId) { it.copy(text = acc, streaming = false) }
        }
        return acc
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
            // 清空该条并重新流式
            updateMessage(messageId) { it.copy(text = "", thinking = "", streaming = true) }
            val answer = streamReply(messages, messageId)
            // 会话尾部同步替换为该新回复（保持后续上下文一致）
            session.replaceLastAssistant(answer)
            _isStreaming.value = false
        }
    }

    fun clearConversation() {
        session.clear()
        regenerateMessages.clear()
        _messages.value = emptyList()
    }

    private fun append(msg: ChatUiMessage) {
        _messages.update { it + msg }
    }

    private fun updateMessage(id: Long, transform: (ChatUiMessage) -> ChatUiMessage) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /**
     * 执行命令类意图（本地执行，不走 LLM），返回展示给用户的提示文本。
     * 已实现：设提醒（LLM 解析时间 + AlarmManager 排程）；识屏（请求授权 → 悬浮小窗）；
     * 记录类见 sendText 的 recordHint；事件监控（MonitorEvent）见 createMonitoredEvent。
     */
    private suspend fun executeCommand(intent: AssistantIntent): String = when (intent) {
        // 防御：正常路由下 RecordDiary 不会走到这里（Agent 已转为聊天+recordHint）
        is AssistantIntent.RecordDiary -> "📔 已记入日记本"
        is AssistantIntent.SetReminder -> createReminder(intent)
        is AssistantIntent.ScreenSense -> {
            // 请求 MainActivity 弹 MediaProjection 授权（聊天页场景 MainActivity 在前台）；
            // 同时广播事件——浮动界面订阅后直接走自己的识图流程
            // （面板场景 MainActivity 在后台收不到请求，必须由面板自己触发）
            screenSenseController.requestScreenSense(intent.action)
            _screenSenseRequested.tryEmit(Unit)
            "👁️ 正在准备识屏…\n请在系统弹出的窗口中点「允许」，截屏后浮动界面会显示识别结果"
        }
        is AssistantIntent.MonitorEvent -> createMonitoredEvent(intent)
        is AssistantIntent.Chat -> intent.text
    }

    /** 创建事件监控：LLM 抽取搜索配置 → 入库（轮询由 EventPollWorker 周期执行） */
    private suspend fun createMonitoredEvent(intent: AssistantIntent.MonitorEvent): String {
        val extracted = eventExtractor.extract(intent.query)
            ?: return "🔎 没听清要关注什么。请再说一次，比如：\n\"关注华为手机新品发布\""
        eventRepository.add(
            displayName = extracted.displayName,
            searchQuery = extracted.searchQuery,
            conditionKeywords = extracted.conditionKeywords,
            customRule = extracted.customRule,
            includeDomains = extracted.includeDomains
        )
        return buildString {
            append("🔎 已开始关注「").append(extracted.displayName).append("」（").append(extracted.searchQuery).append("）")
            append("\n来源：").append(extracted.includeDomains.ifBlank { "不限" })
            if (extracted.customRule.isNotBlank()) append("\n规则：").append(extracted.customRule)
            append("\n有重要动态会通知你")
        }
    }

    /** 创建提醒：LLM 解析结构化时间 → 本地算时间戳 → 入库 → AlarmManager 排程 */
    private suspend fun createReminder(intent: AssistantIntent.SetReminder): String {
        val parsed = reminderTimeParser.parse(intent.timeText) ?: return missingTimeHint()
        val triggerAt = reminderTimeParser.resolveTrigger(Calendar.getInstance(), parsed)
        val id = reminderRepository.add(
            title = parsed.title,
            triggerAtEpochMillis = triggerAt,
            repeatRule = parsed.repeat
        )
        reminderScheduler.schedule(reminderRepository.byId(id)!!)
        val timeText = formatTriggerTime(triggerAt)
        val repeatText = when (parsed.repeat) {
            "daily" -> "，每天重复"
            "weekly" -> "，每周重复"
            else -> ""
        }
        return "⏰ 已设置：$timeText 提醒「${parsed.title}」$repeatText"
    }

    private fun missingTimeHint(): String =
        "⏰ 没听清具体时间。请告诉我时间，比如：\n\"提醒我明天下午3点开会\""

    /** 触发时间显示：今天/明天 + HH:mm */
    private fun formatTriggerTime(millis: Long): String {
        val cal = java.util.Calendar.getInstance()
        val now = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        val time = "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        val dayDiff = (cal.get(java.util.Calendar.DAY_OF_YEAR) - now.get(java.util.Calendar.DAY_OF_YEAR))
        return when {
            dayDiff == 0 -> "今天 $time"
            dayDiff == 1 -> "明天 $time"
            else -> "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日 $time"
        }
    }

    /** 聊天同时记录：写入默认「日记」本（启动时已种子创建，这里防御） */
    private suspend fun writeDiary(
        content: String,
        imagePaths: List<String> = emptyList(),
        tags: List<String> = emptyList()
    ) {
        val book = diaryRepository.defaultBook() ?: return
        diaryRepository.addEntry(book.id, content, source = "chat", imagePaths = imagePaths, tags = tags)
    }

    /** 聊天记录：回复完成后后台 LLM 总结 + AI 标签后写日记（独立协程；总结失败回退原文） */
    private fun writeDiaryInBackground() {
        scope.launch {
            val availableTags = parseDiaryTags(settingsStore.diaryTagsCsv.first())
            val result = diarySummarizer.summarize(diaryContext(), availableTags)
            writeDiary(result.summary, tags = result.tags)
        }
    }

    /** 图片记录：图片存 filesDir + 文字 LLM 总结（失败回退原文）后一并入日记，均后台执行 */
    private fun saveDiaryWithImageInBackground(imageBase64: String) {
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                ImageUtils.decodeBase64Bitmap(imageBase64)?.let { bmp ->
                    ImageUtils.saveToFilesDir(
                        context, ImageUtils.scaleBitmap(bmp), "diary_${System.currentTimeMillis()}.jpg"
                    )
                }
            }
            // 图片保存失败（path=null）文字仍照常入日记
            val availableTags = parseDiaryTags(settingsStore.diaryTagsCsv.first())
            val result = diarySummarizer.summarize(diaryContext(), availableTags)
            writeDiary(result.summary, imagePaths = listOfNotNull(path), tags = result.tags)
        }
    }

    /**
     * 记录总结用的对话上下文：**只取最近一轮**（本次用户消息 + 本次助手回复）。
     * 用户话题可能非常跳跃，多轮上下文容易把记录带偏（记到不想记的内容），
     * 所以只用本轮；助手回复能让总结模型看到主聊天模型的确认/回放内容。
     * 调用时机：必须在 session.addAssistant(回复) 之后（见 sendText/视觉回复后）。
     */
    private fun diaryContext(): String =
        session.all.takeLast(2).joinToString("\n") { msg ->
            "${if (msg.role == "user") "用户" else "助手"}：${msg.textContent}"
        }

    /** 后台静默抽取长期记忆（带重要性过滤，评分不足的不存），失败不打扰用户 */
    private fun extractMemoryInBackground(text: String) {
        scope.launch {
            val facts = memoryExtractor.extract(text)
            if (facts.isNotEmpty()) memoryRepository.addFacts(facts)
        }
    }
}
