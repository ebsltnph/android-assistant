package com.example.assistant.core.ui

/**
 * 聊天消息富文本解析管线（纯 Kotlin，无 Android/Compose 依赖，可跑 JVM 单测）。
 *
 * 两步解析：
 * 1. [splitFormula] 先按公式分隔符把文本切成「公式区 / 非公式区」——
 *    保证公式内容（\textbf、_ 等）不会被后面的 Markdown 解析误判；
 * 2. [parseMarkdown] 对每个非公式区做基础 Markdown 解析（标题/列表/加粗/斜体/行内代码）。
 *
 * 样式用纯数据结构 [RunStyle] 描述，转 Compose SpanStyle 的映射在组件层
 * （RichMessageText.kt）完成——这样本文件可以在 JVM 单测里跑。
 */

/** 公式拆分结果：一段文本要么是非公式区（普通文字 + Markdown），要么是「闭合」的公式 */
sealed interface FormulaSegment {
    /** 非公式区：含普通文字与 Markdown 标记，交给 parseMarkdown 处理 */
    data class Plain(val text: String) : FormulaSegment

    /** 闭合的公式；raw 是含分隔符的原文切片（渲染失败时回退按原文显示用） */
    data class Math(val latex: String, val block: Boolean, val raw: String) : FormulaSegment
}

/** 文本段样式（纯数据描述，不含 Compose 类型） */
data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** 行内代码（等宽字体 + 浅底） */
    val code: Boolean = false,
    /** 标题级别 0=普通正文，1/2/3 级放大字号加粗 */
    val headingLevel: Int = 0,
)

/** 富文本解析产物：一条消息文本 → 若干 token（文本段 / 公式段） */
sealed interface RichToken {
    /** 带样式的文本段 */
    data class Text(val text: String, val style: RunStyle) : RichToken

    /** 公式段；渲染失败时用 raw（含分隔符原文）当普通文本显示 */
    data class Math(val latex: String, val block: Boolean, val raw: String) : RichToken
}

/**
 * 第一步：公式识别。
 * 识别 $$...$$、\[...\]（块级）、$...$、\(...\)（行内）。
 * 只认「闭合」的分隔对，未闭合的留在 Plain 里当普通文本（流式期间安全）；
 * 被反斜杠转义的（\$）不算公式起点。
 */
fun splitFormula(text: String): List<FormulaSegment> {
    val result = mutableListOf<FormulaSegment>()
    val plain = StringBuilder()
    var i = 0
    while (i < text.length) {
        // 被反斜杠转义的字符（如 \$、\\(）一律按普通文本累积，不进公式判断
        if (isEscaped(text, i)) {
            plain.append(text[i]); i++; continue
        }
        val c = text[i]
        when {
            c == '$' -> {
                val isBlock = i + 1 < text.length && text[i + 1] == '$'
                val openerLen = if (isBlock) 2 else 1
                val end = findClosing(text, i + openerLen, if (isBlock) "$$" else "$")
                if (end >= 0) {
                    flushPlain(plain, result)
                    result += FormulaSegment.Math(
                        latex = text.substring(i + openerLen, end).trim(),
                        block = isBlock,
                        raw = text.substring(i, end + openerLen)
                    )
                    i = end + openerLen
                } else {
                    plain.append(c); i++
                }
            }

            c == '\\' && i + 1 < text.length && (text[i + 1] == '(' || text[i + 1] == '[') -> {
                val isBlock = text[i + 1] == '['
                val closeChar = if (isBlock) ']' else ')'
                val end = findClosing(text, i + 2, "\\$closeChar")
                if (end >= 0) {
                    flushPlain(plain, result)
                    result += FormulaSegment.Math(
                        latex = text.substring(i + 2, end).trim(),
                        block = isBlock,
                        raw = text.substring(i, end + 2)
                    )
                    i = end + 2
                } else {
                    plain.append(c); i++
                }
            }

            else -> {
                plain.append(c); i++
            }
        }
    }
    flushPlain(plain, result)
    return result
}

/**
 * 第二步：Markdown 解析（只处理非公式区，逐行）。
 * 行级：'# / ## / ### ' 标题（加粗 + 放大字号）、'- ' 无序列表（前缀换成 '•  '）、
 *       '数字. ' 有序列表（保留原文编号，流式期间即时显示）；
 * 行内：**加粗**、*斜体*、`行内代码`（等宽 + 浅底）。
 * 未闭合的标记一律按普通文本；不在清单内的字符原样保留（含换行）。
 */
