package com.example.assistant.feature.diary

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            summaryGenerator = app.container.dailySummaryGenerator
        )
    }

    val entries by vm.entries.collectAsState()
    val memories by vm.memories.collectAsState()
    val input by vm.input.collectAsState()
    val message by vm.message.collectAsState()

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
            TextButton(onClick = { vm.generateTodaySummary() }) {
                Text("今日小结")
            }
        }

        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("日记") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("记忆") })
        }

        if (subTab == 0) {
            // ---- 日记条目列表 ----
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (entries.isEmpty()) {
                    item {
                        Text(
                            "还没有日记，在下面写点什么吧\n（点键盘上的麦克风图标可直接语音输入）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
                        )
                    }
                } else {
                    items(entries, key = { it.entry.id }) { item ->
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
        // 删除按钮：右上角小圆点（尺寸缩小避免遮挡缩略图，误触影响小）
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(17.dp)
                .background(Color(0x99000000), CircleShape)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "删除这张图片",
                tint = Color.White,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
