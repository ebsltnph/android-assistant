package com.example.assistant.feature.diary

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication
import com.example.assistant.core.AppSharedState
import com.example.assistant.core.vision.ImageUtils
import com.example.assistant.data.db.entity.DiaryEntryWithImages
import com.example.assistant.data.db.entity.DiaryImageEntity
import com.example.assistant.data.db.entity.PeriodSummaryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 日记页：
 * - 「日记」子页：条目列表 + 文字/输入法语音记录 + 补图（相册多选，一条目多张，可删可下载）
 * - 「记忆」子页：长期记忆列表（由 LLM 自动抽取），可删除/清空
 * - 输入法语音：点键盘麦克风即可语音输入（免麦克风权限）
 */
@Composable
fun DiaryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val vm: DiaryViewModel = viewModel {
        DiaryViewModel(
            appContext = app.container.appContext,
            diaryRepository = app.container.diaryRepository,
            memoryRepository = app.container.memoryRepository,
            memoryExtractor = app.container.memoryExtractor,
            summaryGenerator = app.container.dailySummaryGenerator,
            periodSummaryGenerator = app.container.periodSummaryGenerator,
            summaryRepository = app.container.summaryRepository
        )
    }

    val entries by vm.entries.collectAsState()
    val memories by vm.memories.collectAsState()
    val input by vm.input.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val message by vm.message.collectAsState()
    val periodSummary by vm.periodSummary.collectAsState()
    val periodSummaryLoading by vm.periodSummaryLoading.collectAsState()
    val periodSummaries by vm.periodSummaries.collectAsState()
    val clipboard = LocalClipboardManager.current

    // 通知点击「每日小结」→ 弹出完整小结对话框
    val showSummary by AppSharedState.showSummaryRequested.collectAsState()
    var summaryDialogText by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(showSummary) {
        if (showSummary) {
            summaryDialogText = app.container.summaryStore.currentSummary()
            AppSharedState.showSummaryRequested.value = false
        }
    }

    LaunchedEffect(Unit) { vm.initSelectedBook() }

    var subTab by rememberSaveable { mutableStateOf(0) } // 0=日记 1=记忆
    // 期间总结：是否显示「历史 + 生成」入口对话框
    var showPeriodHistoryDialog by rememberSaveable { mutableStateOf(false) }
    // 期间总结：是否显示起止日期选择对话框
    var showPeriodRangeDialog by rememberSaveable { mutableStateOf(false) }
    // 正在查看大图的条目图片路径（点击缩略图打开）
    var viewingImage by rememberSaveable { mutableStateOf<String?>(null) }
    // 下载图片到相册：API 29+ 无需权限；API 28- 需要 WRITE_EXTERNAL_STORAGE（授权后再存）
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val path = viewingImage
        if (granted && path != null) vm.downloadImage(DiaryImageEntity(entryId = 0, path = path)) { }
    }
    val downloadImage: (String) -> Unit = { path ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vm.downloadImage(DiaryImageEntity(entryId = 0, path = path)) { }
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) vm.downloadImage(DiaryImageEntity(entryId = 0, path = path)) { }
            else storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "日记",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { showPeriodHistoryDialog = true },
                enabled = !periodSummaryLoading
            ) {
                Text("期间总结")
            }
            TextButton(onClick = { vm.generateTodaySummary() }) {
                Text("今日小结")
            }
        }

        // 期间总结生成中：持续显示提示（点号循环变化，用协程驱动、不依赖动画系统——
        // 用户可能关闭了「动画时长缩放」，CircularProgressIndicator 等无限动画会停住）
        if (periodSummaryLoading) {
            var dots by remember { mutableStateOf(1) }
            LaunchedEffect(periodSummaryLoading) {
                while (periodSummaryLoading) {
                    kotlinx.coroutines.delay(400)
                    dots = if (dots >= 3) 1 else dots + 1
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⏳ 正在生成期间总结${".".repeat(dots)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("日记") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("记忆") })
        }

        if (subTab == 0) {
            // ---- 搜索框（关键词非空时列表切到搜索结果显示；带清空按钮）----
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { vm.setSearchQuery(it) },
                placeholder = { Text("搜索日记内容…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.setSearchQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )

            // ---- 日记条目列表 ----
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    entries.isEmpty() && searchQuery.isNotBlank() -> item {
                        Text(
                            "没有找到包含「${searchQuery.trim()}」的日记",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
                        )
                    }
                    entries.isEmpty() -> item {
                        Text(
                            "还没有日记，在下面写点什么吧\n（点键盘上的麦克风图标可直接语音输入）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
                        )
                    }
                    else -> items(entries, key = { it.entry.id }) { item ->
                        DiaryEntryCard(
                            entryWithImages = item,
                            onDelete = { vm.deleteEntry(item.entry.id) },
                            onPickImages = { uris -> vm.addImagesToEntry(item.entry.id, uris) },
                            onDeleteImage = { image -> vm.deleteImage(image) },
                            onViewImage = { path -> viewingImage = path }
                        )
                    }
                }
            }

            // ---- 输入区 ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { vm.setInput(it) },
                    placeholder = { Text("记录此刻…（点键盘麦克风可语音）") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4
                )
                IconButton(onClick = { vm.addEntry() }) {
                    Icon(Icons.Filled.Send, contentDescription = "保存")
                }
            }
            Text(
                "💡 点键盘上的麦克风图标可直接语音输入",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
        } else {
            // ---- 记忆子页 ----
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "长期记忆（${memories.size} 条）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (memories.isNotEmpty()) {
                        IconButton(onClick = { vm.clearMemories() }) {
                            Icon(Icons.Filled.Delete, contentDescription = "清空记忆")
                        }
                    }
                }
                if (memories.isEmpty()) {
                    Text(
                        "还没有长期记忆。\n写日记或聊天时，我会自动抽取值得记住的事实存到这里，并在以后对话时记起。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(memories, key = { it.id }) { memory ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    memory.fact,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { vm.deleteMemory(memory.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (message != null) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    message ?: "",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(2000)
                vm.clearMessage()
            }
        }

        // 完整每日小结对话框（通知点击 / 手动入口共用）
        summaryDialogText?.let { text ->
            AlertDialog(
                onDismissRequest = { summaryDialogText = null },
                title = { Text("📋 今日小结") },
                text = {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    TextButton(onClick = { summaryDialogText = null }) { Text("关闭") }
                }
            )
        }

        // 日记图片大图对话框（点击条目缩略图打开；可下载到系统相册）
        viewingImage?.let { path ->
            DiaryImageViewDialog(
                path = path,
                onDownload = { downloadImage(path) },
                onDismiss = { viewingImage = null }
            )
        }

        // 期间总结：历史 + 生成入口对话框
        if (showPeriodHistoryDialog) {
            PeriodHistoryDialog(
                histories = periodSummaries,
                onNew = {
                    showPeriodHistoryDialog = false
                    showPeriodRangeDialog = true
                },
                onSelect = { entity ->
                    showPeriodHistoryDialog = false
                    vm.openPeriodSummary(entity.summary)
                },
                onDismiss = { showPeriodHistoryDialog = false }
            )
        }

        // 期间总结：起止日期选择对话框
        if (showPeriodRangeDialog) {
            PeriodRangeDialog(
                onDismiss = { showPeriodRangeDialog = false },
                onConfirm = { from, to ->
                    showPeriodRangeDialog = false
                    vm.generatePeriodSummary(from, to)
                }
            )
        }

        // 期间总结结果弹窗（生成完成 / 历史查看后展示；可复制、导出）
        periodSummary?.let { text ->
            AlertDialog(
                onDismissRequest = { vm.dismissPeriodSummary() },
                title = { Text("📋 期间总结") },
                text = {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }) { Text("复制") }
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(send, "导出期间总结"))
                        }) { Text("导出") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissPeriodSummary() }) { Text("关闭") }
                }
            )
        }
    }
}

