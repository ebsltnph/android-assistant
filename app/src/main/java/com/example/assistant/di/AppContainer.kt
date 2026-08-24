package com.example.assistant.di

import android.content.Context
import com.example.assistant.core.agent.Agent
import com.example.assistant.core.agent.DailyBriefingGenerator
import com.example.assistant.core.agent.DailySummaryGenerator
import com.example.assistant.core.agent.DiarySummarizer
import com.example.assistant.core.agent.EventExtractor
import com.example.assistant.core.agent.EventHitJudge
import com.example.assistant.core.agent.IntentRouter
import com.example.assistant.core.agent.MemoryExtractor
import com.example.assistant.core.agent.PeriodSummaryGenerator
import com.example.assistant.core.agent.PromptBuilder
import com.example.assistant.core.agent.ReminderTimeParser
import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.core.backup.BackupManager
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.SearchClient
import com.example.assistant.core.network.TavilySearchClient
import com.example.assistant.core.quiet.QuietHours
import com.example.assistant.core.storage.ConversationLog
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.core.storage.SecretStore
import com.example.assistant.core.storage.SettingsStore
import com.example.assistant.core.storage.SummaryStore
import com.example.assistant.core.vision.ScreenSenseController
import com.example.assistant.core.vision.VisionAnalyzer
import com.example.assistant.data.db.AppDatabase
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

    // ---- 网络 ----
    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(secretStore, settingsStore)
    }
    val searchClient: SearchClient by lazy { TavilySearchClient(secretStore) }

    // ---- Agent 编排 ----
    val promptBuilder: PromptBuilder by lazy { PromptBuilder(promptStore) }
    val intentRouter: IntentRouter by lazy { IntentRouter(providerRegistry, promptStore) }
    val agent: Agent by lazy {
        Agent(providerRegistry, promptBuilder, intentRouter, searchClient)
    }
    val memoryExtractor: MemoryExtractor by lazy { MemoryExtractor(providerRegistry, promptStore) }
    val diarySummarizer: DiarySummarizer by lazy { DiarySummarizer(providerRegistry, promptStore) }
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

    // ---- P4：提醒 / 免打扰 / 搜索 / 事件监控 ----
    val reminderTimeParser: ReminderTimeParser by lazy { ReminderTimeParser(providerRegistry, promptStore) }
    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(appContext) }
    val quietHours: QuietHours by lazy { QuietHours(settingsStore) }
    val eventExtractor: EventExtractor by lazy { EventExtractor(providerRegistry, promptStore) }
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
    val visionAnalyzer: VisionAnalyzer by lazy { VisionAnalyzer(providerRegistry, promptStore) }

    // ---- P6：聊天核心（进程级共享单例：聊天页与浮动界面共用同一会话） ----
    val chatViewModel: ChatViewModel by lazy {
        ChatViewModel(
            context = appContext,
            agent = agent,
            intentRouter = intentRouter,
            diarySummarizer = diarySummarizer,
            settingsStore = settingsStore,
            diaryRepository = diaryRepository,
            memoryRepository = memoryRepository,
            memoryExtractor = memoryExtractor,
            reminderRepository = reminderRepository,
            reminderTimeParser = reminderTimeParser,
            reminderScheduler = reminderScheduler,
            eventRepository = eventRepository,
            eventExtractor = eventExtractor,
            visionAnalyzer = visionAnalyzer,
            screenSenseController = screenSenseController,
            conversationLog = conversationLog
        )
    }
}
