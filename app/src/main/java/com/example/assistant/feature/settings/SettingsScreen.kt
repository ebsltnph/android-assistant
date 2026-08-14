package com.example.assistant.feature.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderProfile
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.core.ui.GlassCard
import com.example.assistant.service.FloatingBallService
import kotlinx.coroutines.launch

/**
 * 设置页（2026-08-02 重构，glassmorphism 深墨夜景）：
 * 顶层列表只保留高频入口，详细配置各自点进子页面（内部导航，无 NavHost）。
 *
 * 子页面：模型配置 / 每日小结 / 清晨简报 / 免打扰 / 提示词高级设置。
 * 思考强度已改为 per-provider（在「模型配置」里每个提供商单独设置）。
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
    val floatingBallEnabled by vm.floatingBallEnabled.collectAsState()
    val searchApiKey by vm.searchApiKey.collectAsState()
    val summaryMinute by vm.summaryMinute.collectAsState()
    val briefingMinute by vm.briefingMinute.collectAsState()
    val quietStart by vm.quietStartMinute.collectAsState()
    val quietEnd by vm.quietEndMinute.collectAsState()
    val conversationMaxTurns by vm.conversationMaxTurns.collectAsState()
    val secretLogEnabled by vm.secretLogEnabled.collectAsState()

    // 子页面导航（null = 顶层列表；系统返回键回退）
    var subPage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }
    BackHandler(enabled = subPage != null) { subPage = null }

    // 悬浮窗权限状态（悬浮球需要）；从系统设置页返回时刷新
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

    // 顶层共享的弹窗状态
    var editingPromptKey by remember { mutableStateOf<PromptStore.PromptKey?>(null) }
    var showBallHelp by remember { mutableStateOf(false) }

    val openOverlaySettings: () -> Unit = {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (subPage) {
            null -> SettingsMainList(
                vm = vm,
                profiles = profiles,
                assignments = assignments,
                floatingBallEnabled = floatingBallEnabled,
                searchApiKey = searchApiKey,
                summaryMinute = summaryMinute,
                briefingMinute = briefingMinute,
                quietStart = quietStart,
                quietEnd = quietEnd,
                conversationMaxTurns = conversationMaxTurns,
                overlayGranted = overlayGranted,
                onOpenSubPage = { subPage = it },
                onOpenOverlaySettings = openOverlaySettings,
                onShowBallHelp = { showBallHelp = true },
                onEditPrompt = { editingPromptKey = it },
                onToggleFloatingBall = { on ->
                    vm.setFloatingBallEnabled(on)
                    if (on) FloatingBallService.start(context) else FloatingBallService.stop(context)
                }
            )
            SettingsSubPage.MODEL_CONFIG -> ModelConfigPage(
                vm = vm,
                profiles = profiles,
                assignments = assignments,
                testResult = testResult,
                onBack = { subPage = null },
                onOpenOverlaySettings = openOverlaySettings
            )
            SettingsSubPage.DAILY_SUMMARY -> DailySummaryPage(
                summaryMinute = summaryMinute,
                onMinuteChange = { minute ->
                    vm.setSummaryMinute(minute)
                    // 重排 WorkManager 周期任务（直接用刚选的分钟，避免读到旧值）
                    app.rescheduleDailySummary(minute)
                },
                onBack = { subPage = null }
            )
            SettingsSubPage.BRIEFING -> BriefingPage(
                briefingMinute = briefingMinute,
                onMinuteChange = { minute ->
                    vm.setBriefingMinute(minute)
                    app.rescheduleBriefing(minute)
                },
                onBack = { subPage = null }
            )
            SettingsSubPage.QUIET_HOURS -> QuietHoursPage(
                startMinute = quietStart,
                endMinute = quietEnd,
                onWindowChange = { s, e -> vm.setQuietWindow(s, e) },
                onBack = { subPage = null }
            )
            SettingsSubPage.PROMPTS_ADVANCED -> PromptsAdvancedPage(
                onBack = { subPage = null },
                onEditPrompt = { editingPromptKey = it }
            )
            SettingsSubPage.SECRET -> SecretFeaturePage(
                enabled = secretLogEnabled,
                onToggle = { vm.setSecretLogEnabled(it) },
                onBack = { subPage = null }
            )
            SettingsSubPage.BACKUP -> BackupPage(
                onBack = { subPage = null }
            )
        }
    }

    editingPromptKey?.let { key ->
        PromptEditDialog(key = key, onDismiss = { editingPromptKey = null })
    }
    if (showBallHelp) {
        AlertDialog(
            onDismissRequest = { showBallHelp = false },
            title = { Text("悬浮球说明") },
            text = {
                Text(
                    "需「显示在其他应用上层」权限；开启后通知栏常驻一条「悬浮球运行中」；" +
                        "识图每次都要点一次系统授权。为保证悬浮球不被系统杀掉，建议在系统设置里" +
                        "给随身助手开启「电池无限制」和「自启动」。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = { TextButton(onClick = { showBallHelp = false }) { Text("知道了") } }
        )
    }
}

/** 设置页子页面（内部导航，不引入 NavHost） */
private enum class SettingsSubPage {
    MODEL_CONFIG, DAILY_SUMMARY, BRIEFING, QUIET_HOURS, PROMPTS_ADVANCED, SECRET, BACKUP
}

