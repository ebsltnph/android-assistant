package com.example.assistant.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.backup.BackupFile
import com.example.assistant.core.backup.BackupManager
import com.example.assistant.core.storage.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 备份页状态容器：导出/恢复执行状态 + 待确认的备份预览 + 自动备份开关与间隔。
 * 恢复是破坏性操作：先 preview（只读解析）→ 确认框 → confirmRestore 才真正执行。
 */
class BackupViewModel(
    private val backup: BackupManager,
    private val settingsStore: SettingsStore
) : ViewModel() {

    /** 正在执行导出/恢复（防重复点击） */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** 操作结果提示（成功/失败，中文） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** 待确认的备份（非 null = 弹确认对话框） */
    private val _preview = MutableStateFlow<BackupFile?>(null)
    val preview: StateFlow<BackupFile?> = _preview

    private var pendingUri: Uri? = null

    // ---- 自动备份设置 ----
    val autoBackupEnabled: StateFlow<Boolean> = settingsStore.autoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val autoBackupIntervalDays: StateFlow<Int> = settingsStore.autoBackupIntervalDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7)

    /** 手动导出到用户选的 uri（SAF CreateDocument） */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                backup.exportTo(uri)
                _message.value = "已导出备份 ✅"
            } catch (e: Exception) {
                _message.value = e.message ?: "导出失败"
            } finally {
                _busy.value = false
            }
        }
    }

    /** 用户选了备份文件：只读解析（不碰数据），弹确认框 */
    fun previewBackup(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                pendingUri = uri
                _preview.value = backup.preview(uri)
            } catch (e: Exception) {
                _message.value = e.message ?: "无法读取备份"
            } finally {
                _busy.value = false
            }
        }
    }

    /** 确认恢复（覆盖式，完成后自动重启 App） */
    fun confirmRestore() {
        val uri = pendingUri ?: return
        val data = _preview.value ?: return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                backup.restore(uri, data)
                // restore 完成后自动重启 App，这里不再设 message（进程马上被杀）
            } catch (e: Exception) {
                _message.value = e.message ?: "恢复失败"
                _busy.value = false
            }
        }
    }

    /** 取消恢复确认 */
    fun dismissPreview() {
        pendingUri = null
        _preview.value = null
    }

    fun setAutoBackupEnabled(v: Boolean) {
        viewModelScope.launch { settingsStore.setAutoBackupEnabled(v) }
    }

    fun setAutoBackupIntervalDays(v: Int) {
        viewModelScope.launch { settingsStore.setAutoBackupIntervalDays(v) }
    }
}
