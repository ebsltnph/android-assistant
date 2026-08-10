package com.example.assistant.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 富文本消息组件（数学公式 + 基础 Markdown）。
 *
 * 聊天页 Card 气泡与悬浮球面板玻璃气泡共用，替换原来的纯 Text：
 * - **行内公式**（$…$ / \(…\)）→ jlatexmath 渲染成位图，通过 inlineContent 内嵌进文本流
 *   （已验证能正常显示）；
 * - **块级公式**（$$…$$ / \[…\]）→ 渲染成位图**独立一行居中显示**（不内嵌文本行内——
 *   块级公式位图高达百 dp，塞进 inlineContent 会撑爆文本行高导致空白，见 v1.2.3 渲染坑）；
 * - 加粗/斜体/行内代码/标题/列表 → SpanStyle（零成本）。
 *
 * 流式策略：remember 用「原始值」作 key（text/streaming/颜色/字号/密度/宽度）；
 * 同一公式位图走 [MathRenderer] 的 LRU 缓存，流式期间只真正渲染一次。
 *
 * 注意：复制/重做走 ChatUiMessage.text 原文（含 LaTeX 源与 ** 标记），不受本组件影响。
 */
@Composable
fun RichMessageText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    streaming: Boolean = false,
    cursor: String = "▍",
) {
    if (text.isEmpty() && !streaming) return

    val density = LocalDensity.current
    // 颜色：style 显式指定（面板白 92%）优先；否则跟随 LocalContentColor
    val baseColor = if (style.color != Color.Unspecified) style.color else LocalContentColor.current
    val baseFontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize else 14.sp

    BoxWithConstraints(modifier) {
        val maxWidthPx = constraints.maxWidth
        // Density.density 是设备密度（如 3.0 = 480dpi），*160 得 dpi 整数值
        val densityDpi = (density.density * 160f).toInt()
        // key 全部用值类型；style 对象本身不放进来（每帧新建，放了缓存全废）
        val blocks = remember(text, streaming, baseColor, baseFontSize, density.density, densityDpi, maxWidthPx) {
            val textSizePx = with(density) { baseFontSize.toPx() }
            buildRichBlocks(
                tokens = parseRichText(text),
                baseStyle = style,
                baseColorArgb = baseColor.toArgb(),
                textSizePx = textSizePx,
                densityDpi = densityDpi,
                maxWidthPx = maxWidthPx,
                density = density,
            )
        }
        Column(modifier) {
            for (b in blocks) {
                when (b) {
                    is RichBlock.Text -> {
                        Text(
                            text = b.annotated,
                            style = style,
                            inlineContent = b.inlineContent,
                        )
                    }

                    is RichBlock.Math -> {
                        // 块级公式：独立一行，按位图比例显示，水平居中
                        Image(
                            bitmap = b.imageBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .wrapContentWidth(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
            // 光标 ▍ 在最后追加，永不进入公式/标记
            if (streaming) Text(cursor, style = style)
        }
    }
}

/** 富文本块：文本段（可含行内公式）或独立块级公式 */
internal sealed interface RichBlock {
    /** 文本段：普通文本 + 行内公式（inlineContent 内嵌） */
    data class Text(
        val annotated: AnnotatedString,
        val inlineContent: Map<String, InlineTextContent>,
    ) : RichBlock

    /** 块级公式：独立一行显示的位图 */
    data class Math(val imageBitmap: ImageBitmap) : RichBlock
}

/** token 列表 → 块序列（文本段 / 独立块级公式） */
internal fun buildRichBlocks(
    tokens: List<RichToken>,
    baseStyle: TextStyle,
    baseColorArgb: Int,
    textSizePx: Float,
    densityDpi: Int,
    maxWidthPx: Int,
    density: androidx.compose.ui.unit.Density,
): List<RichBlock> {
    val blocks = mutableListOf<RichBlock>()
    var builder = AnnotatedString.Builder()
    var inlineContent = mutableMapOf<String, InlineTextContent>()
    val baseSpan = baseStyle.toSpanStyle()
    var mathId = 0
    var prevBlockMath = false   // 上一个输出的是块级公式 → 下一个文本段开头换行要剥（块级公式独立一行，前后不留空行）

    /** 结束当前文本段：剥尾部换行后输出，并重建 builder（AnnotatedString.Builder 无 clear） */
    fun flushText() {
        var s = builder.toAnnotatedString()
        // 剥掉尾部连续换行：块级公式独立一行，前面的空行分隔不需要，避免上下大片空白
        var end = s.length
        while (end > 0 && s[end - 1] == '\n') end--
        if (end < s.length) s = s.subSequence(0, end)
        if (s.isNotEmpty()) {
            blocks += RichBlock.Text(s, inlineContent.toMap())
        }
        builder = AnnotatedString.Builder()
        inlineContent = mutableMapOf()
    }

    for (t in tokens) {
        when (t) {
            is RichToken.Text -> {
                var text = t.text
                // 块级公式后的文本段开头换行剥掉（源文本里 \[...\] 和正文之间有空行）
                if (prevBlockMath) text = text.trimStart('\n', '\r')
                prevBlockMath = false
                // RunStyle → SpanStyle（在 base 之上叠加差异），用 pushStyle/pop 应用
                val span = runStyleToSpan(t.style, baseSpan, baseColorArgb)
                if (span == baseSpan) {
                    builder.append(text)
                } else {
                    builder.pushStyle(span)
                    builder.append(text)
                    builder.pop()
                }
            }

            is RichToken.Math -> {
                if (t.block) {
                    // 块级公式：结束当前文本段，独立渲染成一行
                    flushText()
                    val bmp = MathRenderer.render(
                        latex = t.latex,
                        colorArgb = baseColorArgb,
                        textSizePx = textSizePx,
                        densityDpi = densityDpi,
                        maxWidthPx = maxWidthPx,
                        block = true,
                    )
                    if (bmp != null) {
                        blocks += RichBlock.Math(bmp.asImageBitmap())
                        prevBlockMath = true
                    } else {
                        // 渲染失败（非法 LaTeX）：回退显示原文（含分隔符）
                        builder.append(t.raw)
                    }
                } else {
                    // 行内公式：内嵌进文本流（小位图，placeholder 高度在行高内，正常显示）
                    val bmp = MathRenderer.render(
                        latex = t.latex,
                        colorArgb = baseColorArgb,
                        textSizePx = textSizePx,
                        densityDpi = densityDpi,
                        maxWidthPx = maxWidthPx,
                        block = false,
                    )
                    if (bmp != null) {
                        val id = "math_${mathId++}"
                        builder.appendInlineContent(id, t.raw)
                        inlineContent[id] = InlineTextContent(
                            Placeholder(
                                width = with(density) { bmp.width.toSp() },
                                height = with(density) { bmp.height.toSp() },
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) {
                            Image(
                                bitmap = remember(bmp) { bmp.asImageBitmap() },
                                contentDescription = null,
                            )
                        }
                    } else {
                        builder.append(t.raw)
                    }
                }
            }
        }
    }
    flushText()
    return blocks
}

/** RunStyle（纯数据）→ Compose SpanStyle，在 baseSpan 之上叠加差异 */
private fun runStyleToSpan(style: RunStyle, base: SpanStyle, baseColorArgb: Int): SpanStyle {
    var s = base
    if (style.bold) s = s.copy(fontWeight = FontWeight.Bold)
    if (style.italic) s = s.copy(fontStyle = FontStyle.Italic)
    if (style.code) {
        s = s.copy(
            fontFamily = FontFamily.Monospace,
            background = Color(baseColorArgb).copy(alpha = 0.12f),
        )
    }
    if (style.headingLevel > 0) {
        // 标题：加粗 + 放大字号（1/2/3 级固定 22/18/16.sp）
        val size = when (style.headingLevel) {
            1 -> 22.sp
            2 -> 18.sp
            else -> 16.sp
        }
        s = s.copy(fontWeight = FontWeight.Bold, fontSize = size)
    }
    return s
}