/**
 * 日记图片大图对话框：IO 线程解码（宽 ≤ 1024）显示 + 「下载到相册」+「关闭」。
 * 下载回调由外部处理（含 API 28- 的存储权限申请）。
 */
@Composable
private fun DiaryImageViewDialog(
    path: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("📷 日记图片", style = MaterialTheme.typography.titleMedium)
            val big by produceState<Bitmap?>(null, path) {
                value = withContext(Dispatchers.IO) { ImageUtils.decodeFit(path, 1024) }
            }
            big?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "日记图片",
                    modifier = Modifier.fillMaxWidth()
                )
            } ?: Text(
                "图片加载失败",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("下载到相册")
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun DiaryEntryCard(
    entryWithImages: DiaryEntryWithImages,
    onDelete: () -> Unit,
    onPickImages: (List<Uri>) -> Unit,
    onDeleteImage: (DiaryImageEntity) -> Unit,
    onViewImage: (String) -> Unit
) {
    val entry = entryWithImages.entry
    // 相册多选（Photo Picker，免存储权限；Android 13+ 原生多选，旧版本自动回退系统选择器）——
    // 一次最多选 9 张，选完全部追加到条目（保留已有图片）
    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9)
    ) { uris ->
        if (uris.isNotEmpty()) onPickImages(uris)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.content,
                    style = MaterialTheme.typography.bodyMedium
                )
                // 图片列表（一条目多张）：横向滚动缩略图，每张可点看大图、点右上角 ✕ 删除
                if (entryWithImages.images.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        entryWithImages.images.forEach { image ->
                            DiaryImageThumb(
                                image = image,
                                onView = { onViewImage(image.path) },
                                onDelete = { onDeleteImage(image) }
                            )
                        }
                    }
                }
                Text(
                    formatTime(entry.createdAtEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            // 操作按钮：加图 + 删除 横向并排（用户要求加号图标）
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加图片", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** 单张缩略图：96dp 圆角小图，点击看大图，右上角 ✕ 删除本张 */
@Composable
private fun DiaryImageThumb(
    image: DiaryImageEntity,
    onView: () -> Unit,
    onDelete: () -> Unit
) {
    // 缩略图：IO 线程解码（宽 ≤ 256）
    val thumbnail by produceState<Bitmap?>(null, image.path) {
        value = withContext(Dispatchers.IO) { ImageUtils.decodeThumbnail(image.path) }
    }
    Box {
        thumbnail?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "日记图片",
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onView)
            )
        } ?: Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface))
        // 删除按钮：右上角白色叉号（无底色，不遮缩略图）
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "删除这张图片",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** 期间总结的起止日期选择对话框：选开始/结束日期后回调本地毫秒区间 [from, to) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodRangeDialog(
    onDismiss: () -> Unit,
    onConfirm: (fromMillis: Long, toMillis: Long) -> Unit
) {
    val today = Calendar.getInstance()
    val defaultStart = today.clone() as Calendar
    defaultStart.add(Calendar.DAY_OF_MONTH, -6) // 默认最近 7 天

    val startState = rememberDatePickerState(initialSelectedDateMillis = utcMidnightMillis(defaultStart))
    val endState = rememberDatePickerState(initialSelectedDateMillis = utcMidnightMillis(today))

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    val startText = remember(startState.selectedDateMillis) {
        startState.selectedDateMillis?.let { formatDateUtc(it) } ?: ""
    }
    val endText = remember(endState.selectedDateMillis) {
        endState.selectedDateMillis?.let { formatDateUtc(it) } ?: ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("期间总结") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "选择要总结的日记时间段",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开始日期", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { showStartPicker = true }) { Text(startText) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("结束日期", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { showEndPicker = true }) { Text(endText) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = startState.selectedDateMillis != null && endState.selectedDateMillis != null,
                onClick = {
                    val startUtc = startState.selectedDateMillis ?: return@TextButton
                    val endUtc = endState.selectedDateMillis ?: return@TextButton
                    // selectedDateMillis 是 UTC 午夜毫秒：从 UTC 日历取「那天」的年月日，
                    // 再装进本地日历算出本地 0 点时间戳（entriesBetween 用本地时间戳）
                    var from = localDayStartMillis(startUtc)
                    var to = localDayStartMillis(endUtc) + DAY_MS
                    if (from >= to) {
                        // 起止颠倒（开始晚于结束）时互换，保证区间有效
                        val earlierStart = to - DAY_MS
                        val laterEnd = from + DAY_MS
                        from = earlierStart
                        to = laterEnd
                    }
                    onConfirm(from, to)
                }
            ) { Text("生成总结") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showStartPicker) {
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = { TextButton(onClick = { showStartPicker = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showStartPicker = false }) { Text("取消") } }
        ) {
            DatePicker(state = startState)
        }
    }
    if (showEndPicker) {
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = { TextButton(onClick = { showEndPicker = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showEndPicker = false }) { Text("取消") } }
        ) {
            DatePicker(state = endState)
        }
    }
}

