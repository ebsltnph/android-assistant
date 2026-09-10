package com.example.assistant.core.agent

import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ContentPart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话历史（**轮模型**，2026-09-11 重构）。
 *
 * 一轮（Turn）= 用户消息（可空）+ 该轮产生的全部助手侧消息：
 * 工具中间轮的「模型调用原文」与「[结果] 回传」都属于这一轮。
 * 这样"删除单条对话/编辑重发/重做/窗口裁剪"都能以轮为单位整体处理，
 * 不会再出现"删了用户气泡但工具轮还留在上下文里"的情况。
 *
 * 上下文窗口（需求 5：提高缓存命中率）：
 *  - 轮数 ≤ 上限 U：整段发送（每轮只是上一轮的延长 → 前缀逐字节一致，缓存全命中）
 *  - 超过上限 U：只发最近「下限 L」轮，并把会话**物理裁剪**到 L 轮
 *    ⇒ 发送序列 L → L+1 → … → U → L → …，区间内每轮都命中前面全部缓存，
 *      只在重置点失效一次（旧的滚动窗口是每轮都从头部删，等于永远不命中）
 *  - 字符软上限：轮数之外再按字符数兜底（一次 read_webpage 截断 6000 字就顶十几轮闲聊），
 *    超出时从最旧的一轮开始丢，**优先于下限**（至少保留最近 1 轮）
 *
 * 时间戳：用户消息在**创建时**打上 `[yyyy-MM-dd HH:mm]` 前缀（写一次就固定），
 * 因此既给模型提供了"每句话是什么时候说的"和"当前时间"，
 * 又不会因为时间流逝而改动历史（旧实现把"当前时间"放在消息数组中间，
 * 每分钟变一次 → 后面的整段对话历史永远无法命中缓存）。
 */
class Session {

    /**
     * 一轮：userText 为 null 表示只产生了助手消息（浮动面板「记录」提示、识屏结果回填等）。
     * 用户消息实体在 [messages] 里**按需构造**——图片只留文件路径（imagePath），
     * 组装请求时才读成 base64。这样"历史图片保留几张"只需把老轮的 imagePath 置空即可，
     * 不用改写历史消息内容。
     */
    data class Turn(
        val id: Long,
        /** 用户消息文本（已含创建时刻的时间戳前缀） */
        val userText: String?,
        val assistant: MutableList<ChatMessage> = mutableListOf(),
        /** 该轮附带图片的本机文件路径（null = 无图或已被保留策略丢弃） */
        val imagePath: String? = null
    ) {
        /** 该轮的全部消息（按发送顺序） */
        fun messages(): List<ChatMessage> = buildList {
            userText?.let { t ->
                val parts = ArrayList<ContentPart>(3)
                parts += ContentPart.text(t)
                val img = imagePath?.let { imagePartOf(it) }
                if (img != null) {
                    parts += img
                    // 告知本机路径：模型据此调用 write_diary(image_paths=[…])，无需额外静默调用
                    parts += ContentPart.text("[图片已保存到本机：$imagePath]")
                }
                add(ChatMessage("user", parts))
            }
            addAll(assistant)
        }

        /** 该轮的字符当量（图片按固定当量折算，用于字符软上限） */
        fun charWeight(): Int = messages().sumOf { m ->
            m.content.sumOf { part ->
                when {
                    part.type == "text" -> part.text.orEmpty().length
                    part.type == "image_url" -> IMAGE_CHAR_EQUIVALENT
                    else -> 0
                }
            }
        }
    }

    /** 发送上下文 + 本轮统计（状态行展示用） */
    data class BuiltContext(
        val messages: List<ChatMessage>,
        val turns: Int,
        val chars: Int,
        /** 是否因字符软上限而在下限之外又丢掉了轮 */
        val trimmedBySoftCap: Boolean
    )

    private val turns = ArrayDeque<Turn>()
    private var seq = 0L

    val turnCount: Int get() = turns.size

    fun lastTurn(): Turn? = turns.lastOrNull()

    fun hasTurn(turnId: Long): Boolean = turns.any { it.id == turnId }

    /** 全部历史（不裁剪；持久化/状态展示用） */
    fun allTurns(): List<Turn> = turns.toList()

    // ---- 写入 ----

    /**
     * 开始一轮对话。用户消息在这里**打时间戳**（创建时刻固定，之后不再变化）。
     * @param userText 用户输入原文（null = 该轮没有用户消息）
     * @param imagePath 附带图片的本机文件路径（识屏截图/上传图片）
     */
    fun beginTurn(userText: String?, imagePath: String? = null): Long {
        val id = seq++
        turns.addLast(Turn(id, userText?.let { stamp(it) }, mutableListOf(), imagePath))
        return id
    }

    /** 持久化恢复：直接放回轮列表并把 id 序列推进到最大 id 之后 */
    fun restoreTurns(restored: List<Turn>) {
        turns.clear()
        turns.addAll(restored)
        seq = (restored.maxOfOrNull { it.id } ?: -1L) + 1
    }

