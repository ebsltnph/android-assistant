package com.example.assistant.core.agent

import com.example.assistant.core.agent.tools.AssistantTool
import com.example.assistant.core.agent.tools.NativeToolCallAssembler
import com.example.assistant.core.agent.tools.ToolOutcome
import com.example.assistant.core.agent.tools.ToolRegistry
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ChatStream
import com.example.assistant.core.network.ProviderProfile
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ChatResponse
import com.example.assistant.core.network.dto.ChatToolCall
import com.example.assistant.core.network.dto.ContentPart
import com.example.assistant.core.network.dto.FunctionCall
import com.example.assistant.core.network.dto.FunctionDefinition
import com.example.assistant.core.network.dto.StreamOptions
import com.example.assistant.core.network.dto.ToolDefinition
import com.example.assistant.core.network.dto.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Agent 编排器（主模型统一调度 + 原生 Function Calling）：
 * 1. 意图路由：仅剩识屏关键词本地直连；其余全部走对话回路。
 * 2. chatReplyFlow：流式收集 content/reasoning/tool_calls → 本地执行 → 用 role=tool
 *    + tool_call_id 回传 → 继续请求，直到模型给出正式回答。
 * 3. 缓存友好：工具 Schema 走请求 tools 字段；消息里只保留稳定的工具使用原则。
 */
