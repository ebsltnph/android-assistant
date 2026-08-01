package com.example.assistant.feature.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication

/**
 * 聊天页（询问模式）。
 * - 文字提问 + 流式回答
 * - 语音输入：优先用手机输入法的麦克风键（免权限、识别质量好），备选"按住说话"后续阶段加
 */
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val vm: ChatViewModel = viewModel {
        ChatViewModel(
            agent = app.container.agent,
            diaryRepository = app.container.diaryRepository,
            memoryRepository = app.container.memoryRepository,
            memoryExtractor = app.container.memoryExtractor,
            reminderRepository = app.container.reminderRepository,
            reminderTimeParser = app.container.reminderTimeParser,
            reminderScheduler = app.container.reminderScheduler,
            eventRepository = app.container.eventRepository,
            eventExtractor = app.container.eventExtractor
        )
    }
    val clipboard = LocalClipboardManager.current

    val messages by vm.messages.collectAsState()
    val input by vm.inputText.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val error by vm.error.collectAsState()

    val listState = rememberLazyListState()

    // 新消息或流式更新时自动滚到底部
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "聊天",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.clearConversation() }) {
                Icon(Icons.Filled.Delete, contentDescription = "清空对话")
            }
        }

        if (messages.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("你好，我是随身助手", style = MaterialTheme.typography.titleMedium)
                Text(
                    "直接打字提问，或点键盘上的麦克风图标语音输入。\n也可以说「记录…」「提醒我…」「识屏…」，我会自动识别。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        msg = msg,
                        isLastAssistant = msg.role == "assistant" && msg.id == messages.lastOrNull()?.id,
                        onCopy = {
                            clipboard.setText(AnnotatedString(msg.text))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                        onRegenerate = { vm.regenerate(msg.id) }
                    )
                }
            }
        }

        if (error != null) {
            Text(
                error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        OutlinedTextField(
            value = input,
            onValueChange = { vm.setInput(it) },
            placeholder = { Text("问问助手…") },
            trailingIcon = {
                IconButton(
                    onClick = { vm.send() },
                    enabled = !isStreaming
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            maxLines = 5
        )
        Text(
            "💡 点键盘上的麦克风图标可直接语音输入",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
    }
}

@Composable
private fun MessageBubble(
    msg: ChatUiMessage,
    isLastAssistant: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit
) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // 思考过程：灰色小字 + 标注（与正式回答分开）
                if (msg.thinking.isNotEmpty()) {
                    Text(
                        "🧠 思考过程",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        msg.thinking + if (msg.streaming && msg.text.isEmpty()) "▍" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (msg.text.isNotEmpty()) {
                    Text(
                        // 流式输出时追加光标，提示"正在打字"
                        text = msg.text + if (msg.streaming) "▍" else "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // 操作按钮：复制（全部消息）；重新生成（仅最后一条助手回复）
                if (!msg.streaming && (msg.text.isNotEmpty() || msg.thinking.isNotEmpty())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("复制", style = MaterialTheme.typography.labelSmall)
                        }
                        if (isLastAssistant) {
                            TextButton(onClick = onRegenerate, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text("重做", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