    /** 持久化恢复用：由快照内容重建一轮（用户文本已含时间戳，不再补） */
    fun turnFromStored(
        id: Long,
        userText: String?,
        imagePath: String?,
        assistantTexts: List<String>
    ): Turn = Turn(
        id = id,
        userText = userText,
        assistant = assistantTexts.map { ChatMessage("assistant", it) }.toMutableList(),
        imagePath = imagePath
    )

    /**
     * 历史图片保留策略（设置页可调）：
     * 只保留最近 [keep] 张带图轮的图片，更早的把 imagePath 置空（请求里就只剩文字占位）。
     * keep < 0 = 全部保留（命中率最高、最贵）；0 = 只保留当前轮（最省）。
     */
    fun enforceImageRetention(keep: Int) {
        if (keep < 0) return
        val withImage = turns.filter { it.imagePath != null }
        if (withImage.size <= keep) return
        withImage.dropLast(keep).forEach { old ->
            val idx = turns.indexOfFirst { it.id == old.id }
            if (idx >= 0) turns[idx] = turns[idx].copy(imagePath = null)
        }
    }

    /** 把助手侧消息追加到指定轮（工具中间轮与最终回答都走这里） */
    fun appendAssistant(turnId: Long, msg: ChatMessage) {
        val turn = turns.firstOrNull { it.id == turnId } ?: return
        turn.assistant += msg
    }

    /** 只有助手消息的一轮（浮动面板记录提示、识屏结果回填） */
    fun addAssistantOnly(text: String): Long {
        val id = beginTurn(null)
        appendAssistant(id, ChatMessage("assistant", text))
        return id
    }

    // ---- 删除 / 回退 ----

    /** 删除一轮（需求 3：删除单条对话 = 一次删掉该轮的用户消息 + 全部助手消息） */
    fun deleteTurn(turnId: Long): Boolean = turns.removeAll { it.id == turnId }

    /** 撤回最后一轮（编辑重发）：返回被撤回的轮（调用方据此恢复原文） */
    fun removeLastTurn(): Turn? = turns.removeLastOrNull()

    /** 清空某一轮的助手消息（重做：重新生成该轮回答） */
    fun clearAssistant(turnId: Long) {
        turns.firstOrNull { it.id == turnId }?.assistant?.clear()
    }

    /** 替换最后一轮的最终回答（兼容旧调用；正文之外的工具轮保留） */
    fun replaceLastAssistant(text: String) {
        val turn = turns.lastOrNull() ?: return
        if (turn.assistant.isEmpty()) {
            turn.assistant += ChatMessage("assistant", text)
        } else {
            turn.assistant[turn.assistant.lastIndex] = ChatMessage("assistant", text)
        }
    }

    fun clear() = turns.clear()

    // ---- 读取（发送上下文） ----

    /**
     * 组装本次请求的对话尾部，并顺带按上下限做**物理裁剪**（方法名带 build 以示有副作用）。
     * 顺序：先按轮数上限/下限裁，再按字符软上限兜底。
     */
    fun buildContext(minTurns: Int, maxTurns: Int, softCharLimit: Int): BuiltContext {
        val lo = minTurns.coerceAtLeast(1)
        val hi = maxTurns.coerceAtLeast(lo)

        // 1) 轮数窗口：超过上限就裁到下限（这一步定义了"缓存区间"的边界）
        if (turns.size > hi) {
            while (turns.size > lo) turns.removeFirst()
        }

        // 2) 字符软上限：优先于下限，但从最旧的轮开始丢，至少保留最近 1 轮
        var trimmedBySoft = false
        if (softCharLimit > 0) {
            var total = turns.sumOf { it.charWeight() }
            while (total > softCharLimit && turns.size > 1) {
                total -= turns.removeFirst().charWeight()
                trimmedBySoft = true
            }
        }

        val messages = turns.flatMap { it.messages() }
        return BuiltContext(
            messages = messages,
            turns = turns.size,
            chars = turns.sumOf { it.charWeight() },
            trimmedBySoftCap = trimmedBySoft
        )
    }

    /** 当前会话的字符当量（不裁剪，状态行展示用） */
    fun currentCharWeight(): Int = turns.sumOf { it.charWeight() }

    /** 给用户消息打时间戳前缀（创建时刻固定，历史消息永不改写） */
    private fun stamp(text: String): String =
        "[" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date()) + "] " + text

    companion object {
        /** 图片的字符当量（字符软上限的粗略折算：图片 token 与分辨率有关，这里只做量级估计） */
        const val IMAGE_CHAR_EQUIVALENT = 1_500

        /**
         * 把本机图片文件转成请求用的 image part（base64）。
         * 文件不存在/读失败返回 null（历史图片已过期时该轮退化为纯文本，不影响其余上下文）。
         */
        fun imagePartOf(path: String): ContentPart? = try {
            val f = java.io.File(path)
            if (!f.exists()) null
            else ContentPart.image(
                android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP),
                mimeType = if (path.endsWith(".png", true)) "image/png" else "image/jpeg"
            )
        } catch (_: Exception) {
            null
        }
    }
}
