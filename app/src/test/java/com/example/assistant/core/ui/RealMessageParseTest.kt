package com.example.assistant.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用用户真实消息文本验证解析链路：块级 \[...\] 多行公式提取是否正确。
 * 纯 Kotlin，JVM 直接跑。
 */
class RealMessageParseTest {

    // 用户识屏输出中的完整消息原文（含块级多行公式）
    private val realMessage = """
另一类普遍存在的辛变换（symplectic transformations）是所谓的双模压缩变换（two-mode squeezing transformations）\(S_r\)，其辛矩阵为

\[
S_r=
\begin{pmatrix}
\cosh r & 0 & \sinh r & 0\\
0 & \cosh r & 0 & -\sinh r\\
\sinh r & 0 & \cosh r & 0\\
0 & -\sinh r & 0 & \cosh r
\end{pmatrix},
\tag{5.18}
\]

它由哈密顿算符

\[
i(\hat a_1^\dagger \hat a_2^\dagger-\hat a_1\hat a_2)
\]

生成。这个哈密顿量同样不是正的：在实际应用中，它通过非简并参量下转换（non-degenerate parametric down-conversion）过程实现。在该过程中，系统模通过晶体与一个激光场耦合，激光场具有湮灭算符（annihilation operator）\(\hat b\)，耦合哈密顿量为

\[
i(\hat a_1^\dagger\hat a_2^\dagger\hat b-\hat a_1\hat a_2\hat b^\dagger),
\]

并且，与上文讨论的单模简并情形（single-mode, degenerate case）一样，激光模算符可以用它们的期望值代替。

**问题 5.4.**（双模压缩哈密顿量（Two-mode squeezing Hamiltonian））证明，双模压缩变换 \(S_r\) 由哈密顿量

\[
\hat H_{TB}=i(\hat a_1^\dagger\hat a_2^\dagger-\hat a_1\hat a_2)
\]

生成。
""".trimIndent()

    @Test
    fun `真实消息公式提取数量与分隔符`() {
        val tokens = parseRichText(realMessage)
        val math = tokens.filterIsInstance<RichToken.Math>()
        val text = tokens.filterIsInstance<RichToken.Text>()

        println("=== 解析结果：${tokens.size} tokens，其中文本 ${text.size}、公式 ${math.size} ===")
        math.forEachIndexed { i, m ->
            println("[公式$i] block=${m.block} 长度=${m.latex.length} raw=「${m.raw.take(40)}...」")
            println("   latex=「${m.latex.take(80)}${if (m.latex.length>80) "..." else ""}」")
        }

        // 期望 5 个公式：矩阵、哈密顿量、耦合哈密顿量、\hat H_TB、行内 S_r（有 3 处行内 S_r）
        // 实际：矩阵(block) + 哈密顿量(block) + 耦合哈密顿量(block) + \hat H_TB(block) + 行内 \(S_r\) ×3 + \(\hat b\)
        assertTrue("公式数应>=5，实际 ${math.size}", math.size >= 5)

        // 矩阵必须是 block 且 latex 含 begin{pmatrix}
        val matrix = math.first { it.latex.contains("pmatrix") }
        assertEquals(true, matrix.block)
        assertTrue(matrix.latex.contains("\\begin{pmatrix}"))
        println("\n矩阵 latex 含真实换行: ${matrix.latex.contains('\n')}")
        // 关键检查：矩阵 latex 里 \begin 前的反斜杠
        println("矩阵 latex 前 40 字符: ${matrix.latex.take(40).replace("\n", "\\n")}")
    }

    @Test
    fun `块级公式括号闭合提取完整`() {
        val tokens = parseRichText(realMessage)
        val math = tokens.filterIsInstance<RichToken.Math>()
        val matrix = math.first { it.latex.contains("pmatrix") }
        // 提取的 latex 应含全部 4 行矩阵 + end{pmatrix}
        assertTrue("应含 cosh", matrix.latex.contains("cosh r"))
        assertTrue("应含 sinh", matrix.latex.contains("sinh r"))
        assertTrue("应含 end{pmatrix}", matrix.latex.contains("\\end{pmatrix}"))
        // 应含矩阵换行命令 \\
        assertTrue("应含矩阵换行 \\\\", matrix.latex.contains("\\\\"))
        // 剥 tag 前 latex 含 tag（render 内 sanitize 处理）
        println("矩阵 latex 含 tag: ${matrix.latex.contains("\\tag")}")
    }

    @Test
    fun `行内公式仍正常提取`() {
        val tokens = parseRichText(realMessage)
        val math = tokens.filterIsInstance<RichToken.Math>()
        val inline = math.filter { !it.block }
        println("行内公式数: ${inline.size}")
        inline.forEach { println("  行内: 「${it.latex}」") }
        assertTrue("应有行内公式（S_r、\\hat b）", inline.isNotEmpty())
        assertTrue(inline.any { it.latex == "S_r" })
        assertTrue(inline.any { it.latex.contains("\\hat b") })
    }
}
