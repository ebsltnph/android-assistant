package com.example.assistant.core.network

import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ChatResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * OpenAI 兼容的聊天接口（单一契约，所有功能复用）。
 * 流式与非流式共用一个端点，区别仅在请求的 stream 字段。
 */
interface OpenAiChatApi {

    @POST("chat/completions")
    suspend fun chat(
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): ChatResponse

    /** 流式：返回 SSE 原始流，由 [ChatStream] 逐行解析 */
    @POST("chat/completions")
    @retrofit2.http.Streaming
    suspend fun chatStream(
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): Response<ResponseBody>
}
