package com.example.assistant.core.network

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 提供商档案：一组（base URL + API Key + 模型）的配置。
 * 对话、识屏（视觉）、意图分类三个能力可各自指派一个档案。
 * 存于 SecretStore（加密），绝不打日志。
 */
@Serializable
data class ProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,               // 显示名，如 "DeepSeek"
    val baseUrl: String,            // 如 https://api.deepseek.com（注意：不含 /v1 尾缀，拼接时统一处理）
    val apiKey: String = "",
    val model: String = "",
    val supportsVision: Boolean = false, // 是否支持图片输入（识屏需要）
    val isDefault: Boolean = false,
    // 思考深度（per-provider）："default" | "low" | "medium" | "high"
    // （OpenAI 通用参数 reasoning_effort；"default" 跟随模型默认并用旧全局设置兜底，
    //   见 ProviderRegistry.reasoningEffortFor）
    // thinkingMode 字段已废弃（2026-08-07 删思考开关——DeepSeek 专属参数不通用），
    // 保留字段仅为兼容旧 JSON 反序列化，不再读写
    val thinkingMode: String = "default",
    val reasoningEffort: String = "default"
) {
    /** 规范化 baseUrl：去掉末尾斜杠，确保以 /v1 结尾（OpenAI 兼容端点惯例） */
    fun normalizedBaseUrl(): String {
        var url = baseUrl.trim().trimEnd('/')
        if (!url.endsWith("/v1") && !url.endsWith("/v1beta")) {
            url += "/v1"
        }
        return url
    }

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** 三种能力，设置页可分别指派档案 */
enum class Capability(val displayName: String, val description: String) {
    CHAT("对话", "日常问答使用的模型"),
    VISION("识屏（视觉）", "截屏分析、OCR、翻译，需要支持图片输入的模型"),
    CLASSIFY("意图分类", "判断用户意图的轻量模型（默认复用对话档案）")
}
