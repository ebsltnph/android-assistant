package com.example.assistant.di

import android.content.Context
import com.example.assistant.core.agent.Agent
import com.example.assistant.core.agent.ContextCompactor
import com.example.assistant.core.agent.DailyBriefingGenerator
import com.example.assistant.core.agent.DailySummaryGenerator
import com.example.assistant.core.agent.EventHitJudge
import com.example.assistant.core.agent.IntentRouter
import com.example.assistant.core.agent.MemoryExtractor
import com.example.assistant.core.agent.PeriodSummaryGenerator
import com.example.assistant.core.agent.PromptBuilder
import com.example.assistant.core.agent.ReminderTimeParser
import com.example.assistant.core.agent.tools.MonitorEventTool
import com.example.assistant.core.agent.tools.ListRemindersTool
import com.example.assistant.core.agent.tools.ReadDiaryTool
import com.example.assistant.core.agent.tools.ReadWebpageTool
import com.example.assistant.core.agent.tools.ScreenSenseTool
import com.example.assistant.core.agent.tools.SpeakTool
import com.example.assistant.core.agent.tools.UpdateDiaryTool
import com.example.assistant.core.agent.tools.UpdateBufferTool
import com.example.assistant.core.speech.TtsManager
import com.example.assistant.core.agent.tools.SetReminderTool
import com.example.assistant.core.agent.tools.ToolRegistry
import com.example.assistant.core.agent.tools.WebSearchTool
import com.example.assistant.core.agent.tools.WriteDiaryTool
import com.example.assistant.core.agent.tools.WriteMemoryTool
import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.core.backup.BackupManager
import com.example.assistant.core.network.AsrClient
import com.example.assistant.core.network.PageReader
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.SearchClient
import com.example.assistant.core.network.TavilyExtractClient
import com.example.assistant.core.network.TavilySearchClient
import com.example.assistant.data.db.entity.parseDiaryTags
import com.example.assistant.core.quiet.QuietHours
import com.example.assistant.core.storage.ConversationLog
import com.example.assistant.core.storage.ChatSessionStore
import com.example.assistant.core.storage.PrefixSnapshotStore
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.core.storage.SecretStore
import com.example.assistant.core.storage.SettingsStore
import com.example.assistant.core.storage.SummaryStore
import com.example.assistant.core.vision.ScreenSenseController
import com.example.assistant.data.db.AppDatabase
import com.example.assistant.data.repo.BufferRepository
import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.repo.EventRepository
import com.example.assistant.data.repo.MemoryRepository
import com.example.assistant.data.repo.ReminderRepository
import com.example.assistant.data.repo.SummaryRepository
import com.example.assistant.feature.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * 手动依赖注入容器：所有全局单例在这里创建。
 *
 * 各功能模块按需从容器取依赖，未来拆多模块时把对应字段移入独立容器即可。
 * 已接入：存储（加密/DataStore）、网络（OpenAI 兼容）、Agent 编排、Room 数据层。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /** 全局协程域（进程级；App 内长生命周期任务统一用它） */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 浮动界面状态机（P6 悬浮球）：
     * HIDDEN=面板关闭（悬浮球显示）；PANEL_OPEN=面板展开（悬浮球隐藏）；
     * CAPTURING=识屏授权/截屏中（悬浮球隐藏，防截进截图）。
     * 悬浮球服务订阅它控制悬浮球显隐。
     */
    enum class PanelState { HIDDEN, PANEL_OPEN, CAPTURING }
    val panelState: MutableStateFlow<PanelState> = MutableStateFlow(PanelState.HIDDEN)

    /**
     * 识屏框选（v1.4.1）：截屏完成后先弹「选区层」让用户拖动画框，确认后只识别
     * 框内区域。设置开关（screen_sense_region_enabled）在 Application 启动时缓存进来，
     * 供截屏服务（无 Compose 环境）直接同步读取。
     */
    @Volatile
    var screenSenseRegionEnabled: Boolean = true

    /**
     * 选区层心跳：RegionPickerActivity 显示期间由其协程周期刷新的时间戳。
     * 截屏服务等待选区结果时若发现心跳停止（进程被杀后服务重建/Activity 意外消失），
     * 自动放弃等待走整屏识别，避免识屏流程卡死。
     */
    @Volatile
    var regionPickerHeartbeatAt: Long = 0L

    // ---- 存储 ----
    val secretStore: SecretStore by lazy { SecretStore(appContext) }
    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }
    val promptStore: PromptStore by lazy { PromptStore(appContext) }
    val summaryStore: SummaryStore by lazy { SummaryStore(appContext) }

    /** 会话快照（轻量持久化：一个 JSON 文件，不进备份；按保留天数定时清理） */
    val chatSessionStore: ChatSessionStore by lazy { ChatSessionStore(appContext) }

    /**
     * 注入前缀的**冻结快照**（2026-09-17「进行中的事」）：
     * 记忆块与状态块的文本只在"合并点"（窗口回落流程）重渲染一次，
     * 两次合并之间前缀逐字节不变 → 厂商提示词缓存全程命中。
     */
    val prefixSnapshotStore: PrefixSnapshotStore by lazy { PrefixSnapshotStore(appContext) }

    // ---- 秘密功能：对话历史记录（数字分身素材） ----
    val conversationLog: ConversationLog by lazy { ConversationLog(appContext, settingsStore) }

    // ---- v1.3：数据备份与导入（手动导出/恢复 + 定期自动备份） ----
    val backupManager: BackupManager by lazy {
        BackupManager(
            context = appContext,
            db = database,
            settingsStore = settingsStore,
            promptStore = promptStore,
            summaryStore = summaryStore,
            secretStore = secretStore,
            conversationLog = conversationLog
        )
    }

    // ---- 数据库 ----
    val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    val diaryRepository: DiaryRepository by lazy { DiaryRepository(database.diaryDao()) }
    val memoryRepository: MemoryRepository by lazy { MemoryRepository(database.memoryDao()) }
    val reminderRepository: ReminderRepository by lazy { ReminderRepository(database.reminderDao()) }
    val eventRepository: EventRepository by lazy { EventRepository(database.eventDao()) }
    val summaryRepository: SummaryRepository by lazy { SummaryRepository(database.summaryDao()) }

    /** 「进行中的事」缓冲区（第三层记忆：一段时间内成立、会过期的状态） */
    val bufferRepository: BufferRepository by lazy {
        BufferRepository(database.bufferItemDao()) { id -> diaryRepository.entryById(id) != null }
    }

    // ---- 网络 ----
    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(secretStore, settingsStore)
    }
    val searchClient: SearchClient by lazy { TavilySearchClient(secretStore) }
    val pageReader: PageReader by lazy { TavilyExtractClient(secretStore) }

    // ---- Agent 编排（主模型统一调度：工具注册表 + 对话回路） ----
    val promptBuilder: PromptBuilder by lazy { PromptBuilder(promptStore) }
    val intentRouter: IntentRouter by lazy { IntentRouter() }
    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(
            listOf(
                WebSearchTool(searchClient),
                ReadWebpageTool(pageReader),
                // 读日记：主模型可检索用户历史日记
                ReadDiaryTool(diaryRepository),
                // 改日记：按 #id 修改正文/标签或删除（id 来自 read_diary 的结果）
                UpdateDiaryTool(diaryRepository) { parseDiaryTags(settingsStore.diaryTagsCsv.first()) },
                // 读提醒：查看提醒列表（"我有哪些提醒 / 明天有什么安排"）
                ListRemindersTool(reminderRepository),
                // 朗读：模型自主决定何时读、读什么（精简口语版）
                SpeakTool(ttsManager),
                SetReminderTool(reminderRepository, reminderScheduler, reminderTimeParser),
                WriteMemoryTool(memoryRepository),
                // 可用标签随设置变化，用惰性提供者每次执行时现读
                WriteDiaryTool(diaryRepository) { parseDiaryTags(settingsStore.diaryTagsCsv.first()) },
                MonitorEventTool(eventRepository),
                ScreenSenseTool(screenSenseController),
                // 「进行中的事」维护：**只有上下文整理（窗口回落）时允许调用**，
                // 普通轮会被工具内部硬拦截（description 里也写死了不许主动调用）
                UpdateBufferTool(bufferRepository)
            )
        )
    }
    val agent: Agent by lazy {
        Agent(providerRegistry, promptBuilder, intentRouter, toolRegistry)
    }

    /**
     * 上下文整理（2026-09-17）：窗口回落到下限时，把即将离开上下文的对话蒸馏进
     * 「进行中的事」。走**对话同一链路**（复用缓存前缀）+ 尾部【上下文整理】指令 + 工具调用，
     * 所以它的输入几乎全部命中缓存，成本只有那条指令。
     */
    val contextCompactor: ContextCompactor by lazy {
        ContextCompactor(agent, promptBuilder, toolRegistry)
    }
    /** 记忆抽取仅保留给日记页手动保存用（对话内记忆改由 write_memory 工具完成） */
    val memoryExtractor: MemoryExtractor by lazy { MemoryExtractor(providerRegistry, promptStore) }
    val dailySummaryGenerator: DailySummaryGenerator by lazy {
        DailySummaryGenerator(
            diaryRepository = diaryRepository,
            providerRegistry = providerRegistry,
            promptStore = promptStore,
            summaryStore = summaryStore,
            summaryRepository = summaryRepository,
            appContext = appContext
        )
    }
    val periodSummaryGenerator: PeriodSummaryGenerator by lazy {
        PeriodSummaryGenerator(
            diaryRepository = diaryRepository,
            providerRegistry = providerRegistry,
            promptStore = promptStore,
            summaryRepository = summaryRepository
        )
    }

    // ---- P4：提醒 / 免打扰 / 事件监控 ----
    /** 提醒时间本地计算（无 LLM；set_reminder 工具用） */
    val reminderTimeParser: ReminderTimeParser by lazy { ReminderTimeParser() }
    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(appContext) }
    val quietHours: QuietHours by lazy { QuietHours(settingsStore) }
    val eventHitJudge: EventHitJudge by lazy { EventHitJudge(providerRegistry, promptStore) }
    val dailyBriefingGenerator: DailyBriefingGenerator by lazy {
        DailyBriefingGenerator(
            reminderRepository = reminderRepository,
            summaryRepository = summaryRepository,
            providerRegistry = providerRegistry,
            promptStore = promptStore,
            summaryStore = summaryStore
        )
    }

    // ---- P5：识屏 / 分享 ----
    val screenSenseController: ScreenSenseController by lazy { ScreenSenseController() }

    // ---- v1.5.x：语音输出（TTS 朗读，进程级单例） ----
    val ttsManager: TtsManager by lazy { TtsManager(appContext) }

    // ---- v1.5.x：远程语音识别客户端（悬浮球语音输入 remote 模式用） ----
    val asrClient: AsrClient by lazy { AsrClient.create() }

    // ---- P6：聊天核心（进程级共享单例：聊天页与浮动界面共用同一会话） ----
    val chatViewModel: ChatViewModel by lazy {
        ChatViewModel(
            context = appContext,
            agent = agent,
            intentRouter = intentRouter,
            settingsStore = settingsStore,
            diaryRepository = diaryRepository,
            memoryRepository = memoryRepository,
            screenSenseController = screenSenseController,
            conversationLog = conversationLog,
            ttsManager = ttsManager,
            sessionStore = chatSessionStore,
            bufferRepository = bufferRepository,
            compactor = contextCompactor,
            snapshotStore = prefixSnapshotStore
        )
    }
}
