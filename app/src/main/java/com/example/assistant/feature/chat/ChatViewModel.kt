package com.example.assistant.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.assistant.core.agent.Agent
import com.example.assistant.core.agent.Agent.AgentResult
import com.example.assistant.core.agent.AssistantIntent
import com.example.assistant.core.agent.ContextCompactor
import com.example.assistant.core.agent.IntentRouter
import com.example.assistant.core.agent.Session
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.speech.TtsManager
import com.example.assistant.core.storage.ConversationLog
import com.example.assistant.core.storage.ChatSessionStore
import com.example.assistant.core.storage.PrefixSnapshot
import com.example.assistant.core.storage.PrefixSnapshotStore
import com.example.assistant.core.storage.SettingsStore
import com.example.assistant.core.storage.StoredChat
import com.example.assistant.core.storage.StoredSegment
import com.example.assistant.core.storage.StoredTurn
import com.example.assistant.core.storage.StoredUiMessage
import com.example.assistant.core.vision.ImageUtils
import com.example.assistant.core.vision.ScreenSenseController
import com.example.assistant.data.db.entity.parseDiaryTags
import com.example.assistant.data.repo.BufferRepository
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

/**
 * 系统提示消息的角色（2026-09-23）：用于「上下文整理」这类**只上屏、不进模型上下文**的提示行——
 * 它存在 `_messages` 里（可持久化、可划词、跟着轮次一起被删除），
 * 但**绝不写入 Session**，所以模型看不到它，也不会模仿它。
 */
const val ROLE_NOTICE = "notice"

/**
 * 聊天界面的一条消息。`turnId` 指向 Session 里的「轮」——
 * 同一轮的用户气泡与助手气泡共享它，删除单条对话（需求 3）以轮为单位整体处理。
 */
