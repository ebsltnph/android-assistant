package com.example.assistant.feature.buffer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication
import com.example.assistant.core.agent.BufferRenderer
import com.example.assistant.data.db.entity.BufferItemEntity
import com.example.assistant.data.repo.BufferRepository
import com.example.assistant.data.repo.DiaryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「进行中的事」页面（2026-09-17）。
 *
 * 这是缓冲区的**手动入口**（用户要求）：查看 / 新增 / 编辑 / 归档 / 取消归档 / 删除 /
 * 存入日记。页面读的是数据库真相（改完立刻可见）；**注入模型的文本是冻结快照**，
 * 改动会以"粘性通知"的方式让模型下一轮就知道（零缓存代价），并在下次上下文整理时并入快照。
 */
class BufferViewModel(
    private val bufferRepository: BufferRepository,
    private val diaryRepository: DiaryRepository,
    /** 改动 → 写一条粘性通知（由 ChatViewModel 实现，见 notifyManualChange） */
    private val notify: (String) -> Unit
) : ViewModel() {

    val items: StateFlow<List<BufferItemEntity>> = bufferRepository.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)

    fun add(title: String, body: String, kind: String) {
        val t = title.trim()
        if (t.isEmpty() && body.isBlank()) return
        viewModelScope.launch {
            bufferRepository.add(title = t.ifEmpty { body.take(12) }, body = body, kind = kind)
            notify("「进行中的事」：新增了一条「${t.ifEmpty { body.take(12) }}」")
            message.value = "📌 已添加"
        }
    }

    fun update(id: Long, title: String, body: String, kind: String) {
        viewModelScope.launch {
            bufferRepository.update(id, title = title, body = body, kind = kind)
            notify("「进行中的事」：条目 #id=$id 已被修改为「${title.trim()}」")
            message.value = "✏️ 已更新"
        }
    }

    fun setArchived(item: BufferItemEntity, archived: Boolean) {
        viewModelScope.launch {
            bufferRepository.setArchived(item.id, archived)
            notify(
                "「进行中的事」：#id=${item.id}「${item.title}」已被" +
                    (if (archived) "归档（不必再盯）" else "取消归档（重新纳入关注）")
            )
            message.value = if (archived) "📥 已归档" else "📤 已取消归档"
        }
    }

    fun delete(item: BufferItemEntity) {
        viewModelScope.launch {
            bufferRepository.delete(item.id)
            notify("「进行中的事」：#id=${item.id}「${item.title}」已被删除，请勿再使用快照里的这条")
            message.value = "🗑 已删除"
        }
    }

    /** 存入日记：正文进日记本（source=buffer），随后该条默认归档（从缓冲区"毕业"） */
    fun saveToDiary(item: BufferItemEntity, archiveAfter: Boolean = true) {
        viewModelScope.launch {
            val book = diaryRepository.defaultBook()
            if (book == null) {
                message.value = "⚠️ 日记本不可用"
                return@launch
            }
            val content = buildString {
                append(item.title.trim())
                if (item.body.isNotBlank()) append("：").append(item.body.trim())
            }
            diaryRepository.addEntry(book.id, content, source = "buffer")
            if (archiveAfter) bufferRepository.setArchived(item.id, true)
            notify("「进行中的事」：#id=${item.id}「${item.title}」已存入日记" + if (archiveAfter) "并归档" else "")
            message.value = "📔 已存入日记"
        }
    }

    fun clearMessage() {
        message.value = null
    }
}

