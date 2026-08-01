package com.example.assistant.core

import com.example.assistant.MainTab
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 跨页面共享的轻量状态（单例）。
 * 目前用于：点击通知「每日小结」→ 切到日记页弹完整小结；「清晨简报」→ 首页弹完整简报。
 * 状态由 MainActivity 写入（解析 Intent extra），对应页面消费。
 */
object AppSharedState {

    /** 底部导航当前页（由 MainActivity 与底部栏共同驱动） */
    val currentTab = MutableStateFlow(MainTab.Home)

    /** 是否请求显示「最新每日小结」弹窗（DiaryScreen 消费后置回 false） */
    val showSummaryRequested = MutableStateFlow(false)

    /** 待显示的简报全文（HomeScreen 消费后置回 null） */
    val briefingText = MutableStateFlow<String?>(null)

    /** 待确认的提醒 id（提醒通知点击 → App 弹确认窗，确认/稍后后置回 null） */
    val pendingReminderConfirmId = MutableStateFlow<Long?>(null)
}
