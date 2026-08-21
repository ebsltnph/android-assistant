package com.example.assistant.feature.reminder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.data.db.entity.MonitoredEventEntity
import com.example.assistant.data.db.entity.ReminderEntity
import com.example.assistant.data.repo.EventRepository
import com.example.assistant.data.repo.ReminderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 提醒页 ViewModel：提醒列表（含状态）+ 事件监控列表 + 新增/删除/启停。
 */
class ReminderViewModel(
    private val reminderRepository: ReminderRepository,
    private val reminderScheduler: ReminderScheduler,
    private val eventRepository: EventRepository
) : ViewModel() {

    /** 提醒列表（按触发时间升序，含已触发/已取消历史） */
    val reminders: StateFlow<List<ReminderEntity>> = reminderRepository.reminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 事件监控列表 */
    val events: StateFlow<List<MonitoredEventEntity>> = eventRepository.events
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 操作提示（添加/删除结果），显示后自动消失 */
    val message = MutableStateFlow<String?>(null)

    /** 新增提醒（界面手动添加） */
    fun add(title: String, triggerAtEpochMillis: Long, repeatRule: String?) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val id = reminderRepository.add(title, triggerAtEpochMillis, repeatRule)
            reminderScheduler.schedule(reminderRepository.byId(id)!!)
            message.value = "⏰ 已设置提醒"
        }
    }

    /** 单条编辑提醒：取消旧闹钟 → 更新 DB → 按新时间重新排程 */
    fun update(id: Long, title: String, triggerAtEpochMillis: Long, repeatRule: String?) {
        if (title.isBlank()) return
        viewModelScope.launch {
            // 先取消旧闹钟（同时取消 5 分钟确认重复闹钟），再更新并排新闹钟
            reminderScheduler.cancel(id)
            reminderRepository.update(id, title.trim(), triggerAtEpochMillis, repeatRule)
            reminderRepository.byId(id)?.let { reminderScheduler.schedule(it) }
            message.value = "✏️ 提醒已更新"
        }
    }

    /** 删除提醒（同时取消闹钟） */
    fun delete(id: Long) {
        viewModelScope.launch {
            reminderScheduler.cancel(id)
            reminderRepository.delete(id)
        }
    }

    /** 事件监控：启用/停用 */
    fun setEventEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { eventRepository.setEnabled(id, enabled) }
    }

    /** 事件监控：更新配置（名称/搜索词/条件/规则/域名/周期） */
    fun updateEventConfig(
        id: Long, displayName: String, searchQuery: String,
        conditionKeywords: String, customRule: String, includeDomains: String, pollHours: Int
    ) {
        viewModelScope.launch {
            eventRepository.updateConfig(id, displayName, searchQuery, conditionKeywords, customRule, includeDomains)
            eventRepository.updatePollHours(id, pollHours)
            message.value = "🔎 事件已更新"
        }
    }

    /** 删除事件监控 */
    fun deleteEvent(id: Long) {
        viewModelScope.launch { eventRepository.delete(id) }
    }

    /** 某事件的触发历史 Flow（详情弹窗内 collect） */
    fun hitsFor(eventId: Long): kotlinx.coroutines.flow.Flow<List<com.example.assistant.data.db.entity.EventHitEntity>> =
        eventRepository.hitsFor(eventId)

    fun clearMessage() {
        message.value = null
    }
}
