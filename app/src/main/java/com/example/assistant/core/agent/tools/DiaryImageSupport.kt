package com.example.assistant.core.agent.tools

import java.io.File

/**
 * 日记图片路径的统一校验与归位（write_diary / update_diary 共用，2026-10-05 抽出）。
 *
 * - diary_images 下的存在文件：原样使用；
 * - chat_images 下的存在文件（带图对话保存的原图）：**复制**进 diary_images 并返回新路径
 *   （复制后受 DB 引用保护，不会在下次启动被"孤儿图片清理"删掉）；
 * - 其它路径 / 文件不存在：返回 null（模型编造，或文件已被清理）。
 */
internal fun normalizeDiaryImagePath(raw: String): String? {
    val f = File(raw)
    if (!f.exists() || !f.isFile) return null
    val path = f.absolutePath
    // 用**父目录名精确匹配**而不是子串包含：否则 /x/diary_images_evil/a.jpg 之类的路径会被放行
    val parent = f.parentFile?.name
    if (parent == "diary_images") return path
    if (parent != "chat_images") return null
    return try {
        val filesDir = f.parentFile?.parentFile ?: return null
        val dir = File(filesDir, "diary_images").apply { mkdirs() }
        val dest = File(dir, "diary_${System.currentTimeMillis()}_${f.name}")
        f.copyTo(dest, overwrite = false)
        dest.absolutePath
    } catch (_: Exception) {
        null
    }
}
