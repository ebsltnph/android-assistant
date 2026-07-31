package com.example.assistant.feature.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日记页：
 * - 「日记」子页：多日记本（生活/工作/自定义）+ 条目列表 + 文字/输入法语音记录
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

    val books by vm.books.collectAsState()
    val entries by vm.entries.collectAsState()
    val memories by vm.memories.collectAsState()
    val selectedBookId by vm.selectedBookId.collectAsState()
    val input by vm.input.collectAsState()
    val message by vm.message.collectAsState()

    LaunchedEffect(Unit) { vm.initSelectedBook() }

    var subTab by rememberSaveable { mutableStateOf(0) } // 0=日记 1=记忆
    var addingBook by rememberSaveable { mutableStateOf(false) }
    var newBookName by rememberSaveable { mutableStateOf("") }

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
            // ---- 日记本选择 ----
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        books.forEach { book ->
                            FilterChip(
                                selected = book.id == selectedBookId,
                                onClick = { vm.selectBook(book.id) },
                                label = { Text(book.name) }
                            )
                        }
                        FilterChip(
                            selected = addingBook,
                            onClick = { addingBook = !addingBook },
                            label = { Icon(Icons.Filled.Add, contentDescription = "新建日记本") }
                        )
                    }
                }

                if (addingBook) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newBookName,
                                onValueChange = { newBookName = it },
                                placeholder = { Text("日记本名称（如：旅行）") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                if (newBookName.isNotBlank()) {
                                    vm.addBook(newBookName)
                                    newBookName = ""
                                    addingBook = false
                                }
                            }) {
                                Icon(Icons.Filled.Send, contentDescription = "创建")
                            }
                        }
                    }
                }

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
                    items(entries, key = { it.id }) { entry ->
                        DiaryEntryCard(entry) { vm.deleteEntry(entry.id) }
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
    }
}

@Composable
private fun DiaryEntryCard(entry: com.example.assistant.data.db.entity.DiaryEntryEntity, onDelete: () -> Unit) {
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
                Text(
                    formatTime(entry.createdAtEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
