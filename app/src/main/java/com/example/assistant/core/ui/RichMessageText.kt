package com.example.assistant.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.sp

/**
 * 富文本消息组件（数学公式 + 基础 Markdown）。
 *
 * 聊天页 Card 气泡与悬浮球面板玻璃气泡共用，替换原来的纯 Text：
 * - 公式（$...$ / $$...$$ / \(...\) / \[...\]）→ jlatexmath 渲染成位图，
 *   通过 AnnotatedString.appendInlineContent 内嵌进文本流；
 * - 加粗/斜体/行内代码/标题/列表 → SpanStyle 实现（零成本，不引入独立行组件）。
 *
 * 流式渲染策略：remember 用「原始值」作 key（text/streaming/颜色/字号/密度/宽度），
 * 面板侧每帧新建的 TextStyle 对象绝不放进 key（会废掉缓存）；
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
    // （Material3 Card 已按容器色注入 onSurface/onPrimaryContainer，正好是气泡文字色）
    val baseColor = if (style.color != Color.Unspecified) style.color else LocalContentColor.current
    val baseFontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize else 14.sp

    BoxWithConstraints(modifier) {
        val maxWidthPx = constraints.maxWidth
        // Density.density 是设备密度（如 3.0 = 480dpi），*160 得 dpi 整数值
        val densityDpi = (density.density * 160f).toInt()
        // key 全部用值类型；style 对象本身不放进来（每帧新建，放了缓存全废）
        val rich = remember(text, streaming, baseColor, baseFontSize, density.density, densityDpi, maxWidthPx) {
            val textSizePx = with(density) { baseFontSize.toPx() }
            val tokens = parseRichText(text)
            buildRichText(
                tokens = tokens,
                baseStyle = style,
                baseColorArgb = baseColor.toArgb(),
                textSizePx = textSizePx,
                densityDpi = densityDpi,
                maxWidthPx = maxWidthPx,
                streaming = streaming,
                cursor = cursor,
                density = density,
            )
        }
        Text(
            text = rich.annotated,
            style = style,
            inlineContent = rich.inlineContent,
            modifier = modifier,
        )
    }
}

/** 构建结果：AnnotatedString + 公式占位渲染映射 */
internal data class RichText(
    val annotated: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
)

/** token 列表 → AnnotatedString；公式段渲染位图并注册占位，失败回退原文 */
internal fun buildRichText(
    tokens: List<RichToken>,
    baseStyle: TextStyle,
    baseColorArgb: Int,
    textSizePx: Float,
    densityDpi: Int,
    maxWidthPx: Int,
    streaming: Boolean,
    cursor: String,
    density: androidx.compose.ui.unit.Density,
): RichText {
    val builder = AnnotatedString.Builder()
    val inlineContent = mutableMapOf<String, InlineTextContent>()
    val baseSpan = baseStyle.toSpanStyle()
    var mathId = 0

    for (t in tokens) {
        when (t) {
            is RichToken.Text -> {
                // RunStyle → SpanStyle（在 base 之上叠加差异），用 pushStyle/pop 应用
                val span = runStyleToSpan(t.style, baseSpan, baseColorArgb)
                if (span == baseSpan) {
                    builder.append(t.text)
                } else {
                    builder.pushStyle(span)
                    builder.append(t.text)
                    builder.pop()
                }
            }

            is RichToken.Math -> {
                val bmp = MathRenderer.render(
                    latex = t.latex,
                    colorArgb = baseColorArgb,
                    textSizePx = textSizePx,
                    densityDpi = densityDpi,
                    maxWidthPx = maxWidthPx,
                    block = t.block,
                )
                if (bmp != null) {
                    val id = "math_${mathId++}"
                    // altText 用原文（含分隔符），占位图在无障碍/异常兜底时的文案
                    builder.appendInlineContent(id, t.raw)
                    // Placeholder 宽高是 TextUnit（用 sp 与文本字号同尺度），位图像素转 sp
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
                    // 渲染失败（非法 LaTeX）：回退显示原文（含分隔符）
                    builder.append(t.raw)
                }
            }
        }
    }
    // 光标 ▍ 在解析结果之后追加，永不进入公式/标记
    if (streaming) builder.append(cursor)
    return RichText(builder.toAnnotatedString(), inlineContent)
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
