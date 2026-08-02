package com.example.assistant.core.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

/**
 * 图片工具：缩放 / base64 编码 / 从 URI 读图 / 缩略图。
 * 识屏与聊天附件共用（视觉模型输入统一缩到宽 ≤ 1280，控制请求体大小）。
 */
object ImageUtils {

    /** 视觉模型输入的最大宽度（等比缩放，超过则缩小） */
    const val MAX_WIDTH = 1280

    /** 等比缩放：宽 ≤ [MAX_WIDTH]，保持比例 */
    fun scaleBitmap(src: Bitmap): Bitmap {
        if (src.width <= MAX_WIDTH) return src
        val ratio = MAX_WIDTH.toFloat() / src.width
        val w = MAX_WIDTH
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** Bitmap → PNG base64（视觉模型 data URL 用，不带前缀） */
    fun bitmapToBase64(bitmap: Bitmap): String {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** 本地 PNG 文件 → base64 */
    fun fileToBase64(path: String): String {
        return Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
    }

    /**
     * 从内容 URI 读图（分享/相册选择，进程内临时读权限），等比缩到宽 ≤ [MAX_WIDTH]。
     * 读取失败返回 null（调用方给出提示）。
     */
    fun readUriBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)?.let { scaleBitmap(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 聊天列表缩略图（宽 ≤ 256，省内存）；失败返回 null */
    fun decodeThumbnail(path: String): Bitmap? = decodeFit(path, 256)

    /**
     * 按目标宽度采样解码本地图片（inSampleSize 防 OOM，解码后不再放大）。
     * 缩略图/大图展示通用；失败返回 null。
     */
    fun decodeFit(path: String, maxWidth: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            var sample = 1
            while (opts.outWidth / sample > maxWidth * 2) sample *= 2
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, decode)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * base64 → Bitmap（聊天附件存的 PNG base64 解码，供转存日记图片用）。
     * 失败返回 null。
     */
    fun decodeBase64Bitmap(base64: String): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(
                Base64.decode(base64, Base64.NO_WRAP), 0, 0
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Bitmap → 缩略图（聊天列表用） */
    fun thumbnail(bitmap: Bitmap, maxWidth: Int = 256): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(
            bitmap, maxWidth, (bitmap.height * ratio).toInt().coerceAtLeast(1), true
        )
    }

    /** 保存 Bitmap 到 cacheDir/screensense 下，返回文件路径；失败返回 null */
    fun saveToCache(context: Context, bitmap: Bitmap, name: String): String? {
        return try {
            val dir = File(context.cacheDir, "screensense").apply { mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 保存 Bitmap 到 filesDir/diary_images 下（内部存储，系统清理不掉），JPEG 90 压缩。
     * 日记图片存这里，DB 只存路径；失败返回 null。
     */
    fun saveToFilesDir(context: Context, bitmap: Bitmap, name: String): String? {
        return try {
            val dir = File(context.filesDir, "diary_images").apply { mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
