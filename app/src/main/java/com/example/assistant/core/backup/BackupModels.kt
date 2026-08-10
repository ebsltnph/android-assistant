package com.example.assistant.core.backup

import com.example.assistant.core.network.ProviderProfile
import kotlinx.serialization.Serializable

/**
 * 备份文件格式版本（与 App 版本解耦；schema 变化时递增）。
 * 恢复时 version > BACKUP_VERSION 拒绝（备份来自更新版本）。
 */
const val BACKUP_VERSION = 1

/**
 * 备份文件根（zip 里的 backup.json 就是它）。
 * 所有字段带默认值 + Json ignoreUnknownKeys=true：
 * 未来 App 加字段不破坏旧备份；旧 App 读新备份靠 version 拦住。
 */
@Serializable
data class BackupFile(
    /** 固定标识，防止用户选错文件（如选了别的 zip） */
    val format: String = "assistant_backup",
    val version: Int = BACKUP_VERSION,
    /** 备份时间（仅展示） */
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    /** 备份时的 App 版本名（仅展示） */
    val appVersion: String = "",
    val settings: BackupSettings = BackupSettings(),
    /** PromptKey.name -> 已自定义内容（未自定义的组不导出） */
    val prompts: Map<String, String> = emptyMap(),
    /** 提供商档案（apiKey 结构上不存在——备份不含密钥） */
    val providerProfiles: List<BackupProviderProfile> = emptyList(),
    val diaryBooks: List<com.example.assistant.data.db.entity.DiaryBookEntity> = emptyList(),
    val diaryEntries: List<com.example.assistant.data.db.entity.DiaryEntryEntity> = emptyList(),
    /** path 为备份机绝对路径，恢复时按文件名重映射到本机 */
    val diaryImages: List<com.example.assistant.data.db.entity.DiaryImageEntity> = emptyList(),
    val memories: List<com.example.assistant.data.db.entity.MemoryEntity> = emptyList(),
    val reminders: List<com.example.assistant.data.db.entity.ReminderEntity> = emptyList(),
    val monitoredEvents: List<com.example.assistant.data.db.entity.MonitoredEventEntity> = emptyList(),
    val eventHits: List<com.example.assistant.data.db.entity.EventHitEntity> = emptyList(),
    val dailySummaries: List<com.example.assistant.data.db.entity.DailySummaryEntity> = emptyList(),
    /** zip 里是否有 secret_log/chat_history.txt */
    val secretLogIncluded: Boolean = false,
    /** SummaryStore 最近一份小结（App 内展示的缓存，与 dailySummaries 历史表重复，低优先级） */
    val latestSummary: LatestSummary? = null,
    val latestBriefing: LatestSummary? = null
)

/** 普通设置（DataStore，含自动备份配置） */
@Serializable
data class BackupSettings(
    /** Capability.name -> profileId（空串 = 未指派） */
    val capabilityProfileIds: Map<String, String> = emptyMap(),
    val ttsEnabled: Boolean = false,
    /** 每日小结时间（分钟数，默认 21:00） */
    val dailySummaryMinute: Int = 21 * 60,
    /** 清晨简报时间（分钟数，默认 7:30） */
    val briefingMinuteOfDay: Int = 7 * 60 + 30,
    /** 免打扰（默认 23:00-07:00） */
    val quietStartMinute: Int = 23 * 60,
    val quietEndMinute: Int = 7 * 60,
    val reasoningEffort: String = "default",
    val floatingBallEnabled: Boolean = false,
    val conversationMaxTurns: Int = 10,
    val secretLogEnabled: Boolean = false,
    /** 定期自动备份开关与间隔（天） */
    val autoBackupEnabled: Boolean = false,
    val autoBackupIntervalDays: Int = 7
)

/**
 * 提供商档案（备份版）：**刻意没有 apiKey 字段**——编译期保证密钥不会进备份。
 * 从 ProviderProfile 转换见 toBackup()/toProfile()。
 */
@Serializable
data class BackupProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val supportsVision: Boolean = false,
    val isDefault: Boolean = false,
    val reasoningEffort: String = "default"
)

/** 导出用：档案 → 备份 DTO（丢掉 apiKey；thinkingMode 字段已废弃不导出） */
fun ProviderProfile.toBackup() = BackupProviderProfile(
    id = id, name = name, baseUrl = baseUrl, model = model,
    supportsVision = supportsVision, isDefault = isDefault,
    reasoningEffort = reasoningEffort
)

/** 恢复用：备份 DTO → 档案（apiKey 置空，用户恢复后重填） */
fun BackupProviderProfile.toProfile() = ProviderProfile(
    id = id, name = name, baseUrl = baseUrl, model = model,
    supportsVision = supportsVision, isDefault = isDefault,
    reasoningEffort = reasoningEffort
)

/** SummaryStore 里带日期的小结/简报 */
@Serializable
data class LatestSummary(val text: String, val date: String? = null)

/** 备份/恢复过程错误（message 直接面向用户，中文） */
class BackupException(message: String) : Exception(message)