// ======================= 顶层列表 =======================

@Composable
private fun SettingsMainList(
    vm: SettingsViewModel,
    profiles: List<ProviderProfile>,
    assignments: Map<Capability, String?>,
    floatingBallEnabled: Boolean,
    searchApiKey: String,
    summaryMinute: Int,
    briefingMinute: Int,
    quietStart: Int,
    quietEnd: Int,
    conversationMaxTurns: Int,
    overlayGranted: Boolean,
    onOpenSubPage: (SettingsSubPage) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onShowBallHelp: () -> Unit,
    onEditPrompt: (PromptStore.PromptKey) -> Unit,
    onToggleFloatingBall: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("设置", style = MaterialTheme.typography.headlineSmall) }

        // ---- 1. 模型配置入口 ----
        item {
            EntryCard(
                title = "模型配置",
                subtitle = "提供商、连接测试、思考强度、能力指派",
                onClick = { onOpenSubPage(SettingsSubPage.MODEL_CONFIG) }
            )
        }

        // ---- 2. 悬浮球 ----
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("悬浮球", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "在任意应用上层悬浮一个小球，点开即可识屏 / 提醒 / 记录 / 对话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onShowBallHelp) { Text("说明") }
                    Switch(
                        checked = floatingBallEnabled,
                        onCheckedChange = onToggleFloatingBall
                    )
                }
                if (floatingBallEnabled && !overlayGranted) {
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

        // ---- 3. 每日小结 ----
        item {
            EntryCard(
                title = "每日小结 · ${formatMinute(summaryMinute)}",
                subtitle = "每天定时汇总日记生成小结（可同步系统日历）",
                onClick = { onOpenSubPage(SettingsSubPage.DAILY_SUMMARY) }
            )
        }

        // ---- 4. 清晨简报 ----
        item {
            EntryCard(
                title = "清晨简报 · ${formatMinute(briefingMinute)}",
                subtitle = "每天推送今日提醒 + 昨日小结",
                onClick = { onOpenSubPage(SettingsSubPage.BRIEFING) }
            )
        }

        // ---- 5. 免打扰 ----
        item {
            val enabled = quietStart != quietEnd
            EntryCard(
                title = "免打扰" + if (enabled) " · ${formatMinute(quietStart)}-${formatMinute(quietEnd)}" else "",
                subtitle = if (enabled) "时段内提醒静默、事件监控不打扰" else "未开启（提醒将随时响铃）",
                onClick = { onOpenSubPage(SettingsSubPage.QUIET_HOURS) }
            )
        }

        // ---- 6. 数据备份与导入（v1.3：手动导出/恢复 + 定期自动备份） ----
        item {
            EntryCard(
                title = "数据备份与导入",
                subtitle = "导出全部数据到文件 / 从备份恢复（含定期自动备份）",
                onClick = { onOpenSubPage(SettingsSubPage.BACKUP) }
            )
        }

        // ---- 7. 聊天上下文长度 ----
        item {
            ConversationLengthCard(
                current = conversationMaxTurns,
                onSave = { vm.setConversationMaxTurns(it) }
            )
        }

        // ---- 8. 搜索（keyless 默认） ----
        item {
            SearchSettingsCard(apiKey = searchApiKey, onSaveKey = { vm.saveSearchApiKey(it) })
        }

        // ---- 9. 提示词：只保留两个常用项，其余进「高级设置」 ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "提示词",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onOpenSubPage(SettingsSubPage.PROMPTS_ADVANCED) }) {
                    Text("高级设置")
                }
            }
        }
        item {
            PromptCard(
                key = PromptStore.PromptKey.ASSISTANT_SYSTEM,
                onEdit = { onEditPrompt(PromptStore.PromptKey.ASSISTANT_SYSTEM) }
            )
        }
        item {
            PromptCard(
                key = PromptStore.PromptKey.SCREEN_SENSE,
                onEdit = { onEditPrompt(PromptStore.PromptKey.SCREEN_SENSE) }
            )
        }

        // ---- 10. 版本号（隐藏入口：连点 3 次进秘密功能——不显眼，防止误入） ----
        item {
            val context = LocalContext.current
            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                } catch (_: Exception) {
                    ""
                }
            }
            // 三连击计数：两次点击间隔超过 1.5 秒视为重新开始
            var tapCount by remember { mutableStateOf(0) }
            var lastTapAt by remember { mutableStateOf(0L) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    "v$versionName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val now = System.currentTimeMillis()
                            tapCount = if (now - lastTapAt > 1_500) 1 else tapCount + 1
                            lastTapAt = now
                            if (tapCount >= 3) {
                                tapCount = 0
                                onOpenSubPage(SettingsSubPage.SECRET)
                            }
                        }
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 列表入口卡片：标题 + 简述 + 右箭头（glassmorphism 玻璃卡） */
@Composable
private fun EntryCard(title: String, subtitle: String, onClick: () -> Unit) {
    GlassCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ======================= 模型配置子页面 =======================

@Composable
private fun ModelConfigPage(
    vm: SettingsViewModel,
    profiles: List<ProviderProfile>,
    assignments: Map<Capability, String?>,
    testResult: Map<String, SettingsViewModel.TestResult>,
    onBack: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    var editing by remember { mutableStateOf<ProviderProfile?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader("模型配置", onBack) }
        item {
            Text(
                "每个提供商可单独测试连接、设置思考强度；对话 / 识屏 / 分类可指派不同提供商。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

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
            ModelProviderCard(
                profile = profile,
                testResult = testResult[profile.id],
                onEdit = { editing = profile },
                onDelete = { vm.deleteProfile(profile.id) },
                onTest = { vm.testConnection(profile.id) },
                onThinkingChange = { e -> vm.setProfileThinking(profile.id, e) }
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

        // 视觉模型状态说明（原「识屏」分区信息并入此处）
        item { HorizontalDivider() }
        item {
            val visionProfile = remember(assignments, profiles) {
                val assignedId = assignments[Capability.VISION]
                profiles.firstOrNull { it.id == assignedId }
                    ?: profiles.firstOrNull { it.isDefault }
            }
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("识屏（视觉）模型", style = MaterialTheme.typography.titleSmall)
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
}

/** 提供商卡片：档案信息 + 连接测试 + 思考强度（per-provider） */
@Composable
private fun ModelProviderCard(
    profile: ProviderProfile,
    testResult: SettingsViewModel.TestResult?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onThinkingChange: (String) -> Unit
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

            // 连接测试（每个提供商独立）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                OutlinedButton(onClick = onTest, enabled = profile.isConfigured()) {
                    Text("测试连接")
                }
                when (val r = testResult) {
                    null -> {}
                    is SettingsViewModel.TestResult.Testing -> Text(
                        " 测试中…", style = MaterialTheme.typography.bodySmall
                    )
                    is SettingsViewModel.TestResult.Success -> Text(
                        " ✓ ${r.reply}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    is SettingsViewModel.TestResult.Failure -> Text(
                        " ✗ ${r.message}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 思考深度（OpenAI 通用参数 reasoning_effort，2026-08-07 起只留这一个下拉：
            // 思考开关跟随模型默认——DeepSeek 的 thinking 开关是它家专属参数，
            // 中转站/OpenAI 系模型不认识会直接 HTTP 400 拒绝）
            ThinkingSettingRow(
                label = "思考深度",
                options = effortOptions,
                current = effortOptions.firstOrNull { it.second == profile.reasoningEffort }?.first
                    ?: effortOptions.first().first,
                onSelect = { onThinkingChange(it.second) }
            )
        }
    }
}

private val effortOptions = listOf(
    "跟随模型默认" to "default",
    "简洁（思考短）" to "low",
    "均衡" to "medium",
    "深入（思考长）" to "high"
)

/** 一行"标签 + 下拉选择"设置项 */
@Composable
private fun ThinkingSettingRow(
    label: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (Pair<String, String>) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        var expanded by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { expanded = true }) { Text(current) }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(opt.first) },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}

// ======================= 时间设置子页面 =======================

/** 每日小结子页面：时间 + 系统日历同步 */
@Composable
private fun DailySummaryPage(
    summaryMinute: Int,
    onMinuteChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // 系统日历 Provider 要求 READ + WRITE 两个权限同时具备（只给 WRITE 会写入失败）
    val calendarGranted = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED &&
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader("每日小结", onBack) }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}

/** 清晨简报子页面：时间选择 */
@Composable
private fun BriefingPage(
    briefingMinute: Int,
    onMinuteChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader("清晨简报", onBack) }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}

