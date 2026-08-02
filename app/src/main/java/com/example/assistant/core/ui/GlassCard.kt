package com.example.assistant.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 通用玻璃拟态卡片（glassmorphism，风格与浮动界面一致）：
 * 低透明白玻璃底（顶部略亮模拟玻璃高光）+ 1dp 半透明白描边 + 大圆角 + 柔和阴影。
 * 背景放深墨夜景（NightBackground）上才有效果。
 *
 * @param onClick 非空则整卡可点击（带涟漪反馈）
 * @param containerAlpha 白玻璃透明度（默认 0.08，需要更亮可调大）
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(20.dp),
    containerAlpha: Float = 0.08f,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val background = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = containerAlpha + 0.04f),
            Color.White.copy(alpha = containerAlpha * 0.6f)
        )
    )
    Card(
        modifier = modifier.shadow(
            10.dp,
            shape,
            ambientColor = Color(0xFF060A13).copy(alpha = 0.6f),
            spotColor = Color(0xFF060A13).copy(alpha = 0.5f)
        ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
    ) {
        Column(
            modifier = Modifier
                .background(background)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(contentPadding)
        ) {
            content()
        }
    }
}
