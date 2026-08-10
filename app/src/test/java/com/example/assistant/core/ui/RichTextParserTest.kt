package com.example.assistant.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 富文本解析器单测：公式分隔符 + 基础 Markdown。
 * 纯 Kotlin 逻辑（无 Android 依赖），JVM 直接跑。
 */
class RichTextParserTest {

    // ---------- 公式拆分 ----------

    @Test
    fun `行内公式美元符号形式`() {
        val segs = splitFormula("勾股定理 \$a^2+b^2=c^2\$ 成立")
        assertEquals(3, segs.size)
        assertEquals("勾股定理 ", (segs[0] as FormulaSegment.Plain).text)
        val math = segs[1] as FormulaSegment.Math
        assertEquals("a^2+b^2=c^2", math.latex)
        assertEquals(false, math.block)
        assertEquals("\$a^2+b^2=c^2\$", math.raw)
    }

    @Test
    fun `块级公式双美元`() {
        val segs = splitFormula("质能方程 \$\$E=mc^2\$\$")
        assertEquals(2, segs.size)
        val math = segs[1] as FormulaSegment.Math
        assertEquals("E=mc^2", math.latex)
        assertEquals(true, math.block)
    }

    @Test
    fun `括号分隔符转义形式`() {
        val segs = splitFormula("行内 \\(x+1\\) 和块级 \\[\\frac{1}{2}\\]")
        assertEquals(4, segs.size)
        assertEquals("x+1", (segs[1] as FormulaSegment.Math).latex)
        assertEquals(false, (segs[1] as FormulaSegment.Math).block)
        assertEquals("\\frac{1}{2}", (segs[3] as FormulaSegment.Math).latex)
        assertEquals(true, (segs[3] as FormulaSegment.Math).block)
    }

    @Test
    fun `未闭合公式按普通文本`() {
        val segs = splitFormula("价格 \$5 美元")
        assertEquals(1, segs.size)
        assertEquals("价格 \$5 美元", (segs[0] as FormulaSegment.Plain).text)
    }

    @Test
    fun `转义美元符号不算公式起点`() {
        val segs = splitFormula("\\\$5 和 \$x\$")
        assertEquals(2, segs.size)
        assertEquals("\\\$5 和 ", (segs[0] as FormulaSegment.Plain).text)
        assertEquals("x", (segs[1] as FormulaSegment.Math).latex)
    }

    @Test
    fun `双美元优先于单美元`() {
        val segs = splitFormula("\$\$a\$\$ 和 \$b\$")
        assertEquals(3, segs.size)
        assertEquals(true, (segs[0] as FormulaSegment.Math).block)
        assertEquals(false, (segs[2] as FormulaSegment.Math).block)
    }

    // ---------- Markdown ----------

    @Test
    fun `加粗生效`() {
        val tokens = parseMarkdown("这是**加粗**文字")
        assertEquals(3, tokens.size)
        assertEquals("这是", tokens[0].text)
        assertEquals(true, tokens[1].style.bold)
        assertEquals("加粗", tokens[1].text)
        assertEquals("文字", tokens[2].text)
    }

    @Test
    fun `斜体生效`() {
        val tokens = parseMarkdown("用 *强调* 一下")
        assertEquals(3, tokens.size)
        assertEquals(true, tokens[1].style.italic)
    }

    @Test
    fun `行内代码生效`() {
        val tokens = parseMarkdown("执行 `adb install` 命令")
        assertEquals(3, tokens.size)
        assertEquals(true, tokens[1].style.code)
    }

    @Test
    fun `未闭合加粗按字面量`() {
        val tokens = parseMarkdown("还没**写完")
        assertEquals(1, tokens.size)
        assertEquals("还没**写完", tokens[0].text)
        assertEquals(false, tokens[0].style.bold)
    }

    @Test
    fun `标题分级`() {
        val tokens = parseMarkdown("# 一级\n## 二级\n### 三级")
        assertEquals(1, tokens[0].style.headingLevel)
        assertEquals(2, tokens[2].style.headingLevel)
        assertEquals(3, tokens[4].style.headingLevel)
    }

    @Test
    fun `无序列表换圆点`() {
        val tokens = parseMarkdown("- 第一项\n- 第二项")
        assertEquals("•  ", tokens[0].text)
        assertEquals("第一项", tokens[1].text)
        assertEquals("\n", tokens[2].text)
        assertEquals("•  ", tokens[3].text)
        assertEquals("第二项", tokens[4].text)
    }

    @Test
    fun `有序列表保留编号`() {
        val tokens = parseMarkdown("1. 步骤一\n2. 步骤二")
        assertEquals("1. ", tokens[0].text)
        assertEquals("步骤一", tokens[1].text)
        assertEquals("2. ", tokens[3].text)
        assertEquals("步骤二", tokens[4].text)
    }

    // ---------- 两步合并 ----------

    @Test
    fun `公式内容不被误判为 Markdown`() {
        val tokens = parseRichText("**加粗** 和 \$\\textbf{abc}\$ 公式")
        // 加粗生效；公式里的 \textbf 不应被当作 Markdown
        val textTokens = tokens.filterIsInstance<RichToken.Text>()
        val mathTokens = tokens.filterIsInstance<RichToken.Math>()
        assertEquals(true, textTokens.first().style.bold)
        assertEquals(1, mathTokens.size)
        assertEquals("\\textbf{abc}", mathTokens.first().latex)
    }

    @Test
    fun `普通文本原样保留`() {
        val tokens = parseRichText("你好世界 123 a * b 结束")
        assertEquals(1, tokens.size)
        assertEquals("你好世界 123 a * b 结束", (tokens[0] as RichToken.Text).text)
    }

    @Test
    fun `乘法星号无配对不斜体`() {
        val tokens = parseRichText("3 * 4")
        assertEquals(1, tokens.size)
        assertEquals(false, (tokens[0] as RichToken.Text).style.italic)
    }
}