/** 免打扰子页面：开关 + 起止时间（跨午夜支持，如 23:00-07:00） */
@Composable
private fun QuietHoursPage(
    startMinute: Int,
    endMinute: Int,
    onWindowChange: (Int, Int) -> Unit,
    onBack: () -> Unit
) {
    // 起止相同 = 未启用
    val enabled = startMinute != endMinute
    var enabledState by remember { mutableStateOf(enabled) }
    var startState by remember { mutableStateOf(startMinute) }
    var endState by remember { mutableStateOf(endMinute) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader("免打扰", onBack) }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        "免打扰时段内：提醒静默（不响铃）、事件监控不打扰。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ======================= 提示词高级设置子页面 =======================

/** 提示词高级设置：除「助手系统提示词」「识屏提示词」外的其余各组 */
@Composable
private fun PromptsAdvancedPage(
    onBack: () -> Unit,
    onEditPrompt: (PromptStore.PromptKey) -> Unit
) {
    val advancedKeys = PromptStore.PromptKey.entries.filter {
        it != PromptStore.PromptKey.ASSISTANT_SYSTEM && it != PromptStore.PromptKey.SCREEN_SENSE
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader("提示词高级设置", onBack) }
        item {
            Text(
                "低频提示词（记忆抽取 / 小结 / 分类 / 时间解析 / 事件 / 简报 / 搜索判断）。可随时编辑或恢复默认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(advancedKeys) { key ->
            PromptCard(key = key, onEdit = { onEditPrompt(key) })
        }
    }
}

/** 一行提示词卡片（显示名 + 简述 + 编辑按钮） */
@Composable
private fun PromptCard(key: PromptStore.PromptKey, onEdit: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(key.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    key.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onEdit) { Text("编辑") }
        }
    }
}

