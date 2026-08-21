package com.example.assistant.feature.memory

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication
import com.example.assistant.data.db.entity.MemoryEntity
import com.example.assistant.data.repo.MemoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 长期记忆管理页（v1.4 从日记页移到首页入口）：
 * 查看 / 手动添加 / 单条编辑 / 删除 / 清空。
 * 只负责记忆，不掺入日记内容。
 */
class MemoryViewModel(private val memoryRepository: MemoryRepository) : ViewModel() {

    val memories: StateFlow<List<MemoryEntity>> = memoryRepository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)

    fun addMemory(fact: String) {
        val text = fact.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            memoryRepository.addFact(text)
            message.value = "🧠 已添加长期记忆"
        }
    }

    fun updateMemory(id: Long, fact: String) {
        val text = fact.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            memoryRepository.update(id, text)
            message.value = "✏️ 记忆已更新"
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch { memoryRepository.delete(id) }
    }

    fun clearMemories() {
        viewModelScope.launch {
            memoryRepository.clearAll()
            message.value = "🧹 记忆已清空"
        }
    }

    fun clearMessage() {
        message.value = null
    }
}

@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val vm: MemoryViewModel = viewModel {
        MemoryViewModel(app.container.memoryRepository)
    }
    val memories by vm.memories.collectAsState()
    val message by vm.message.collectAsState()

    var editingMemoryId by remember { mutableStateOf<Long?>(null) }
    var editingMemoryText by remember { mutableStateOf("") }
    var addingMemory by remember { mutableStateOf(false) }

    // modifier 来自 HomeScreen 传入的 Scaffold innerPadding：避免标题被状态栏遮挡、底部被 Tab 栏盖住
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "长期记忆（${memories.size} 条）",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { addingMemory = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加记忆")
            }
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
                    // Box 布局：文字占满左侧并预留右侧按钮区，
                    // 编辑/删除按钮通过 align(CenterEnd) 固定在最右侧
                    Box(modifier = Modifier.fillMaxWidth()) {
                        SelectionContainer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 96.dp)
                        ) {
                            Text(
                                memory.fact,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            IconButton(
                                onClick = {
                                    editingMemoryId = memory.id
                                    editingMemoryText = memory.fact
                                }
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "编辑记忆")
                            }
                            IconButton(onClick = { vm.deleteMemory(memory.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除")
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

    // 添加 / 编辑记忆对话框
    if (addingMemory || editingMemoryId != null) {
        MemoryEditDialog(
            initialText = if (editingMemoryId != null) editingMemoryText else "",
            title = if (editingMemoryId != null) "编辑长期记忆" else "添加长期记忆",
            onDismiss = {
                addingMemory = false
                editingMemoryId = null
                editingMemoryText = ""
            },
            onSave = { newText ->
                val id = editingMemoryId
                if (id != null) vm.updateMemory(id, newText) else vm.addMemory(newText)
                addingMemory = false
                editingMemoryId = null
                editingMemoryText = ""
            }
        )
    }
}

@Composable
private fun MemoryEditDialog(
    initialText: String,
    title: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("记忆内容") },
                placeholder = { Text("如：我常用的编程语言是 Kotlin") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSave(text) }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
