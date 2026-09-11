package com.example.assistant.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 主界面环境背景（静态绘制，无动画）：
 * 垂直深墨渐变打底 + 四处极淡氛围光晕（右上香槟金 / 左下信息蓝 / 左上冷白 / 左中金），
 * 让玻璃卡片有「透出光」的层次感——与浮动界面的光斑语言一致，但更克制。
 *
 * ⚠️ 性能（2026-09-11 卡顿排查实锤）：整屏背景里的大半径径向渐变如果留在主绘制层，
 * **每一帧都要重新录制绘制命令 + 重新光栅化**（滚动时特别明显：UI 线程 6ms+、GPU 6–9ms）。
 * 这里用 `graphicsLayer(CompositingStrategy.Offscreen)` 把静态背景录进**独立图层**：
 * 只在尺寸/内容变化时录制一次，之后每帧只是一次纹理合成。
 */
@Composable
fun NightBackdrop(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
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
        glow(Color(0xFFE4B863).copy(alpha = 0.21f), 0.95f, -0.06f, 0.70f)  // 右上：香槟金晨光
        glow(Color(0xFF7FB3E3).copy(alpha = 0.16f), 0.00f, 1.08f, 0.60f)   // 左下：夜空蓝
        glow(Color(0xFFFFFFFF).copy(alpha = 0.07f), -0.06f, 0.26f, 0.42f)  // 左上：冷白高光
        glow(Color(0xFFE4B863).copy(alpha = 0.09f), 0.10f, 0.74f, 0.36f)   // 左中：金微光
    }
}