class Agent(
    private val providerRegistry: ProviderRegistry,
    private val promptBuilder: PromptBuilder,
    private val intentRouter: IntentRouter,
    private val toolRegistry: ToolRegistry
) {

    /** 单次回复的事件流（chatReplyFlow 产出，界面层据此渲染气泡） */
    sealed interface ReplyEvent {
        /** 流式增量：text 在协议标记缓冲期为空串；thinking 为思考增量 */
        data class Delta(val text: String, val thinking: String) : ReplyEvent

        /**
         * 一轮流结束：本轮保留的正文并入气泡基线（工具轮可以没有正文），
         * 下一轮的流式文本从基线之后追加显示。
         */
        data class RoundSettled(val prose: String) : ReplyEvent

        /** 正在执行一批工具调用（界面显示「🔧 …」状态） */
        data class ToolsRunning(val labels: List<String>) : ReplyEvent

        /**
         * 最终回答。
         * @param answer 上屏用：正文 + 系统页脚「🔧 已执行：…」
         * @param body 进会话历史用：只有正文，不带页脚
         * @param toolNames 成功执行过的工具名
         * @param exchanges 原生工具调用轮（assistant 回放消息 + 配对的 tool 结果）
         * @param usage **本轮全部请求的用量合计**
         * @param requests 本轮发出的模型请求次数
         */
        data class Final(
            val answer: String,
            val toolNames: List<String>,
            val exchanges: List<ToolExchange>,
            val usage: Usage? = null,
            val requests: Int = 0,
            val body: String = answer
        ) : ReplyEvent
    }

    /**
     * 可原样写回会话的一轮工具回放。
     * assistant 消息带 tool_calls（以及 DeepSeek 需要的 reasoning_content）；
     * results 里每一条 tool 消息都与对应 call_id 配对，图片消息作为额外一条 user 回放。
     */
    data class ToolExchange(
        val assistantMessage: ChatMessage,
        val results: List<ToolReplay>
    )

    data class ToolReplay(
        val message: ChatMessage,
        val imagePaths: List<String> = emptyList()
    )

    sealed interface AgentResult {
        /** 需要走对话回路（流式 + 工具循环）；capability 决定用哪个档案 */
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
     * 路由用户消息。识屏关键词本地直连；其余一律进对话回路，
     * 由主模型决定是否调用工具、调用哪个。
     */
    suspend fun route(
        text: String,
        memoryText: String? = null,
        history: List<ChatMessage> = emptyList(),
        diaryTags: List<String> = emptyList(),
        preferVision: Boolean = false,
        bufferText: String? = null,
        /** false = 跳过识屏关键词本地直连（定时对话等后台消息不该触发授权流程） */
        allowKeywordRoute: Boolean = true
    ): AgentResult {
        val keyword = if (allowKeywordRoute) intentRouter.keywordRoute(text) else null
        if (keyword != null) return AgentResult.Command(keyword)
        return chatRequested(text, memoryText, history, diaryTags, preferVision, bufferText)
    }

    /** 当前「识屏（视觉）」档案是否勾选了"支持图片输入" */
    suspend fun visionModelSupportsImages(): Boolean =
        providerRegistry.profileFor(Capability.VISION)?.supportsVision == true

    /**
     * 按目标档案能力处理历史消息：
     * - 不支持图片 → 历史图片换文字占位（当前用户消息的图片保留）；
     * - 非 DeepSeek 官方端点 → 去掉 reasoning_content（它只对 DeepSeek 思考回放有意义，
     *   发给不认识该字段的兼容网关可能 400）。
     */
    suspend fun messagesForCapability(
        messages: List<ChatMessage>,
        capability: Capability
    ): List<ChatMessage> {
        val profile = providerRegistry.profileFor(capability)
        val supports = profile?.supportsVision == true
        val preserveReasoning = profile?.isOfficialDeepSeekEndpoint() == true
        val noReasoning = if (preserveReasoning) messages else messages.map { msg ->
            if (msg.reasoningContent == null) msg else msg.copy(reasoningContent = null)
        }
        return if (supports) noReasoning else stripHistoricalImages(noReasoning)
    }

    /**
     * 静态前缀指纹（系统提示词 / 工具原则+标签 / 工具 schema / 记忆 / 状态块）。
     * 工具 schema 变化与提示词变化一样会让厂商缓存失效，需要窗口回落一次。
     */
    suspend fun cachePrefixSignature(
        memoryText: String?,
        diaryTags: List<String>,
        bufferText: String? = null
    ): String = promptBuilder.prefixSignature(
        memoryText,
        toolRegistry.schemaSignature(),
        diaryTags,
        bufferText
    )

    /**
     * 一次性非流式对话请求（上下文整理压缩轮用）。
     * 与主回路一样携带原生工具声明；返回 tool_calls 与 reasoning_content。
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
            messages = messagesForCapability(messages, capability),
            temperature = 0.2,
            maxTokens = maxTokens,
            reasoningEffort = effort,
            tools = toolRegistry.definitions(),
            toolChoice = "auto"
        )
        val header = providerRegistry.authHeader(profile.apiKey)
        val response = providerRegistry.chatCompat(profile, request, header, api)
        val message = response.choices.firstOrNull()?.message
        return OnceResult(
            text = message?.textContent.orEmpty(),
            toolCalls = message?.toolCalls.orEmpty(),
            reasoningContent = message?.reasoningContent,
            usage = response.usage
        )
    }

    /** 一次性请求的结果：正文 + 原生工具调用 + 思考回放 + 用量 */
    data class OnceResult(
        val text: String,
        val toolCalls: List<ChatToolCall> = emptyList(),
        val reasoningContent: String? = null,
        val usage: Usage? = null
    )

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
            diaryTags = diaryTags,
            bufferText = bufferText
        )
        val out = messagesForCapability(messages, capability)
        return AgentResult.ChatRequested(out, capability)
    }

    /**
     * 把**非最后一条**用户消息里的图片 part 换成文字占位。
     * 最后一条用户消息 = 本轮输入，永远保留。
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

    /** 一次调用的解析/校验结果（给 UI 生成 label，也给执行层复用） */
    private data class PlannedCall(
        val rawId: String,
        val toolName: String,
        val tool: AssistantTool?,
        val args: JsonObject?,
        val parseError: String?,
        val label: String
    )

    /**
     * 主模型驱动的原生工具回路：
     *
     *   流式收集 content + reasoning_content + tool_calls
     *     ├─ tool_calls 非空 → 每个调用执行 → assistant(tool_calls) + role=tool 结果回传
     *     └─ 没有 tool_calls → 正式回答结束
     *
     * 最多 [ToolRegistry.MAX_TOOL_ROUNDS] 个工具轮，guard 双保险；
     * 只使用结构化 tool_calls，不解析正文协议。
     */
    fun chatReplyFlow(
        baseMessages: List<ChatMessage>,
        capability: Capability = Capability.CHAT
    ): Flow<ReplyEvent> = flow {
        var messages = messagesForCapability(baseMessages, capability)
        var forcedFinal = false
        var toolRounds = 0
        val finalized = StringBuilder()
        val usedToolNames = LinkedHashSet<String>()
        val usedLabels = LinkedHashSet<String>()
        val exchanges = mutableListOf<ToolExchange>()
        val successMemo = HashMap<String, String>()
        var usageSum: Usage? = null
        var requests = 0
        var currentCapability = capability

        fun absorbProse(prose: String) {
            val p = toolRegistry.stripMachineLines(prose)
            if (p.isEmpty()) return
            if (finalized.isNotEmpty()) finalized.append("\n\n")
            finalized.append(p)
        }

        var guard = 0
        while (true) {
            if (++guard > GUARD_LIMIT) break

            // 目标档案能力可能因 read_diary_image 切换到视觉档案；每次请求前统一裁剪图片/推理字段
            messages = messagesForCapability(messages, currentCapability)

            val assembler = NativeToolCallAssembler().apply { reset() }
            val contentAcc = StringBuilder()
            val reasoningAcc = StringBuilder()
            var released = false
            var reqUsage: Usage? = null
            requests++

            chatStream(messages, currentCapability).collect { chunk ->
                chunk.usage?.let { reqUsage = it }
                val delta = chunk.choices.firstOrNull()?.delta
                val textDelta = delta?.textContent.orEmpty()
                val thinkDelta = delta?.reasoningContent.orEmpty()
                assembler.acceptAll(delta?.toolCalls)
                if (textDelta.isNotEmpty()) contentAcc.append(textDelta)
                if (thinkDelta.isNotEmpty()) reasoningAcc.append(thinkDelta)

                if (!released) {
                    if (ProtocolLeakFilter.shouldWithhold(contentAcc.toString())) {
                        // 开头疑似协议标记：暂不上屏（思考仍可显示）
                        if (thinkDelta.isNotEmpty()) emit(ReplyEvent.Delta("", thinkDelta))
                    } else {
                        released = true
                        emit(ReplyEvent.Delta(contentAcc.toString(), thinkDelta))
                    }
                } else {
                    emit(ReplyEvent.Delta(textDelta, thinkDelta))
                }
            }
            usageSum = accumulateUsage(usageSum, reqUsage)

            val rawContent = contentAcc.toString()
            val cleanContent = ProtocolLeakFilter.strip(rawContent)
            val batch = assembler.finish()

            // 缺 id/name、重复 id：无法生成配对的 tool 结果，绝不猜测执行
            if (batch.calls.isNotEmpty() && !batch.canExecute) {
                throw IllegalStateException(
                    "模型返回了不完整的工具调用（" + batch.protocolErrors.joinToString("；") + "），已中止本轮。请重试。"
                )
            }

            val act = !forcedFinal && toolRounds < ToolRegistry.MAX_TOOL_ROUNDS &&
                batch.canExecute
            if (!act) {
                // 已到上限/强制收尾后模型仍请求工具：不执行，保留它的正文（如果有）
                if (batch.hasCalls && cleanContent.isBlank()) {
                    throw IllegalStateException("工具调用次数已达上限，但模型没有给出最终回答。请再发一次消息。")
                }
                if (ProtocolLeakFilter.hasLeak(rawContent) && cleanContent.isBlank() && !batch.hasCalls) {
                    throw IllegalStateException(
                        "模型返回了无法识别的工具协议标记，已阻止其显示且未执行。请重试。"
                    )
                }
                absorbProse(cleanContent)
                emit(ReplyEvent.RoundSettled(toolRegistry.stripMachineLines(cleanContent)))
                emit(finish(finalized, usedToolNames, usedLabels, exchanges, usageSum, requests))
                return@flow
            }

            toolRounds++
            val roundProse = toolRegistry.stripMachineLines(cleanContent).trim()
            absorbProse(roundProse)
            emit(ReplyEvent.RoundSettled(roundProse))

            val planned = batch.calls.map { call ->
                val tool = toolRegistry.find(call.name)
                val args = call.parsedArgs()
                val parseError = when {
                    tool == null -> "未知工具：${call.name}"
                    args == null -> "参数不是合法 JSON 对象：${call.arguments.take(300)}"
                    else -> null
                }
                val label = when {
                    tool == null -> "未知工具 ${call.name}"
                    args == null -> "${call.name}（参数错误）"
                    else -> try {
                        tool.actionLabel(args)
                    } catch (_: Exception) {
                        tool.name
                    }
                }
                PlannedCall(call.id, call.name, tool, args, parseError, label)
            }
            emit(ReplyEvent.ToolsRunning(planned.map { it.label }))

            val assistantMessage = ChatMessage(
                role = "assistant",
                content = listOf(ContentPart.text(cleanContent)),
                reasoningContent = replayReasoningFor(currentCapability, reasoningAcc.toString()),
                toolCalls = batch.calls.map { call ->
                    ChatToolCall(
                        id = call.id,
                        type = "function",
                        function = FunctionCall(name = call.name, arguments = call.arguments)
                    )
                }
            )
            messages = messages + assistantMessage

            val replays = mutableListOf<ToolReplay>()
            val roundMemo = HashMap<String, ToolOutcome>()
            val roundImages = LinkedHashSet<String>()

            planned.forEach { p ->
                val outcome: ToolOutcome = when {
                    p.parseError != null -> ToolOutcome.Failure(p.parseError)
                    p.tool == null || p.args == null -> ToolOutcome.Failure("工具调用无法执行")
                    else -> {
                        val sig = p.toolName + "|" + p.args.toString()
                        val canReuse = !p.tool.readOnly
                        val cachedFeedback = if (canReuse) successMemo[sig] else null
                        val roundCached = if (canReuse) roundMemo[sig] else null
                        when {
                            cachedFeedback != null -> ToolOutcome.Success(
                                cachedFeedback + "\n（与此前一次调用参数完全相同，以上为复用的结果）"
                            )
                            roundCached is ToolOutcome.Success -> ToolOutcome.Success(
                                roundCached.feedback +
                                    "\n（本轮重复调用参数完全相同，以上为复用的结果）"
                            )
                            roundCached is ToolOutcome.Failure -> ToolOutcome.Failure(
                                roundCached.error +
                                    "\n（本轮重复调用参数完全相同，以上为复用的错误）"
                            )
                            else -> {
                                val o = toolRegistry.execute(p.tool, p.args)
                                if (o is ToolOutcome.Success && !p.tool.readOnly) {
                                    successMemo[sig] = o.feedback
                                }
                                if (!p.tool.readOnly) roundMemo[sig] = o
                                o
                            }
                        }
                    }
                }
                when (outcome) {
                    is ToolOutcome.Success -> {
                        usedToolNames += p.toolName
                        usedLabels += p.label
                        outcome.images.filter { it.isNotBlank() }.forEach { roundImages += it }
                        replays += ToolReplay(
                            message = ChatMessage(
                                role = "tool",
                                content = listOf(ContentPart.text(outcome.feedback)),
                                toolCallId = p.rawId
                            )
                        )
                    }
                    is ToolOutcome.Failure -> {
                        replays += ToolReplay(
                            message = ChatMessage(
                                role = "tool",
                                content = listOf(ContentPart.text("错误：" + outcome.error)),
                                toolCallId = p.rawId
                            )
                        )
                    }
                }
            }

            // 所有工具结果先配对完成；图片作为额外 user 消息注入，后续请求切到视觉档案
            replays.forEach { messages = messages + it.message }
            if (roundImages.isNotEmpty()) {
                currentCapability = Capability.VISION
                val parts = ArrayList<ContentPart>(roundImages.size + 1)
                parts += ContentPart.text(IMAGE_INJECTION_PREFIX)
                roundImages.forEach { path -> Session.imagePartOf(path)?.let { parts += it } }
                val imageMsg = ChatMessage("user", parts)
                messages = messages + imageMsg
                replays += ToolReplay(imageMsg, roundImages.toList())
            }

            exchanges += ToolExchange(assistantMessage, replays.toList())

            if (toolRounds >= ToolRegistry.MAX_TOOL_ROUNDS && !forcedFinal) {
                messages += ChatMessage("user", ToolRegistry.FORCED_FINAL_NOTE)
                forcedFinal = true
            }
        }

        emit(finish(finalized, usedToolNames, usedLabels, exchanges, usageSum, requests))
    }

    /** DeepSeek 官方端点的工具轮必须回传 reasoning_content；其他端点不发 */
    private suspend fun replayReasoningFor(capability: Capability, reasoning: String): String? {
        if (reasoning.isBlank()) return null
        val profile = providerRegistry.profileFor(capability) ?: return null
        return reasoning.takeIf { profile.isOfficialDeepSeekEndpoint() }
    }

    /**
     * 组装最终回答：正文 + 已执行动作页脚。
     * answer 给界面；body 给会话历史（不含页脚）。
     */
    private fun finish(
        finalized: StringBuilder,
        usedToolNames: Set<String>,
        usedLabels: Set<String>,
        exchanges: List<ToolExchange>,
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

    /** 发送流式对话请求（原生 tools/tool_calls；工具回路由 chatReplyFlow 编排） */
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
            // 8192：推理模型思考也吃这个配额，且多轮工具场景回答更长
            maxTokens = CHAT_MAX_TOKENS,
            stream = true,
            reasoningEffort = effort,
            streamOptions = StreamOptions(includeUsage = true),
            tools = toolRegistry.definitions(),
            toolChoice = "auto"
        )
        val header = providerRegistry.authHeader(profile.apiKey)
        return flow {
            val response = providerRegistry.chatStreamCompat(profile, request, header, api)
            emitAll(ChatStream.parse(response.body()!!))
        }
    }

    /** 测试连接：对指定档案发一个最小请求验证配置是否正确（含原生 tools 参数探测） */
    suspend fun testConnection(profile: ProviderProfile): Result<String> {
        if (profile.supportsAudio) return testAudioConnection(profile)
        return try {
            val api = providerRegistry.apiFor(profile)
            val effort = providerRegistry.reasoningEffortFor(profile)
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(ChatMessage("user", "你好，请回复\"连接成功\"四个字")),
                maxTokens = 512,
                reasoningEffort = effort,
                tools = listOf(toolProbeDefinition()),
                toolChoice = "auto"
            )
            val header = providerRegistry.authHeader(profile.apiKey)
            val response = providerRegistry.chatCompat(profile, request, header, api)
            val message = response.choices.firstOrNull()?.message
            val reply = message?.textContent?.takeIf { it.isNotBlank() }
                ?: if (!message?.toolCalls.isNullOrEmpty()) "模型返回了工具调用（未执行）" else null
                ?: return Result.failure(IllegalStateException("响应中没有内容"))
            Result.success("$reply（原生工具调用可用）")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 仅供测试连接探测 tools 参数是否被接受；不执行、不影响主工具列表 */
    private fun toolProbeDefinition(): ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = "assistant_capability_probe",
            description = "Internal capability probe. Do not call in normal replies.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
                put("additionalProperties", false)
            }
        )
    )

    /**
     * 语音识别档案的连接测试：上传 0.1 秒静音 WAV 打真实的转写接口。
     */
    private suspend fun testAudioConnection(profile: ProviderProfile): Result<String> {
        val data = ByteArray(1600 * 2 + 44)
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

        /** 单轮对话回复的输出配额（推理模型的思考过程也占这个配额） */
        private const val CHAT_MAX_TOKENS = 8192

        /** 界面上的系统页脚前缀（不进会话历史；正文里出现即视为模型仿写） */
        const val FOOTER_PREFIX = "🔧 已执行："

        /** 目标模型不支持图片时，历史图片替换成的文字占位 */
        const val HISTORICAL_IMAGE_PLACEHOLDER = "[（历史图片：当前模型不支持图片输入，已省略）]"

        /** 工具注入图片时的说明文本（不把图片和 tool 结果混在同一条消息里） */
        const val IMAGE_INJECTION_PREFIX =
            "以下是工具刚刚返回的图片（已附在本条消息里）。请直接看图回答，不要在回复里复述这段说明。"

        /** 带图轮报错时的排查提示 */
        const val IMAGE_MODEL_GUIDE =
            "💡 排查：带图对话用的是「设置 → 模型配置 → 能力指派 → 识屏（视觉）」指派的档案。" +
                "请确认它就是支持图片输入的模型（如通义 qwen-vl、智谱 GLM-4V、Kimi vision、gpt-4o 等），" +
                "并在编辑该提供商时打开「支持图片输入」开关——建议把「对话」与「识屏」指派成同一个" +
                "带图模型，这样两种轮次共用同一套提示词缓存。"
    }
}

/**
 * 累计本轮的用量（整轮合计）：工具回路里一轮对话可能发出多次请求，
 * 界面缓存命中率要按整轮算。
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
