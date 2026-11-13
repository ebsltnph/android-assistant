package com.example.assistant.feature.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.agent.Agent
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderProfile
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.WebAccessRegistry
import com.example.assistant.core.network.WebProvider
import com.example.assistant.core.scheduled.ScheduledChat
import com.example.assistant.core.scheduled.ScheduledChatManager
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.core.storage.SecretStore
import com.example.assistant.core.storage.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(
    private val secretStore: SecretStore,
    private val settingsStore: SettingsStore,
    private val promptStore: PromptStore,
    private val registry: ProviderRegistry,
    private val agent: Agent,
    private val webAccess: WebAccessRegistry,
    private val scheduledChats: ScheduledChatManager
) : ViewModel() {

    private val _profiles = MutableStateFlow<List<ProviderProfile>>(emptyList())
    val profiles: StateFlow<List<ProviderProfile>> = _profiles

    /** capability -> 指派档案 id（null = 使用默认档案） */
    private val _assignments = MutableStateFlow<Map<Capability, String?>>(emptyMap())
    val assignments: StateFlow<Map<Capability, String?>> = _assignments

    /** 测试连接的结果（per-provider：profileId -> 结果；Testing 表示该档案正在测试） */
    private val _testResult = MutableStateFlow<Map<String, TestResult>>(emptyMap())
    val testResult: StateFlow<Map<String, TestResult>> = _testResult

    /** 每日小结自动总结时间（分钟数，默认 21:00=1260，可精确到分钟） */
    val summaryMinute: StateFlow<Int> = settingsStore.dailySummaryMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 21 * 60)

    /** 每日小结自动任务开关（默认开） */
    val dailySummaryEnabled: StateFlow<Boolean> = settingsStore.dailySummaryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setSummaryMinute(minute: Int) {
        viewModelScope.launch { settingsStore.setDailySummaryMinute(minute) }
    }

    fun setDailySummaryEnabled(v: Boolean) {
        viewModelScope.launch { settingsStore.setDailySummaryEnabled(v) }
    }

    // ---- 搜索（Tavily）配置：空 key = keyless 免费模式 ----
    val searchApiKey = MutableStateFlow(secretStore.searchApiKey())

    fun saveSearchApiKey(key: String) {
        secretStore.saveSearchApiKey(key)
        searchApiKey.value = key
    }

    // ---- 联网搜索/抓取（两个独立选择；默认 Tavily，不自动切换） ----
    val searchProvider: StateFlow<WebProvider> = settingsStore.searchProvider
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WebProvider.TAVILY)
    val fetchProvider: StateFlow<WebProvider> = settingsStore.fetchProvider
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WebProvider.TAVILY)

    /** DeepSeek Web 专用 Key（空 = 复用官方 DeepSeek 对话档案） */
    private val _deepSeekWebApiKey = MutableStateFlow(secretStore.deepSeekWebApiKey())
    val deepSeekWebApiKey: StateFlow<String> = _deepSeekWebApiKey

    /** DeepSeek 凭据来源（设置页展示，不显示 Key 本身） */
    private val _deepSeekCredentialDescription = MutableStateFlow("正在检查…")
    val deepSeekCredentialDescription: StateFlow<String> = _deepSeekCredentialDescription

    /** 测试状态：key = "search_tavily" / "fetch_deepseek" … */
    private val _webTests = MutableStateFlow<Map<String, WebTestUiState>>(emptyMap())
    val webTests: StateFlow<Map<String, WebTestUiState>> = _webTests

    fun setSearchProvider(provider: WebProvider) {
        viewModelScope.launch { settingsStore.setSearchProvider(provider) }
    }

    fun setFetchProvider(provider: WebProvider) {
        viewModelScope.launch { settingsStore.setFetchProvider(provider) }
    }

    fun saveDeepSeekWebApiKey(key: String) {
        secretStore.saveDeepSeekWebApiKey(key)
        _deepSeekWebApiKey.value = key.trim()
        viewModelScope.launch { refreshDeepSeekCredential() }
    }

    /**
     * 显式测试指定提供商；不读取/切换当前选择，也绝不回退到另一家。
     * DeepSeek 每次测试会产生一次可能的搜索/抓取费用（UI 已提示）。
     */
    fun testWebProvider(provider: WebProvider, search: Boolean) {
        val testKey = testKey(provider, search)
        viewModelScope.launch {
            _webTests.value = _webTests.value + (testKey to WebTestUiState(running = true))
            val result = if (search) webAccess.probeSearch(provider)
            else webAccess.probeFetch(provider)
            _webTests.value = _webTests.value + (testKey to WebTestUiState(
                running = false,
                success = result.success,
                message = result.message + "（${result.elapsedMs} ms）"
            ))
        }
    }

    private suspend fun refreshDeepSeekCredential() {
        _deepSeekCredentialDescription.value = webAccess.deepSeekCredentialDescription()
    }

    /** 从设置页返回/档案更新后刷新凭据来源 */
    fun refreshWebAccessInfo() {
        viewModelScope.launch { refreshDeepSeekCredential() }
    }

    private fun testKey(provider: WebProvider, search: Boolean): String =
        (if (search) "search_" else "fetch_") + provider.storageValue

    // ---- 定时对话（2026-11-14） ----
    /** 全部定时配置（启用/停用、保存后立即反映；排程由 Manager 同步处理） */
    val scheduledChatList: StateFlow<List<ScheduledChat>> = scheduledChats.schedules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun scheduledChatCanExact(): Boolean = scheduledChats.canExact

    fun nextScheduledChatAt(schedule: ScheduledChat): Long? =
        scheduledChats.nextTriggerAt(schedule)

    fun saveScheduledChat(item: ScheduledChat) {
        viewModelScope.launch { scheduledChats.save(item) }
    }

    fun setScheduledChatEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { scheduledChats.setEnabled(id, enabled) }
    }

    fun deleteScheduledChat(id: Long) {
        viewModelScope.launch { scheduledChats.delete(id) }
    }

    fun runScheduledChatNow(id: Long) {
        scheduledChats.runNow(id)
    }

    // ---- 清晨简报 ----
    val briefingMinute: StateFlow<Int> = settingsStore.briefingMinuteOfDay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7 * 60 + 30)

    /** 清晨简报自动任务开关（默认开） */
    val briefingEnabled: StateFlow<Boolean> = settingsStore.briefingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setBriefingMinute(minutes: Int) {
        viewModelScope.launch { settingsStore.setBriefingMinuteOfDay(minutes) }
    }

    fun setBriefingEnabled(v: Boolean) {
        viewModelScope.launch { settingsStore.setBriefingEnabled(v) }
    }

    // ---- 免打扰时段（分钟数；起止相同 = 未启用） ----
    val quietStartMinute: StateFlow<Int> = settingsStore.quietStartMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 23 * 60)

    val quietEndMinute: StateFlow<Int> = settingsStore.quietEndMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7 * 60)

    fun setQuietWindow(startMinute: Int, endMinute: Int) {
        viewModelScope.launch { settingsStore.setQuietWindow(startMinute, endMinute) }
    }

    // ---- P6：悬浮球 ----
    val floatingBallEnabled: StateFlow<Boolean> = settingsStore.floatingBallEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setFloatingBallEnabled(v: Boolean) {
        viewModelScope.launch { settingsStore.setFloatingBallEnabled(v) }
    }

    // ---- v1.5.x：悬浮球语音输入方式 + 远程识别接口配置 ----
    val panelVoiceMode: StateFlow<String> = settingsStore.panelVoiceMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ime")

    fun setPanelVoiceMode(v: String) {
        viewModelScope.launch { settingsStore.setPanelVoiceMode(v) }
    }

    /** 点悬浮球是否自动开始语音（默认开） */
    val panelAutoVoiceEnabled: StateFlow<Boolean> = settingsStore.panelAutoVoiceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setPanelAutoVoiceEnabled(v: Boolean) {
        viewModelScope.launch { settingsStore.setPanelAutoVoiceEnabled(v) }
    }

    /** 远程识别「说完停顿」（毫秒，默认 2500） */
    val voiceSilenceMs: StateFlow<Int> = settingsStore.voiceSilenceMs
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            SettingsStore.DEFAULT_VOICE_SILENCE_MS
        )

    fun setVoiceSilenceMs(v: Int) {
        viewModelScope.launch { settingsStore.setVoiceSilenceMs(v) }
    }

    // ---- v1.5.x：悬浮球外观 ----
    val bubbleIconEmoji: StateFlow<String> = settingsStore.bubbleIconEmoji
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val bubbleIconImagePath: StateFlow<String> = settingsStore.bubbleIconImagePath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 选 emoji 图标（清掉自定义图片，两者互斥、图片优先所以必须清） */
    fun setBubbleIconEmoji(v: String) {
        viewModelScope.launch {
            settingsStore.setBubbleIconImagePath("")
            settingsStore.setBubbleIconEmoji(v)
        }
    }

    /**
     * 从相册导入自定义图片做悬浮球图标：居中裁方 → 缩到 256px → 存私有目录。
     * 成功后自动清掉 emoji（图片优先）。onDone(true)=导入成功。
     */
    fun importBubbleImage(context: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    // 先记住当前图片路径：新图落盘后把旧文件删掉（避免残留）
                    val oldPath = settingsStore.bubbleIconImagePath.first()
                    val src = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    } ?: return@withContext false
                    // 居中裁成正方形再缩放（悬浮球是圆的，方图裁圆形不变形）
                    val side = minOf(src.width, src.height)
                    if (side <= 0) return@withContext false
                    val square = Bitmap.createBitmap(
                        src, (src.width - side) / 2, (src.height - side) / 2, side, side
                    )
                    val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
                    // 每次导入用独立文件名：路径变化才会触发悬浮球重载（同路径覆盖内容不会刷新）
                    val f = File(context.filesDir, "bubble_icon_" + System.currentTimeMillis() + ".png")
                    f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    settingsStore.setBubbleIconEmoji("")
                    settingsStore.setBubbleIconImagePath(f.absolutePath)
                    // 删除上一张自定义图片文件
                    if (oldPath.isNotBlank() && oldPath != f.absolutePath) {
                        try { File(oldPath).delete() } catch (_: Exception) {}
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            onDone(ok)
        }
    }

    // ---- v1.4.1：识屏框选 ----
    /** 识屏后先弹选区层手动框选（**默认开** = 截屏后先框选再识别） */
    val screenSenseRegionEnabled: StateFlow<Boolean> = settingsStore.screenSenseRegionEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setScreenSenseRegionEnabled(v: Boolean) {
        viewModelScope.launch { settingsStore.setScreenSenseRegionEnabled(v) }
    }

    // ---- 对话：聊天上下文长度（上下限双阈值 + 字符软上限） ----
    /** 上限（默认 20 轮）：超过就回落到下限 */
    val conversationMaxTurns: StateFlow<Int> = settingsStore.conversationMaxTurns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)

    fun setConversationMaxTurns(v: Int) {
        viewModelScope.launch { settingsStore.setConversationMaxTurns(v) }
    }

    /** 下限（默认 5 轮） */
    val conversationMinTurns: StateFlow<Int> = settingsStore.conversationMinTurns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 5)

    fun setConversationMinTurns(v: Int) {
        viewModelScope.launch { settingsStore.setConversationMinTurns(v) }
    }

    /** 字符软上限（默认 24000 字当量；0 = 关闭） */
    val conversationCharLimit: StateFlow<Int> = settingsStore.conversationCharLimit
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            SettingsStore.DEFAULT_CONTEXT_CHAR_LIMIT
        )

    fun setConversationCharLimit(v: Int) {
        viewModelScope.launch { settingsStore.setConversationCharLimit(v) }
    }

    /** 会话快照保留天数（0 = 不留存；默认 7） */
    val chatSessionRetentionDays: StateFlow<Int> = settingsStore.chatSessionRetentionDays
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            SettingsStore.DEFAULT_CHAT_RETENTION_DAYS
        )

    fun setChatSessionRetentionDays(v: Int) {
        viewModelScope.launch { settingsStore.setChatSessionRetentionDays(v) }
    }

    /** 历史图片保留档位（-1 = 全部；0 = 只当前轮；默认 0 = 仅当前轮） */
    val chatImageKeep: StateFlow<Int> = settingsStore.chatImageKeep
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            SettingsStore.DEFAULT_CHAT_IMAGE_KEEP
        )

    fun setChatImageKeep(v: Int) {
        viewModelScope.launch { settingsStore.setChatImageKeep(v) }
    }

    // ---- 「进行中的事」缓冲区（2026-09-17）----

    /** 总开关（默认开）：关掉后状态块不注入、窗口回落时也不做上下文整理 */
    val bufferEnabled: StateFlow<Boolean> = settingsStore.bufferEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setBufferEnabled(v: Boolean) {
        viewModelScope.launch { settingsStore.setBufferEnabled(v) }
    }

    /** 注入块字符上限（默认 1200；超限只在注入文本里省略，不删数据） */
    val bufferInjectCharLimit: StateFlow<Int> = settingsStore.bufferInjectCharLimit
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            SettingsStore.DEFAULT_BUFFER_CHAR_LIMIT
        )

    fun setBufferInjectCharLimit(v: Int) {
        viewModelScope.launch { settingsStore.setBufferInjectCharLimit(v) }
    }

    /** 注入块最大条数（默认 15） */
    val bufferInjectMaxItems: StateFlow<Int> = settingsStore.bufferInjectMaxItems
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            SettingsStore.DEFAULT_BUFFER_MAX_ITEMS
        )

    fun setBufferInjectMaxItems(v: Int) {
        viewModelScope.launch { settingsStore.setBufferInjectMaxItems(v) }
    }

    /** 触发上下文整理的最小批次（字符当量，默认 2000） */
    val bufferCompactMinChars: StateFlow<Int> = settingsStore.bufferCompactMinChars
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            SettingsStore.DEFAULT_BUFFER_COMPACT_MIN_CHARS
        )

    fun setBufferCompactMinChars(v: Int) {
        viewModelScope.launch { settingsStore.setBufferCompactMinChars(v) }
    }

    // ---- 秘密功能：对话历史记录开关 ----
    val secretLogEnabled: StateFlow<Boolean> = settingsStore.secretLogEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setSecretLogEnabled(v: Boolean) {
        viewModelScope.launch { settingsStore.setSecretLogEnabled(v) }
    }

    init {
        refresh()
    }

    fun refresh() {
        _profiles.value = secretStore.loadProfiles()
        viewModelScope.launch {
            _assignments.value = Capability.entries.associateWith { cap ->
                settingsStore.currentProfileIdFor(cap).ifBlank { null }
            }
            refreshDeepSeekCredential()
        }
    }

    /** 保存档案（新增或更新）；设为默认时清除其他默认 */
    fun saveProfile(profile: ProviderProfile) {
        var list = _profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        if (profile.isDefault) {
            list = list.map { if (it.id == profile.id) it else it.copy(isDefault = false) }.toMutableList()
        }
        if (list.isNotEmpty() && list.none { it.isDefault }) {
            list[0] = list[0].copy(isDefault = true)
        }
        secretStore.saveProfiles(list)
        registry.invalidate()
        refresh()
    }

    fun deleteProfile(id: String) {
        var list = _profiles.value.filterNot { it.id == id }.toMutableList()
        if (list.isNotEmpty() && list.none { it.isDefault }) {
            list[0] = list[0].copy(isDefault = true)
        }
        secretStore.saveProfiles(list)
        registry.invalidate()
        refresh()
    }

    fun assignCapability(capability: Capability, profileId: String?) {
        viewModelScope.launch {
            settingsStore.setProfileIdFor(capability, profileId ?: "")
            registry.invalidate()
            refresh()
        }
    }

    /** 对指定档案测试连接（per-provider，结果按档案 id 记录） */
    fun testConnection(profileId: String) {
        viewModelScope.launch {
            _testResult.value = _testResult.value + (profileId to TestResult.Testing)
            val result = _profiles.value.firstOrNull { it.id == profileId }
                ?.let { agent.testConnection(it) }
                ?: Result.failure(IllegalStateException("档案不存在"))
            _testResult.value = _testResult.value + (profileId to result.fold(
                onSuccess = { TestResult.Success(it) },
                onFailure = { TestResult.Failure(it.message ?: "连接失败") }
            ))
        }
    }

    /** 设置某档案的思考深度（per-provider，随档案持久化） */
    fun setProfileThinking(profileId: String, reasoningEffort: String) {
        val profile = _profiles.value.firstOrNull { it.id == profileId } ?: return
        saveProfile(profile.copy(reasoningEffort = reasoningEffort))
    }

    sealed interface TestResult {
        data object Testing : TestResult
        data class Success(val reply: String) : TestResult
        data class Failure(val message: String) : TestResult
    }
}

/** 联网搜索/抓取测试的 UI 状态 */
data class WebTestUiState(
    val running: Boolean = false,
    val success: Boolean? = null,
    val message: String = ""
)
