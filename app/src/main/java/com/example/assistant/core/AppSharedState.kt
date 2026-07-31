package com.example.assistant.core

import com.example.assistant.MainTab
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 跨页面共享的轻量状态（单例）。
 * 目前用于：点击通知「每日小结」→ 打开 App 后切到日记页并弹出完整小结。
 * 状态由 MainActivity 写入（解析 Intent extra），DiaryScreen 消费。
 */
object AppSharedState {

    /** 底部导航当前页（由 MainActivity 与底部栏共同驱动） */
    val currentTab = MutableStateFlow(MainTab.Home)

    /** 是否请求显示「最新每日小结」弹窗（DiaryScreen 消费后置回 false） */
    val showSummaryRequested = MutableStateFlow(false)
}
