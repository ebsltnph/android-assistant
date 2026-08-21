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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.assistant.data.db.entity.parseDiaryTags
import com.example.assistant.data.db.entity.tagList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 日记页：条目列表 + 标签筛选 + 写日记 + 补图（相册多选，一条目多张，可删可下载）。
 * 长期记忆已移到首页独立入口（Home → 长期记忆），不再挂在日记页下。
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
    val searchQuery by vm.searchQuery.collectAsState()
    val message by vm.message.collectAsState()
    val periodSummary by vm.periodSummary.collectAsState()
    val periodSummaryLoading by vm.periodSummaryLoading.collectAsState()
    val periodSummaries by vm.periodSummaries.collectAsState()
    val selectedFilterTags by vm.selectedFilterTags.collectAsState()
    val untaggedOnly by vm.untaggedOnly.collectAsState()
    val diaryTagsCsv by app.container.settingsStore.diaryTagsCsv.collectAsState(initial = "")
    val diaryTags = remember(diaryTagsCsv) { parseDiaryTags(diaryTagsCsv) }
    val clipboard = LocalClipboardManager.current

    // 写日记对话框（右上角 + 打开；替代原来底部常驻输入区）
    var showWriteDialog by remember { mutableStateOf(false) }
    // 搜索是否展开（默认只显示放大镜，点击后与标签同行展开）
    var searchExpanded by remember { mutableStateOf(false) }
    // 标签管理对话框
    var showTagManage by remember { mutableStateOf(false) }

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

    // 期间总结：是否显示「历史 + 生成」入口对话框
    var showPeriodHistoryDialog by rememberSaveable { mutableStateOf(false) }
    // 期间总结：是否显示起止日期选择对话框
    var showPeriodRangeDialog by rememberSaveable { mutableStateOf(false) }
    // 正在查看大图的条目图片路径（点击缩略图打开）
    var viewingImage by rememberSaveable { mutableStateOf<String?>(null) }
    // 单条编辑：日记文字 + 标签
    var editingEntryId by remember { mutableStateOf<Long?>(null) }
    var editingEntryText by remember { mutableStateOf("") }
    var editingEntryTags by remember { mutableStateOf<Set<String>>(emptySet()) }
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
            // 搜索默认收成放大镜，点击后展开（与标签同一行）
            IconButton(onClick = { searchExpanded = !searchExpanded }) {
                Icon(Icons.Filled.Search, contentDescription = if (searchExpanded) "收起搜索" else "搜索")
            }
            TextButton(
                onClick = { showPeriodHistoryDialog = true },
                enabled = !periodSummaryLoading
            ) {
                Text("期间总结")
            }
            TextButton(onClick = { vm.generateTodaySummary() }) {
                Text("今日小结")
            }
            IconButton(onClick = { showWriteDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "写日记")
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

            // ---- 搜索（默认收起，点放大镜展开） + 标签筛选，压缩成同一行 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (searchExpanded) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { vm.setSearchQuery(it) },
                        placeholder = { Text("搜日记…") },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { vm.setSearchQuery("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.width(160.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(start = if (searchExpanded) 8.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = untaggedOnly,
                        onClick = { vm.toggleUntaggedOnly() },
                        label = { Text("未分类", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.heightIn(min = 30.dp)
                    )
                    diaryTags.forEach { tag ->
                        FilterChip(
                            selected = tag in selectedFilterTags,
                            onClick = { vm.toggleFilterTag(tag) },
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.heightIn(min = 30.dp)
                        )
                    }
                    TextButton(onClick = { showTagManage = true }) { Text("管理", style = MaterialTheme.typography.labelSmall) }
                }
            }

            // ---- 日记条目列表 ----
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
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
                            onEdit = {
                                editingEntryId = item.entry.id
                                editingEntryText = item.entry.content
                                editingEntryTags = item.entry.tagList().toSet()
                            },
                            onDelete = { vm.deleteEntry(item.entry.id) },
                            onPickImages = { uris -> vm.addImagesToEntry(item.entry.id, uris) },
                            onDeleteImage = { image -> vm.deleteImage(image) },
                            onViewImage = { path -> viewingImage = path }
                        )
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
                    SelectionContainer {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
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

        // 日记单条编辑对话框（内容 + 标签）
        editingEntryId?.let { id ->
            DiaryEditDialog(
                initialText = editingEntryText,
                initialTags = editingEntryTags,
                availableTags = diaryTags,
                onDismiss = {
                    editingEntryId = null
                    editingEntryText = ""
                    editingEntryTags = emptySet()
                },
                onSave = { newText, newTags ->
                    vm.updateEntry(id, newText, newTags.toList())
                    editingEntryId = null
                    editingEntryText = ""
                    editingEntryTags = emptySet()
                }
            )
        }

        // 写日记对话框（右上角 + 打开）：内容 + 标签
        if (showWriteDialog) {
            DiaryEditDialog(
                title = "写日记",
                hint = "推荐在聊天中直接说「记录…」，助手会自动整理内容并打标签。",
                initialText = "",
                initialTags = emptySet(),
                availableTags = diaryTags,
                onDismiss = { showWriteDialog = false },
                onSave = { newText, newTags ->
                    vm.addEntry(newText, newTags.toList())
                    showWriteDialog = false
                }
            )
        }

        // 标签管理对话框：查看/添加/删除用户自定义标签词汇
        if (showTagManage) {
            TagManageDialog(
                tags = diaryTags,
                onDismiss = { showTagManage = false },
                onAdd = { newTag ->
                    val clean = newTag.trim()
                    if (clean.isNotEmpty() && diaryTags.none { it == clean }) {
                        val newCsv = (diaryTags + clean).joinToString(",")
                        app.container.appScope.launch { app.container.settingsStore.setDiaryTagsCsv(newCsv) }
                    }
                },
                onRemove = { tag ->
                    val newCsv = diaryTags.filter { it != tag }.joinToString(",")
                    app.container.appScope.launch { app.container.settingsStore.setDiaryTagsCsv(newCsv) }
                }
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
                    SelectionContainer {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
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

/** 日记单条编辑/写日记对话框：内容 + 标签（title 区分编辑与新建；hint 为新建时的推荐提示） */
@Composable
private fun DiaryEditDialog(
    title: String = "编辑日记",
    hint: String? = null,
    initialText: String,
    initialTags: Set<String>,
    availableTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, Set<String>) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var tags by remember { mutableStateOf(initialTags) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hint.isNullOrBlank()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("日记内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4
                )
                if (availableTags.isNotEmpty()) {
                    Text(
                        "标签（可多选，不选 = 未分类）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableTags.forEach { tag ->
                            FilterChip(
                                selected = tag in tags,
                                onClick = {
                                    tags = if (tag in tags) tags - tag else tags + tag
                                },
                                label = { Text(tag) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSave(text, tags) }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 标签管理对话框：用户自定义标签词汇表的增删 */
@Composable
private fun TagManageDialog(
    tags: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var newTag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理日记标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "标签词汇表用于：手动选择、筛选（且匹配）、聊天记录时 AI 选 0-3 个标签。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (tags.isEmpty()) {
                    Text(
                        "还没有标签，先添加一个。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tags.forEach { tag ->
                        AssistChip(
                            onClick = { onRemove(tag) },
                            label = { Text(tag) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "删除", modifier = Modifier.size(14.dp)) }
                        )
                    }
                }
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = { Text("新标签") },
                    placeholder = { Text("如：健身") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = newTag.isNotBlank(),
                onClick = {
                    onAdd(newTag)
                    newTag = ""
                }
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/** 日记卡片上的超小标签药丸：显示用，不占高度 */
@Composable
private fun DiaryTagChip(tag: String) {
    Text(
        text = tag,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
private fun DiaryEntryCard(
    entryWithImages: DiaryEntryWithImages,
    onEdit: () -> Unit,
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
    // 单按钮 + 下拉菜单：编辑内容 / 添加图片 / 删除，避免三个按钮挤压文字空间
    var actionMenuExpanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SelectionContainer {
                    Text(
                        entry.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // 条目标签：极小药丸，横向左右滑动，不换行，压到最小
                val entryTags = entry.tagList()
                if (entryTags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        entryTags.forEach { tag ->
                            DiaryTagChip(tag)
                        }
                    }
                }
                // 图片列表（一条目多张）：横向滚动缩略图，每张可点看大图、点右上角 ✕ 删除
                if (entryWithImages.images.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // 单一操作按钮：点击弹出「编辑内容 / 添加图片 / 删除」，不再占三格空间
            Box {
                IconButton(
                    onClick = { actionMenuExpanded = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "更多操作", modifier = Modifier.size(16.dp))
                }
                DropdownMenu(
                    expanded = actionMenuExpanded,
                    onDismissRequest = { actionMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("✏️ 编辑内容与标签") },
                        onClick = {
                            actionMenuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("🖼️ 添加图片") },
                        onClick = {
                            actionMenuExpanded = false
                            pickImageLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("🗑️ 删除") },
                        onClick = {
                            actionMenuExpanded = false
                            onDelete()
                        }
                    )
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
