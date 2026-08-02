package com.example.assistant.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.storage.SettingsStore
import com.example.assistant.core.storage.SummaryStore
import com.example.assistant.data.db.entity.DailySummaryEntity
import com.example.assistant.data.db.entity.MonitoredEventEntity
import com.example.assistant.data.db.entity.ReminderEntity
import com.example.assistant.data.repo.EventRepository
import com.example.assistant.data.repo.ReminderRepository
import com.example.assistant.data.repo.SummaryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel（2026-08-02 改版）：清晨简报 / 昨日小结 / 最近提醒 / 事件监控 / 悬浮球开关。
 */
class HomeViewModel(
    summaryStore: SummaryStore,
    summaryRepository: SummaryRepository,
    reminderRepository: ReminderRepository,
    eventRepository: EventRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {

    /** 最新清晨简报（随时可看，不必等通知） */
    val latestBriefing: StateFlow<String?> = summaryStore.latestBriefing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val latestBriefingDate: StateFlow<String?> = summaryStore.latestBriefingDate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 最新每日小结（Room 按日期倒序，第一条即最近一次，通常是昨天） */
    val latestSummary: StateFlow<DailySummaryEntity?> = summaryRepository.summaries
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 最近（将到）的 4 条提醒：pending 且未过期，按时间升序 */
    val upcomingReminders: StateFlow<List<ReminderEntity>> = reminderRepository.reminders
        .map { list ->
            list.filter { it.status == "pending" && it.triggerAtEpochMillis >= System.currentTimeMillis() }
                .take(4)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 启用的监控事件（首页展示列表） */
    val events: StateFlow<List<MonitoredEventEntity>> = eventRepository.events
        .map { it.filter { e -> e.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 悬浮球开关（首页可直接启停，与设置页同一状态） */
    val floatingBallEnabled: StateFlow<Boolean> = settingsStore.floatingBallEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setFloatingBallEnabled(v: Boolean) {
        viewModelScope.launch { settingsStore.setFloatingBallEnabled(v) }
    }
}
