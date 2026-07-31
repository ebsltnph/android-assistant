package com.example.assistant.core.network

import com.example.assistant.core.network.dto.ChatResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import java.io.BufferedReader

/**
 * SSE（Server-Sent Events）流式解析：
 * OpenAI 兼容协议返回形如
 *   data: {"choices":[{"delta":{"content":"你"}}]}
 *   data: [DONE]
 * 的逐行数据，这里把每行解析成 ChatResponse 增量，遇 [DONE] 结束。
 */
object ChatStream {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: ResponseBody): Flow<ChatResponse> = flow {
        body.use { b ->
            val reader: BufferedReader = b.byteStream().bufferedReader(Charsets.UTF_8)
            for (line in reader.lineSequence()) {
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                try {
                    emit(json.decodeFromString<ChatResponse>(payload))
                } catch (e: Exception) {
                    // 个别厂商可能在流中夹带非 JSON 行（如空行），跳过
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
