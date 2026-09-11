package com.example.assistant.feature.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderProfile
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.core.ui.GlassCard
import com.example.assistant.service.FloatingBallService
import kotlinx.coroutines.flow.first
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
    val bubbleIconEmoji by vm.bubbleIconEmoji.collectAsState()
    // 悬浮球自定义图片：相册选图 → VM 裁剪存私有目录
    val bubbleImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.importBubbleImage(context, uri) { }
    }
    val screenSenseRegionEnabled by vm.screenSenseRegionEnabled.collectAsState()
    val searchApiKey by vm.searchApiKey.collectAsState()
    val summaryMinute by vm.summaryMinute.collectAsState()
    val dailySummaryEnabled by vm.dailySummaryEnabled.collectAsState()
    val briefingMinute by vm.briefingMinute.collectAsState()
    val briefingEnabled by vm.briefingEnabled.collectAsState()
    val quietStart by vm.quietStartMinute.collectAsState()
    val quietEnd by vm.quietEndMinute.collectAsState()
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
                dailySummaryEnabled = dailySummaryEnabled,
                briefingMinute = briefingMinute,
                briefingEnabled = briefingEnabled,
                quietStart = quietStart,
                quietEnd = quietEnd,
                overlayGranted = overlayGranted,
                onOpenSubPage = { subPage = it },
                onOpenOverlaySettings = openOverlaySettings,
                onShowBallHelp = { showBallHelp = true },
                onEditPrompt = { editingPromptKey = it },
                onToggleFloatingBall = { on ->
                    vm.setFloatingBallEnabled(on)
                    if (on) FloatingBallService.start(context) else FloatingBallService.stop(context)
                },
                bubbleIconEmoji = bubbleIconEmoji,
                onSetBubbleIcon = { vm.setBubbleIconEmoji(it) },
                onPickBubbleImage = {
                    bubbleImagePicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                screenSenseRegionEnabled = screenSenseRegionEnabled,
                onToggleScreenSenseRegion = { on ->
                    vm.setScreenSenseRegionEnabled(on)
                    // 同步更新 container 缓存：截屏服务直接读缓存，不等重启
                    app.container.screenSenseRegionEnabled = on
                }
            )
            SettingsSubPage.USER_GUIDE -> UsageGuidePage(
                onBack = { subPage = null }
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
                enabled = dailySummaryEnabled,
                onEnabledChange = { on ->
                    vm.setDailySummaryEnabled(on)
                    if (on) app.rescheduleDailySummary(summaryMinute) else app.stopDailySummary()
                },
                onMinuteChange = { minute ->
                    vm.setSummaryMinute(minute)
                    // 只有开启时才重排；关闭状态下只保存时间，等开启时再排
                    if (dailySummaryEnabled) app.rescheduleDailySummary(minute)
                },
                onBack = { subPage = null }
            )
            SettingsSubPage.BRIEFING -> BriefingPage(
                briefingMinute = briefingMinute,
                enabled = briefingEnabled,
                onEnabledChange = { on ->
                    vm.setBriefingEnabled(on)
                    if (on) app.rescheduleBriefing(briefingMinute) else app.stopBriefing()
                },
                onMinuteChange = { minute ->
                    vm.setBriefingMinute(minute)
                    // 只有开启时才重排；关闭状态下只保存时间，等开启时再排
                    if (briefingEnabled) app.rescheduleBriefing(minute)
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
            SettingsSubPage.BALL_VOICE -> BallVoicePage(
                vm = vm,
                onBack = { subPage = null }
            )
            SettingsSubPage.CHAT_CONTEXT -> ChatContextPage(
                vm = vm,
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
                        "识屏每次都要点一次系统授权。为保证悬浮球不被系统杀掉，建议在系统设置里" +
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
    USER_GUIDE, MODEL_CONFIG, DAILY_SUMMARY, BRIEFING, QUIET_HOURS, PROMPTS_ADVANCED, SECRET, BACKUP,
    /** 悬浮球语音输入（配置项多，折叠进子页，主页只显示摘要） */
    BALL_VOICE,
    /** 聊天上下文（上下限/字符上限/会话保留/历史图片，同样折叠进子页） */
    CHAT_CONTEXT
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
    dailySummaryEnabled: Boolean,
    briefingMinute: Int,
    briefingEnabled: Boolean,
    quietStart: Int,
    quietEnd: Int,
    overlayGranted: Boolean,
    onOpenSubPage: (SettingsSubPage) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onShowBallHelp: () -> Unit,
    onEditPrompt: (PromptStore.PromptKey) -> Unit,
    onToggleFloatingBall: (Boolean) -> Unit,
    bubbleIconEmoji: String,
    onSetBubbleIcon: (String) -> Unit,
    onPickBubbleImage: () -> Unit,
    screenSenseRegionEnabled: Boolean,
    onToggleScreenSenseRegion: (Boolean) -> Unit
) {
    // 悬浮球图标选择弹窗开关（对话框与入口行不在同一 item 作用域，故放函数级）
    var showIconPicker by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("设置", style = MaterialTheme.typography.headlineSmall) }

        // ---- 0. 使用说明（放在设置页最上方，便于新用户了解全部功能） ----
        item {
            EntryCard(
                title = "使用说明",
                subtitle = "功能总览：聊天、记录、提醒、识屏、悬浮球、备份等",
                onClick = { onOpenSubPage(SettingsSubPage.USER_GUIDE) }
            )
        }

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
                // 语音输入：配置项多，折叠进独立子页；主页只显示当前设定摘要
                BallVoiceSummaryRow(
                    vm = vm,
                    onOpen = { onOpenSubPage(SettingsSubPage.BALL_VOICE) }
                )
                // 悬浮球图标：点开选择 emoji，实时生效
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("悬浮球图标", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "更换悬浮球中间显示的图案"
                            ,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showIconPicker = true }) {
                        Text(if (bubbleIconEmoji.isBlank()) "默认" else bubbleIconEmoji)
                    }
                }
            }
        }

        // 悬浮球图标选择弹窗（emoji 网格；首项=默认玻璃球）
        if (showIconPicker) {
            item {
            AlertDialog(
                onDismissRequest = { showIconPicker = false },
                title = { Text("选择悬浮球图标") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                showIconPicker = false
                                onPickBubbleImage()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("🖼️ 从相册选择图片") }
                        val options: List<Pair<String, String>> =
                            listOf("" to "默认") + listOf("🫧", "✨", "🤖", "💫", "🌙", "⚡", "🍀", "🎯", "🧠", "🌟", "🐱", "☕", "🎈", "🌈", "🔥").map { it to it }
                        options.chunked(4).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowItems.forEach { (value, label) ->
                                    val selected = bubbleIconEmoji == value
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            )
                                            .border(
                                                1.dp,
                                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(14.dp)
                                            )
                                            .clickable {
                                                onSetBubbleIcon(value)
                                                showIconPicker = false
                                            }
                                    ) {
                                        Text(if (label == "默认") "默认" else value, fontSize = 15.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showIconPicker = false }) { Text("关闭") }
                }
            )
            }
        }

        // ---- 2.5 识屏框选（v1.4.1）----
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("识屏后框选区域", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (screenSenseRegionEnabled) "截屏后先拖动框选，只识别框内部分"
                            else "关闭中：直接识别整个屏幕（截图即识别范围）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = screenSenseRegionEnabled,
                        onCheckedChange = onToggleScreenSenseRegion
                    )
                }
            }
        }

        // ---- 3. 每日小结 ----
        item {
            EntryCard(
                title = "每日小结 · ${formatMinute(summaryMinute)}（${if (dailySummaryEnabled) "已开启" else "已关闭"}）",
                subtitle = if (dailySummaryEnabled) "每天定时汇总日记生成小结（可同步系统日历）"
                else "自动生成已关闭，仍可在日记页手动生成",
                onClick = { onOpenSubPage(SettingsSubPage.DAILY_SUMMARY) }
            )
        }

        // ---- 4. 清晨简报 ----
        item {
            EntryCard(
                title = "清晨简报 · ${formatMinute(briefingMinute)}（${if (briefingEnabled) "已开启" else "已关闭"}）",
                subtitle = if (briefingEnabled) "每天推送今日提醒 + 昨日小结"
                else "自动推送已关闭，历史简报仍可在首页查看",
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

        // ---- 7. 聊天上下文（上下限/字符上限/会话保留/历史图片 → 独立子页） ----
        item {
            ChatContextEntryCard(
                vm = vm,
                onOpen = { onOpenSubPage(SettingsSubPage.CHAT_CONTEXT) }
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
        // （原「识屏提示词」入口已删除：识图并入聊天通道后由三个快捷动作提示词承担，
        //   见「高级设置 → 提示词」里的「识屏·提取文字 / 翻译 / 总结内容」）

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
                "每个提供商可单独测试连接、设置思考强度；对话 / 识屏 / 语音识别可指派不同提供商。" +
                    "（建议把「对话」与「识屏」指派成同一个带图模型：两边的提示词缓存才能共用。）",
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
                            "⚠️ 未配置识屏模型。带图片的消息会用它——请在「能力指派」里指派一个支持图片" +
                                "输入的模型（如通义 qwen-vl、智谱 GLM-4V、Kimi vision、OpenAI gpt-4o）；" +
                                "DeepSeek 官方 API 不支持图片。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        !visionProfile.supportsVision -> Text(
                            "⚠️ 识屏模型：${visionProfile.name}（未勾选「支持图片输入」，编辑该提供商开启；" +
                                "未开启时历史图片不会发给它，当前轮的图片仍会尝试发送）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        else -> {
                            val chatProfile = remember(assignments, profiles) {
                                val assignedId = assignments[Capability.CHAT]
                                profiles.firstOrNull { it.id == assignedId }
                                    ?: profiles.firstOrNull { it.isDefault }
                            }
                            Text(
                                "✓ 识屏模型：${visionProfile.name}（${visionProfile.model}）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                if (chatProfile?.id == visionProfile.id)
                                    "与「对话」是同一个档案 → 文字轮与带图轮共用同一条缓存前缀，命中率最优。"
                                else
                                    "与「对话」是不同档案（对话：${chatProfile?.name ?: "未配置"}）→ " +
                                        "文字轮与带图轮的提示词缓存相互独立；想让缓存共用就把两者指派成同一个模型。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 语音识别模型状态说明（悬浮球语音输入 remote 模式用）
        item { HorizontalDivider() }
        item {
            val asrProfile = remember(assignments, profiles) {
                val assignedId = assignments[Capability.ASR]
                profiles.firstOrNull { it.id == assignedId }
                    ?: profiles.firstOrNull { it.isDefault }
            }
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("语音识别模型", style = MaterialTheme.typography.titleSmall)
                    when {
                        asrProfile == null || !asrProfile.isConfigured() -> Text(
                            "⚠️ 未配置语音识别模型。请在「能力指派」中把「语音识别」指派给支持音频转文字的模型" +
                                "（如硅基流动的 Qwen3-ASR / SenseVoiceSmall / 星辰 ASR）；悬浮球语音输入的远程识别模式使用它。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        !asrProfile.supportsAudio -> Text(
                            "⚠️ 语音识别模型：${asrProfile.name}（未勾选「支持语音识别」，编辑该提供商开启）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        else -> Text(
                            "✓ 语音识别模型：${asrProfile.name}（${asrProfile.model}）",
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
                if (profile.supportsAudio) AssistChip(onClick = {}, label = { Text("语音") })
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
            // 实际生效状态：参数是否真的随请求发出 / 是否被自动降级 / 全局旧设置兜底
            val appCtx = LocalContext.current.applicationContext as AssistantApplication
            var effortHint by remember(profile.reasoningEffort, profile.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(profile.reasoningEffort, profile.id, testResult) {
                val reg = appCtx.container.providerRegistry
                val global = appCtx.container.settingsStore.reasoningEffort.first()
                val st = reg.effortStatusFor(profile)
                effortHint = when {
                    st == "stripped" ->
                        "⚠️ 该模型不认识思考参数，请求已自动去掉该参数（重启 App 后会重新探测）"
                    profile.reasoningEffort != "default" ->
                        "每次对话发送 reasoning_effort=${profile.reasoningEffort}" +
                            if (st?.startsWith("sent") == true) " ✓ 已发出" else ""
                    global != "default" ->
                        "档案为「跟随模型默认」，但旧版全局设置为 $global，实际按 $global 发送"
                    else -> "不发送该参数，由模型自行决定思考深度"
                }
            }
            effortHint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

private val effortOptions = listOf(
    "跟随模型默认" to "default",
    "极简（几乎不思考）" to "minimal",
    "低（快）" to "low",
    "中（均衡）" to "medium",
    "高（深入）" to "high",
    "极高（最强推理）" to "xhigh"
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
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动生成每日小结", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (enabled) "开启中 · 每天定时汇总日记并推送" else "已关闭 · 可随时在日记页手动生成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = onEnabledChange)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自动总结时间", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        MinutePicker(current = summaryMinute, onChange = onMinuteChange)
                    }
                    Text(
                        "每天此时自动汇总当天日记，生成小结并推送通知（当天无日记则不打扰；关闭后仅取消自动任务，不影响已有小结）。",
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
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动推送清晨简报", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (enabled) "开启中 · 每天早上推送今日提醒 + 昨日小结" else "已关闭 · 历史简报仍可在首页查看",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = onEnabledChange)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("简报时间", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        MinutePicker(current = briefingMinute, onChange = onMinuteChange)
                    }
                    Text(
                        "每天此时推送清晨简报：今日提醒 + 昨日小结。关闭后仅取消自动推送，不影响首页已生成的内容。",
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

/** 提示词高级设置：除「助手系统提示词」外的其余各组（含识屏三个快捷动作提示词） */
@Composable
private fun PromptsAdvancedPage(
    onBack: () -> Unit,
    onEditPrompt: (PromptStore.PromptKey) -> Unit
) {
    val advancedKeys = PromptStore.PromptKey.entries.filter {
        it != PromptStore.PromptKey.ASSISTANT_SYSTEM
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader("提示词高级设置", onBack) }
        item {
            Text(
                "低频提示词（记忆抽取 / 小结 / 期间总结 / 事件命中 / 简报）。可随时编辑或恢复默认。",
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

// ======================= 聊天上下文（入口卡 + 子页面） =======================

/** 历史图片保留张数的展示文案 */
private fun imageKeepLabel(keep: Int): String = when (keep) {
    -1 -> "全部"
    0 -> "仅当前轮"
    else -> "最近 $keep 张"
}

/** 主页入口卡：只显示当前设定，详细配置进子页 */
@Composable
private fun ChatContextEntryCard(vm: SettingsViewModel, onOpen: () -> Unit) {
    val minTurns by vm.conversationMinTurns.collectAsState()
    val maxTurns by vm.conversationMaxTurns.collectAsState()
    val charLimit by vm.conversationCharLimit.collectAsState()
    val retentionDays by vm.chatSessionRetentionDays.collectAsState()
    val imageKeep by vm.chatImageKeep.collectAsState()

    EntryCard(
        title = "聊天上下文",
        subtitle = "下限 $minTurns / 上限 $maxTurns 轮 · 字符上限 $charLimit · " +
            "会话保留 $retentionDays 天 · 历史图片 ${imageKeepLabel(imageKeep)}",
        onClick = onOpen
    )
}

/**
 * 聊天上下文子页面（2026-09-11 从主页折叠进来）：上下限双阈值 + 字符软上限 + 会话保留 + 历史图片。
 * 双阈值的意义：轮数到上限后回落到下限再重新累积，发送序列 L→L+1→…→U→L，
 * 区间内每轮请求都是上一轮的延长，提示词缓存才能命中（旧的固定窗口滚动等于每轮都失效）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatContextPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val minTurns by vm.conversationMinTurns.collectAsState()
    val maxTurns by vm.conversationMaxTurns.collectAsState()
    val charLimit by vm.conversationCharLimit.collectAsState()
    val retentionDays by vm.chatSessionRetentionDays.collectAsState()
    val imageKeep by vm.chatImageKeep.collectAsState()

    var minText by remember(minTurns) { mutableStateOf(minTurns.toString()) }
    var maxText by remember(maxTurns) { mutableStateOf(maxTurns.toString()) }
    var charText by remember(charLimit) { mutableStateOf(charLimit.toString()) }
    var retentionText by remember(retentionDays) { mutableStateOf(retentionDays.toString()) }

    val minV = minText.toIntOrNull()
    val maxV = maxText.toIntOrNull()
    val charV = charText.toIntOrNull()
    val retentionV = retentionText.toIntOrNull()
    val valid = minV != null && maxV != null && charV != null && retentionV != null &&
        minV in 1..100 && maxV in 1..100 && minV <= maxV && charV in 0..400_000 &&
        retentionV in 0..90

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader("聊天上下文", onBack) }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("上下文轮数（下限 / 上限）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "轮数到上限后，下一条消息只带最近「下限」轮发给模型再重新累积。" +
                            "这样每轮请求的前缀都是上一次的延长，提示词缓存才能命中。" +
                            "当前：下限 $minTurns · 上限 $maxTurns 轮。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = minText,
                            onValueChange = { minText = it.filter(Char::isDigit).take(3) },
                            label = { Text("下限（1-100）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = maxText,
                            onValueChange = { maxText = it.filter(Char::isDigit).take(3) },
                            label = { Text("上限（1-100）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("字符软上限", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "轮数不等于 token：一次网页阅读就顶十几轮闲聊。这里按「字」粗略折算" +
                            "（图片按 1500 字当量），超出时从最旧的轮开始丢（优先于下限，至少保留最近 1 轮）。" +
                            "0 = 关闭该保护。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = charText,
                        onValueChange = { charText = it.filter(Char::isDigit).take(6) },
                        label = { Text("字符上限（0 = 关闭）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("会话记录保留", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "对话内容只存在本机一个文件里（不进备份、不上传）。" +
                            "超过保留天数会在下次启动时清空；填 0 = 不留存，保存后**立即清空**当前会话记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = retentionText,
                        onValueChange = { retentionText = it.filter(Char::isDigit).take(2) },
                        label = { Text("保留天数（0 = 不留存，默认 7）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("历史图片保留张数", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "带图对话的图片每轮都要重发，保留越少越省 token 与流量。当前：${imageKeepLabel(imageKeep)}。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        listOf(
                            0 to "仅当前轮",
                            1 to "最近 1 张",
                            3 to "最近 3 张",
                            -1 to "全部"
                        ).forEach { (v, label) ->
                            FilterChip(
                                selected = imageKeep == v,
                                onClick = { vm.setChatImageKeep(v) },
                                label = { Text(label) }
                            )
                        }
                    }
                    Text(
                        "只在新图片加入时把更早的图从上下文里换成文字（一次性的缓存失效）；" +
                            "当前轮的图片永远会发给模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = valid,
                    onClick = {
                        minV?.let { vm.setConversationMinTurns(it) }
                        maxV?.let { vm.setConversationMaxTurns(it) }
                        charV?.let { vm.setConversationCharLimit(it) }
                        retentionV?.let { vm.setChatSessionRetentionDays(it) }
                    }
                ) { Text("保存") }
                Text(
                    if (valid) "下限 $minV · 上限 $maxV · 字符 $charV · 保留 $retentionV 天"
                    else "下限须 ≤ 上限，范围 1-100；字符 0-400000；保留 0-90 天",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        item {
            Text(
                "提示：聊天页标题右侧的状态行会实时显示「上下文 N/M 轮 · 约 N 字 · 缓存命中 %」，" +
                    "想确认缓存策略是否生效就看那里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    var supportsAudio by remember { mutableStateOf(initial?.supportsAudio ?: false) }
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
                    Text("支持图片输入（带图对话用）", modifier = Modifier.weight(1f))
                    Switch(checked = supportsVision, onCheckedChange = {
                        supportsVision = it
                        if (it) supportsAudio = false   // 语音识别档案与对话/视觉链路互斥
                    })
                }
                Text(
                    "勾选后：带图片的那一轮会把图发给它，历史里的图片也会一并带上。" +
                        "没勾选时带图轮仍会尝试发送（当前轮的图），但历史图片会被自动替换成文字，" +
                        "避免纯文本模型直接报错。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("支持语音识别（悬浮球语音用）", modifier = Modifier.weight(1f))
                    Switch(checked = supportsAudio, onCheckedChange = {
                        supportsAudio = it
                        if (it) { supportsVision = false; isDefault = false }
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设为默认档案", modifier = Modifier.weight(1f))
                    Switch(checked = isDefault, onCheckedChange = {
                        isDefault = it
                        if (it) supportsAudio = false
                    })
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
                            supportsAudio = supportsAudio,
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
    // 用进程级 appScope 写入：不能用 rememberCoroutineScope——对话框关闭会 cancel，
    // 这是“提示词保存有时不成功”的根因（DataStore 写入还没落盘就被取消）
    val appScope = app.container.appScope
    var text by remember(key) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        text = store.prompt(key)
        errorText = null
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("编辑「${key.displayName}」") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    enabled = !saving
                )
                Text(
                    key.description + "。恢复默认会丢弃当前修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                errorText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    errorText = null
                    appScope.launch {
                        try {
                            store.setPrompt(key, text)
                            onDismiss()
                        } catch (e: Exception) {
                            saving = false
                            errorText = "保存失败：${e.message ?: "未知错误"}"
                        }
                    }
                }
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            Row {
                // 恢复默认：删掉已存值 → 读回代码默认（同样走 appScope，避免被取消）
                TextButton(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        errorText = null
                        appScope.launch {
                            try {
                                store.resetPrompt(key)
                                text = store.prompt(key)
                                saving = false
                            } catch (e: Exception) {
                                saving = false
                                errorText = "恢复默认失败：${e.message ?: "未知错误"}"
                            }
                        }
                    }
                ) { Text("恢复默认") }
                TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

// ======================= 悬浮球语音输入（摘要行 + 子页面） =======================

/** 语音方式/停顿的展示文案（摘要行与子页面共用） */
private fun voiceModeLabel(mode: String): String = when (mode) {
    "system" -> "系统听写"
    "remote" -> "远程识别"
    else -> "键盘语音"
}

private fun silenceLabel(ms: Int): String =
    if (ms % 1000 == 0) "${ms / 1000}s" else "${ms / 1000.0}s"

/** 主页摘要行：只显示当前设定，整行可点进子页（右侧与其它入口卡一致的箭头） */
@Composable
private fun BallVoiceSummaryRow(vm: SettingsViewModel, onOpen: () -> Unit) {
    val autoVoice by vm.panelAutoVoiceEnabled.collectAsState()
    val mode by vm.panelVoiceMode.collectAsState()
    val silence by vm.voiceSilenceMs.collectAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("语音输入", style = MaterialTheme.typography.titleSmall)
            Text(
                if (!autoVoice) "已关闭自动语音 · 点球只打开面板"
                else "点悬浮球自动开始 · " + voiceModeLabel(mode) +
                    (if (mode == "remote") " · 停顿 " + silenceLabel(silence) else ""),
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

/**
 * 悬浮球语音输入子页面：总开关 + 方式三选一 + 说完停顿。
 * 远程识别的模型在「模型配置 → 能力指派 → 语音识别」指派。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BallVoicePage(vm: SettingsViewModel, onBack: () -> Unit) {
    val autoVoice by vm.panelAutoVoiceEnabled.collectAsState()
    val mode by vm.panelVoiceMode.collectAsState()
    val silence by vm.voiceSilenceMs.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader("悬浮球语音输入", onBack) }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("点悬浮球自动开始语音", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (autoVoice) "点悬浮球后直接进入下面的语音方式"
                                else "关闭：点悬浮球只打开面板，需要时手动点麦克风",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoVoice,
                            onCheckedChange = { vm.setPanelAutoVoiceEnabled(it) }
                        )
                    }
                }
            }
        }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("语音输入方式", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        FilterChip(
                            selected = mode == "ime",
                            enabled = autoVoice,
                            onClick = { vm.setPanelVoiceMode("ime") },
                            label = { Text("键盘语音") }
                        )
                        FilterChip(
                            selected = mode == "system",
                            enabled = autoVoice,
                            onClick = { vm.setPanelVoiceMode("system") },
                            label = { Text("系统听写") }
                        )
                        FilterChip(
                            selected = mode == "remote",
                            enabled = autoVoice,
                            onClick = { vm.setPanelVoiceMode("remote") },
                            label = { Text("远程识别") }
                        )
                    }
                    Text(
                        when {
                            !autoVoice -> "已关闭自动语音：方式选择暂不生效；面板里的麦克风按钮仍按所选方式工作。"
                            mode == "system" -> "点悬浮球后直接开始系统听写（部分机型不支持，不支持时自动改弹键盘）"
                            mode == "remote" -> "点悬浮球后录音并上传到「语音识别」指派的模型，识别文字自动发送"
                            else -> "默认：打开面板后弹出键盘，点键盘上的麦克风即可语音输入"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("说完停顿（远程识别）", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        listOf(1500, 2000, 2500, 3000, 4000, 5000).forEach { ms ->
                            FilterChip(
                                selected = silence == ms,
                                onClick = { vm.setVoiceSilenceMs(ms) },
                                label = { Text(silenceLabel(ms)) }
                            )
                        }
                    }
                    Text(
                        "说完静音这么久就判定结束并开始识别（默认 2.5s）。觉得话没说完就被截断就调长，" +
                            "觉得停顿太久就调短。仅「远程识别」方式生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}