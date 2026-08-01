package com.example.assistant.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型 JSON 输出解析工具。
 *
 * 背景：deepseek-v4-flash 对 response_format=json_object 支持不稳定（曾输出
 * 自相矛盾的 JSON、或思考吃光配额返回空 content），因此各 JSON 组件统一
 * **不用 json_object 模式**，改用纯提示词约束 + 这里的健壮解析：
 * 剥 ```json 围栏 → 提取 {…} 子串 → 手动字段提取（容忍 null/字符串数字）。
 */
object JsonExtract {

    val json = Json { ignoreUnknownKeys = true }

    /** 从模型输出中提取 JSON 对象子串（容忍围栏与前后废话） */
    fun extractObject(text: String): String {
        val t = text.trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        return if (start >= 0 && end > start) t.substring(start, end + 1) else t
    }

    /** 提取字段为字符串（数字/字符串/布尔都转成字符串；null 返回 null） */
    fun str(obj: JsonObject, key: String): String? =
        obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    /** 提取字段为 Long（容忍数字或字符串数字） */
    fun long(obj: JsonObject, key: String): Long? =
        str(obj, key)?.toLongOrNull()

    /** 提取字段为 Int（容忍数字或字符串数字） */
    fun int(obj: JsonObject, key: String): Int? =
        str(obj, key)?.toIntOrNull()

    /** 提取字段为布尔（容忍 true/"true"/1） */
    fun bool(obj: JsonObject, key: String): Boolean =
        str(obj, key)?.let { it == "true" || it == "1" } ?: false

    /** 解析为 JsonObject；失败返回 null */
    fun objectOf(text: String): JsonObject? =
        try {
            json.parseToJsonElement(extractObject(text)).jsonObject
        } catch (e: Exception) {
            null
        }
}
