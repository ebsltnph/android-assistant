package com.example.assistant.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 远程语音转文字客户端：OpenAI 兼容 /audio/transcriptions（Whisper 接口）。
 * 任何实现了该端点的官方/中转站服务都可用；WAV 文件上传，返回 JSON 里的 text。
 */
class AsrClient private constructor(private val http: OkHttpClient) {

    /** 识别结果：Text 成功 / Error 失败（message 面向用户可读） */
    sealed class Result {
        data class Text(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun transcribe(baseUrl: String, apiKey: String, model: String, wavFile: File): Result =
        withContext(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", wavFile.name, wavFile.asRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("model", model)
                    .addFormDataPart("language", "zh")   // 明确中文：短句自动检测语言常猜错
                    .addFormDataPart("response_format", "json")
                    .build()
                val req = Request.Builder()
                    .url(normalize(baseUrl) + "/audio/transcriptions")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Result.Error("识别请求失败 HTTP " + resp.code + "：" + text.take(160))
                    } else {
                        val t = extractTextField(text)
                        if (t.isNullOrBlank()) Result.Error("识别服务返回了空结果")
                        else Result.Text(t.trim())
                    }
                }
            } catch (e: Exception) {
                Result.Error("识别请求异常：" + (e.message ?: e.javaClass.simpleName))
            }
        }

    /** 规范化 baseUrl：去尾斜杠、补 /v1（与 ProviderProfile.normalizedBaseUrl 同规则） */
    private fun normalize(base: String): String {
        var u = base.trim().trimEnd('/')
        if (!u.endsWith("/v1") && !u.endsWith("/v1beta")) u += "/v1"
        return u
    }

    /** 从响应 JSON 里取 text 字段（手工扫描，容忍转义；避免引正则的转义麻烦） */
    private fun extractTextField(json: String): String? {
        val key = "\"text\""
        var i = json.indexOf(key)
        if (i < 0) return null
        i = json.indexOf(':', i + key.length)
        if (i < 0) return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val quote = 34.toChar()
        val bs = 92.toChar()
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == bs && i + 1 < json.length -> {
                    when (val n = json[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'r' -> { i += 2 }
                        else -> { sb.append(n); i += 2 }   // \" \\ / uXXXX 等一律按字面处理
                    }
                }
                c == quote -> return sb.toString()
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    companion object {
        fun create(): AsrClient = AsrClient(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        )
    }
}