fun parseMarkdown(text: String): List<RichToken.Text> {
    val out = mutableListOf<RichToken.Text>()
    val lines = text.split("\n")
    for ((idx, line) in lines.withIndex()) {
        val notLast = idx < lines.lastIndex
        // 标题（# / ## / ###）
        val heading = when {
            line.startsWith("### ") -> 3
            line.startsWith("## ") -> 2
            line.startsWith("# ") -> 1
            else -> 0
        }
        if (heading > 0) {
            out += parseInline(line.removePrefix("#".repeat(heading)).removePrefix(" "), RunStyle(headingLevel = heading))
            if (notLast) out += RichToken.Text("\n", RunStyle())
            continue
        }
        // 无序列表：前缀换成圆点
        if (line.startsWith("- ")) {
            out += RichToken.Text("•  ", RunStyle())
            out += parseInline(line.removePrefix("- "), RunStyle())
            if (notLast) out += RichToken.Text("\n", RunStyle())
            continue
        }
        // 有序列表：保留原文编号
        val ordered = ORDERED_LINE.find(line)
        if (ordered != null) {
            out += RichToken.Text("${ordered.groupValues[1]}. ", RunStyle())
            out += parseInline(ordered.groupValues[2], RunStyle())
            if (notLast) out += RichToken.Text("\n", RunStyle())
            continue
        }
        out += parseInline(line, RunStyle())
        if (notLast) out += RichToken.Text("\n", RunStyle())
    }
    return out
}

/** 两步合并：文本 → token 列表（供组件层 buildRichText 消费） */
fun parseRichText(text: String): List<RichToken> {
    val out = mutableListOf<RichToken>()
    for (seg in splitFormula(text)) {
        when (seg) {
            is FormulaSegment.Plain -> out += parseMarkdown(seg.text)
            is FormulaSegment.Math -> out += RichToken.Math(seg.latex, seg.block, seg.raw)
        }
    }
    return out
}

// ---------- 内部辅助 ----------

private val ORDERED_LINE = Regex("^(\\d+)\\.\\s+(.*)$")

/** 判断 text 下标 i 处的字符是否被反斜杠转义（i 前连续 \ 为奇数个即被转义） */
private fun isEscaped(text: String, i: Int): Boolean {
    var j = i - 1
    var backslashes = 0
    while (j >= 0 && text[j] == '\\') {
        backslashes++
        j--
    }
    return backslashes % 2 == 1
}

/** 从 start 开始找「未转义」的闭合分隔符 closer，返回其起始下标；找不到返回 -1 */
private fun findClosing(text: String, start: Int, closer: String): Int {
    var j = start
    while (j < text.length) {
        if (text.startsWith(closer, j) && !isEscaped(text, j)) return j
        j++
    }
    return -1
}

private fun flushPlain(plain: StringBuilder, result: MutableList<FormulaSegment>) {
    if (plain.isNotEmpty()) {
        result += FormulaSegment.Plain(plain.toString())
        plain.clear()
    }
}

/**
 * 行内解析：**加粗**、*斜体*、`行内代码`。
 * ** 优先于 * 配对；未闭合的标记按普通文本；只做单层不递归（嵌套按字面量）。
 * 返回的每段文本都带 base 样式（标题/列表已由调用方把行级样式合进 base）。
 */
private fun parseInline(line: String, base: RunStyle): List<RichToken.Text> {
    val out = mutableListOf<RichToken.Text>()
    val sb = StringBuilder()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            // ** 加粗
            c == '*' && i + 1 < line.length && line[i + 1] == '*' -> {
                val end = line.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush(sb, out, base)
                    out += RichToken.Text(line.substring(i + 2, end), base.copy(bold = true))
                    i = end + 2
                } else {
                    sb.append(c); i++
                }
            }

            // * 斜体
            c == '*' -> {
                val end = line.indexOf('*', i + 1)
                if (end > i + 1) {
                    flush(sb, out, base)
                    out += RichToken.Text(line.substring(i + 1, end), base.copy(italic = true))
                    i = end + 1
                } else {
                    sb.append(c); i++
                }
            }

            // ` 行内代码
            c == '`' -> {
                val end = line.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush(sb, out, base)
                    out += RichToken.Text(line.substring(i + 1, end), base.copy(code = true))
                    i = end + 1
                } else {
                    sb.append(c); i++
                }
            }

            else -> {
                sb.append(c); i++
            }
        }
    }
    flush(sb, out, base)
    return out
}

private fun flush(sb: StringBuilder, out: MutableList<RichToken.Text>, style: RunStyle) {
    if (sb.isNotEmpty()) {
        out += RichToken.Text(sb.toString(), style)
        sb.clear()
    }
}
