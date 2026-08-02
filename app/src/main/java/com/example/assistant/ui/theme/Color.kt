package com.example.assistant.ui.theme

import androidx.compose.ui.graphics.Color

// ============ 深墨夜景配色（glassmorphism，与浮动界面一致） ============
// 背景：深墨蓝夜空；卡片：低透明白玻璃（见 GlassCard）；强调色：香槟金

/** 最深层背景（App 背景） */
val NightBackground = Color(0xFF0B1322)
/** 更深的背景（浮动界面最深色） */
val NightBackgroundDeep = Color(0xFF060A13)
/** surface：比背景略亮的深墨蓝 */
val NightSurface = Color(0xFF0F1A2E)
/** 玻璃感深蓝灰（卡片/气泡底色、surfaceVariant） */
val NightSurfaceVariant = Color(0xFF16233A)
/** 再亮一档（surfaceContainerHighest 等） */
val NightSurfaceHigh = Color(0xFF1C2B47)

/** 主文字（近白） */
val NightOnBackground = Color(0xFFE8EDF7)
/** 次要文字（灰蓝） */
val NightOnSurfaceVariant = Color(0xFF8A97AE)

/** 香槟金强调色（全 App 唯一强调色） */
val ChampagneGold = Color(0xFFE4B863)
/** 香槟金深底（primaryContainer） */
val ChampagneGoldContainer = Color(0xFF3A2E0F)
val OnChampagneGoldContainer = Color(0xFFF5DCA0)

/** 次级色（中性蓝灰） */
val NightSecondary = Color(0xFFA8B4C8)
val NightSecondaryContainer = Color(0xFF22304A)
val OnNightSecondaryContainer = Color(0xFFD0D9E8)

/** 描边/分隔线 */
val NightOutline = Color(0xFF3A4A66)

/** 信息蓝（识图等点缀） */
val NightInfo = Color(0xFF7FB3E3)
