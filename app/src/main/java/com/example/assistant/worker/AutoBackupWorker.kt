package com.example.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.assistant.AssistantApplication
import kotlinx.coroutines.flow.first

/**
 * 定期自动备份 Worker（周期 1/3/7 天，由 AssistantApplication 调度）：
 * 把全量数据（不含 API Key）导出到公共「下载」目录 backup_<时间戳>.zip，
 * 保留最近 3 份。只生成文件，不重启 App（恢复仍手动触发）。
 * 失败不重试（下次周期再试），与 EventPollWorker 策略一致。
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as AssistantApplication).container
        return try {
            // 运行前复核开关：关闭时跳过一次（兜底 cancelUniqueWork 的取消竞态）
            if (!container.settingsStore.autoBackupEnabled.first()) return Result.success()
            container.backupManager.exportToDownloadDir(keepCount = 3)
            Result.success()
        } catch (e: Exception) {
            // 备份失败静默（不打扰用户），下次周期自动再试
            Result.success()
        }
    }
}
