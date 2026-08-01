package com.example.assistant.di

import android.content.Context
import com.example.assistant.core.agent.Agent
import com.example.assistant.core.agent.DailyBriefingGenerator
import com.example.assistant.core.agent.DailySummaryGenerator
import com.example.assistant.core.agent.EventExtractor
import com.example.assistant.core.agent.EventHitJudge
import com.example.assistant.core.agent.IntentRouter
import com.example.assistant.core.agent.MemoryExtractor
import com.example.assistant.core.agent.PromptBuilder
import com.example.assistant.core.agent.ReminderTimeParser
import com.example.assistant.core.agent.SearchJudger
import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.SearchClient
import com.example.assistant.core.network.TavilySearchClient
import com.example.assistant.core.quiet.QuietHours
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.core.storage.SecretStore
import com.example.assistant.core.storage.SettingsStore
import com.example.assistant.core.storage.SummaryStore
import com.example.assistant.core.vision.ScreenSenseController
import com.example.assistant.core.vision.VisionAnalyzer
import com.example.assistant.data.db.AppDatabase
import com.example.assistant.service.ScreenResultOverlay
import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.repo.EventRepository
import com.example.assistant.data.repo.MemoryRepository
import com.example.assistant.data.repo.ReminderRepository
import com.example.assistant.data.repo.SummaryRepository

/**
 * 手动依赖注入容器：所有全局单例在这里创建。
 *
 * 各功能模块按需从容器取依赖，未来拆多模块时把对应字段移入独立容器即可。
 * 已接入：存储（加密/DataStore）、网络（OpenAI 兼容）、Agent 编排、Room 数据层。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    // ---- 存储 ----
    val secretStore: SecretStore by lazy { SecretStore(appContext) }
    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }
    val promptStore: PromptStore by lazy { PromptStore(appContext) }
    val summaryStore: SummaryStore by lazy { SummaryStore(appContext) }

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
    val searchJudger: SearchJudger by lazy { SearchJudger(providerRegistry, promptStore) }
    val agent: Agent by lazy {
        Agent(providerRegistry, promptBuilder, intentRouter, searchJudger, searchClient)
    }
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
    val screenResultOverlay: ScreenResultOverlay by lazy {
        ScreenResultOverlay(appContext, visionAnalyzer, screenSenseController)
    }
}
