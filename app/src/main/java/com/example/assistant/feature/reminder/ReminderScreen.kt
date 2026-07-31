package com.example.assistant.feature.reminder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 提醒页：P4 实现。
 * - 定时提醒（精确闹钟）+ 重复提醒（每日/每周）
 * - 新闻类事件监控（周期搜索，命中时通知）
 */
@Composable
fun ReminderScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "提醒",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "提醒功能开发中（P4：定时提醒 + 事件监控）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
