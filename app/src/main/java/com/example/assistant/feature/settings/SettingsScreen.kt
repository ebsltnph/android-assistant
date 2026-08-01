package com.example.assistant.feature.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderProfile
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.service.FloatingBallService
import kotlinx.coroutines.launch

/**
 * 设置页：模型提供商管理（多档案、能力指派、测试连接）+ 提示词编辑。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val vm: SettingsViewModel = viewModel {
        SettingsViewModel(
            app.container.secretStore,
            app.container.settingsStore,
            app.container.promptStore,
            app.container.providerRegistry,
            app.container.agent
        )
    }

    val profiles by vm.profiles.collectAsState()
    val assignments by vm.assignments.collectAsState()
    val testResult by vm.testResult.collectAsState()

    var editing by remember { mutableStateOf<ProviderProfile?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingPromptKey by remember { mutableStateOf<PromptStore.PromptKey?>(null) }

    // 悬浮窗权限状态（识屏小窗需要）；从系统设置页返回时刷新
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("模型提供商", style = MaterialTheme.typography.titleLarge) }

        if (profiles.isEmpty()) {
            item {
                Text(
                    "还没有配置模型提供商。添加一个即可开始聊天（支持 DeepSeek、通义、Kimi、OpenAI 等 OpenAI 兼容接口）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(profiles, key = { it.id }) { profile ->
            ProviderCard(
                profile = profile,
                onEdit = { editing = profile },
                onDelete = { vm.deleteProfile(profile.id) }
            )
        }

        item {
            OutlinedButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("添加提供商")
            }
        }

        item { HorizontalDivider() }
        item { Text("能力指派", style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                "每个能力可单独使用一个提供商（例如识屏用支持视觉的模型）。默认都使用「默认」档案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Capability.entries.forEach { cap ->
            item {
                CapabilityRow(
                    capability = cap,
                    assignedId = assignments[cap],
                    profiles = profiles,
                    onAssign = { vm.assignCapability(cap, it) }
                )
            }
        }

        item { HorizontalDivider() }
        item { Text("连接测试", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { vm.testConnection() },
                    enabled = profiles.isNotEmpty()
                ) {
                    Text("测试当前「对话」配置")
                }
                when (val r = testResult) {
                    null -> {}
                    is SettingsViewModel.TestResult.Testing -> Text(" 测试中…")
                    is SettingsViewModel.TestResult.Success ->
                        Text(" ✓ ${r.reply}", color = MaterialTheme.colorScheme.primary)
                    is SettingsViewModel.TestResult.Failure ->
                        Text(" ✗ ${r.message}", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        item { HorizontalDivider() }
        item { Text("搜索（联网）", style = MaterialTheme.typography.titleLarge) }
        item {
            SearchSettingsCard(
                apiKey = vm.searchApiKey.collectAsState().value,
                onSaveKey = { vm.saveSearchApiKey(it) }
            )
        }

        item { HorizontalDivider() }
        item { Text("识屏", style = MaterialTheme.typography.titleLarge) }
        item {
            // 视觉模型状态：能力指派 → 默认档案兜底（与 ProviderRegistry.profileFor 一致）
            val visionProfile = remember(assignments, profiles) {
                val assignedId = assignments[Capability.VISION]
                profiles.firstOrNull { it.id == assignedId }
                    ?: profiles.firstOrNull { it.isDefault }
            }
            ScreenSenseSettingsCard(
                visionProfile = visionProfile,
                overlayGranted = overlayGranted,
                onOpenOverlaySettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )
        }

        item { HorizontalDivider() }
        item { Text("悬浮球", style = MaterialTheme.typography.titleLarge) }
        item {
            // 悬浮球开关：开 → 启动前台服务（常驻），关 → 停止
            FloatingBallSettingsCard(
                enabled = vm.floatingBallEnabled.collectAsState().value,
                overlayGranted = overlayGranted,
                onEnabledChange = { on ->
                    vm.setFloatingBallEnabled(on)
                    if (on) FloatingBallService.start(context) else FloatingBallService.stop(context)
                },
                onOpenOverlaySettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )
        }

        item { HorizontalDivider() }
        item { Text("高级设置", style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                "控制推理模型（如 DeepSeek v4 flash）的思考行为。关闭思考可省流量加快回复，调浅深度可减少思考消耗。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            AdvancedSettingsCard(
                thinkingMode = vm.thinkingMode.collectAsState().value,
                reasoningEffort = vm.reasoningEffort.collectAsState().value,
                onThinkingModeChange = { vm.setThinkingMode(it) },
                onReasoningEffortChange = { vm.setReasoningEffort(it) }
            )
        }

        item { HorizontalDivider() }
        item { Text("每日小结", style = MaterialTheme.typography.titleLarge) }
        item {
            DailySummarySettingsCard(
                summaryMinute = vm.summaryMinute.collectAsState().value,
                onMinuteChange = { minute ->
                    vm.setSummaryMinute(minute)
                    // 重排 WorkManager 周期任务（直接用刚选的分钟，避免读到旧值）
                    app.rescheduleDailySummary(minute)
                }
            )
        }

        item { HorizontalDivider() }
        item { Text("清晨简报", style = MaterialTheme.typography.titleLarge) }
        item {
            BriefingSettingsCard(
                briefingMinute = vm.briefingMinute.collectAsState().value,
                onMinuteChange = { minute ->
                    vm.setBriefingMinute(minute)
                    // 重排 WorkManager（直接用刚选的值）
                    app.rescheduleBriefing(minute)
                }
            )
        }

        item { HorizontalDivider() }
        item { Text("免打扰", style = MaterialTheme.typography.titleLarge) }
        item {
            QuietHoursSettingsCard(
                startMinute = vm.quietStartMinute.collectAsState().value,
                endMinute = vm.quietEndMinute.collectAsState().value,
                onWindowChange = { start, end -> vm.setQuietWindow(start, end) }
            )
        }

        item { HorizontalDivider() }
        item { Text("提示词（可编辑）", style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                "各组提示词相互独立、各有默认值（含 LLM 判断类）。可随时编辑或恢复默认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PromptStore.PromptKey.entries.forEach { key ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(key.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                key.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { editingPromptKey = key }) { Text("编辑") }
                    }
                }
            }
        }
    }

    // 添加 / 编辑提供商对话框
    if (showAddDialog || editing != null) {
        ProviderEditDialog(
            initial = editing,
            onDismiss = {
                showAddDialog = false
                editing = null
            },
            onSave = { profile ->
                vm.saveProfile(profile)
                showAddDialog = false
                editing = null
            }
        )
    }

    // 提示词编辑对话框（9 组提示词通用，含恢复默认）
    editingPromptKey?.let { key ->
        PromptEditDialog(
            key = key,
            onDismiss = { editingPromptKey = null }
        )
    }
}

/**
 * 悬浮球设置卡片（P6）：开关 + 悬浮窗权限引导 + 使用说明。
 * 开启 = 启动前台服务（通知栏常驻一条低优先级通知，悬浮球在任意应用上层）。
 */
