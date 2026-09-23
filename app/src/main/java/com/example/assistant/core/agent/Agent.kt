package com.example.assistant.core.agent

import com.example.assistant.core.agent.tools.ToolOutcome
import com.example.assistant.core.agent.tools.ToolRegistry
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ChatStream
import com.example.assistant.core.network.ProviderProfile
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ChatResponse
import com.example.assistant.core.network.dto.ContentPart
import com.example.assistant.core.network.dto.StreamOptions
import com.example.assistant.core.network.dto.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Agent 编排器（主模型统一调度架构）：
 * 1. 意图路由：仅剩识屏关键词本地直连（瞬时、无需模型）；其余全部走对话回路，
 *    由主聊天模型在回复中自主调用工具完成（提醒/记录/记忆/监控/搜索/读网页/识屏）
 * 2. chatReplyFlow 工具回路：模型流式输出 → 解析 [调用] 行 → 本地执行 → 结果回传 →
 *    模型继续（可自纠重试、连环调用），直到给出正式回答
 * 3. 缓存友好：静态块（系统外壳/工具手册）与易变块（时间/标签）分离，见 PromptBuilder
 */
class Agent(
    private val providerRegistry: ProviderRegistry,
    private val promptBuilder: PromptBuilder,
    private val intentRouter: IntentRouter,
    private val toolRegistry: ToolRegistry
) {

    /** 单次回复的事件流（chatReplyFlow 产出，界面层据此渲染气泡） */
    sealed interface ReplyEvent {
        /** 流式增量：text 在标记缓冲期为空串；thinking 为思考增量 */
        data class Delta(val text: String, val thinking: String) : ReplyEvent

        /**
         * 一轮流结束：本轮保留的正文并入气泡基线（纯调用轮 prose 为空串=仅重置流式区），
         * 下一轮的流式文本从基线之后追加显示。
         */
        data class RoundSettled(val prose: String) : ReplyEvent

        /** 正在执行一批工具调用（界面显示「🔧 …」状态） */
        data class ToolsRunning(val labels: List<String>) : ReplyEvent

        /**
         * 最终回答。
         * @param answer 上屏用：正文 + 系统页脚「🔧 已执行：…」
         * @param body 进会话历史用：**只有正文**，不带页脚——页脚是界面装饰，
         *        写进历史后模型会模仿它在正文里自己编"已执行"清单（2026-09-14 修的 bug）
         * @param toolNames 成功执行过的工具名
         * @param exchanges 工具中间轮：(模型输出原文, 回传的结果消息)
         * @param usage **本轮全部请求的用量合计**（不是最后一次请求；界面显示的缓存命中率按整轮算）
         * @param requests 本轮发出的模型请求次数（工具回路每转一圈多一次）
         */
        data class Final(
            val answer: String,
            val toolNames: List<String>,
            val exchanges: List<Pair<String, String>>,
            val usage: Usage? = null,
            val requests: Int = 0,
            val body: String = answer
        ) : ReplyEvent
    }

    sealed interface AgentResult {
        /** 需要走对话回路（流式 + 工具循环）；capability 决定用哪个档案（带图轮走「识屏」档案） */
        data class ChatRequested(
            val messages: List<ChatMessage>,
            val capability: Capability = Capability.CHAT
        ) : AgentResult

        /** 命令类意图（目前仅识屏关键词直连；由上层处理） */
        data class Command(val intent: AssistantIntent) : AgentResult

        /** 未配置模型等无法处理的情况 */
        data class Error(val message: String) : AgentResult
    }

    /**
     * 路由用户消息。识屏关键词本地直连（瞬时、离线可用）；其余一律进对话回路，
     * 由主模型决定是否调用工具、调用哪个——不再有独立的意图分类/判断调用。
     *
     * @param history 会话历史对话尾部（应已含当前用户消息）
     * @param diaryTags 用户自定义日记标签词汇表（进静态块，供 write_diary 选标签）
     * @param preferVision 本轮带图片：改用「识屏（视觉）」指派的档案
     *        （**同一条通道，只换模型**——上下文结构、工具回路、思考展示完全一致）
     */
    suspend fun route(
        text: String,
        memoryText: String? = null,
        history: List<ChatMessage> = emptyList(),
        diaryTags: List<String> = emptyList(),
        preferVision: Boolean = false,
        bufferText: String? = null
    ): AgentResult {
        // 关键词只保留识屏直连
        val keyword = intentRouter.keywordRoute(text)
        if (keyword != null) return AgentResult.Command(keyword)
        return chatRequested(text, memoryText, history, diaryTags, preferVision, bufferText)
    }

    /**
     * 后台静默工具回路：跑完整工具循环但不产生任何界面事件、不进会话历史。
     *
     * ⚠️ 2026-09-11 起**不再有调用方**：带图消息改走同一条聊天通道（主模型自己调 write_diary），
     * 项目不再有任何"隐藏的模型调用"。保留此方法会重新引入静默消耗，故已删除实现，
     * 仅留注释说明历史（备份/文档见 CLAUDE.md 开发日志）。
     */

    /** 当前「识屏（视觉）」档案是否勾选了"支持图片输入"（带图轮失败时的排查提示用） */
    suspend fun visionModelSupportsImages(): Boolean =
        providerRegistry.profileFor(Capability.VISION)?.supportsVision == true

    /**
     * 按目标档案能力处理图片：不支持图片输入时，把**历史**图片换成文字占位
     * （最后一条用户消息里的图保留 —— 那是用户本轮的明确意图）。
     *
     * 为什么要抽出来共用：「上下文整理」压缩轮走的是同一条对话链路，必须用**同一套**图片规则，
     * 否则在纯文本对话模型下，整理请求会带着历史图片直接 HTTP 400、每次都失败
     * （2026-09-23 审出来的潜在坑；两条路径前缀一致时缓存也才对得上）。
     */
    suspend fun messagesForCapability(
        messages: List<ChatMessage>,
        capability: Capability
    ): List<ChatMessage> {
        val supports = providerRegistry.profileFor(capability)?.supportsVision == true
        return if (supports) messages else stripHistoricalImages(messages)
    }

    /**
     * 静态前缀指纹（系统提示词 / 工具手册 / 日记标签 / 长期记忆 / 「进行中的事」状态块）。
     * 供会话层判断"这一轮的前缀是不是和上一轮一样"——不一样就说明厂商缓存的前缀全废了，
     * 窗口该回落到下限重新起跑（见 Session.planWindow）。
     *
     * ⚠️ 记忆与状态块走**冻结快照**（PrefixSnapshotStore）：它们的文本只在合并点变，
     * 合并后调用方会 Session.markPrefixSignature() 对齐基线，不会重复砍窗口。
     */
    suspend fun cachePrefixSignature(
        memoryText: String?,
        diaryTags: List<String>,
        bufferText: String? = null
    ): String = promptBuilder.prefixSignature(memoryText, toolRegistry.manual(), diaryTags, bufferText)

    /**
     * 一次性非流式对话请求（**上下文整理压缩轮**用，2026-09-17）。
     *
     * 与 chatStream 的关键差别：不流式（压缩轮不需要逐字上屏）、temperature 更低
     * （整理要求稳定、不要发挥）、maxTokens 更小。工具回路不在这里——压缩轮只要
     * "一次请求 + 解析调用行 + 执行"，由 ContextCompactor 自己编排。
     */
    suspend fun chatOnce(
        messages: List<ChatMessage>,
        capability: Capability = Capability.CHAT,
        maxTokens: Int = 2048
    ): OnceResult {
        val profile = providerRegistry.profileFor(capability)
            ?: throw IllegalStateException("未配置对话提供商")
        val api = providerRegistry.apiFor(profile)
        val effort = providerRegistry.reasoningEffortFor(profile)
        val request = ChatRequest(
            model = profile.model,
            messages = messages,
            temperature = 0.2,
            maxTokens = maxTokens,
            reasoningEffort = effort
        )
        val header = providerRegistry.authHeader(profile.apiKey)
        val response = providerRegistry.chatCompat(profile, request, header, api)
        return OnceResult(
            text = response.choices.firstOrNull()?.message?.textContent.orEmpty(),
            usage = response.usage
        )
    }

    /** 一次性请求的结果：正文 + 用量（压缩轮的用量单独统计，见 ContextStatus.compactUsage） */
    data class OnceResult(val text: String, val usage: Usage? = null)

    /** 组装对话回路的初始请求消息 */
    private suspend fun chatRequested(
        text: String,
        memoryText: String?,
        history: List<ChatMessage>,
        diaryTags: List<String>,
        preferVision: Boolean,
        bufferText: String? = null
    ): AgentResult {
        val capability = if (preferVision) Capability.VISION else Capability.CHAT
        val profile = providerRegistry.profileFor(capability)
            ?: return AgentResult.Error("尚未配置模型提供商，请到「设置」填写")
        if (!profile.isConfigured()) {
            return AgentResult.Error("模型提供商未配置完整，请到「设置」检查")
        }
        val conversation = if (history.isNotEmpty()) history else listOf(ChatMessage("user", text))
        val messages = promptBuilder.buildChatMessages(
            memoryText = memoryText,
            conversation = conversation,
            toolManual = toolRegistry.manual(),
            diaryTags = diaryTags,
            bufferText = bufferText
        )
        // 目标模型不支持图片输入时：历史里的图片会直接 400，替换为文字占位
        // （当前轮的图片始终保留——那是用户的明确意图，不支持就让 API 报错并给出提示）
        val out = if (profile.supportsVision) messages else stripHistoricalImages(messages)
        return AgentResult.ChatRequested(out, capability)
    }

    /**
     * 把**非最后一条**用户消息里的图片 part 换成文字占位。
     * 最后一条用户消息 = 本轮输入，永远保留（历史图片已在 Session 层按保留策略裁剪）。
     */
    private fun stripHistoricalImages(messages: List<ChatMessage>): List<ChatMessage> {
        val lastUserIdx = messages.indexOfLast { it.role == "user" }
        if (lastUserIdx < 0) return messages
        return messages.mapIndexed { i, m ->
            if (i == lastUserIdx || m.content.none { it.type == "image_url" }) m
            else m.copy(content = m.content.map {
                if (it.type == "image_url") ContentPart.text(HISTORICAL_IMAGE_PLACEHOLDER) else it
            })
        }
    }

    /**
     * 主模型驱动的工具回路（架构核心）：
     *
     *   流式收集一轮回复
     *     ├─ 存在合法"[调用]"行 → 执行全部调用，结果以 user 消息接回对话，再收集一轮
     *     │   （纯调用轮整轮隐藏；混合轮正文剥掉调用行后累加保留，不丢失）
     *     └─ 否则视为正式回答结束
     *
     * 单次回复最多 MAX_TOOL_ROUNDS 个工具轮；超限注入强制收尾指令；
     * guard 上限双保险保证任何情况下必然终止。
     */
    fun chatReplyFlow(
        baseMessages: List<ChatMessage>,
        capability: Capability = Capability.CHAT
    ): Flow<ReplyEvent> = flow {
        var messages = baseMessages
        var forcedFinal = false
        var toolRounds = 0
        val finalized = StringBuilder()                        // 各轮保留正文的累加
        val usedToolNames = LinkedHashSet<String>()           // 成功执行过的工具名
        val usedLabels = LinkedHashSet<String>()              // 成功执行过的动作描述（页脚）
        val exchanges = mutableListOf<Pair<String, String>>() // (模型输出原文, 回传的结果消息)
        val successMemo = HashMap<String, String>()           // 本回复内成功调用备忘（签名→feedback），重复调用直接复用
        var usageSum: Usage? = null                           // 本轮**全部请求**的用量合计（见 accumulateUsage）
        var requests = 0                                      // 本轮发出的模型请求次数

        /**
         * 各轮保留正文的累加。顺带剥掉模型仿写的机器清单行
         * （历史里的系统页脚会让它学着在正文里编「🔧 已执行：…」，一条回复里出现好几条）。
         */
        fun absorbProse(prose: String) {
            val p = toolRegistry.stripMachineLines(prose)
            if (p.isEmpty()) return
            if (finalized.isNotEmpty()) finalized.append("\n\n")
            finalized.append(p)
        }

        var guard = 0
        while (true) {
            if (++guard > GUARD_LIMIT) break

            // ---- 一轮流式收集（开头标记缓冲：疑似"[调用"时不上屏）----
            var acc = ""
            var released = false
            var reqUsage: Usage? = null                 // 本次请求的用量（流式厂商在最后一个 chunk 带）
            requests++
            chatStream(messages, capability).collect { chunk ->
                chunk.usage?.let { reqUsage = it }
                val delta = chunk.choices.firstOrNull()?.delta
                val t = delta?.textContent.orEmpty()
                val th = delta?.reasoningContent.orEmpty()
                if (t.isNotEmpty()) acc += t
                if (!released && toolRegistry.stillBuffering(acc)) {
                    if (th.isNotEmpty()) emit(ReplyEvent.Delta("", th))
                } else {
                    released = true
                    emit(ReplyEvent.Delta(t, th))
                }
            }
            usageSum = accumulateUsage(usageSum, reqUsage)

            // ---- 判定这一轮是否发起工具调用 ----
            val calls = toolRegistry.parseCalls(acc)
            val act = !forcedFinal && toolRounds < ToolRegistry.MAX_TOOL_ROUNDS && calls.isNotEmpty()
            if (!act) {
                val finalProse = toolRegistry.stripCallLines(acc)
                absorbProse(finalProse)
                // 最后一轮也要发一次"轮结束"：界面据此把流式原文替换成干净正文。
                // 否则模型仿写的「🔧 已执行：…」行、以及工具轮数用尽时残留的调用行，
                // 会永远留在最终气泡里（界面渲染的是分段内容，不是 answer 文本）——2026-09-14
                emit(ReplyEvent.RoundSettled(toolRegistry.stripMachineLines(finalProse)))
                emit(finish(finalized, usedToolNames, usedLabels, exchanges, usageSum, requests))
                return@flow
            }

            toolRounds++
            val pure = toolRegistry.isPureCallTurn(acc)
            // 中间轮也一样：回传给模型的"自己说过的话"里不保留仿写的清单行
            val cleanAcc = toolRegistry.stripMachineLines(acc)
            val roundProse = if (pure) "" else toolRegistry.stripCallLines(cleanAcc).trim()
            absorbProse(roundProse)
            emit(ReplyEvent.RoundSettled(roundProse))

            // ---- 去重后执行调用并组装结果消息 ----
            // 模型偶发重复输出同一行调用：本轮内按签名去重，跨轮成功结果直接复用（省网络也防状态重复）
            val uniqueCalls = calls.distinctBy { it.tool.name + "|" + it.args.toString() }
            emit(ReplyEvent.ToolsRunning(uniqueCalls.map { it.label }))
            val sb = StringBuilder("[结果]")
            uniqueCalls.forEachIndexed { i, call ->
                val sig = call.tool.name + "|" + call.args.toString()
                // 只读工具（read_diary/list_reminders/read_webpage/web_search）**不复用旧结果**：
                // 它们不产生副作用，而且别的工具可能刚改过数据——复用会让模型看到改动前的旧数据，
                // 误以为"没改成功"（用户实测：连续用同样参数读日记拿到的是上一次的结果）
                val cachedFeedback = if (call.tool.readOnly) null else successMemo[sig]
                val outcome = if (cachedFeedback != null) {
                    ToolOutcome.Success(cachedFeedback + "\n（与此前一次调用参数完全相同，以上为复用的结果）")
                } else {
                    val o = toolRegistry.execute(call)
                    if (o is ToolOutcome.Success && !call.tool.readOnly) successMemo[sig] = o.feedback
                    o
                }
                sb.append("\n\n").append(i + 1).append(". tool=").append(call.tool.name)
                when (outcome) {
                    is ToolOutcome.Success -> {
                        usedToolNames += call.tool.name
                        usedLabels += call.label
                        sb.append("｜状态：成功\n").append(outcome.feedback)
                    }
                    is ToolOutcome.Failure ->
                        sb.append("｜状态：失败\n").append(outcome.error)
                }
            }
            sb.append("\n\n请根据以上结果继续：信息足够就直接给出正式回答；有失败可修正参数重新调用（剩余次数有限），或如实告知用户。")
            val resultsMsg = sb.toString()

            messages = messages + ChatMessage("assistant", cleanAcc) + ChatMessage("user", resultsMsg)
            exchanges += cleanAcc to resultsMsg

            if (toolRounds >= ToolRegistry.MAX_TOOL_ROUNDS && !forcedFinal) {
                messages += ChatMessage("user", ToolRegistry.FORCED_FINAL_NOTE)
                forcedFinal = true
            }
        }

        // guard 兜底出口（正常流程到不了这里）
        emit(finish(finalized, usedToolNames, usedLabels, exchanges, usageSum, requests))
    }

    /**
     * 组装最终回答：正文 + 已执行动作页脚。
     * `answer` 给界面（含页脚），`body` 给会话历史（**不含页脚**——页脚进历史会被模型模仿）。
     * toolNames/usage/requests 随事件外传（记录兜底判断、缓存命中展示用）。
     */
    private fun finish(
        finalized: StringBuilder,
        usedToolNames: Set<String>,
        usedLabels: Set<String>,
        exchanges: List<Pair<String, String>>,
        usage: Usage?,
        requests: Int
    ): ReplyEvent.Final {
        val body = finalized.toString().ifBlank { "（模型没有返回内容，请重试或换个说法）" }
        val answer = if (usedLabels.isEmpty()) body
        else body + "\n\n" + FOOTER_PREFIX + usedLabels.joinToString("、")
        return ReplyEvent.Final(
            answer = answer,
            toolNames = usedToolNames.toList(),
            exchanges = exchanges.toList(),
            usage = usage,
            requests = requests,
            body = body
        )
    }

    /** 发送流式对话请求（单次请求；工具回路由 chatReplyFlow 编排多次调用本方法） */
    suspend fun chatStream(
        messages: List<ChatMessage>,
        capability: Capability = Capability.CHAT
    ): Flow<ChatResponse> {
        val profile = providerRegistry.profileFor(capability)
            ?: throw IllegalStateException("未配置对话提供商")
        val api = providerRegistry.apiFor(profile)
        val effort = providerRegistry.reasoningEffortFor(profile)
        val request = ChatRequest(
            model = profile.model,
            messages = messages,
            temperature = 0.7,
            // 4096：推理模型思考占配额，且多轮工具场景回答更长（2048 曾被吃光）
            maxTokens = 4096,
            stream = true,
            reasoningEffort = effort,
            // 流式也要用量统计：缓存命中 token 是验证"提示词缓存是否生效"的唯一手段
            streamOptions = StreamOptions(includeUsage = true)
        )
        val header = providerRegistry.authHeader(profile.apiKey)
        return flow {
            val response = providerRegistry.chatStreamCompat(profile, request, header, api)
            emitAll(ChatStream.parse(response.body()!!))
        }
    }

    /** 测试连接：对指定档案发一个最小请求验证配置是否正确（per-provider） */
    suspend fun testConnection(profile: ProviderProfile): Result<String> {
        // 语音识别档案：对话接口必然 400（音频模型不收聊天请求），改走真实的语音接口探测
        if (profile.supportsAudio) return testAudioConnection(profile)
        return try {
            val api = providerRegistry.apiFor(profile)
            val effort = providerRegistry.reasoningEffortFor(profile)
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(ChatMessage("user", "你好，请回复\"连接成功\"四个字")),
                // 512：推理模型的思考过程占配额，20 会被吃光导致空响应
                maxTokens = 512,
                reasoningEffort = effort
            )
            val header = providerRegistry.authHeader(profile.apiKey)
            val response = providerRegistry.chatCompat(profile, request, header, api)
            val reply = response.choices.firstOrNull()?.message?.textContent
                ?: return Result.failure(IllegalStateException("响应中没有内容"))
            Result.success(reply)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 语音识别档案的连接测试：上传 0.1 秒静音 WAV 打真实的转写接口。
     * HTTP 通了就算成功（静音转不出文字是正常的）；失败给具体错误。
     */
    private suspend fun testAudioConnection(profile: ProviderProfile): Result<String> {
        // 0.1 秒 16kHz 静音 WAV（内存生成，不落盘）
        val data = ByteArray(1600 * 2 + 44)  // 1600 采样 × 2 字节 + 44 头
        java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size - 44); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(16000); putInt(32000); putShort(16); putShort(16)
            put("data".toByteArray()); putInt(data.size - 44)
        }
        val tmp: java.io.File = kotlin.io.path.createTempFile(prefix = "asr_test", suffix = ".wav").toFile()
        try {
            tmp.writeBytes(data)
            val client = com.example.assistant.core.network.AsrClient.create()
            val r: com.example.assistant.core.network.AsrClient.Result = client.transcribe(
                profile.normalizedBaseUrl(), profile.apiKey, profile.model, tmp
            )
            if (r is com.example.assistant.core.network.AsrClient.Result.Text) {
                val suffix = if (r.text.isBlank()) "" else "：" + r.text.take(30)
                return Result.success("语音接口连接成功" + suffix)
            }
            if (r is com.example.assistant.core.network.AsrClient.Result.Error) {
                // 空结果 = 接口通了只是没转出文字（静音），也算连接成功
                if (r.message.contains("空结果")) {
                    return Result.success("语音接口连接成功（测试音为静音，无转写内容）")
                }
                return Result.failure(IllegalStateException(r.message))
            }
            return Result.failure(IllegalStateException("未知的识别响应"))
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    companion object {
        /** 循环保险丝：正常最多 纯答/多轮工具+强制收尾 轮 */
        private const val GUARD_LIMIT = 10

        /** 界面上的系统页脚前缀（不进会话历史；正文里出现即视为模型仿写） */
        const val FOOTER_PREFIX = "🔧 已执行："

        /** 目标模型不支持图片时，历史图片替换成的文字占位 */
        const val HISTORICAL_IMAGE_PLACEHOLDER = "[（历史图片：当前模型不支持图片输入，已省略）]"

        /** 带图轮报错时的排查提示（模型不支持图片输入的典型症状是 HTTP 400） */
        const val IMAGE_MODEL_GUIDE =
            "💡 排查：带图对话用的是「设置 → 模型配置 → 能力指派 → 识屏（视觉）」指派的档案。" +
                "请确认它就是支持图片输入的模型（如通义 qwen-vl、智谱 GLM-4V、Kimi vision、gpt-4o 等），" +
                "并在编辑该提供商时打开「支持图片输入」开关——建议把「对话」与「识屏」指派成同一个" +
                "带图模型，这样两种轮次共用同一套提示词缓存。"
    }
}

/**
 * 累计本轮的用量（**整轮合计**，2026-09-14）：
 * 工具回路里"一轮对话"可能发出多次请求（每转一圈多发一次），界面上的缓存命中率要按整轮算，
 * 只看最后一次请求会误导（最后一次请求的前缀最长、命中率天然最高）。
 * 任何一个请求没带用量就按 0 计（厂商未报告），累计结果里缓存字段全无则保持 null = 未报告。
 */
internal fun accumulateUsage(sum: Usage?, next: Usage?): Usage? {
    if (next == null) return sum
    if (sum == null) return next
    val cached = if (sum.cachedTokens == null && next.cachedTokens == null) null
    else (sum.cachedTokens ?: 0) + (next.cachedTokens ?: 0)
    return Usage(
        promptTokens = (sum.promptTokens ?: 0) + (next.promptTokens ?: 0),
        completionTokens = (sum.completionTokens ?: 0) + (next.completionTokens ?: 0),
        totalTokens = (sum.totalTokens ?: 0) + (next.totalTokens ?: 0),
        promptCacheHitTokens = cached,
        promptCacheMissTokens = sum.promptCacheMissTokens?.plus(next.promptCacheMissTokens ?: 0)
    )
}
