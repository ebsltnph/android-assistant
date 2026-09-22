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
 *
 * **粘性通知（2026-09-17「进行中的事」）**：用户在界面上改动长期记忆/缓冲区时，
 * 不在前缀块里立刻改文本（那会让整段缓存失效），而是往**当前最后一轮**追加一条
 * 「[系统] …」通知行——追加在尾部 = 纯延长 = 零未命中，模型下一轮就能看到；
 * 前缀块里的文本由**冻结快照**在"合并点"（窗口回落流程）统一重渲染。
 *
 * ⚠️ 通知**只能追加、之后永不重写/重排**：若每轮把"待合并变更"重新拼在对话末尾，
 * 它的位置会随轮次后移，导致**每轮都多付「一轮对话 + 通知」的未命中**，永远累积不起来。
 */
class Session {

    /** 窗口回落的触发原因（2026-09-17 抽出，供 planWindow 只读预测用） */
    enum class WindowTrigger { NONE, PREFIX, MAX_TURNS, SOFT_CAP }

    /**
     * 只读的窗口计划：这一轮**将**怎么裁剪（不改任何状态）。
     *
     * 存在的理由：回落（裁剪）会把轮从 Session 里**物理删除**，而"上下文整理"（压缩轮）
     * 恰恰要用那些**即将被删掉的轮**当作压缩输入。所以先 `planWindow()` 拿到 willDrop，
     * 压缩 + 合并快照做完，再 `buildContext(plan)` 真正执行裁剪。
     */
    data class WindowPlan(
        val trigger: WindowTrigger = WindowTrigger.NONE,
        val reason: String? = null,
        /** 本次将离开窗口的轮（压缩输入的依据） */
        val willDrop: List<Turn> = emptyList(),
        val keptTurns: Int = 0,
        val keptChars: Int = 0,
        val trimmedBySoftCap: Boolean = false
    )

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
        val imagePath: String? = null,
        /** 这一轮的创建时刻（会话记录按保留天数自动清理用；0 = 未知，一律不删） */
        val createdAt: Long = 0L,
        /**
         * 粘性通知（用户手动改了长期记忆/缓冲区时追加，见类注释）。
         * **单独一个列表而不是塞进 assistant**：否则"重做"（clearAssistant/replaceLastAssistant）
         * 会把它清掉，变更信息就静默丢了。
         */
        val notices: MutableList<String> = mutableListOf()
    ) {
        /** 该轮的全部消息（按发送顺序；通知渲染在本轮助手消息之后） */
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
            notices.forEach { add(ChatMessage("user", it)) }
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
        val trimmedBySoftCap: Boolean,
        /** 本次把窗口**回落到下限**的原因（null = 这一轮窗口没动）；状态行展示用 */
        val windowReset: String? = null
    )

    private val turns = ArrayDeque<Turn>()
    private var seq = 0L

    /** 上一次请求用的静态前缀指纹（null = 还没发过请求）——变了说明厂商缓存前缀整段失效 */
    private var lastPrefixSignature: String? = null

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
        turns.addLast(
            Turn(id, userText?.let { stamp(it) }, mutableListOf(), imagePath, System.currentTimeMillis())
        )
        return id
    }

    /** 持久化恢复：直接放回轮列表并把 id 序列推进到最大 id 之后 */
    fun restoreTurns(restored: List<Turn>) {
        turns.clear()
        turns.addAll(restored)
        seq = (restored.maxOfOrNull { it.id } ?: -1L) + 1
    }

    /**
     * 持久化恢复用：由快照内容重建一轮（用户文本已含时间戳，不再补）。
     * 旧快照里的助手消息带着系统页脚「🔧 已执行：…」——那是界面装饰，写进上下文会被模型模仿
     * （用户实测到"一条回复里好几个已执行"），这里恢复时顺手剥掉（2026-09-14）。
     */
    fun turnFromStored(
        id: Long,
        userText: String?,
        imagePath: String?,
        assistantTexts: List<String>,
        createdAt: Long = 0L,
        notices: List<String> = emptyList()
    ): Turn = Turn(
        id = id,
        userText = userText,
        assistant = assistantTexts
            .map { com.example.assistant.core.agent.tools.stripFakeFooterLines(it) }
            .filter { it.isNotBlank() }
            .map { ChatMessage("assistant", it) }
            .toMutableList(),
        imagePath = imagePath,
        createdAt = createdAt,
        notices = notices.filter { it.isNotBlank() }.toMutableList()
    )

    /**
     * 历史图片保留策略（设置页可调）：把太老的带图轮的 imagePath 置空（请求里就只剩文字）。
     *
     * - `keep < 0`：全部保留（缓存命中率最高、也最贵）
     * - `keep = 0`：只保留**当前这一轮**的图片——下一轮（哪怕只是文字）开始时这张图就不再发送
     * - `keep ≥ 1`：保留最近 keep 张带图轮
     *
     * ⚠️ 无论 keep 取多少，**当前轮的图片永远保留**。原实现直接 `dropLast(keep)`，
     * keep=0 时 `dropLast(0)` 返回整个列表 ⇒ 连刚加进来的当前轮图片也被置空，
     * 用户实测"设成仅当前轮反而一张图都发不出去"就是这里。
     */
    fun enforceImageRetention(keep: Int) {
        if (keep < 0) return
        val withImage = turns.filter { it.imagePath != null }
        if (withImage.isEmpty()) return
        // keep=0：只有"最近一轮恰好就是带图轮"时才保留它，否则一张都不留
        val keepCount = if (keep == 0) {
            if (withImage.last().id == turns.lastOrNull()?.id) 1 else 0
        } else {
            keep
        }
        if (withImage.size <= keepCount) return
        withImage.dropLast(keepCount).forEach { old ->
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

    // ---- 粘性通知（用户手动改动长期记忆 / 「进行中的事」时调用）----

    /**
     * 往**当前最后一轮**追加一条通知（形如 `[系统] 用户手动更新了…`）。
     *
     * 为什么挂在轮里而不是单独一个尾部块：挂进去之后它的**位置就固定了**，
     * 后续轮次都是它的延长（缓存继续命中）；而"每轮重新拼在最后"会让位置逐轮后移，
     * 每轮都要重算一次（见类注释）。
     *
     * @return false = 会话里还没有任何一轮（没有可挂载的位置），调用方应改为立即合并快照
     */
    fun appendNotice(text: String): Boolean {
        val turn = turns.lastOrNull() ?: return false
        turn.notices += text
        return true
    }

    /** 是否存在未合并的通知（冷启动时据此决定要不要立刻合并一次快照） */
    fun hasNotices(): Boolean = turns.any { it.notices.isNotEmpty() }

    /**
     * 清除全部通知（合并时调用）。
     * 合并点必然伴随前缀重建（快照重渲染），所以清通知不会产生额外的缓存代价。
     */
    fun stripNotices() {
        turns.forEach { it.notices.clear() }
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
     * **只读**预测这一轮将怎么裁剪（不改任何状态）。
     *
     * 存在的理由：回落（裁剪）会把轮从 Session 里**物理删除**，而「上下文整理」（压缩轮）
     * 恰恰要用那些即将被删掉的轮当输入。所以流程是：
     * `planWindow()` 拿 willDrop → 压缩 + 合并快照 → `buildContext(plan)` 真正裁剪。
     *
     * 触发条件（与历史行为完全一致）：
     *  1. 静态前缀变了（系统提示词/工具手册/日记标签/长期记忆被改）——厂商缓存的公共前缀
     *     在对话之前就断了，整段历史全部失效，再扛着 U 轮只是白付全价；
     *  2. 轮数到上限 U（原有的 L→L+1→…→U→L 循环）；
     *  3. 字符软上限被触发（原来只丢"刚好超出的那几轮"，前缀从头就变了同样全失效）。
     * 三种情况都统一裁到下限 L，之后每轮继续延长 → 只有在重置点付一次全价。
     *
     * @param prefixSignature 静态前缀指纹（Agent.cachePrefixSignature）；null = 不做前缀变更检测
     */
    fun planWindow(
        minTurns: Int,
        maxTurns: Int,
        softCharLimit: Int,
        prefixSignature: String? = null
    ): WindowPlan {
        val lo = minTurns.coerceAtLeast(1)
        val hi = maxTurns.coerceAtLeast(lo)
        // 一次性算好每轮字符当量（charWeight 会读图片文件，多次调用很贵）
        val weights = turns.map { it.charWeight() }
        var size = turns.size
        var drop = 0
        var trigger = WindowTrigger.NONE
        var reason: String? = null

        fun mark(t: WindowTrigger, r: String) {
            if (trigger == WindowTrigger.NONE) { trigger = t; reason = r }
        }
        fun trimTo(target: Int) {
            if (size > target) { drop += size - target; size = target }
        }
        fun keptChars(): Int = weights.drop(drop).sum()

        // 0) 静态前缀变了 → 之前缓存的前缀全部作废，直接回落到下限重新起跑
        if (prefixSignature != null && prefixSignature != lastPrefixSignature) {
            val hadBefore = lastPrefixSignature != null
            if (hadBefore && size > lo) {
                trimTo(lo)
                mark(WindowTrigger.PREFIX, RESET_PREFIX)
            }
        }

        // 1) 轮数窗口：超过上限就裁到下限（这一步定义了"缓存区间"的边界）
        if (size > hi) {
            trimTo(lo)
            mark(WindowTrigger.MAX_TURNS, RESET_MAX_TURNS)
        }

        // 2) 字符软上限：**同样回落到下限**（前缀已经被破坏，留着中间几轮没有缓存收益），
        //    若下限轮数自身仍超限，再继续从最旧的丢（至少保留最近 1 轮）
        var trimmedBySoft = false
        if (softCharLimit > 0) {
            var total = keptChars()
            if (total > softCharLimit) {
                trimmedBySoft = true
                if (size > lo) {
                    trimTo(lo)
                    mark(WindowTrigger.SOFT_CAP, RESET_SOFT_CAP)
                    total = keptChars()
                }
                while (total > softCharLimit && size > 1) {
                    total -= weights[drop]
                    drop++
                    size--
                    mark(WindowTrigger.SOFT_CAP, RESET_SOFT_CAP)
                }
            }
        }

        return WindowPlan(
            trigger = trigger,
            reason = reason,
            willDrop = turns.take(drop).toList(),
            keptTurns = size,
            keptChars = keptChars(),
            trimmedBySoftCap = trimmedBySoft
        )
    }

    /**
     * 组装本次请求的对话尾部，并**执行**裁剪（方法名带 build 以示有副作用）。
     *
     * @param plan 已由 [planWindow] 算好的计划（压缩流程必须在裁剪前拿到 willDrop，
     *        所以支持传入）；null = 内部现算（状态行刷新等只读场景）
     * @param reasonOverride 覆盖展示用的回落原因（压缩轮显示「上下文已整理」用）
     */
    fun buildContext(
        minTurns: Int,
        maxTurns: Int,
        softCharLimit: Int,
        prefixSignature: String? = null,
        plan: WindowPlan? = null,
        reasonOverride: String? = null
    ): BuiltContext {
        val p = plan ?: planWindow(minTurns, maxTurns, softCharLimit, prefixSignature)
        // 指纹基线照旧在"真正发请求"这条路径上更新
        if (prefixSignature != null) lastPrefixSignature = prefixSignature
        repeat(p.willDrop.size) { if (turns.isNotEmpty()) turns.removeFirst() }

        val messages = turns.flatMap { it.messages() }
        return BuiltContext(
            messages = messages,
            turns = turns.size,
            chars = turns.sumOf { it.charWeight() },
            trimmedBySoftCap = p.trimmedBySoftCap,
            windowReset = reasonOverride ?: p.reason
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

        /** 窗口回落原因：静态前缀（提示词/长期记忆/标签）变了 */
        const val RESET_PREFIX = "长期记忆或提示词有更新"

        /** 窗口回落原因：轮数到上限（正常的 L→U→L 循环） */
        const val RESET_MAX_TURNS = "轮数达到上限"

        /** 窗口回落原因：字符数到软上限 */
        const val RESET_SOFT_CAP = "字符数达到上限"

        /**
         * 窗口回落原因：本次回落点顺带做了「上下文整理」（压缩轮把即将离开的轮折进缓冲区）。
         * 展示优先级最高——用户看到"已整理"比看到"轮数达到上限"更贴合实际发生的事。
         */
        const val RESET_COMPACTED = "上下文已整理"

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
