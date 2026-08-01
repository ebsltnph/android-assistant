package com.example.assistant.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication
import com.example.assistant.core.AppSharedState

/**
 * 首页：问候语 + 今日简报入口（随时可看最新简报，不必等通知）。
 * 点击清晨简报通知 → 打开 App 后在这里弹出完整简报。
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val vm: HomeViewModel = viewModel { HomeViewModel(app.container.summaryStore) }

    val briefing by vm.latestBriefing.collectAsState()
    val briefingDate by vm.latestBriefingDate.collectAsState()

    // 点击通知进入：直接弹最新简报
    val notifyBriefing by AppSharedState.briefingText.collectAsState()
    var briefingDialog by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(notifyBriefing) {
        if (notifyBriefing != null) {
            briefingDialog = notifyBriefing
            AppSharedState.briefingText.value = null
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "你好，我是随身助手",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "今天想聊点什么？",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        // 简报入口：有简报可随时点开看（日期是今天显示"今日简报"）
        if (briefing != null) {
            val isToday = briefingDate == todayString()
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = { briefingDialog = briefing },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isToday) "🌅 今日清晨简报（点击查看）" else "🌅 最近清晨简报（${briefingDate}，点击查看）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    briefingDialog?.let { text ->
        AlertDialog(
            onDismissRequest = { briefingDialog = null },
            title = { Text("🌅 清晨简报") },
            text = {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { briefingDialog = null }) { Text("关闭") }
            }
        )
    }
}

private fun todayString(): String {
    val cal = java.util.Calendar.getInstance()
    return "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
}
