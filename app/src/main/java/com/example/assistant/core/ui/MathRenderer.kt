package com.example.assistant.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
 * 注意：jlatexmath 对非法 LaTeX 抛异常是常态（这里统一 try/catch 返回 null，
 * 由调用方回退显示原文），绝不崩 UI。
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

    private const val MAX_BYTES = 12 * 1024 * 1024

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
        val key = Key(latex, colorArgb, textSizePx, densityDpi, maxWidthPx, block)
        cache.get(key)?.let { return it }
        val bmp = try {
            createBitmap(latex, colorArgb, textSizePx, densityDpi, maxWidthPx, block)
        } catch (e: Exception) {
            // jlatexmath 对非法 LaTeX（\xbad、未闭合 \frac 等）抛 ParseException 等异常
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

        // 总高度 = 高度 + 基线深度（getIconHeight 不含 depth，depth 会裁掉）
        var w = icon.iconWidth
        var h = icon.iconHeight + icon.iconDepth
        if (w <= 0 || h <= 0) return null

        // 尺寸守卫：超宽/总像素超限按比例缩小，杜绝巨型位图 OOM
        val totalPixels = w.toLong() * h
        if (w > maxWidthPx || totalPixels > MAX_PIXELS) {
            val scale = minOf(maxWidthPx.toFloat() / w, MAX_PIXELS.toFloat() / totalPixels)
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val g2 = AndroidGraphics2D()
        g2.setCanvas(canvas)
        icon.paintIcon(
            object : Component {
                override fun getForeground(): Color = color
            },
            g2,
            0, 0,
        )
        return bitmap
    }

    /** 防止病态输入生成超大位图（约 300 万像素上限） */
    private const val MAX_PIXELS = 3_000_000
}
