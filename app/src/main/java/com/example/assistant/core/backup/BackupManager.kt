package com.example.assistant.core.backup

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.room.withTransaction
import com.example.assistant.core.network.Capability
import com.example.assistant.core.storage.ConversationLog
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.core.storage.SecretStore
import com.example.assistant.core.storage.SettingsStore
import com.example.assistant.core.storage.SummaryStore
import com.example.assistant.data.db.AppDatabase
import com.example.assistant.data.db.entity.DiaryImageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 数据备份与导入核心。
 *
 * 备份格式：ZIP = backup.json（全量结构化数据）+ images/<文件名>.jpg（日记图片）
 *            + secret_log/chat_history.txt（对话历史）。
 * **不含 API Key**（BackupProviderProfile 结构上无 apiKey 字段，编译期保证）；
 * 恢复后模型配置保留但需重填 key。
 *
 * 手动导出/导入走 SAF（CreateDocument/OpenDocument，免存储权限）；
 * 自动备份写公共「下载」目录（MediaStore，API 29+ 免权限，卸载不丢）。
 * 恢复为覆盖式：清空现有数据再写入，完成后自动重启 App（数据层单例需刷新）。
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val promptStore: PromptStore,
    private val summaryStore: SummaryStore,
    private val secretStore: SecretStore,
    private val conversationLog: ConversationLog
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ==================== 手动导出 ====================

    /** 一键导出到 uri（SAF CreateDocument 给的写权限 uri）。IO 线程执行 */
    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val backup = collectBackup()
            // 临时文件放 cacheDir（自动备份目录 filesDir/backup 只放自动备份，互不干扰）
            val tmp = File(context.cacheDir, "backup_export_${timestamp()}.zip")
            try {
                writeBackupZip(backup, tmp)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                } ?: throw BackupException("无法写入所选文件")
            } finally {
                tmp.delete()
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "导出失败", e)
            throw BackupException("导出失败：${e.message}")
        }
    }

    // ==================== 恢复 ====================

    /** 只读解析备份文件（校验格式/版本），供确认对话框展示；不修改任何数据 */
    suspend fun preview(uri: Uri): BackupFile = withContext(Dispatchers.IO) {
        try {
            val backup = readBackupJson(uri)
            validate(backup)
            backup
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "预览备份失败", e)
            throw BackupException("无法读取备份：${e.message}")
        }
    }

    /**
     * 覆盖式恢复：清空现有数据 → 写回备份内容 → 自动重启。
     * [data] 为 [preview] 已解析并校验的备份内容。
     */
    suspend fun restore(uri: Uri, data: BackupFile) = withContext(Dispatchers.IO) {
        val importZip = File(context.filesDir, "backup/import.zip").apply { parentFile?.mkdirs() }
        try {
            // 1. 拷贝到本地（SAF 权限随对话框关闭失效，必须先落地）
            context.contentResolver.openInputStream(uri)?.use { ins ->
                importZip.outputStream().use { ins.copyTo(it) }
            } ?: throw BackupException("无法读取备份文件")

            // 2. 校验
            ZipFile(importZip).use { zip ->
                val jsonEntry = zip.getEntry("backup.json")
                    ?: throw BackupException("不是有效的备份文件（缺少 backup.json）")
                val decoded = json.decodeFromString<BackupFile>(zip.getInputStream(jsonEntry).readBytes().decodeToString())
                validate(decoded)

                // 3. 清旧日记图片文件 + 解图回写（白名单式：只解备份里引用到的文件名，防 zip-slip）
                val imagesDir = File(context.filesDir, "diary_images").apply { mkdirs() }
                imagesDir.listFiles()?.forEach { it.delete() }
                val wanted = decoded.diaryImages.mapNotNull { File(it.path).name }
                for (name in wanted) {
                    val entry = zip.getEntry("images/$name") ?: continue
                    File(imagesDir, name).outputStream().use { out ->
                        zip.getInputStream(entry).copyTo(out)
                    }
                }

                // 4. secret_log 覆盖
                if (decoded.secretLogIncluded) {
                    val logEntry = zip.getEntry("secret_log/chat_history.txt")
                    if (logEntry != null) {
                        val logFile = conversationLog.file().apply { parentFile?.mkdirs() }
                        logFile.outputStream().use { out ->
                            zip.getInputStream(logEntry).copyTo(out)
                        }
                    }
                }
            }

            // 5. DB 恢复（唯一事务：失败整体回滚，不留半恢复状态）
            db.withTransaction {
                // 清空：子表在前（防外键中间态）
                db.diaryDao().clearAllImages()
                db.diaryDao().clearAllEntries()
                db.diaryDao().clearAllBooks()
                db.eventDao().clearAllHits()
                db.eventDao().clearAll()
                db.memoryDao().clearAll()
                db.reminderDao().clearAll()
                db.summaryDao().clearAll()
                // 写入：父表在前（FK 顺序）
                db.diaryDao().insertAllBooks(data.diaryBooks)
                db.diaryDao().insertAllEntries(data.diaryEntries)
                db.diaryDao().insertImages(data.diaryImages.map { remapImagePath(it) })
                db.eventDao().insertAll(data.monitoredEvents)
                db.eventDao().insertAllHits(data.eventHits)
                db.memoryDao().insertAll(data.memories)
                db.reminderDao().insertAll(data.reminders)
                db.summaryDao().insertAll(data.dailySummaries)
            }

            // 6. DataStore / 加密存储恢复（事务外）
            restoreSettings(data.settings)
            restorePrompts(data.prompts)
            restoreSummaries(data.latestSummary, data.latestBriefing)
            secretStore.saveProfiles(data.providerProfiles.map { it.toProfile() })
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "恢复失败", e)
            throw BackupException("恢复失败：${e.message}")
        } finally {
            importZip.delete()
        }

        // 7. 自动重启（数据层单例：ConversationLog.enabled / ProviderRegistry 缓存 / ChatViewModel 会话态
        //    都不刷新，必须重启才生效）
        restartApp()
    }

    // ==================== 自动备份（写公共「下载」目录） ====================

    /**
     * 自动备份到公共「下载」目录（API 29+ MediaStore 免权限；卸载 App 不丢，文件管理器可见）。
     * 保留最近 [keepCount] 份，超限删最旧。返回生成的媒体 uri；失败抛 BackupException。
     */
    suspend fun exportToDownloadDir(keepCount: Int = 3): Uri = withContext(Dispatchers.IO) {
        val backup = collectBackup()
        val name = "backup_${timestamp()}.zip"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw BackupException("无法在下载目录创建备份")
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    writeBackupZipToStream(backup, out)
                } ?: throw BackupException("无法写入下载目录")
                cleanupOldBackupsInDownloadDir(keepCount)
                uri
            } catch (e: BackupException) {
                // 写入失败删掉刚建的占位文件
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            // API 26-28：走外部存储目录（需 WRITE_EXTERNAL_STORAGE 运行时权限，maxSdkVersion=28 已声明）
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ).apply { mkdirs() }
            val file = File(dir, name)
            try {
                writeBackupZip(backup, file)
                cleanupLegacyDownloadDir(dir, keepCount)
                Uri.fromFile(file)
            } catch (e: Exception) {
                file.delete()
                throw BackupException("自动备份失败：${e.message}")
            }
        }
    }

    /** 清理下载目录里超出的旧备份（只认 backup_*.zip，按显示名排序删最旧） */
    private fun cleanupOldBackupsInDownloadDir(keepCount: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val cursor = context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("backup_%"),
            "${MediaStore.Downloads.DISPLAY_NAME} ASC"
        ) ?: return
        val ids = ArrayList<Long>()
        cursor.use {
            while (it.moveToNext()) {
                ids.add(it.getLong(0))
            }
        }
        // 字典序 = 时间序（文件名时间戳），只保留最新 keepCount 份
        if (ids.size > keepCount) {
            for (i in 0 until ids.size - keepCount) {
                val id = ids[i]
                context.contentResolver.delete(
                    Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()),
                    null, null
                )
            }
        }
    }

    /** API 26-28 路径的清理（直接删 File） */
    private fun cleanupLegacyDownloadDir(dir: File, keepCount: Int) {
        val files = dir.listFiles()
            ?.filter { it.name.startsWith("backup_") && it.name.endsWith(".zip") }
            ?.sortedBy { it.name } ?: return
        if (files.size > keepCount) {
            files.dropLast(keepCount).forEach { it.delete() }
        }
    }

    // ==================== 内部：收集与打包 ====================

    /** 收集全部数据（不含任何 API Key）——手动导出与自动备份共用 */
    private suspend fun collectBackup(): BackupFile {
        // 设置（DataStore 逐 key）
        val settings = BackupSettings(
            capabilityProfileIds = Capability.entries.associate { it.name to settingsStore.currentProfileIdFor(it) },
            ttsEnabled = settingsStore.ttsEnabled.first(),
            dailySummaryEnabled = settingsStore.dailySummaryEnabled.first(),
            dailySummaryMinute = settingsStore.dailySummaryMinute.first(),
            briefingEnabled = settingsStore.briefingEnabled.first(),
            briefingMinuteOfDay = settingsStore.briefingMinuteOfDay.first(),
            quietStartMinute = settingsStore.quietStartMinute.first(),
            quietEndMinute = settingsStore.quietEndMinute.first(),
            reasoningEffort = settingsStore.reasoningEffort.first(),
            floatingBallEnabled = settingsStore.floatingBallEnabled.first(),
            conversationMaxTurns = settingsStore.conversationMaxTurns.first(),
            diaryTagsCsv = settingsStore.diaryTagsCsv.first(),
            secretLogEnabled = settingsStore.secretLogEnabled.first(),
            autoBackupEnabled = settingsStore.autoBackupEnabled.first(),
            autoBackupIntervalDays = settingsStore.autoBackupIntervalDays.first()
        )
        // 提示词：只导出用户自定义过的组
        val prompts = PromptStore.PromptKey.entries
            .filter { promptStore.isCustomized(it) }
            .associate { it.name to promptStore.prompt(it) }
        // 提供商档案（apiKey 在 DTO 结构上不存在）
        val profiles = secretStore.loadProfiles().map { it.toBackup() }
        // Room 全表
        val diaryBooks = db.diaryDao().allBooks()
        val diaryEntries = db.diaryDao().allEntries()
        val diaryImages = db.diaryDao().allImages()
        val memories = db.memoryDao().allMemoriesFull()
        val reminders = db.reminderDao().all()
        val events = db.eventDao().allEvents()
        val hits = db.eventDao().allHits()
        val summaries = db.summaryDao().all()
        // secret_log 是否存在
        val logFile = conversationLog.file()
        val secretLogIncluded = logFile.exists() && logFile.length() > 0
        // SummaryStore 最近一份
        val latestSummary = summaryStore.latestSummary.first()?.let {
            LatestSummary(it, summaryStore.latestSummaryDate.first())
        }
        val latestBriefing = summaryStore.latestBriefing.first()?.let {
            LatestSummary(it, summaryStore.latestBriefingDate.first())
        }
        return BackupFile(
            appVersion = currentVersionName(),
            settings = settings,
            prompts = prompts,
            providerProfiles = profiles,
            diaryBooks = diaryBooks,
            diaryEntries = diaryEntries,
            diaryImages = diaryImages,
            memories = memories,
            reminders = reminders,
            monitoredEvents = events,
            eventHits = hits,
            dailySummaries = summaries,
            secretLogIncluded = secretLogIncluded,
            latestSummary = latestSummary,
            latestBriefing = latestBriefing
        )
    }

    /** 写 zip 到文件（backup.json + images 目录 + secret_log） */
    private suspend fun writeBackupZip(backup: BackupFile, dest: File) {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { writeBackupZipToStream(backup, it) }
    }

    /** 把 backup 打成 zip 流（文件/MediaStore 共用） */
    private fun writeBackupZipToStream(backup: BackupFile, out: java.io.OutputStream) {
        ZipOutputStream(out).use { zos ->
            // backup.json
            zos.putNextEntry(ZipEntry("backup.json"))
            zos.write(json.encodeToString(backup).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            // 日记图片（跳过缺失文件）
            val seen = HashSet<String>()
            for (img in backup.diaryImages) {
                val name = File(img.path).name
                if (!seen.add(name)) continue
                val f = File(img.path)
                if (f.exists()) {
                    zos.putNextEntry(ZipEntry("images/$name"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            // secret_log
            if (backup.secretLogIncluded) {
                val logFile = conversationLog.file()
                if (logFile.exists()) {
                    zos.putNextEntry(ZipEntry("secret_log/chat_history.txt"))
                    logFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    /** 从 uri 读 backup.json（preview 用，只读）——把 zip 落到本地再解，避免 SAF 流中断 */
    private fun readBackupJson(uri: Uri): BackupFile {
        val tmp = File(context.cacheDir, "preview_${timestamp()}.zip")
        context.contentResolver.openInputStream(uri)?.use { ins ->
            tmp.outputStream().use { ins.copyTo(it) }
        } ?: throw BackupException("无法读取所选文件")
        return try {
            ZipFile(tmp).use { zip ->
                val entry = zip.getEntry("backup.json")
                    ?: throw BackupException("不是有效的备份文件（缺少 backup.json）")
                json.decodeFromString<BackupFile>(zip.getInputStream(entry).readBytes().decodeToString())
            }
        } finally {
            tmp.delete()
        }
    }

    /** 校验格式与版本（不满足抛 BackupException） */
    private fun validate(backup: BackupFile) {
        if (backup.format != "assistant_backup") {
            throw BackupException("不是有效的备份文件")
        }
        if (backup.version > BACKUP_VERSION) {
            throw BackupException("此备份由更新版本的 App 生成，请先升级 App 后再恢复")
        }
    }

    // ==================== 内部：恢复子步骤 ====================

    /** 图片路径重映射：取文件名 + 本机 filesDir 拼接（同设备结果不变，换机自动修路径） */
    private fun remapImagePath(image: DiaryImageEntity): DiaryImageEntity =
        image.copy(path = File(context.filesDir, "diary_images/${File(image.path).name}").absolutePath)

    private suspend fun restoreSettings(s: BackupSettings) {
        Capability.entries.forEach { c ->
            settingsStore.setProfileIdFor(c, s.capabilityProfileIds[c.name] ?: "")
        }
        settingsStore.setTtsEnabled(s.ttsEnabled)
        settingsStore.setDailySummaryEnabled(s.dailySummaryEnabled)
        settingsStore.setDailySummaryMinute(s.dailySummaryMinute)
        settingsStore.setBriefingEnabled(s.briefingEnabled)
        settingsStore.setBriefingMinuteOfDay(s.briefingMinuteOfDay)
        settingsStore.setQuietWindow(s.quietStartMinute, s.quietEndMinute)
        settingsStore.setReasoningEffort(s.reasoningEffort)
        settingsStore.setFloatingBallEnabled(s.floatingBallEnabled)
        settingsStore.setConversationMaxTurns(s.conversationMaxTurns)
        settingsStore.setDiaryTagsCsv(s.diaryTagsCsv)
        settingsStore.setSecretLogEnabled(s.secretLogEnabled)
        settingsStore.setAutoBackupEnabled(s.autoBackupEnabled)
        settingsStore.setAutoBackupIntervalDays(s.autoBackupIntervalDays)
    }

    /** 提示词恢复：备份有值 setPrompt，没有则 resetPrompt（回默认，同时清掉目标机自定义） */
    private suspend fun restorePrompts(prompts: Map<String, String>) {
        for (key in PromptStore.PromptKey.entries) {
            val value = prompts[key.name]
            if (value != null) promptStore.setPrompt(key, value)
            else promptStore.resetPrompt(key)
        }
    }

    private suspend fun restoreSummaries(summary: LatestSummary?, briefing: LatestSummary?) {
        summary?.let { summaryStore.save(it.text, it.date ?: "") }
        briefing?.let { summaryStore.saveBriefing(it.text, it.date ?: "") }
    }

    /** 恢复完成后自动重启 App（先登记启动 intent，延迟再杀进程） */
    private fun restartApp() {
        try {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ?.let { context.startActivity(it) }
        } catch (_: Exception) {
        }
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 800)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date())

    /** 当前 App 版本名（备份文件记录用；读取失败回退空串） */
    private fun currentVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private companion object {
        const val TAG = "BackupManager"
    }
}