data class ChatUiMessage(
    val id: Long,
    val turnId: Long,
    val role: String,        // "user" | "assistant"
    val text: String,
    /** 推理模型的思考过程（独立于正式回答展示，带"思考过程"标注） */
    val thinking: String = "",
    val streaming: Boolean = false,
    /** 消息附带的图片缩略图（识屏截图 / 上传的图片），空表示无图 */
    val image: Bitmap? = null,
    /** 附带图片在本机的文件路径（chat_images 下；会话只存路径不存 base64） */
    val imagePath: String? = null,
    /** 分段内容（按真实时序：思考块/正文段/工具执行行）；非空时优先于 text/thinking 渲染 */
    val segments: List<MsgSegment> = emptyList(),
    /** 该轮能否「重做」（走对话通道生成的才行） */
    val regenerable: Boolean = false,
    /** 创建时刻（会话记录按保留天数自动清理用；0 = 未知，一律不删） */
    val createdAt: Long = System.currentTimeMillis()
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

/**
 * 上下文状态（聊天页状态行展示用）：轮数窗口 + 字符当量 + **本轮全部请求**的缓存命中。
 * 这些机制对用户本来完全不可见（荣耀 logcat 也拿不到日志），故直接显示到界面上。
 *
 * 2026-09-14：命中率从"最后一次请求"改成"最后一轮对话"的合计——工具回路里一轮可能发好几次
 * 请求（每转一圈一次），只看最后一次会偏乐观（最后一次前缀最长、命中率天然最高）。
 */
data class ContextStatus(
    val turns: Int = 0,
    val minTurns: Int = 5,
    val maxTurns: Int = 20,
    val chars: Int = 0,
    val charLimit: Int = 24_000,
    val promptTokens: Int? = null,
    val cachedTokens: Int? = null,
    val trimmedBySoftCap: Boolean = false,
    /** 本轮发出的模型请求次数（0 = 还没请求过） */
    val requests: Int = 0,
    /** 上一次窗口回落到下限的原因（解释"缓存为什么在这里重置"；null = 还没重置过） */
    val windowReset: String? = null
) {
    /** 缓存命中率（**整轮合计**；厂商未报告缓存字段时为 null） */
    val cacheHitPercent: Int?
        get() {
            val total = promptTokens ?: return null
            val hit = cachedTokens ?: return null
            if (total <= 0) return null
            return (hit * 100 / total).coerceIn(0, 100)
        }
}

/**
 * 上下文整理失败时给用户的可操作提示（**非阻塞**：回答照常，这里只留一条提示）。
 * 重试/放弃都只影响水位线，不影响对话。
 */
data class CompactionNotice(val message: String)

/**
 * 过期会话记录的清理判定（纯函数，便于单测）：返回"可以删掉"的轮 id。
 *
 * **三条必须同时满足**：
 *  1. 该轮创建时刻已超过保留天数（`createdAt <= cutoff`）；
 *  2. 该轮**已不在模型上下文里**（`id !in contextTurnIds`）——还在上下文窗口里的轮一旦被删，
 *     下一次请求的提示词前缀就变了，厂商缓存整段失效（用户明确要求避免的正是这个）；
 *  3. 该轮**已被压缩水位线覆盖**（`id <= coveredThroughTurnId`，2026-09-17 补）——
 *     离开窗口但还没折进「进行中的事」的轮如果先被清理删掉，那段内容就**永久消失**了
 *     （压缩失败/放弃过的批次正属此类，要留到下次上下文整理一起补）。
 * createdAt <= 0 表示时间未知（旧快照解析不出来）：一律保留，宁可不删也不误删。
 */
internal fun expiredTurnIds(
    turnCreatedAt: Map<Long, Long>,
    contextTurnIds: Set<Long>,
    cutoffMillis: Long,
    coveredThroughTurnId: Long
): Set<Long> = turnCreatedAt
    .filter { (id, at) ->
        at > 0L && at <= cutoffMillis && id !in contextTurnIds && id <= coveredThroughTurnId
    }
    .keys

/**
 * 旧快照里"所属轮已经不在上下文、文件里也没留时间"的那批界面消息，给它们估一个创建时刻。
 *
 * 为什么需要：升级前的快照格式**没有** createdAt，那些轮又早已被窗口裁掉（不在 turns 里），
 * 于是时间戳无从得知——按"未知一律保留"的保守规则，它们会**永远**躲过清理（实测用户文件里
 * 91 条消息有 63 条属于这种孤儿，正是最该被清掉的老内容）。
 *
 * 取值：现存最旧一轮的创建时刻（没有就退回快照保存时刻）。因为被裁掉的轮一定比现存最旧的轮更早，
 * 这个估计值**只会比真实时刻更晚** ⇒ 年龄被低估 ⇒ 删除只会更晚发生，绝不会提前误删。
 */
internal fun estimateLegacyCreatedAt(knownTurnTimes: Collection<Long>, savedAt: Long): Long =
    knownTurnTimes.filter { it > 0L }.minOrNull() ?: savedAt

/** 一轮流式回复的产出：最终回答 + 工具信息（写回会话历史 / 记录兜底用） */
private data class StreamOutcome(
    val answer: String,
    /**
     * 进会话历史的正文（不带系统页脚「🔧 已执行：…」）。
     * 页脚写进历史会被模型模仿，在正文里自己编"已执行"清单（2026-09-14 修的 bug）。
     */
    val body: String = answer,
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
 *
 * 2026-09-11 起会话改用「轮」模型（Session.Turn）：
 * 删除单条、编辑重发、重做、上下文上下限裁剪都以轮为单位，界面与上下文不会再错位。
 */
class ChatViewModel(
    private val context: Context,
    private val agent: Agent,
    private val intentRouter: IntentRouter,
    private val settingsStore: SettingsStore,
    private val diaryRepository: DiaryRepository,
    private val memoryRepository: MemoryRepository,
    private val screenSenseController: ScreenSenseController,
    private val conversationLog: ConversationLog,
    private val ttsManager: TtsManager,
    private val sessionStore: ChatSessionStore,
    /** 「进行中的事」缓冲区（页面与压缩写入） */
    private val bufferRepository: BufferRepository,
    /** 上下文整理（窗口回落时把即将丢弃的对话蒸馏进缓冲区） */
    private val compactor: ContextCompactor,
    /** 注入前缀的冻结快照（只在合并点重渲染，保证两次合并之间前缀逐字节不变） */
    private val snapshotStore: PrefixSnapshotStore
) {

    /** 协程域：进程级共享，用 SupervisorJob 防止单个任务失败影响其他任务 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    /** 上下文状态（状态行）：轮数/字符当量/上次缓存命中 */
    private val _contextStatus = MutableStateFlow(ContextStatus())
    val contextStatus: StateFlow<ContextStatus> = _contextStatus

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

    /** 浮动面板语音输入方式（ime/system/remote），面板据此决定点开后的动作 */
    val panelVoiceMode: StateFlow<String> = settingsStore.panelVoiceMode
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), "ime")

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 待发送图片附件（分享/上传后等待用户输入文字，一起发送） */
    private val _pendingImage = MutableStateFlow<PendingImage?>(null)
    val pendingImage: StateFlow<PendingImage?> = _pendingImage

    private val session = Session()
    private var counter = 0L

    /** 上下文窗口设置（DataStore 实时同步；状态行展示用） */
    private var minTurns = 5
    private var maxTurns = 20
    private var charLimit = SettingsStore.DEFAULT_CONTEXT_CHAR_LIMIT

    /** 会话快照保留天数（0 = 不留存） */
    private var retentionDays = SettingsStore.DEFAULT_CHAT_RETENTION_DAYS

    /** 历史图片保留张数（-1 = 全部保留；0 = 只当前轮；默认 1） */
    private var imageRetention = SettingsStore.DEFAULT_CHAT_IMAGE_KEEP

    // ---- 「进行中的事」缓冲区（2026-09-17）----

    /** 缓冲区总开关（关 = 不注入状态块、窗口回落时不做上下文整理） */
    private var bufferEnabled = true
    private var bufferCharLimit = SettingsStore.DEFAULT_BUFFER_CHAR_LIMIT
    private var bufferMaxItems = SettingsStore.DEFAULT_BUFFER_MAX_ITEMS

    /** 触发上下文整理的最小批次（字符当量；小于它就跳过整理，水位线照常推进） */
    private var compactMinChars = SettingsStore.DEFAULT_BUFFER_COMPACT_MIN_CHARS

    /**
     * 压缩水位线：**已折进缓冲区的最大轮 id**（随会话快照持久化）。
     * 用途：① 不重复整理同一批；② 整理失败/放弃的批次不被删除，留到下次一起补（自愈）；
     * ③ 配合 [expiredTurnIds] 的第三条件，避免"还没整理就被保留天数删掉"。
     */
    private var coveredThroughTurnId = 0L

    /** 冷启动恢复时快照里还有未合并的通知 → 第一轮请求前先合并一次（前缀反正要断，早付早好） */
    private var restoredWithPendingNotices = false

    /** 整理失败时记住的待整理轮（「重试」用；进程重启后由磁盘自愈兜底） */
    private var failedCompactionBatch: List<Session.Turn> = emptyList()

    /**
     * 离开窗口但**还没折进缓冲区**的轮（2026-09-23 补）。
     *
     * 用户实测踩到过一次真丢内容：字符软上限把 6 轮裁掉，但那次整理因"批次太短"跳过，
     * 旧逻辑还把水位线推进了 → 6 轮既没进缓冲区也永远不会再整理。
     * 现在：被裁掉且未覆盖的轮一律记在这里（并随会话快照持久化），
     * 下次整理作为"补整理片段"一起带上；被覆盖后才从列表里移除。
     */
    private var uncoveredTurns: List<Session.Turn> = emptyList()

    /** 上下文整理失败的可操作提示（非阻塞：回答照常，这里只留一条提示让用户重试/放弃） */
    private val _compactionNotice = MutableStateFlow<CompactionNotice?>(null)
    val compactionNotice: StateFlow<CompactionNotice?> = _compactionNotice

    /**
     * 识屏流程被触发的事件：浮动界面订阅它直接走自己的识图流程。
     * 关键词直连与 screen_sense 工具两条路径统一经 controller.requests 转发到这里，
     * 面板场景 MainActivity 在后台收不到授权请求事件，必须由面板自己触发。
     */
    private val _screenSenseRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val screenSenseRequested: SharedFlow<Unit> = _screenSenseRequested

    init {
        // 识屏结果 → 追加消息 + 截图进附件栏（小窗「在 App 中继续」）
        scope.launch {
            screenSenseController.results.collect { result ->
                if (result.imagePath.isNotEmpty()) {
                    addFileAttachment(result.imagePath)
                }
                if (result.resultText.isNotBlank()) {
                    val turnId = session.addAssistantOnly(result.resultText)
                    append(assistantMsg(counter++, turnId, result.resultText))
                    persistSession()
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
        // 上下文窗口设置实时生效（下限/上限/字符软上限）
        scope.launch {
            settingsStore.conversationMinTurns.collect {
                minTurns = it
                refreshContextStatus()
            }
        }
        scope.launch {
            settingsStore.conversationMaxTurns.collect {
                maxTurns = it
                refreshContextStatus()
            }
        }
        scope.launch {
            settingsStore.conversationCharLimit.collect {
                charLimit = it
                refreshContextStatus()
            }
        }
        // 会话快照保留天数：
        //  - 改为 0（不留存）→ 只**停止持久化并删掉已存快照**，当前会话与聊天界面继续保留
        //    （用户确认的语义：0 = 只停止"记录到磁盘"，不等于清空当前对话；要清空用聊天页的删除按钮）
        //  - 改为 N 天 → 立刻按新天数清理一次（超过 N 天且已不在上下文里的轮）
        scope.launch {
            settingsStore.chatSessionRetentionDays.collect { days ->
                retentionDays = days
                if (days <= 0) {
                    sessionStore.clear()
                } else {
                    val before = _messages.value.size
                    pruneExpiredRecords()
                    if (_messages.value.size != before) persistSession()
                }
            }
        }
        // 历史图片保留张数（-1 = 全部；影响 token 成本，见设置页说明）
        scope.launch {
            settingsStore.chatImageKeep.collect { imageRetention = it }
        }
        // 「进行中的事」设置（实时生效；注入上限只在合并点起作用）
        scope.launch { settingsStore.bufferEnabled.collect { bufferEnabled = it } }
        scope.launch { settingsStore.bufferInjectCharLimit.collect { bufferCharLimit = it } }
        scope.launch { settingsStore.bufferInjectMaxItems.collect { bufferMaxItems = it } }
        scope.launch { settingsStore.bufferCompactMinChars.collect { compactMinChars = it } }
        // 启动恢复（轻量持久化；超过保留天数视为过期直接丢掉）
        scope.launch { restoreSession() }
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
                sendImageTurn(text, image.base64, null, image.thumbnail)
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
     * 浮动界面识图模式对话 / 聊天页附件：文字要求 + 图片**一起**发给模型。
     *
     * 2026-09-11 起图片走**同一条聊天通道**（只换成「识屏（视觉）」指派的档案）：
     * 图片只是这一轮用户消息里的一个 image part——相当于"多花一张图的 token"。
     * 于是带图轮与纯文字轮完全同质：有完整上下文、能调用工具、展示思维链、
     * 支持重做/编辑重发/删除单条，不再有独立的视觉通道与隐藏的静默调用。
     *
     * @param imageBase64 图片 base64（聊天页附件）；与 [imageFilePath] 二选一
     * @param imageFilePath 图片本机路径（浮动面板截图）
     */
    fun sendImageMessage(
        text: String,
        imageBase64: String? = null,
        imageFilePath: String? = null,
        thumbnail: Bitmap?
    ) {
        val t = text.trim()
        if (_isStreaming.value) return
        if (imageBase64 == null && imageFilePath == null) return
        scope.launch {
            _isStreaming.value = true
            _error.value = null
            if (t.isNotEmpty()) conversationLog.log(t)
            sendImageTurn(t, imageBase64, imageFilePath, thumbnail)
            _isStreaming.value = false
        }
    }

    /** 带图消息实发：图片落盘（chat_images）→ 会话留路径 → 走对话回路 */
    private suspend fun sendImageTurn(
        text: String,
        imageBase64: String?,
        imageFilePath: String?,
        thumbnail: Bitmap?
    ) {
        val path = withContext(Dispatchers.IO) {
            when {
                !imageFilePath.isNullOrEmpty() -> ImageUtils.importToChatImages(context, imageFilePath)
                !imageBase64.isNullOrEmpty() ->
                    ImageUtils.decodeBase64Bitmap(imageBase64)?.let { bmp ->
                        ImageUtils.saveToChatImages(context, ImageUtils.scaleBitmap(bmp))
                    }
                else -> null
            }
        }
        // 没写要求时给一句默认指令（否则模型只看到一张图，不知道要做什么）
        val instruction = text.ifBlank { DEFAULT_IMAGE_INSTRUCTION }
        val turnId = session.beginTurn(instruction, path)
        // 历史图片保留策略在 runTurn 里统一执行（对当前轮的图永远放行）
        _messages.update {
            it + ChatUiMessage(
                id = counter++, turnId = turnId, role = "user", text = text,
                image = thumbnail, imagePath = path
            )
        }
        runTurn(turnId, text, preferVision = true)
    }

    /** 浮动界面「记录」模式：文本直接写入默认日记本（不经模型），并给出反馈消息 */
    fun writeDiaryNow(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        conversationLog.log(t)
        scope.launch {
            writeDiary(t)
            val hint = "📔 已记入日记本"
            val turnId = session.addAssistantOnly(hint)
            append(assistantMsg(counter++, turnId, hint))
            persistSession()
        }
    }

    /** 普通文字消息：路由 + 主模型工具回路流式回复 */
    private suspend fun sendText(text: String) {
        // 秘密功能：记录用户发出的内容（数字分身素材）
        conversationLog.log(text)
        val turnId = session.beginTurn(text)
        _messages.update { it + userMsg(counter++, turnId, text, null) }
        runTurn(turnId, text)
    }

    /**
     * 跑一轮对话回路（发送/重做共用）：
     * 用**当前**会话（而非历史请求快照）重建上下文，因此删除/裁剪/记忆更新后重做不会发出过期请求。
     * @param preferVision 本轮带图片 → 用「识屏（视觉）」指派的档案（同一条通道，只换模型）
     */
    private suspend fun runTurn(turnId: Long, rawText: String, preferVision: Boolean = false) {
        // 历史图片保留策略：统一在这里执行（所有请求都从这里发出：发送/带图/重做/面板）。
        // keep=0「仅当前轮」= 只有最近一轮的图会发出去，下一轮（哪怕只是文字）起这张图就不再发送；
        // keep≥1 保留最近 N 张；keep<0 全留。当前轮的图永远保留（见 Session.enforceImageRetention）。
        session.enforceImageRetention(imageRetention)
        // 静态前缀（系统提示词/工具手册/记忆快照/标签/状态快照）变了 → 厂商缓存的前缀整段失效，
        // 把指纹交给会话层比较：不一致就把窗口回落到下限重新起跑（与"到上限回落"共用同一条
        // L→U 序列，避免白扛着 U 轮历史每轮付全价）。
        val diaryTags = parseDiaryTags(settingsStore.diaryTagsCsv.first())
        var snapshot = snapshotStore.load() ?: rebuildSnapshotFromDb()
        var signature = prefixSignatureOf(snapshot, diaryTags)
        var plan = session.planWindow(minTurns, maxTurns, charLimit, signature)

        // ---- 合并点（2026-09-17）----
        // 窗口回落（或冷启动带着未合并通知）时：① 把即将离开的对话蒸馏进「进行中的事」；
        // ② 用数据库重渲染冻结快照（记忆 + 状态）；③ 清掉会话里的粘性通知。
        // ⚠️ 必须在 buildContext（物理裁剪）**之前**：压缩要用那些马上要被删掉的轮。
        var reasonOverride: String? = null
        if (plan.trigger != Session.WindowTrigger.NONE || restoredWithPendingNotices) {
            val compacted = compactAtResetPoint(plan, snapshot, diaryTags)
            snapshot = mergePrefixSnapshot()
            signature = prefixSignatureOf(snapshot, diaryTags)
            restoredWithPendingNotices = false
            if (compacted) reasonOverride = Session.RESET_COMPACTED
        }

        val memoryText = snapshot.memoryText.ifBlank { null }
        val bufferText = snapshot.bufferText.ifBlank { null }
        val ctx = session.buildContext(minTurns, maxTurns, charLimit, signature, plan, reasonOverride)
        _contextStatus.update { it.withContext(ctx, minTurns, maxTurns, charLimit) }
        when (val result = agent.route(
            rawText, memoryText = memoryText, history = ctx.messages,
            diaryTags = diaryTags, preferVision = preferVision, bufferText = bufferText
        )) {
            is AgentResult.Command -> {
                // 目前只有识屏关键词直连会走到这里
                val hint = executeCommand(result.intent)
                session.appendAssistant(turnId, ChatMessage("assistant", hint))
                append(assistantMsg(counter++, turnId, hint))
            }
            is AgentResult.Error -> {
                val msg = "⚠️ ${result.message}"
                session.appendAssistant(turnId, ChatMessage("assistant", msg))
                append(assistantMsg(counter++, turnId, msg))
            }
            is AgentResult.ChatRequested -> {
                val streamingId = counter++
                append(assistantMsg(streamingId, turnId, "", streaming = true, regenerable = true))
                val outcome = streamReply(result.messages, streamingId, result.capability)
                // 带图轮失败且该档案没勾"支持图片输入" → 补一句明确的排查提示
                val answer = if (preferVision && outcome.answer.contains("[出错：") &&
                    !agent.visionModelSupportsImages()
                ) {
                    outcome.answer + "\n\n" + Agent.IMAGE_MODEL_GUIDE
                } else outcome.answer
                // 工具中间轮写回会话历史：后续追问时模型能看到调用过什么、拿到过什么结果
                outcome.exchanges.forEach { (request, resultsMsg) ->
                    session.appendAssistant(turnId, ChatMessage("assistant", request))
                    session.appendAssistant(turnId, ChatMessage("user", resultsMsg))
                }
                // 只把**正文**写回历史（不带界面的「🔧 已执行：…」页脚）：
                // 页脚进历史后模型会模仿它在正文里自己编执行清单（2026-09-14 修的 bug）
                session.appendAssistant(turnId, ChatMessage("assistant", outcome.body))
                updateMessage(streamingId) { it.copy(text = answer) }
                // 记录兜底："记录…"类请求但模型没调 write_diary → 静默存原文（记录不能丢）。
                // 但模型已经处理过日记（读/改/删）时不要再兜底写入——否则"把日记改成…"会被
                // 当成新记录多写一条（update_diary 走的是修改语义）。
                val diaryTouched = outcome.toolNames.any {
                    it == "write_diary" || it == "update_diary" || it == "read_diary"
                }
                if (!diaryTouched && intentRouter.looksLikeDiaryRequest(rawText)) {
                    writeDiary(rawText)
                }
            }
        }
        // 每轮结束都清一次过期记录（超过保留天数 + 已不在上下文里的轮才删，见 pruneExpiredRecords）
        pruneExpiredRecords()
        refreshContextStatus()
        persistSession()
    }

    // ---- 「进行中的事」：冻结快照 / 上下文整理 / 粘性通知（2026-09-17）----

    private suspend fun prefixSignatureOf(snapshot: PrefixSnapshot, diaryTags: List<String>): String =
        agent.cachePrefixSignature(
            snapshot.memoryText.ifBlank { null },
            diaryTags,
            snapshot.bufferText.ifBlank { null }
        )

    /** 从数据库现渲染一份冻结快照（首装 / 文件损坏 / 恢复备份后重建） */
    private suspend fun rebuildSnapshotFromDb(): PrefixSnapshot = PrefixSnapshot(
        memoryText = memoryRepository.memoryContextText().orEmpty(),
        // 设置值现读，避免启动时收集器还没跑完拿到默认值
        bufferText = if (settingsStore.bufferEnabled.first()) {
            bufferRepository.renderSnapshotText(
                settingsStore.bufferInjectCharLimit.first(),
                settingsStore.bufferInjectMaxItems.first()
            )
        } else {
            ""
        },
        savedAt = System.currentTimeMillis()
    )

    /** 重渲染并写回冻结快照（不含通知清理） */
    private suspend fun rebuildAndSaveSnapshot(): PrefixSnapshot {
        val snapshot = rebuildSnapshotFromDb()
        snapshotStore.save(snapshot)
        return snapshot
    }

    /**
     * 合并：重渲染冻结快照（记忆 + 状态）+ 清掉会话里的粘性通知，并写回文件。
     * **只在合并点调用**——那一刻前缀无论如何都要重建，所以这些变化不产生额外缓存代价。
     */
    private suspend fun mergePrefixSnapshot(): PrefixSnapshot {
        val snapshot = rebuildAndSaveSnapshot()
        session.stripNotices()
        persistSession()
        return snapshot
    }

    /**
     * 窗口回落点的上下文整理。返回是否真的跑了压缩轮（状态行显示「上下文已整理」用）。
     *
     * 以下情况不跑压缩，但**照常推进水位线**（否则保留天数清理会被永久卡住，同一批也会被反复重试）：
     *  - 缓冲区总开关关闭；批次太小（小于 compactMinChars，视为不值得整理）；没有待整理的轮。
     * 只有**调用失败**才不推进：批次留在磁盘上，下次回落点由 [collectLeftovers] 一起补（自愈）。
     */
    private suspend fun compactAtResetPoint(
        plan: Session.WindowPlan,
        snapshot: PrefixSnapshot,
        diaryTags: List<String>
    ): Boolean {
        // 先把"即将离开窗口且还没覆盖过"的轮记进待补列表——万一这次整理跳过或失败，
        // 它们不会随窗口裁剪静默消失（用户实测踩过：6 轮被裁掉后内容永久丢失）
        rememberUncovered(plan.willDrop)
        val leaving = plan.willDrop.filter { it.id > coveredThroughTurnId }
        val leftovers = collectLeftovers()
        val covers = (leaving + leftovers).distinctBy { it.id }.sortedBy { it.id }
        if (covers.isEmpty()) return false
        if (!bufferEnabled) {
            advanceWatermark(covers)
            return false
        }
        val weight = covers.sumOf { it.charWeight() }
        if (weight < compactMinChars) {
            // 太短：视为「不值得现在整理」，但**不推进水位线**——这批会作为"漏掉的片段"
            // 留到下次整理一起带上（自愈，见 collectLeftovers），内容不会静默丢掉。
            // 代价：在被覆盖之前，保留天数清理不会删它们（expiredTurnIds 的第三条件）。
            // 只在真的发生窗口回落时提示，避免冷启动等场景刷屏。
            if (plan.trigger != Session.WindowTrigger.NONE) {
                append(
                    ChatUiMessage(
                        id = counter++,
                        turnId = session.lastTurn()?.id ?: -1L,
                        role = ROLE_NOTICE,
                        text = "🧩 窗口回落：这 ${covers.size} 轮较短（约 $weight 字），本次跳过整理、" +
                            "留到下次一起整理（整理阈值可在 设置 → 进行中的事 里调低）"
                    )
                )
            }
            return false
        }
        return runCompaction(snapshot, diaryTags, session.allTurns(), plan.willDrop.size, leftovers, covers)
    }

    /**
     * 「立即整理当前对话」：用户手动触发一次上下文整理（2026-09-23 补）。
     *
     * 为什么需要：自动整理只在**窗口回落**时发生——轮数超过上限（默认 20）或触发字符软上限；
     * 而字符软上限那次会把窗口裁到很少几轮，重新累积到 21 轮又要聊很久。
     * 手动触发不受「批次太短就跳过」的限制（用户明确要求整理），结果照常进缓冲区与注入快照。
     */
    fun compactNow() {
        if (_isStreaming.value) return
        scope.launch {
            _isStreaming.value = true
            _error.value = null
            try {
                val all = session.allTurns()
                if (all.isEmpty()) {
                    _error.value = "当前没有对话可以整理"
                    return@launch
                }
                val diaryTags = parseDiaryTags(settingsStore.diaryTagsCsv.first())
                val snapshot = snapshotStore.load() ?: rebuildSnapshotFromDb()
                val leftovers = collectLeftovers()
                // 本次要整理的对象 = 窗口里还没覆盖的轮 + 之前漏掉的片段（补整理）
                val covers = (all.filter { it.id > coveredThroughTurnId } + leftovers)
                    .distinctBy { it.id }
                    .sortedBy { it.id }
                if (covers.isEmpty()) {
                    append(
                        ChatUiMessage(
                            id = counter++,
                            turnId = all.last().id,
                            role = ROLE_NOTICE,
                            text = "🧩 当前这段对话已经整理过了（要继续整理请先多聊几轮，或先清空对话）"
                        )
                    )
                    return@launch
                }
                runCompaction(snapshot, diaryTags, all, all.size, leftovers, covers)
                mergePrefixSnapshot()
                refreshContextStatus()
            } catch (e: Exception) {
                _error.value = "上下文整理出错：${e.message}"
            } finally {
                _isStreaming.value = false
            }
        }
    }

    /**
     * 真正发起一次压缩请求并处理结果。
     * @param covers 本次"整理了就算覆盖"的轮（成功 → 推进水位线；失败 → 记住待重试）
     * @param showNotice 是否在对话里插一条提示气泡（清空对话时不必插——列表马上会被清空）
     */
    private suspend fun runCompaction(
        snapshot: PrefixSnapshot,
        diaryTags: List<String>,
        windowTurns: List<Session.Turn>,
        leavingCount: Int,
        leftovers: List<Session.Turn>,
        covers: List<Session.Turn>,
        showNotice: Boolean = true
    ): Boolean {
        if (windowTurns.isEmpty()) {
            advanceWatermark(covers)
            return false
        }
        // 整理要几秒（一次模型请求，用户在等回复），必须在界面上给出运行提示，
        // 否则"发了消息什么都不发生、过几秒才回"——用户实测反馈过看不到任何动静。
        val noticeId = if (showNotice) counter++ else -1L
        if (showNotice) {
            append(
                ChatUiMessage(
                    id = noticeId,
                    turnId = session.lastTurn()?.id ?: -1L,
                    role = ROLE_NOTICE,
                    text = "📦 正在整理上下文…"
                )
            )
        }

        val out = compactor.compact(
            memoryText = snapshot.memoryText.ifBlank { null },
            bufferText = snapshot.bufferText.ifBlank { null },
            conversationTurns = windowTurns,
            leavingCount = leavingCount,
            leftoverTurns = leftovers,
            diaryTags = diaryTags
        )

        if (out.error != null) {
            // 失败：不推进水位线（内容留着下次补），给用户一条可操作提示，回答照常
            failedCompactionBatch = covers
            _compactionNotice.value = CompactionNotice(
                "📦 上下文整理失败（${out.error}）：最早的 ${covers.size} 轮还没整理。"
            )
            if (showNotice) {
                updateMessage(noticeId) {
                    it.copy(
                        text = "⚠️ 上下文整理失败（${out.error}）：这 ${covers.size} 轮先留着，" +
                            "下次整理会一起补（回答不受影响）"
                    )
                }
            }
            return false
        }

        advanceWatermark(covers)
        if (showNotice) {
            updateMessage(noticeId) { it.copy(text = compactionDoneText(leavingCount, leftovers.size, out)) }
        }
        return true
    }

    /** 整理完成的提示文案（把用量直接写在气泡里——用户要求不要藏在要展开的状态行里） */
    private fun compactionDoneText(
        leavingCount: Int,
        leftoverCount: Int,
        out: ContextCompactor.Outcome
    ): String {
        val folded = if (leftoverCount > 0) "$leavingCount＋$leftoverCount（补整理）" else "$leavingCount"
        val hit = out.usage?.let { u ->
            val total = u.promptTokens ?: return@let null
            val cached = u.cachedTokens ?: return@let null
            if (total <= 0) null else (cached * 100 / total).coerceIn(0, 100)
        }
        return buildString {
            append("🧩 上下文整理完成：最早 ").append(folded).append(" 轮已折进「进行中的事」")
            if (!out.wrote) append("（这批没有值得记录的进展）")
            append("｜").append(out.requests).append(" 次请求")
            out.usage?.promptTokens?.let { append(" · ").append(it).append(" tokens") }
            hit?.let { append(" · 缓存命中 ").append(it).append('%') }
        }
    }

    /** 推进压缩水位线（覆盖到的轮允许被保留天数正常清理） */
    private fun advanceWatermark(turns: List<Session.Turn>) {
        if (turns.isEmpty()) return
        coveredThroughTurnId = maxOf(coveredThroughTurnId, turns.maxOf { it.id })
        failedCompactionBatch = failedCompactionBatch.filter { it.id > coveredThroughTurnId }
        // 已覆盖的轮从"待补整理"里移除（剩下的继续等下次一起整理）
        uncoveredTurns = uncoveredTurns.filter { it.id > coveredThroughTurnId }
        _compactionNotice.value = null
    }

    /** 记住"离开窗口但还没整理"的轮（去重、按 id 升序、只留最近 MAX_LEFTOVER_TURNS 轮） */
    private fun rememberUncovered(turns: List<Session.Turn>) {
        val add = turns.filter { it.id > coveredThroughTurnId }
        if (add.isEmpty()) return
        uncoveredTurns = (uncoveredTurns + add)
            .distinctBy { it.id }
            .sortedBy { it.id }
            .takeLast(MAX_LEFTOVER_TURNS)
    }

    /**
     * 之前没整理成功、已经离开窗口的轮（自愈用），四个来源按优先级合并去重：
     *  1. 内存里记的待补列表 [uncoveredTurns]（本次进程内最准）；
     *  2. 内存里记的失败批次 [failedCompactionBatch]；
     *  3. 会话快照文件里的 uncoveredTurns（进程重启后的兜底）；
     *  4. **界面消息里能重建出来的轮**——这一条专门捞"旧版本静默丢掉的那些"：
     *     只要气泡还在聊天界面上（属于同一轮的消息、轮号大于水位线、不在当前窗口里），
     *     就能把用户消息 + 助手正文重新拼回一轮，交给整理补上。
     * 上限 [MAX_LEFTOVER_TURNS] 轮，防一次带太多把上下文撑爆。
     */
    private suspend fun collectLeftovers(): List<Session.Turn> {
        val live = session.allTurns().map { it.id }.toSet()
        val fromDisk = withContext(Dispatchers.IO) {
            val snap = sessionStore.load() ?: return@withContext emptyList()
            // 3) 老快照没有 uncoveredTurns 字段 → 退回用 turns 里"已出窗口且未覆盖"的那些
            (snap.uncoveredTurns + snap.turns.filter { it.id !in live }).map { st ->
                session.turnFromStored(
                    st.id, st.userText, st.imagePath, st.assistant, st.createdAt, st.notices
                )
            }
        }
        return (uncoveredTurns + failedCompactionBatch + fromDisk + turnsRebuiltFromUi())
            .distinctBy { it.id }
            .filter { it.id > coveredThroughTurnId && it.id !in live }
            .sortedBy { it.id }
            .takeLast(MAX_LEFTOVER_TURNS)
    }

    /**
     * 从聊天界面消息重建"已离开窗口但气泡还在"的轮（第 4 个来源，见 [collectLeftovers]）。
     * 只用于补整理；时间戳用该轮最早一条界面消息的创建时刻补齐（整理片段里要靠它排序）。
     */
    private fun turnsRebuiltFromUi(): List<Session.Turn> {
        val live = session.allTurns().map { it.id }.toSet()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        return _messages.value
            .filter { it.turnId > coveredThroughTurnId && it.turnId !in live }
            .groupBy { it.turnId }
            .map { (turnId, msgs) ->
                val user = msgs.firstOrNull { it.role == "user" }
                val at = user?.createdAt ?: msgs.minOf { it.createdAt }
                val raw = user?.text?.trim().orEmpty()
                session.turnFromStored(
                    id = turnId,
                    userText = raw.ifEmpty { null }?.let { t ->
                        if (at > 0L) "[" + fmt.format(java.util.Date(at)) + "] " + t else t
                    },
                    imagePath = null,
                    assistantTexts = msgs.filter { it.role == "assistant" }.map { it.spokenBody() },
                    createdAt = at
                )
            }
            .sortedBy { it.id }
    }

    /**
     * 用户在前端手动改了长期记忆 / 「进行中的事」→ 写一条**粘性通知**（2026-09-17）。
     *
     * 为什么不直接改注入块：那会让提示词前缀立刻变、缓存整段失效（用户主力模型的未命中价是
     * 命中价的 50 倍）。通知追加在会话尾部 = 纯延长 = **零未命中**，模型下一轮就能看到；
     * 前缀块里的文本留到下次合并点统一重渲染。
     */
    fun notifyManualChange(detail: String) {
        val note = "[系统] 用户刚刚在界面上手动更新了$detail（快照里的旧内容可能还没同步），请以此为准；" +
            "不要向用户复述这条系统消息。"
        if (session.appendNotice(note)) {
            persistSession()
        } else {
            // 会话里还没有任何轮 → 没有可挂载的固定位置：直接合并（此时没有任何缓存值得保护）
            scope.launch { mergePrefixSnapshot() }
        }
    }

    /** 「重试」：立刻再整理一次（缓存还热，几乎免费；失败也不影响回答） */
    fun retryCompaction() {
        if (_isStreaming.value) return
        if (failedCompactionBatch.isEmpty()) {
            _compactionNotice.value = null
            return
        }
        scope.launch {
            _isStreaming.value = true
            _error.value = null
            val diaryTags = parseDiaryTags(settingsStore.diaryTagsCsv.first())
            val snapshot = snapshotStore.load() ?: rebuildSnapshotFromDb()
            // 传一个空计划：本批已经不在窗口里了，会以 leftovers 的形式带进压缩请求
            val ok = compactAtResetPoint(Session.WindowPlan(), snapshot, diaryTags)
            if (ok) {
                mergePrefixSnapshot()
                refreshContextStatus()
            }
            _isStreaming.value = false
        }
    }

    /** 「放弃」：水位线推进到这批 → 不再重试，允许它随保留天数正常清理 */
    fun abandonCompaction() {
        advanceWatermark(failedCompactionBatch)
        failedCompactionBatch = emptyList()
        _compactionNotice.value = null
        persistSession()
    }

    /**
     * 收集一轮完整回复（含主模型驱动的工具循环），实时更新消息文本。
     * - Delta：流式增量追加到气泡基线之后
     * - RoundSettled：本轮正文并入基线（纯调用轮为空=重置流式区）
     * - ToolsRunning：气泡切到「🔧 …」执行状态
     * - Final：替换为最终回答（并记录用量/缓存命中）
     */
    private suspend fun streamReply(
        messages: List<ChatMessage>,
        messageId: Long,
        capability: com.example.assistant.core.network.Capability =
            com.example.assistant.core.network.Capability.CHAT
    ): StreamOutcome {
        var base = ""      // 已定格正文（各轮保留正文的累加；维护 text 字段供复制/滚动）
        var roundAcc = ""  // 本轮流式文本累计
        var textThisRound = false  // 本轮是否流出过正文（RoundSettled 时定位要清洗的段）
        var exchanges: List<Pair<String, String>> = emptyList()
        var toolNames: List<String> = emptyList()
        var finalAnswer: String? = null
        var finalBody: String? = null
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
            agent.chatReplyFlow(messages, capability).collect { ev ->
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
                        finalBody = ev.body
                        // 用量 = **本轮全部请求的合计**（工具回路里一轮可能发好几次请求），
                        // 每次都用本轮的值覆盖上一轮的（厂商没返回就是 null，不残留旧数字）
                        _contextStatus.update {
                            it.copy(
                                promptTokens = ev.usage?.promptTokens,
                                cachedTokens = ev.usage?.cachedTokens,
                                requests = ev.requests
                            )
                        }
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
        // 最终回答以 Final 事件为准；异常中断时退回已定格正文+本轮累计（此时也没有页脚）
        val answer = finalAnswer ?: (base + "\n\n" + roundAcc).trim()
        return StreamOutcome(
            answer = answer,
            body = finalBody ?: answer,
            exchanges = exchanges,
            toolNames = toolNames
        )
    }

    /** 视觉模型流式回复已随"识图并入聊天通道"删除（图片轮与文字轮走同一条 chatReplyFlow） */

    /**
     * 重新生成某条助手回复：用**当前会话**重建这一轮的请求（不再使用历史请求快照），
     * 因此删除/裁剪/记忆更新之后重做也不会发出过期上下文。
     * 只对最后一轮有意义（界面也只对最后一条助手消息显示重做按钮）。
     */
    fun regenerate(messageId: Long) {
        if (_isStreaming.value) return
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (!msg.regenerable) return
        val turn = session.lastTurn() ?: return
        if (turn.id != msg.turnId) return
        val rawText = rawUserText(turn) ?: return
        scope.launch {
            _isStreaming.value = true
            _error.value = null
            // 会话侧丢掉这一轮已有的助手消息，重新生成（工具轮一起重来，避免"旧工具结果+新回答"的矛盾）
            session.clearAssistant(turn.id)
            updateMessage(messageId) {
                it.copy(text = "", thinking = "", streaming = true, segments = emptyList(), regenerable = true)
            }
            // 带图轮重做仍走「识屏」档案（图片还在会话里）
            runTurn(turn.id, rawText, preferVision = turn.imagePath != null)
            _isStreaming.value = false
        }
    }

    /**
     * 编辑重发：撤回「最后一条用户消息」——界面与会话历史同步删掉它及其后的助手回复，
     * 返回原文供调用方填回输入框；用户改完再发送即全新一轮，上下文与界面保持一致。
     * 带图消息：restoreImage=true 时按会话里记的图片路径把原图还原进附件栏（聊天页）。
     * 返回 null = 不可撤回（正在流式 / 该条不是最后的用户消息）。
     */
    fun withdrawForEdit(messageId: Long, restoreImage: Boolean = false): String? {
        if (_isStreaming.value) return null
        val list = _messages.value
        val lastUser = list.lastOrNull { it.role == "user" } ?: return null
        if (lastUser.id != messageId) return null
        val idx = list.indexOf(lastUser)
        // 界面与该轮的会话历史一起删（按 turnId 精确定位，多删的助手提示轮一并清理）
        val removed = list.drop(idx)
        _messages.update { it.take(idx) }
        removed.map { it.turnId }.distinct().forEach { session.deleteTurn(it) }
        refreshContextStatus()
        persistSession()
        // 带图消息：把原图（chat_images 下的文件）还原进附件栏，编辑后可直接重发
        val path = lastUser.imagePath
        if (restoreImage && path != null) {
            scope.launch {
                val pending = withContext(Dispatchers.IO) {
                    val bmp = ImageUtils.decodeFit(path, ImageUtils.MAX_WIDTH)
                    if (bmp == null) null
                    else PendingImage(ImageUtils.thumbnail(bmp), ImageUtils.bitmapToBase64(bmp))
                }
                if (pending != null) _pendingImage.value = pending
            }
        }
        return lastUser.text
    }

    /**
     * 删除单条对话（需求 3）：一次删掉这一轮的**用户消息 + 全部助手回复**（含工具中间轮），
     * 使其不再出现在上下文里。**不回退工具副作用**——已创建的提醒/已写入的日记/已记的记忆都保留。
     * 返回 true = 已从上下文与界面移除（流式中不可删）。
     */
    fun deleteTurn(turnId: Long): Boolean {
        if (_isStreaming.value) return false
        val affected = _messages.value.filter { it.turnId == turnId }
        if (affected.isEmpty()) return false
        _messages.update { list -> list.filterNot { it.turnId == turnId } }
        session.deleteTurn(turnId)
        refreshContextStatus()
        persistSession()
        return true
    }

    /**
     * 清空对话。
     *
     * @param alsoCompact 是否顺带把要点整理进「进行中的事」（确认弹窗里的勾选项，默认勾上）——
     *        由用户显式选择，所以**不受"批次太小就跳过"的限制**。
     *        无论是否整理都会合并一次快照（记忆/状态的界面改动要落进注入块）。
     */
    fun clearConversation(alsoCompact: Boolean = false) {
        if (_isStreaming.value) return
        scope.launch {
            val all = session.allTurns()
            if (alsoCompact && all.isNotEmpty() && settingsStore.bufferEnabled.first()) {
                _isStreaming.value = true
                _error.value = null
                try {
                    val diaryTags = parseDiaryTags(settingsStore.diaryTagsCsv.first())
                    val snapshot = snapshotStore.load() ?: rebuildSnapshotFromDb()
                    // 顺带把之前"没整理成功"的片段也一起整理（否则清空会让它们彻底消失）
                    val leftovers = collectLeftovers()
                    val covers = (all + leftovers).distinctBy { it.id }.sortedBy { it.id }
                    // showNotice=false：对话马上要被清空，插提示气泡没意义
                    runCompaction(
                        snapshot, diaryTags, all, all.size, leftovers, covers, showNotice = false
                    )
                } catch (_: Exception) {
                    // 整理失败不影响清空
                }
                _isStreaming.value = false
            }
            session.clear()
            _messages.value = emptyList()
            _contextStatus.value = ContextStatus(
                minTurns = minTurns, maxTurns = maxTurns, charLimit = charLimit
            )
            coveredThroughTurnId = 0L
            failedCompactionBatch = emptyList()
            uncoveredTurns = emptyList()
            _compactionNotice.value = null
            // 会话已空：快照按数据库最新内容重渲染（没有轮可挂通知），并清掉会话文件
            rebuildAndSaveSnapshot()
            sessionStore.clear()
        }
    }

    // ---- 会话轻量持久化（随时可丢的内容：只存一个 JSON，不进备份） ----

    /**
     * 启动恢复：按保留天数判断快照是否过期；恢复界面消息与会话轮。
     * 恢复的历史如果前缀一致，服务端提示词缓存可能仍在有效期内（也可能顺带命中）。
     */
    private suspend fun restoreSession() {
        // 冻结快照与会话保留是两码事：即使不留存会话记录，注入前缀的冻结快照也要就位
        // （缺失说明首装/升级/文件损坏 → 从数据库渲染一次；文本与旧行为一致，不额外断前缀）
        if (snapshotStore.load() == null) rebuildAndSaveSnapshot()

        val days = settingsStore.chatSessionRetentionDays.first()
        retentionDays = days
        if (days <= 0) {
            // 不留存：删掉旧文件即可（内存里本来就没有历史，当前会话不受影响）
            sessionStore.clear()
            return
        }
        if (sessionStore.pruneIfExpired(days)) return
        val snap = sessionStore.load() ?: return
        if (snap.turns.isEmpty() && snap.messages.isEmpty()) return
        coveredThroughTurnId = snap.coveredThroughTurnId
        // 待补整理的轮（离开窗口但还没折进缓冲区）：恢复到内存，下次整理一起带上
        uncoveredTurns = snap.uncoveredTurns.map { st ->
            session.turnFromStored(st.id, st.userText, st.imagePath, st.assistant, st.createdAt, st.notices)
        }

        // 旧快照没有 createdAt 字段（2026-09-17 之前）：回退到 userText 的 [时间] 前缀，
        // 再不行用快照保存时刻；解析不出来的轮记 0 = 未知（清理逻辑一律保留，不误删）
        val turnTime = snap.turns.associate { t ->
            val at = if (t.createdAt > 0L) t.createdAt else ChatSessionStore.parseStampedAt(t.userText)
            t.id to (if (at > 0L) at else snap.savedAt)
        }
        session.restoreTurns(
            snap.turns.map { t ->
                session.turnFromStored(
                    t.id, t.userText, t.imagePath, t.assistant, turnTime[t.id] ?: 0L, t.notices
                )
            }
        )
        // 快照里还留着未合并的粘性通知（用户上次改记忆/状态后没触发过回落）：
        // 第一轮请求前先合并一次——此刻前缀无论如何都要断，早付早好
        restoredWithPendingNotices = session.hasNotices()
        // 带图轮：从 chat_images 路径懒加载缩略图（文件可能已被清理策略删掉 → 无缩略图）
        val thumbs = withContext(Dispatchers.IO) {
            snap.turns.mapNotNull { t ->
                t.imagePath?.let { p -> ImageUtils.decodeThumbnail(p)?.let { b -> t.id to b } }
            }.toMap()
        }
        val pathByTurn = snap.turns.associate { it.id to it.imagePath }
        // 孤儿消息（所属轮已被裁掉、文件里也没存过时刻）的估计时刻，见 estimateLegacyCreatedAt
        val legacyAt = estimateLegacyCreatedAt(turnTime.values, snap.savedAt)
        var stamped = false
        _messages.value = snap.messages.map { m ->
            val known = m.createdAt.takeIf { it > 0L } ?: turnTime[m.turnId]
            val createdAt = known ?: legacyAt.also { stamped = true }
            ChatUiMessage(
                id = m.id,
                turnId = m.turnId,
                role = m.role,
                text = m.text,
                thinking = m.thinking,
                streaming = false,
                image = thumbs[m.turnId],
                imagePath = pathByTurn[m.turnId],
                segments = m.segments.map { s ->
                    when (s.kind) {
                        "think" -> MsgSegment.Think(s.text)
                        "tools" -> MsgSegment.Tools(s.labels)
                        else -> MsgSegment.Text(s.text)
                    }
                },
                regenerable = m.regenerable,
                createdAt = createdAt
            )
        }
        counter = (_messages.value.maxOfOrNull { it.id } ?: -1L) + 1
        // 恢复的历史图片也按当前保留策略收敛（用户可能把保留张数改小了）
        session.enforceImageRetention(settingsStore.chatImageKeep.first())
        // 启动即清理过期记录（超过保留天数且已不在上下文里的轮）；补过时间戳也立刻写回文件（否则下次保存又变回 0）
        val before = _messages.value.size
        pruneExpiredRecords()
        if (stamped || _messages.value.size != before) persistSession()
        refreshContextStatus()
    }

    /** 保存会话快照（界面消息 + 会话轮；条数上限防文件无限增长） */
    private fun persistSession() {
        if (retentionDays <= 0) return
        val ui = _messages.value.takeLast(ChatSessionStore.MAX_UI_MESSAGES)
        val uiTurnIds = ui.map { it.turnId }.toSet()
        val turns = session.allTurns()
            .filter { it.id in uiTurnIds }
            .takeLast(ChatSessionStore.MAX_TURNS)
        val snapshot = StoredChat(
            savedAt = System.currentTimeMillis(),
            coveredThroughTurnId = coveredThroughTurnId,
            uncoveredTurns = uncoveredTurns.takeLast(ChatSessionStore.MAX_TURNS).map { t -> t.toStored() },
            turns = turns.map { it.toStored() },
            messages = ui.map { m ->
                StoredUiMessage(
                    id = m.id,
                    turnId = m.turnId,
                    role = m.role,
                    text = m.text,
                    thinking = m.thinking,
                    regenerable = m.regenerable,
                    createdAt = m.createdAt,
                    segments = m.segments.map { s ->
                        when (s) {
                            is MsgSegment.Think -> StoredSegment("think", s.text)
                            is MsgSegment.Text -> StoredSegment("text", s.text)
                            is MsgSegment.Tools -> StoredSegment("tools", labels = s.labels)
                        }
                    }
                )
            }
        )
        scope.launch { sessionStore.save(snapshot) }
    }

    // ---- 过期会话记录清理（2026-09-17） ----

    /**
     * 每一轮的创建时刻：会话（上下文）里有就用它的，否则用该轮界面消息里最早的一条。
     * 两者都是 0（未知）时该轮会出现在 map 里但值为 0 —— 清理判定据此保守保留。
     */
    private fun turnCreatedAtMap(): Map<Long, Long> {
        val map = HashMap<Long, Long>()
        _messages.value.forEach { m ->
            val cur = map[m.turnId]
            map[m.turnId] = if (cur == null) m.createdAt else minOf(cur, m.createdAt)
        }
        session.allTurns().forEach { t ->
            val cur = map[t.id]
            if (t.createdAt > 0L && (cur == null || cur <= 0L)) map[t.id] = t.createdAt
        }
        return map
    }

    /**
     * 自动清理过期的会话记录（每条回复结束、启动恢复、改保留天数时各跑一次）：
     * **同时满足「超过保留天数」和「已不在模型上下文中」才删**——见 [expiredTurnIds]。
     * 删的是整轮（界面消息 + 持久化快照里的对应内容一起消失）；还在窗口里的过期轮先留着，
     * 等它随窗口滚动出上下文的下一次清理再删。
     * 保留天数填 0 = 不留存：只停止写文件、不删屏幕上的对话（用户确认过的语义）。
     */
    private fun pruneExpiredRecords() {
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - retentionDays * DAY_MS
        val contextIds = session.allTurns().map { it.id }.toSet()
        val expired = expiredTurnIds(turnCreatedAtMap(), contextIds, cutoff, coveredThroughTurnId)
        if (expired.isEmpty()) return
        _messages.update { list -> list.filterNot { it.turnId in expired } }
    }

    // ---- 内部工具 ----

    private fun userMsg(id: Long, turnId: Long, text: String, image: Bitmap?): ChatUiMessage =
        ChatUiMessage(id = id, turnId = turnId, role = "user", text = text, image = image)


    private fun assistantMsg(
        id: Long,
        turnId: Long,
        text: String,
        streaming: Boolean = false,
        regenerable: Boolean = false
    ): ChatUiMessage = ChatUiMessage(
        id = id, turnId = turnId, role = "assistant", text = text,
        streaming = streaming, regenerable = regenerable
    )

    /** 兼容旧实现里对"重做占位"的清理（当前无占位表，保留空实现以免调用点遗漏） */
    private fun append(msg: ChatUiMessage) {
        _messages.update { it + msg }
    }

    private fun updateMessage(id: Long, transform: (ChatUiMessage) -> ChatUiMessage) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** 状态行：按当前会话（会触发一次窗口裁剪，与真实发送口径一致）刷新 */
    private fun refreshContextStatus() {
        val ctx = session.buildContext(minTurns, maxTurns, charLimit)
        _contextStatus.update { it.withContext(ctx, minTurns, maxTurns, charLimit) }
    }

    private fun ContextStatus.withContext(
        ctx: Session.BuiltContext,
        min: Int,
        max: Int,
        limit: Int
    ): ContextStatus = copy(
        turns = ctx.turns,
        chars = ctx.chars,
        minTurns = min,
        maxTurns = max,
        charLimit = limit,
        trimmedBySoftCap = ctx.trimmedBySoftCap,
        // 回落原因只在真的回落的那次更新（runTurn 末尾还会再刷新一次状态，那次不会再回落，
        // 不能把原因抹掉——它解释的是"当前窗口为什么从这里起算"）
        windowReset = ctx.windowReset ?: windowReset
    )

    /**
     * 从一轮里取出用户的**原文**（去掉 Session 打的时间戳前缀），
     * 供重发/重做时做关键词判断与记录兜底。
     */
    private fun rawUserText(turn: Session.Turn): String? {
        val text = turn.userText ?: return null
        return text.replaceFirst(TIMESTAMP_PREFIX, "")
    }

    /** 一轮 → 持久化 DTO（会话轮与"待补整理的轮"共用） */
    private fun Session.Turn.toStored(): StoredTurn = StoredTurn(
        id = id,
        userText = userText,
        imagePath = imagePath,
        assistant = assistant.map { it.textContent },
        createdAt = createdAt,
        notices = notices.toList()
    )

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

    companion object {
        /** 用户消息时间戳前缀（Session 写入，见 Session.stamp） */
        private val TIMESTAMP_PREFIX = Regex("""^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}] """)

        /** 只发图片没写要求时的默认指令 */
        private const val DEFAULT_IMAGE_INSTRUCTION = "请描述这张图片"

        /** 一天的毫秒数（会话记录保留天数换算用） */
        private const val DAY_MS = 24L * 3600_000L

        /** 一次上下文整理最多补带多少"漏掉的历史片段"轮（防上下文爆炸） */
        private const val MAX_LEFTOVER_TURNS = 30

        /** 后台静默抽取长期记忆的旧入口已删除：对话内记忆改由主模型 write_memory 工具完成 */
    }
}
