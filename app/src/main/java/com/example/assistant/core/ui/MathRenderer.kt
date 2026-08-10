package com.example.assistant.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.LruCache
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXIcon
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color
import ru.noties.jlatexmath.awt.Component
import ru.noties.jlatexmath.awt.Insets

/**
 * 数学公式渲染封装（jlatexmath）：
 * LaTeX → TeXFormula → TeXIcon → Bitmap。
 *
 * 带 LRU 缓存：key 覆盖全部影响因子（latex 原文 / 前景色 / 字号像素 / DPI / 宽度约束 / 块行内），
 * 保证流式每 token 重渲染时同一公式只真正跑一次 jlatexmath，其余命中缓存。
 *
 * 容错（绝不崩 UI）：
 * - 非法 LaTeX（\xbad、\tag 等 jlatexmath 不认的命令）→ try/catch 返回 null，调用方回退原文
 * - 渲染结果空白（绘制异常/空 box）→ 抽样检测全透明返回 null，同样回退原文
 * - 超宽/超大 → 用 createScaledBitmap 等比图像缩放（不是裁剪，保证公式完整）
 */
object MathRenderer {

    /** 渲染影响因子（缓存 key） */
    data class Key(
        val latex: String,
        val colorArgb: Int,      // 跟随气泡文字色的 ARGB Int
        val textSizePx: Float,   // 与正文匹配的字号像素
        val densityDpi: Int,     // 影响位图清晰度
        val maxWidthPx: Int,     // 气泡内容区可用宽度（超宽公式等比缩小的上限）
        val block: Boolean,      // 块级/行内（块级用 DISPLAY 样式，行内用 TEXT）
    )

    private const val TAG = "MathRenderer"
    private const val MAX_BYTES = 12 * 1024 * 1024
    private const val MAX_PIXELS = 3_000_000

    /** LRU 按 Bitmap 字节数计容量（防历史消息公式撑爆内存）；缓存内不手动 recycle，驱逐交给 GC */
    private val cache = object : LruCache<Key, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount
    }

    /** jlatexmath 字体/资源初始化只做一次（幂等） */
    private val initLock = Any()
    private var initialized = false

    /** 首次渲染前调用一次：加载库的 assets 字体。重复调用无害 */
    fun init(context: Context) {
        synchronized(initLock) {
            if (initialized) return
            // 用 applicationContext 避免持有 Activity 泄漏
            JLatexMathAndroid.init(context.applicationContext)
            initialized = true
        }
    }

    /**
     * 渲染一个公式为 Bitmap；失败返回 null（调用方回退原文）。
     * 同步在主线程调用（jlatexmath 典型 <10ms 纯 CPU），命中缓存时几乎零开销。
     */
    fun render(
        latex: String,
        colorArgb: Int,
        textSizePx: Float,
        densityDpi: Int,
        maxWidthPx: Int,
        block: Boolean,
    ): Bitmap? {
        // 先剥掉 jlatexmath 不支持的 \tag{}/\label{}（教材公式常带），避免整个公式解析失败回退原文
        val clean = sanitize(latex)
        if (clean.isEmpty()) return null
        val key = Key(clean, colorArgb, textSizePx, densityDpi, maxWidthPx, block)
        cache.get(key)?.let { return it }
        val bmp = try {
            createBitmap(clean, colorArgb, textSizePx, densityDpi, maxWidthPx, block)
        } catch (e: Exception) {
            // jlatexmath 对非法 LaTeX 抛 ParseException 等异常
            Log.e(TAG, "公式渲染异常 latex=[$latex]：${e.javaClass.simpleName} ${e.message}")
            null
        } ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** 实际渲染（可抛异常，由 render 兜底） */
    private fun createBitmap(
        latex: String,
        colorArgb: Int,
        textSizePx: Float,
        densityDpi: Int,
        maxWidthPx: Int,
        block: Boolean,
    ): Bitmap? {
        val formula = TeXFormula(latex)
        val style = if (block) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT
        val icon = formula.createTeXIcon(style, textSizePx)
        // 去掉默认内边距，让位图贴近公式本身
        icon.setInsets(Insets(0, 0, 0, 0))

        val color = Color(colorArgb)
        icon.setForeground(color)

        // 总高度 = 高度 + 基线深度（getIconHeight 不含 depth）
        val iconW = icon.iconWidth
        val iconDepth = icon.iconDepth
        val iconH = icon.iconHeight + iconDepth
        if (iconW <= 0 || iconH <= 0) {
            Log.e(TAG, "公式图标尺寸无效：$latex w=$iconW h=$iconH")
            return null
        }

        // 先按原始尺寸渲染（保证公式内容完整绘制，避免缩小后裁剪丢失）
        val bitmap = Bitmap.createBitmap(iconW, iconH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val g2 = AndroidGraphics2D()
        g2.setCanvas(canvas)
        // 关键：paintIcon 的 y 必须传基线深度 iconDepth——基线放在 bitmap 底部 depth 处，
        // 上方 height 部分 + 下方 depth 部分才都落在 bitmap 内。
        // 之前传 0,0 基线在顶部，块级公式（矩阵/分式）主体全画到负坐标被裁掉 → 空白
        icon.paintIcon(
            object : Component {
                override fun getForeground(): Color = color
            },
            g2,
            0, iconDepth,
        )

        // 空白自检：渲染结果几乎全透明 → 视为失败（有占位符但内容画不出来，回退原文）
        if (isBlank(bitmap)) {
            Log.e(TAG, "公式渲染结果空白：$latex")
            bitmap.recycle()
            return null
        }

        // 尺寸守卫：超宽/总像素超限 → createScaledBitmap 等比缩小（图像缩放，不是裁剪）
        val totalPixels = iconW.toLong() * iconH
        if (iconW > maxWidthPx || totalPixels > MAX_PIXELS) {
            val scale = minOf(maxWidthPx.toFloat() / iconW, MAX_PIXELS.toFloat() / totalPixels)
            val w = (iconW * scale).toInt().coerceAtLeast(1)
            val h = (iconH * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        return bitmap
    }

    /**
     * 净化公式，返回 jlatexmath 能解析的形式：
     * 1. 剥掉不支持的编号/标签命令 \tag{}/\label{}
     * 2. **换行符转空格**——LLM 输出的块级公式是多行的（\[ 换行 + 内容 + 换行 \]），
     *    jlatexmath 解析含真实换行 \n 的公式会抛异常（真机实测矩阵/哈密顿量因此渲染失败）。
     *    LaTeX 里真实换行等价于空格（\\ 才是显式换行），转空格无损。
     */
    private fun sanitize(latex: String): String = latex
        .replace(Regex("\\\\tag\\{[^}]*\\}"), "")
        .replace(Regex("\\\\label\\{[^}]*\\}"), "")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()

    /** 抽样检测位图是否几乎全透明（内容没画出来） */
    private fun isBlank(bmp: Bitmap): Boolean {
        val stepX = maxOf(1, bmp.width / 20)
        val stepY = maxOf(1, bmp.height / 20)
        var x = 0
        while (x < bmp.width) {
            var y = 0
            while (y < bmp.height) {
                val alpha = (bmp.getPixel(x, y) ushr 24) and 0xFF
                if (alpha > 40) return false
                y += stepY
            }
            x += stepX
        }
        return true
    }
}
