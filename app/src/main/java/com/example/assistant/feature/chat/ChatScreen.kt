package com.example.assistant.feature.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.assistant.AssistantApplication
import com.example.assistant.core.ui.RichMessageText

/**
 * 聊天页（询问模式）。
 * - 文字提问 + 流式回答
 * - 图片：点 + 上传（附件模式，文字要求与图片一起发给识屏模型）
 * - 语音输入：优先用手机输入法的麦克风键（免权限、识别质量好），备选"按住说话"后续阶段加
 */
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    // P6：聊天核心改为进程级共享单例（浮动界面与聊天页共用同一会话），
    // 直接从容器取，不再每屏创建一个
    val vm = remember { app.container.chatViewModel }
    val clipboard = LocalClipboardManager.current

    val messages by vm.messages.collectAsState()
    val input by vm.inputText.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val error by vm.error.collectAsState()
    val speakingMsgId by vm.speakingMsgId.collectAsState()
    val pendingImage by vm.pendingImage.collectAsState()

    // 相册选图（Photo Picker，免存储权限）
    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { vm.setPendingImageFromUri(it) }
    }

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
                // 空状态英雄区：玻璃圆盘 + 气泡吉祥物
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🫧", fontSize = 38.sp)
                }
                Spacer(Modifier.height(16.dp))
                Text("你好，我是随身助手", style = MaterialTheme.typography.titleMedium)
                Text(
                    "直接打字提问，或点键盘上的麦克风图标语音输入。\n也可以说「记录…」「提醒我…」「识屏…」，我会自动识别。\n点 + 可以上传图片一起分析。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        msg = msg,
                        isLastAssistant = msg.role == "assistant" && msg.id == messages.lastOrNull()?.id,
                        speakingThis = speakingMsgId == msg.id,
                        onSpeak = { vm.speakMessage(msg) },
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

        // 附件栏：待发送图片（等用户输入文字要求一起发送）
        pendingImage?.let { p ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    bitmap = p.thumbnail.asImageBitmap(),
                    contentDescription = "待发送图片",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Column(Modifier.weight(1f)) {
                    Text("已添加图片", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "输入要求后与图片一起发送",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.removePendingImage() }) {
                    Icon(Icons.Filled.Close, contentDescription = "移除图片")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 上传图片（附件模式，与文字一起发给识屏模型）
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
            ) {
                IconButton(
                    onClick = {
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !isStreaming
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加图片", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { vm.setInput(it) },
                placeholder = { Text("问问助手…") },
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFE4B863).copy(alpha = 0.55f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier.weight(1f),
                maxLines = 5
            )
            // 发送：有内容时点亮香槟金
            val canSend = !isStreaming && (input.isNotBlank() || pendingImage != null)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (canSend) Color(0xFFE4B863)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
            ) {
                IconButton(onClick = { vm.send() }, enabled = canSend) {
                    Icon(
                        Icons.Filled.Send, contentDescription = "发送",
                        tint = if (canSend) Color(0xFF1A1300) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
    speakingThis: Boolean,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit
) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        // 气泡 + 外侧操作按钮：用户消息按钮在右下，助手消息按钮在左下
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            // 不对称圆角：靠近发言者一侧的底角收紧，形成「说话」的方向感
            val bubbleShape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomEnd = if (isUser) 6.dp else 18.dp,
                bottomStart = if (isUser) 18.dp else 6.dp
            )
            Card(
                shape = bubbleShape,
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(
                    1.dp,
                    if (isUser) Color(0xFFE4B863).copy(alpha = 0.38f)
                    else Color.White.copy(alpha = 0.13f)
                ),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            if (isUser) Brush.linearGradient(
                                listOf(
                                    Color(0xFFE4B863).copy(alpha = 0.20f),
                                    Color(0xFFE4B863).copy(alpha = 0.11f)
                                )
                            )
                            else Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.085f), Color.White.copy(alpha = 0.05f))
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // 消息附带图片（识屏截图 / 上传图片）
                    msg.image?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "消息图片",
                            modifier = Modifier
                                .width(180.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                        )
                    }
                    if (msg.segments.isNotEmpty()) {
                        // 多轮工具回复：思考块/正文段/工具执行行按真实使用顺序渲染
                        msg.segments.forEachIndexed { si, seg ->
                            when (seg) {
                                is MsgSegment.Think -> ThinkingBlock(
                                    emoji = "🧠",
                                    text = seg.text,
                                    showCursor = msg.streaming && si == msg.segments.lastIndex
                                )
                                is MsgSegment.Text -> if (seg.text.isNotEmpty()) {
                                    SelectionContainer {
                                        RichMessageText(
                                            text = seg.text,
                                            streaming = msg.streaming && si == msg.segments.lastIndex,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                is MsgSegment.Tools -> ToolsStatusLine(seg.labels)
                            }
                        }
                    } else {
                        // 单段消息（视觉回复/命令提示等）：旧字段渲染
                        // 思考过程：默认收起只显示摘要，点击展开/收起（思考可能很长）
                        if (msg.thinking.isNotEmpty()) {
                            ThinkingBlock(emoji = "🧠", text = msg.thinking, showCursor = msg.streaming && msg.text.isEmpty())
                        }
                        if (msg.text.isNotEmpty()) {
                            // 富文本渲染：数学公式 + 基础 Markdown（加粗/斜体/代码/标题/列表）
                            SelectionContainer {
                                RichMessageText(
                                    text = msg.text,
                                    streaming = msg.streaming,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
            // 气泡外操作按钮（图标）：复制（全部消息）；朗读（助手消息）；重做（仅最后一条助手回复）
            if (!msg.streaming && (msg.text.isNotEmpty() || msg.thinking.isNotEmpty())) {
                Row(
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "复制",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    // 朗读按钮（仅助手消息）：金色高亮 = 正在朗读这条，再点停止
                    if (!isUser) {
                        IconButton(onClick = onSpeak, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Filled.VolumeUp,
                                contentDescription = if (speakingThis) "停止朗读" else "朗读",
                                tint = if (speakingThis) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    if (isLastAssistant) {
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "重做",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 可折叠思考块（分段时间线用；showCursor=流式进行中且是最后一个片段） */
@Composable
private fun ThinkingBlock(emoji: String, text: String, showCursor: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp)
    ) {
        Text(
            if (expanded) "$emoji 思考过程（点击收起）" else "$emoji 思考过程（点击展开）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val shown = if (expanded) text + if (showCursor) "▍" else ""
        else text.take(60) + (if (text.length > 60) "…" else "")
        SelectionContainer {
            Text(
                shown,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 工具执行状态行（气泡内的「🔧 …」小字） */
@Composable
private fun ToolsStatusLine(labels: List<String>) {
    Text(
        "🔧 " + labels.joinToString("、"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