/** DatePicker 返回 UTC 午夜毫秒 → 本地当天 0 点毫秒（entriesBetween 用本地时间戳） */
private fun localDayStartMillis(utcMidnightMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnightMillis }
    return Calendar.getInstance().apply {
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** 本地某天 0 点（UTC 毫秒表示），供 DatePicker 的 initialSelectedDateMillis 使用 */
private fun utcMidnightMillis(cal: Calendar): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return utc.timeInMillis
}

private fun formatDateUtc(utcMillis: Long): String {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return "%d年%d月%d日".format(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH) + 1, utc.get(Calendar.DAY_OF_MONTH))
}

/** 期间总结历史对话框：列出最近 5 条（点击重新查看）+ 「生成新总结」入口 */
@Composable
private fun PeriodHistoryDialog(
    histories: List<PeriodSummaryEntity>,
    onNew: () -> Unit,
    onSelect: (PeriodSummaryEntity) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("📋 期间总结", style = MaterialTheme.typography.titleMedium)
            if (histories.isEmpty()) {
                Text(
                    "还没有期间总结。\n点下方「生成新总结」，把一段时间的日记整理成总结。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "历史记录（最近 ${PeriodSummaryEntity.MAX_KEEP} 条）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    histories.forEach { h ->
                        Card(
                            onClick = { onSelect(h) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    formatRange(h.fromMillis, h.toMillis),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "生成于 ${formatTime(h.createdAtEpochMillis)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onNew) { Text("生成新总结") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

/** 区间标签（本地时间戳）：如「8月1日 ～ 8月7日」 */
private fun formatRange(fromMillis: Long, toMillis: Long): String {
    val fmt = SimpleDateFormat("M月d日", Locale.CHINA)
    return "${fmt.format(Date(fromMillis))} ～ ${fmt.format(Date(toMillis - 1))}"
}
