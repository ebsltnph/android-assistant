package com.example.assistant.core.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * OkHttp + Retrofit 构建。每个 provider 档案一个 Retrofit 实例（按 baseUrl 缓存），
 * 连接池复用，利于长连接与提示词缓存链路稳定。
 */
object ApiClient {

    private val json = Json {
        ignoreUnknownKeys = true   // 各家厂商响应字段有差异，忽略未知字段
        // 只在非默认值时写字段：避免每条消息都带 tool_calls:null / reasoning_content:null。
        // 必须随请求出现的常量字段（role/type/temperature 等）用 @EncodeDefault 强制写出。
        encodeDefaults = false
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 流式长连接需要较长读超时
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    fun create(baseUrl: String): OpenAiChatApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiChatApi::class.java)
}
