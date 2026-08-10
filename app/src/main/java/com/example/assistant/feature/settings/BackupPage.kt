package com.example.assistant.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication
import com.example.assistant.core.backup.BackupFile
import com.example.assistant.core.ui.GlassCard

/**
 * 数据备份与导入子页面（v1.3）：
 * - 定期自动备份：开关 + 间隔（每天/3天/周），写公共「下载」目录，保留 3 份
 * - 手动导出：SAF CreateDocument 导出全量备份（不含 API Key）
 * - 导入恢复：SAF OpenDocument 选备份 → 确认框（显示摘要）→ 覆盖恢复 → 自动重启
 */
@Composable
fun BackupPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val vm: BackupViewModel = viewModel {
        BackupViewModel(app.container.backupManager, app.container.settingsStore)
    }

    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val preview by vm.preview.collectAsState()
    val autoEnabled by vm.autoBackupEnabled.collectAsState()
    val intervalDays by vm.autoBackupIntervalDays.collectAsState()

    // SAF launchers（免存储权限）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { vm.exportTo(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.previewBackup(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("数据备份与导入", style = MaterialTheme.typography.titleLarge)
            }
        }

        // ---- 说明卡 ----
        item {
            GlassCard {
                Text(
                    "备份范围：设置、提示词、模型配置、日记（含图片）、记忆、提醒、事件监控、每日小结、对话历史。\n" +
                        "备份不包含 API Key——恢复后需在「模型配置」重新填写密钥。恢复会清空当前所有数据并自动重启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 定期自动备份 ----
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("定期自动备份", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "每隔一段时间自动生成一份完整备份",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoEnabled,
                            onCheckedChange = { enabled ->
                                vm.setAutoBackupEnabled(enabled)
                                if (enabled) app.rescheduleAutoBackup(intervalDays)
                                else app.stopAutoBackup()
                            }
                        )
                    }
                    if (autoEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("间隔", style = MaterialTheme.typography.bodyMedium)
                            IntervalDropdown(
                                currentDays = intervalDays,
                                onSelect = { days ->
                                    vm.setAutoBackupIntervalDays(days)
                                    app.rescheduleAutoBackup(days)
                                },
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                    Text(
                        "自动备份会保存到手机「下载」文件夹（卸载 App 也不丢失），保留最近 3 份。\n" +
                            "建议同时定期用下方「导出备份」另存一份到安全位置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- 手动导出 / 导入 ----
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch("assistant_backup.zip") },
                            enabled = !busy
                        ) { Text("导出备份") }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                            enabled = !busy
                        ) { Text("导入备份") }
                    }
                    Text(
                        if (busy) "正在处理…" else (message ?: "导出到任意位置；导入需从备份文件恢复。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // ---- 恢复确认对话框 ----
    preview?.let { data ->
        RestoreConfirmDialog(
            data = data,
            onConfirm = { vm.confirmRestore() },
            onDismiss = { vm.dismissPreview() }
        )
    }
}

/** 自动备份间隔下拉（每天/每 3 天/每周） */
@Composable
private fun IntervalDropdown(currentDays: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (currentDays) {
        1 -> "每天"
        3 -> "每 3 天"
        else -> "每周"
    }
    OutlinedButton(onClick = { expanded = true }, modifier = modifier) {
        Text(label)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("每天") }, onClick = { onSelect(1); expanded = false })
        DropdownMenuItem(text = { Text("每 3 天") }, onClick = { onSelect(3); expanded = false })
        DropdownMenuItem(text = { Text("每周") }, onClick = { onSelect(7); expanded = false })
    }
}

/** 恢复确认：显示备份摘要 + 覆盖警告 */
@Composable
private fun RestoreConfirmDialog(
    data: BackupFile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val summary = buildString {
        append("日记 ${data.diaryEntries.size} 条（含图片 ${data.diaryImages.size} 张）")
        append(" · 记忆 ${data.memories.size} 条")
        append(" · 提醒 ${data.reminders.size} 条")
        append(" · 事件 ${data.monitoredEvents.size} 个")
        append(" · 小结 ${data.dailySummaries.size} 条")
        if (data.prompts.isNotEmpty()) append(" · 提示词 ${data.prompts.size} 组")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复备份？") },
        text = {
            Text(
                "$summary\n\n" +
                    "恢复将清空当前所有数据并写入备份内容，完成后 App 自动重启。此操作不可撤销，备份后的新数据会丢失。",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认恢复") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
