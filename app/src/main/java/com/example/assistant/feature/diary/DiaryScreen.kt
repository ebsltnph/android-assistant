package com.example.assistant.feature.diary

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
 * 日记页：P3 实现。
 * - 多日记本（生活/工作），语音或文字记录，带准确时间戳
 * - 每日自动整理、长期记忆抽取
 * - 日记朗读、"历史上的今天"、导出/导入
 */
@Composable
fun DiaryScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "日记",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "日记功能开发中（P3：支持生活/工作分本、语音记录）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