@Composable
private fun FloatingBallSettingsCard(
    enabled: Boolean,
    overlayGranted: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用悬浮球", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "在任意应用上层悬浮一个小球，点开即可识屏 / 提醒 / 记录 / 对话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                Text(
                    "说明：需悬浮窗权限；开启后通知栏常驻一条「悬浮球运行中」（可手动关掉，不影响功能）；" +
                        "识图每次都要点一次系统授权。为保证悬浮球不被系统杀掉，建议在系统设置里给随身助手开启" +
                        "「电池无限制」和「自启动」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (!overlayGranted) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("悬浮窗权限：未开启", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onOpenOverlaySettings) { Text("去开启") }
                    }
                }
            }
        }
    }
}

/**
 * 识屏设置卡片：视觉模型配置状态 + 悬浮窗权限引导。
 * （识屏入口：聊天说「识屏」/ 快捷磁贴 / 分享 / 聊天上传图片）
 */
@Composable
private fun ScreenSenseSettingsCard(
    visionProfile: ProviderProfile?,
    overlayGranted: Boolean,
    onOpenOverlaySettings: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("识别屏幕 / 图片", style = MaterialTheme.typography.titleSmall)
            // 视觉模型状态
            when {
                visionProfile == null || !visionProfile.isConfigured() -> Text(
                    "⚠️ 未配置识屏模型。请在「能力指派」中把「识屏（视觉）」指派给支持图片输入的模型" +
                        "（如通义 qwen-vl、智谱 GLM-4V、Kimi vision、OpenAI gpt-4o）；" +
                        "DeepSeek 官方 API 不支持图片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                !visionProfile.supportsVision -> Text(
                    "⚠️ 识屏模型：${visionProfile.name}（未勾选「支持图片输入」，编辑该提供商开启）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                else -> Text(
                    "✓ 识屏模型：${visionProfile.name}（${visionProfile.model}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // 悬浮窗权限状态（识屏小窗显示在其他 App 上层需要）
            Text(
                if (overlayGranted) "悬浮窗权限：已开启 ✓"
                else "⚠️ 悬浮窗权限未开启（识屏小窗需要）",
                style = MaterialTheme.typography.bodySmall,
                color = if (overlayGranted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            if (!overlayGranted) {
                Button(onClick = onOpenOverlaySettings) { Text("去开启悬浮窗权限") }
            }
        }
    }
}

/** 分钟级时间选择器：小时 + 分钟两个下拉并排（所有时间设置统一用） */
@Composable
private fun MinutePicker(current: Int, onChange: (Int) -> Unit) {
    val hour = current / 60
    val minute = current % 60
    var hourExpanded by remember { mutableStateOf(false) }
    var minuteExpanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { hourExpanded = true }) {
            Text("%02d".format(hour), style = MaterialTheme.typography.bodyMedium)
        }
        Text(":", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { minuteExpanded = true }) {
            Text("%02d".format(minute), style = MaterialTheme.typography.bodyMedium)
        }
    }
    DropdownMenu(expanded = hourExpanded, onDismissRequest = { hourExpanded = false }) {
        (0..23).forEach { h ->
            DropdownMenuItem(
                text = { Text("%02d:00".format(h)) },
                onClick = { onChange(h * 60 + minute); hourExpanded = false }
            )
        }
    }
    DropdownMenu(expanded = minuteExpanded, onDismissRequest = { minuteExpanded = false }) {
        (0..59).forEach { m ->
            DropdownMenuItem(
                text = { Text(":%02d".format(m)) },
                onClick = { onChange(hour * 60 + m); minuteExpanded = false }
            )
        }
    }
}