@Composable
fun BufferScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val container = app.container
    val vm: BufferViewModel = viewModel {
        BufferViewModel(
            bufferRepository = container.bufferRepository,
            diaryRepository = container.diaryRepository,
            notify = { detail -> container.chatViewModel.notifyManualChange(detail) }
        )
    }
    val all by vm.items.collectAsState()
    val message by vm.message.collectAsState()
    val enabled by container.settingsStore.bufferEnabled
        .collectAsState(initial = true)

    val active = all.filter { it.isActive() }
    val archived = all.filterNot { it.isActive() }

    var editing by remember { mutableStateOf<BufferItemEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BufferItemEntity?>(null) }
    var archivedOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "进行中的事（${active.size} 条）",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加")
            }
        }

        Text(
            "助手在每次「上下文整理」（对话窗口回落）时，会把即将遗忘的进展蒸馏到这里，" +
                "之后每轮对话都带着它；你也可以手动维护。" +
                "改动会在下一轮立刻告知助手，并在下次整理时并入。" +
                "（设置 → 进行中的事 可调整开关与上限）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (!enabled) {
            Text(
                "⚠️ 「进行中的事」当前已在设置里关闭：不会注入、也不会自动整理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (active.isEmpty() && archived.isEmpty()) {
            Text(
                "这里还是空的。\n聊到持续几天的进展（在做实验、写代码、准备某个东西）时，" +
                    "助手会在上下文整理时自动记一条；需要盯一阵子的事（比如感冒）也记在这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 32.dp)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(active, key = { it.id }) { item ->
                    BufferItemCard(
                        item = item,
                        onEdit = { editing = item },
                        onArchive = { vm.setArchived(item, true) },
                        onDiary = { vm.saveToDiary(item) },
                        onDelete = { deleting = item }
                    )
                }
                if (archived.isNotEmpty()) {
                    item(key = "archived-header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { archivedOpen = !archivedOpen }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                if (archivedOpen) "已归档（${archived.size}）— 点击收起"
                                else "已归档（${archived.size}）— 点击展开",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (archivedOpen) {
                        items(archived, key = { it.id }) { item ->
                            BufferItemCard(
                                item = item,
                                archived = true,
                                onEdit = { editing = item },
                                onArchive = { vm.setArchived(item, false) },
                                onDiary = { vm.saveToDiary(item, archiveAfter = false) },
                                onDelete = { deleting = item }
                            )
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
    }

    if (adding) {
        BufferEditDialog(
            initialTitle = "", initialBody = "", initialKind = BufferItemEntity.KIND_PROGRESS,
            title = "添加「进行中的事」",
            onDismiss = { adding = false },
            onSave = { t, b, k -> vm.add(t, b, k); adding = false }
        )
    }
    editing?.let { item ->
        BufferEditDialog(
            initialTitle = item.title, initialBody = item.body, initialKind = item.kind,
            title = "编辑 #id=${item.id}",
            onDismiss = { editing = null },
            onSave = { t, b, k -> vm.update(item.id, t, b, k); editing = null }
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条？") },
            text = { Text("「${item.title}」将被彻底删除（不是归档）。已归档的条目可以随时取消归档，建议优先归档。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(item); deleting = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun BufferItemCard(
    item: BufferItemEntity,
    archived: Boolean = false,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDiary: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (archived) 0.4f else 0.7f),
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.title.ifBlank { "（未命名）" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (item.kind == BufferItemEntity.KIND_WATCH) "关注" else "在办",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (item.body.isNotBlank()) {
            SelectionContainer {
                Text(
                    item.body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        val diaryIds = item.diaryIdList()
        if (diaryIds.isNotEmpty()) {
            Text(
                "相关日记：" + diaryIds.joinToString("、") { "#id=$it" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "更新于 " + BufferRenderer.formatDay(item.updatedAtEpochMillis) +
                    "（" + BufferRenderer.relativeDay(item.updatedAtEpochMillis) + "）" +
                    if (item.source == BufferItemEntity.SOURCE_USER) "｜手动" else "｜自动整理",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onEdit) { Text("编辑", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = onArchive) {
                Text(
                    if (archived) "取消归档" else "归档",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            TextButton(onClick = onDiary) { Text("存入日记", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = onDelete) {
                Text("删除", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BufferEditDialog(
    initialTitle: String,
    initialBody: String,
    initialKind: String,
    title: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var t by remember { mutableStateOf(initialTitle) }
    var b by remember { mutableStateOf(initialBody) }
    var kind by remember { mutableStateOf(initialKind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = t,
                    onValueChange = { t = it },
                    label = { Text("事项名") },
                    placeholder = { Text("如：自由空间平衡零差探测器搭建") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = b,
                    onValueChange = { b = it },
                    label = { Text("进展（保留关键数据）") },
                    placeholder = { Text("已完成：…；待办：…；关键数据：…") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 4
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    listOf(
                        BufferItemEntity.KIND_PROGRESS to "在办事项",
                        BufferItemEntity.KIND_WATCH to "需要盯一阵"
                    ).forEach { (value, label) ->
                        TextButton(onClick = { kind = value }) {
                            Text(
                                (if (kind == value) "● " else "○ ") + label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (kind == value) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = t.isNotBlank() || b.isNotBlank(),
                onClick = { onSave(t, b, kind) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