// ======================= 秘密功能（对话历史记录） =======================

/**
 * 秘密功能子页：对话历史记录（数字分身素材）。
 * - 开关：记录所有用户发出的对话内容（不含模型回复），只存本机文件
 * - 统计：已记录条数 + 文件大小
 * - 导出：通过系统分享（文件由 FileProvider 授权给目标应用）
 * - 清空：删除记录文件
 */
@Composable
private fun SecretFeaturePage(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val scope = rememberCoroutineScope()

    // 统计信息（条数/大小）：进入页面时读取一次，导出/清空后刷新
    var stats by remember { mutableStateOf(0 to 0L) }
    var hint by remember { mutableStateOf<String?>(null) }
    // 清空确认（防误触：点「清空」先弹确认框）
    var confirmClear by remember { mutableStateOf(false) }
    fun refreshStats() {
        scope.launch {
            stats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.container.conversationLog.stats()
            }
        }
    }
    LaunchedEffect(Unit) { refreshStats() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader("秘密功能", onBack) }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("记录对话历史", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (enabled) "开启中 · 每次对话后追加保存" else "已关闭 · 默认关闭，开启后开始记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = onToggle)
                    }
                    Text(
                        "开启后，你发出的每一条消息（不含模型回复）都会追加保存到本机文件，用于后续提取你的特征、制作数字分身。文件只存在这台手机里，不会上传；关闭记录不会删除已有记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已记录", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${stats.first} 条 · ${formatBytes(stats.second)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val file = app.container.conversationLog.file()
                                if (!file.exists() || file.length() == 0L) {
                                    hint = "还没有记录内容，先聊几句吧"
                                    return@OutlinedButton
                                }
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", file
                                    )
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "导出对话历史"))
                                    hint = null
                                } catch (e: Exception) {
                                    hint = "导出失败：${e.message}"
                                }
                            }
                        ) { Text("导出") }
                        OutlinedButton(
                            onClick = { confirmClear = true }
                        ) { Text("清空") }
                    }
                    hint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            Text(
                "提示：导出后会进入系统分享菜单，可选择保存到文件/发送到其他地方。建议定期导出备份，防止手机丢失后素材丢失。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 清空确认（防误触）
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空对话记录？") },
            text = {
                Text(
                    "将删除全部已记录的对话历史（当前 ${stats.first} 条），此操作不可恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.container.conversationLog.clear()
                    refreshStats()
                    hint = "已清空记录"
                    confirmClear = false
                }) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

/** 字节数 → 可读文本（B/KB/MB） */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

// ======================= 聊天上下文长度 =======================

/**
 * 聊天上下文长度设置：对话能记住的最近轮数（5-50，默认 10）。
 * 输入即改本地态，点「保存」才持久化（与搜索 API Key 卡片同一交互）。
 */
@Composable
private fun ConversationLengthCard(current: Int, onSave: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 5..50
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("聊天上下文长度", style = MaterialTheme.typography.titleSmall)
            Text(
                "对话能记住的最近轮数（5-50，默认 10）。越大上下文越全、越费 token。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(2) },
                label = { Text("轮数（5-50）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = valid, onClick = { parsed?.let(onSave) }) { Text("保存") }
                Text(
                    if (valid) "输入 $parsed 轮"
                    else "当前 $current 轮 · 输入 5-50 之间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ======================= 搜索（keyless 默认折叠） =======================

/**
 * 搜索设置卡片（Tavily）：默认 keyless 免费模式（免注册、有限流）；
 * 点「填入 API Key」展开输入框；填 Key 后每月 1000 次免费（tavily.com 注册）。
 */
@Composable
private fun SearchSettingsCard(
    apiKey: String,
    onSaveKey: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(apiKey) }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("搜索（联网）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (apiKey.isBlank()) "对话搜索与新闻监控 · 当前为免费模式（keyless）"
                        else "对话搜索与新闻监控 · 已配置 API Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (apiKey.isBlank()) {
                    AssistChip(onClick = {}, label = { Text("免费模式") })
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "填入 API Key")
                }
            }
            if (expanded) {
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
                        if (key.isBlank()) "留空 = keyless 免费模式" else "已保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ======================= 通用组件 =======================

/** 子页面顶部：返回按钮 + 标题 */
@Composable
private fun SubPageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/** 分钟数 → "HH:mm"（用于列表里显示当前设定时间） */
private fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

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
    androidx.compose.material3.DropdownMenu(expanded = hourExpanded, onDismissRequest = { hourExpanded = false }) {
        (0..23).forEach { h ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("%02d:00".format(h)) },
                onClick = { onChange(h * 60 + minute); hourExpanded = false }
            )
        }
    }
    androidx.compose.material3.DropdownMenu(expanded = minuteExpanded, onDismissRequest = { minuteExpanded = false }) {
        (0..59).forEach { m ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(":%02d".format(m)) },
                onClick = { onChange(hour * 60 + m); minuteExpanded = false }
            )
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

    GlassCard {
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
            androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("默认（$defaultName）") },
                    onClick = { onAssign(null); expanded = false }
                )
                profiles.forEach { p ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { onAssign(p.id); expanded = false }
                    )
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

/** 提示词编辑对话框：加载当前值、保存、恢复默认 */
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