/**
 * 每日小结设置卡片：
 * - 自动总结时间（小时+分钟，保存后重排周期任务）
 * - 系统日历同步开关（需 WRITE_CALENDAR 权限）
 */
@Composable
private fun DailySummarySettingsCard(
    summaryMinute: Int,
    onMinuteChange: (Int) -> Unit
) {
    val context = LocalContext.current
    // 系统日历 Provider 要求 READ + WRITE 两个权限同时具备（只给 WRITE 会写入失败）
    val calendarGranted = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED &&
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    val calendarLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动总结时间", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                MinutePicker(current = summaryMinute, onChange = onMinuteChange)
            }
            Text(
                "每天此时自动汇总当天日记，生成小结并推送通知（当天无日记则不打扰）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (calendarGranted) "✓ 已同步到系统日历（每天小结成为日历事件）"
                    else "同步到系统日历（可在日历 App 查看每日小结）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (calendarGranted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (!calendarGranted) {
                    OutlinedButton(onClick = {
                        calendarLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.READ_CALENDAR,
                                android.Manifest.permission.WRITE_CALENDAR
                            )
                        )
                    }) { Text("授权") }
                }
            }
        }
    }
}

/** 清晨简报设置卡片：时间选择（小时+分钟），保存后重排周期任务 */
@Composable
private fun BriefingSettingsCard(
    briefingMinute: Int,
    onMinuteChange: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("简报时间", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                MinutePicker(current = briefingMinute, onChange = onMinuteChange)
            }
            Text(
                "每天此时推送清晨简报：今日提醒 + 昨日小结。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 免打扰设置卡片：开关 + 起止时间（跨午夜支持，如 23:00-07:00） */
@Composable
private fun QuietHoursSettingsCard(
    startMinute: Int,
    endMinute: Int,
    onWindowChange: (Int, Int) -> Unit
) {
    // 起止相同 = 未启用
    val enabled = startMinute != endMinute
    var enabledState by remember { mutableStateOf(enabled) }
    var startState by remember { mutableStateOf(startMinute) }
    var endState by remember { mutableStateOf(endMinute) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用免打扰", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(
                    checked = enabledState,
                    onCheckedChange = { on ->
                        enabledState = on
                        // 关闭 = 起止相同；开启 = 用当前选的时段
                        if (!on) onWindowChange(startState, startState)
                        else onWindowChange(startState, endState)
                    }
                )
            }
            // 起止时间选择（仅开启时显示，精确到分钟）
            if (enabledState) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开始", modifier = Modifier.weight(1f))
                    MinutePicker(current = startState) { m ->
                        startState = m
                        onWindowChange(m, endState)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("结束", modifier = Modifier.weight(1f))
                    MinutePicker(current = endState) { m ->
                        endState = m
                        onWindowChange(startState, m)
                    }
                }
            }
            Text(
                if (enabledState) "免打扰时段内：提醒静默（不响铃）、事件监控不打扰。"
                else "免打扰时段内：提醒静默（不响铃）、事件监控不打扰。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 搜索设置卡片（Tavily）：留空 = keyless 免费模式（免注册、有限流）；
 * 填 API Key = 免费 1000 次/月，注册 https://tavily.com 获取。
 */
@Composable
private fun SearchSettingsCard(
    apiKey: String,
    onSaveKey: (String) -> Unit
) {
    var key by remember { mutableStateOf(apiKey) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "对话搜索与新闻监控共用。留空则用免注册免费模式（有限流）；注册 Tavily（tavily.com）填 Key 后每月 1000 次免费。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Tavily API Key（可选）") },
                placeholder = { Text("tvly-…，留空用免费模式") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onSaveKey(key) }) { Text("保存") }
                Text(
                    if (key.isBlank()) "当前：免费模式（keyless）" else "已配置 API Key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 高级设置卡片：思考开关 + 思考深度。
 * 均为三态/多选下拉，"跟随默认"= 不发送参数（各厂商模型按自身默认行为）。
 */
@Composable
private fun AdvancedSettingsCard(
    thinkingMode: String,
    reasoningEffort: String,
    onThinkingModeChange: (String) -> Unit,
    onReasoningEffortChange: (String) -> Unit
) {
    // 下拉选项：显示名 -> 存储值
    val thinkingOptions = listOf(
        "跟随模型默认" to "default",
        "开启思考" to "on",
        "关闭思考" to "off"
    )
    val effortOptions = listOf(
        "跟随模型默认" to "default",
        "简洁（思考短）" to "low",
        "均衡" to "medium",
        "深入（思考长）" to "high"
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DropdownSettingRow(
                label = "思考模式",
                options = thinkingOptions,
                current = thinkingOptions.firstOrNull { it.second == thinkingMode }?.first ?: thinkingOptions.first().first,
                onSelect = { onThinkingModeChange(it.second) }
            )
            DropdownSettingRow(
                label = "思考深度",
                options = effortOptions,
                current = effortOptions.firstOrNull { it.second == reasoningEffort }?.first ?: effortOptions.first().first,
                onSelect = { onReasoningEffortChange(it.second) }
            )
        }
    }
}

/** 一行"标签 + 下拉选择"设置项 */
@Composable
private fun DropdownSettingRow(
    label: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (Pair<String, String>) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        var expanded by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { expanded = true }) {
            Text(current)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.first) },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    profile: ProviderProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (profile.isDefault) AssistChip(onClick = {}, label = { Text("默认") })
                if (profile.supportsVision) AssistChip(onClick = {}, label = { Text("视觉") })
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "编辑") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
            }
            Text(
                "${profile.model.ifBlank { "（未填模型）" }} · ${profile.baseUrl.ifBlank { "（未填地址）" }}",
                style = MaterialTheme.typography.bodySmall,
                color = if (profile.isConfigured()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
            if (!profile.isConfigured()) {
                Text("配置不完整", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    capability: Capability,
    assignedId: String?,
    profiles: List<ProviderProfile>,
    onAssign: (String?) -> Unit
) {
    val defaultName = profiles.firstOrNull { it.isDefault }?.name ?: "（无默认档案）"
    val currentName = profiles.firstOrNull { it.id == assignedId }?.name ?: "默认"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(capability.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        capability.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                var expanded by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { expanded = true }) {
                    Text(currentName)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("默认（$defaultName）") },
                        onClick = { onAssign(null); expanded = false }
                    )
                    profiles.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name) },
                            onClick = { onAssign(p.id); expanded = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderEditDialog(
    initial: ProviderProfile?,
    onDismiss: () -> Unit,
    onSave: (ProviderProfile) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "https://api.deepseek.com") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "deepseek-chat") }
    var supportsVision by remember { mutableStateOf(initial?.supportsVision ?: false) }
    var isDefault by remember { mutableStateOf(initial?.isDefault ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加提供商" else "编辑提供商") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（如 DeepSeek）") }, singleLine = true)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, supportingText = { Text("如 https://api.deepseek.com，无需带 /v1") }, singleLine = true)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") }, singleLine = true)
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名（如 deepseek-chat）") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("支持图片输入（识屏用）", modifier = Modifier.weight(1f))
                    Switch(checked = supportsVision, onCheckedChange = { supportsVision = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设为默认档案", modifier = Modifier.weight(1f))
                    Switch(checked = isDefault, onCheckedChange = { isDefault = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ProviderProfile(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.ifBlank { "未命名" },
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            model = model,
                            supportsVision = supportsVision,
                            isDefault = isDefault
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 提示词编辑对话框（9 组通用）：加载当前值、保存、恢复默认 */
@Composable
private fun PromptEditDialog(
    key: PromptStore.PromptKey,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val store = app.container.promptStore
    val scope = rememberCoroutineScope()
    // 打开时读取当前已保存的提示词（用户没改过时才是默认值）
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        text = store.prompt(key)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑「${key.displayName}」") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8
                )
                Text(
                    key.description + "。恢复默认会丢弃当前修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { store.setPrompt(key, text) }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                // 恢复默认：删掉已存值 → 读回代码默认
                TextButton(onClick = {
                    scope.launch {
                        store.resetPrompt(key)
                        text = store.prompt(key)
                    }
                }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
