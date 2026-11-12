package com.example.assistant.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.io.IOException

/** 网络搜索/抓取的失败分类（用于给用户和模型不同、可行动的提示） */
enum class WebFailureKind {
    CONFIG,
    AUTH,
    PAYMENT,
    POLICY,
    NOT_FOUND,
    RATE_LIMIT,
    SERVER,
    NETWORK,
    INVALID_REQUEST,
    MALFORMED_RESPONSE,
    UNKNOWN
}

/**
 * 搜索/抓取统一异常。message 直接面向用户/模型：
 * 带提供商、操作、HTTP 状态和可行动建议，不包含 API Key。
 */
class WebApiException(
    val provider: WebProvider,
    val operation: String,
    val kind: WebFailureKind,
    val statusCode: Int? = null,
    val detail: String? = null,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null
) : Exception(buildMessage(provider, operation, kind, statusCode, detail), cause) {

    val retryable: Boolean
        get() = kind == WebFailureKind.RATE_LIMIT || kind == WebFailureKind.SERVER ||
            statusCode == 408 || (statusCode != null && statusCode in 500..599)

    companion object {
        private const val DETAIL_MAX = 400

        internal fun buildMessage(
            provider: WebProvider,
            operation: String,
            kind: WebFailureKind,
            statusCode: Int?,
            detail: String?
        ): String {
            val prefix = "${provider.displayName} $operation 失败"
            val status = statusCode?.let { "HTTP $it" }
            val reason = when (kind) {
                WebFailureKind.CONFIG -> "尚未配置可用的凭据"
                WebFailureKind.AUTH -> "鉴权失败，请检查 API Key"
                WebFailureKind.PAYMENT -> "账户余额不足，请充值或换回免费提供商"
                WebFailureKind.POLICY -> "请求被服务端策略拒绝（抓取页面可能不允许抓取）"
                WebFailureKind.NOT_FOUND -> "服务没有取到可用内容（页面可能不存在或无可提取正文）"
                WebFailureKind.RATE_LIMIT -> if (provider == WebProvider.TAVILY) {
                    "触发限流（keyless 模式额度有限），请稍后重试或在设置里填入免费 Tavily API Key"
                } else "触发限流，请稍后重试"
                WebFailureKind.SERVER -> "服务暂时不可用，请稍后重试"
                WebFailureKind.NETWORK -> "网络不可达或超时，请检查网络后重试"
                WebFailureKind.INVALID_REQUEST -> "请求参数不合法"
                WebFailureKind.MALFORMED_RESPONSE -> "服务返回了无法解析的响应"
                WebFailureKind.UNKNOWN -> "未知错误"
            }
            val head = listOfNotNull(prefix, status, reason).joinToString("：")
            val cleanDetail = detail?.trim()?.takeIf { it.isNotEmpty() }?.let(::cleanDetail)
            return if (cleanDetail.isNullOrBlank()) head else "$head（$cleanDetail）"
        }

        /** 去掉 HTML/换行噪声，限制长度；绝不回显 Authorization */
        private fun cleanDetail(raw: String): String {
            val lower = raw.lowercase()
            if (lower.startsWith("<!doctype") || lower.startsWith("<html")) {
                return "服务返回了 HTML 错误页"
            }
            return raw.replace(Regex("\\s+"), " ")
                .take(DETAIL_MAX)
                .ifBlank { "无错误详情" }
        }
    }
}

/** 设置页“测试搜索/抓取”的结果（不抛异常，便于直接展示） */
data class WebProbeResult(
    val success: Boolean,
    val message: String,
    val elapsedMs: Long
)

/** 把 HTTP 状态映射成可行动的错误分类（纯函数，便于单测） */
internal fun mapWebHttpFailure(
    provider: WebProvider,
    operation: String,
    code: Int,
    body: String?,
    retryAfter: String? = null
): WebApiException {
    val kind = when (code) {
        400, 422 -> WebFailureKind.INVALID_REQUEST
        401 -> WebFailureKind.AUTH
        402 -> WebFailureKind.PAYMENT
        403 -> WebFailureKind.POLICY
        404 -> WebFailureKind.NOT_FOUND
        408 -> WebFailureKind.NETWORK
        429 -> WebFailureKind.RATE_LIMIT
        in 500..599 -> WebFailureKind.SERVER
        else -> WebFailureKind.UNKNOWN
    }
    val retrySeconds = retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    return WebApiException(
        provider = provider,
        operation = operation,
        kind = kind,
        statusCode = code,
        detail = body,
        retryAfterSeconds = retrySeconds
    )
}

/** 任意底层异常 → WebApiException；协程取消必须原样抛出 */
internal fun webFailureOf(
    provider: WebProvider,
    operation: String,
    e: Throwable
): WebApiException {
    if (e is WebApiException) return e
    if (e is CancellationException) throw e
    if (e is HttpException) {
        val body = try {
            e.response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        return mapWebHttpFailure(
            provider, operation, e.code(), body,
            e.response()?.headers()?.get("Retry-After")
        )
    }
    if (e is IOException) {
        return WebApiException(
            provider = provider,
            operation = operation,
            kind = WebFailureKind.NETWORK,
            detail = e.message,
            cause = e
        )
    }
    if (e is kotlinx.serialization.SerializationException) {
        return WebApiException(
            provider = provider,
            operation = operation,
            kind = WebFailureKind.MALFORMED_RESPONSE,
            detail = e.message,
            cause = e
        )
    }
    return WebApiException(
        provider = provider,
        operation = operation,
        kind = WebFailureKind.UNKNOWN,
        detail = e.message ?: e.javaClass.simpleName,
        cause = e
    )
}

/**
 * 只读搜索/抓取的一次有界重试：408/429/5xx 最多重试一次。
 * 不重试鉴权、计费、参数错误；绝不切换提供商。
 */
internal suspend fun <T> withWebReadRetry(
    provider: WebProvider,
    operation: String,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: WebApiException) {
            if (!e.retryable || attempt >= 1) throw e
            attempt++
            // 尊重 Retry-After，但最多等 5 秒，避免工具回路长时间卡住
            val waitMs = ((e.retryAfterSeconds ?: 1L) * 1000L).coerceIn(300L, 5000L)
            delay(waitMs)
        }
    }
}
