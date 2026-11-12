package com.example.assistant.core.agent

/**
 * 工具协议标记泄漏防护。
 *
 * 原生 Function Calling 下，调用应只出现在结构化 tool_calls 里；但个别模型/中转站
 * 仍可能把 DSML、XML 或旧项目的中文标记漏进正文。这里只做“防泄漏与清理”，
 * **不把这些标记当成新的调用语法执行**。
 */
object ProtocolLeakFilter {

    private val MARKERS = listOf(
        "[调用]",
        "［调用］",
        "<｜dsml｜",
        "<|dsml|",
        "<｜｜dsml｜｜",
        "<|tool▁",
        "<|tool_call",
        "<|tool_calls_section_begin|>",
        "<|tool_calls_section_end|>",
        "<function_calls>",
        "<function_call>",
        "<tool_call>",
        "<tool_calls>",
        "<|python_tag|>",
        "<|im_start|>tool"
    )

    /** 完整的块级泄漏：优先整段剥掉，避免把里面的 JSON/参数当正文展示 */
    private val BLOCK_PATTERNS = listOf(
        Regex("""(?is)<[｜|]{0,2}\s*dsml\s*[｜|]{0,2}\s*tool_calls.*?</[｜|]{0,2}\s*dsml\s*[｜|]{0,2}\s*tool_calls\s*>"""),
        Regex("""(?is)<[｜|]{0,2}\s*dsml\s*[｜|]{0,2}\s*calls.*?</[｜|]{0,2}\s*dsml\s*[｜|]{0,2}\s*calls\s*>"""),
        Regex("""(?is)<[｜|]{0,2}\s*dsml\s*[｜|]{0,2}\s*invoke\b.*?</[｜|]{0,2}\s*dsml\s*[｜|]{0,2}\s*invoke\s*>"""),
        Regex("""(?is)<function_calls>.*?</function_calls>"""),
        Regex("""(?is)<function_call>.*?</function_call>"""),
        Regex("""(?is)<tool_calls>.*?</tool_calls>"""),
        Regex("""(?is)<tool_call>.*?</tool_call>""")
    )

    /** 完整的 `<|...|>` / `<｜...｜>` 特殊 token（含续片残留，如 `<|tool▁calls▁begin|>`） */
    private val SPECIAL_TOKEN = Regex("""<[ \t]*[｜|]{1,2}[^>\n]{0,160}[｜|]{1,2}[ \t]*>""")

    /** 开头的旧调用行（含参数续行直到下一条空行/结尾；至少隐藏这一行本身） */
    private val LEGACY_CALL_ANYWHERE = Regex("""[\[［][ \t]*调[ \t]*用[ \t]*[\]］].*$""")

    /**
     * 流式期间是否暂缓上屏。
     * - 开头空白 → 暂缓（等第一个字符再判断）；
     * - 当前累计是任一标记的前缀 → 暂缓；
     * - 已经完整出现标记 → 暂缓（该轮完整结束后统一剥离；正常正文不会以这些标记开头）。
     */
    fun shouldWithhold(acc: String): Boolean {
        val t = acc.trimStart()
        if (t.isEmpty()) return true
        val lower = t.lowercase()
        if (lower.startsWithAny(MARKERS)) return true
        // 可能是标记的一部分（如已收到 "<｜DSM"）
        return MARKERS.any { marker -> marker.startsWith(lower) }
    }

    fun hasLeak(text: String): Boolean {
        val lower = text.lowercase()
        return lower.containsAny(MARKERS) ||
            SPECIAL_TOKEN.containsMatchIn(text) ||
            BLOCK_PATTERNS.any { it.containsMatchIn(text) } ||
            text.lineSequence().any { LEGACY_CALL_ANYWHERE.containsMatchIn(it) }
    }

    /** 剥掉已知标记/块与旧调用行；不解析、不执行其中的内容 */
    fun strip(text: String): String {
        var out = text
        BLOCK_PATTERNS.forEach { pattern -> out = pattern.replace(out, "") }

        // 逐行清理残留的特殊 token / 旧调用行，并吞掉紧随其后的 JSON 参数续行。
        val kept = mutableListOf<String>()
        var skippingLegacyPayload = false
        out.lines().forEach { line ->
            val trimmed = line.trim()
            val tokenMatch = SPECIAL_TOKEN.find(line)
            val legacyMatch = LEGACY_CALL_ANYWHERE.find(line)
            if (legacyMatch != null || tokenMatch != null) {
                if (legacyMatch != null) {
                    // [调用] 后面的整行（通常就是 JSON 参数）都不要
                    val prefix = line.take(legacyMatch.range.first).trim()
                    if (prefix.isNotEmpty()) kept += prefix
                    skippingLegacyPayload = true
                } else if (tokenMatch != null) {
                    // 特殊 token 本身删掉；同一行 token 后的正常文字要保留，
                    // 若后面紧跟 JSON 参数则按 payload 吞掉。
                    val prefix = line.take(tokenMatch.range.first).trim()
                    val suffix = line.substring(tokenMatch.range.last + 1).trim()
                    if (prefix.isNotEmpty()) kept += prefix
                    if (suffix.isNotEmpty()) {
                        if (looksLikeJsonPayload(suffix)) {
                            skippingLegacyPayload = true
                        } else {
                            kept += suffix
                        }
                    }
                }
                return@forEach
            }
            if (skippingLegacyPayload) {
                if (looksLikeJsonPayload(trimmed)) {
                    if (trimmed.isEmpty()) skippingLegacyPayload = false
                    return@forEach
                }
                skippingLegacyPayload = false
            }
            kept += line
        }
        out = kept.joinToString("\n")

        // 残留的标记字面量（不区分大小写）
        MARKERS.forEach { marker ->
            out = out.replace(marker, "", ignoreCase = true)
        }
        return out.trim()
    }

    private fun String.startsWithAny(markers: List<String>): Boolean =
        markers.any { startsWith(it) }

    private fun String.containsAny(markers: List<String>): Boolean =
        markers.any { contains(it) }

    private fun looksLikeJsonPayload(line: String): Boolean {
        val t = line.trim()
        return t.isEmpty() || t.startsWith("{") || t.startsWith("[") ||
            t.startsWith("\"") || t.endsWith("}") || t.endsWith("]") ||
            t.startsWith("</") || t.startsWith("parameter") || t.startsWith("invoke")
    }
}
