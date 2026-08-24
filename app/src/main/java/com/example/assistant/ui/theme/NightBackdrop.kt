package com.example.assistant.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 主界面环境背景（静态绘制一次，无动画开销）：
 * 垂直深墨渐变打底 + 三处极淡氛围光晕（右上香槟金 / 左下信息蓝 / 左上冷白），
 * 让玻璃卡片有「透出光」的层次感——与浮动界面的光斑语言一致，但更克制。
 */
@Composable
fun NightBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF0D1728), Color(0xFF0B1322), Color(0xFF091120))
            )
        )

        fun glow(color: Color, cx: Float, cy: Float, r: Float) {
            val center = Offset(size.width * cx, size.height * cy)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color, Color.Transparent),
                    center = center,
                    radius = size.height * r
                ),
                radius = size.height * r,
                center = center
            )
        }
        glow(Color(0xFFE4B863).copy(alpha = 0.08f), 0.95f, -0.06f, 0.60f)  // 右上：香槟金晨光
        glow(Color(0xFF7FB3E3).copy(alpha = 0.07f), 0.00f, 1.04f, 0.52f)   // 左下：夜空蓝
        glow(Color(0xFFFFFFFF).copy(alpha = 0.03f), -0.06f, 0.28f, 0.36f)  // 左上：冷白高光
    }
}
