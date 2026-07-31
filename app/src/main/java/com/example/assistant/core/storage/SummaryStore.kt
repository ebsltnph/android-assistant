package com.example.assistant.core.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.summaryDataStore by preferencesDataStore(name = "summaries")

/**
 * 每日小结存储：保存最新一份小结全文（通知栏只能展开看一部分，
 * 这里保留完整文本，供 App 内查看与通知点击跳转）。
 */
class SummaryStore(context: Context) {

    private val dataStore = context.applicationContext.summaryDataStore

    /** 最近一次生成的小结全文（无则 null） */
    val latestSummary: Flow<String?> =
        dataStore.data.map { it[KEY_SUMMARY_TEXT] }

    /** 该小结对应的日期（yyyy-MM-dd） */
    val latestSummaryDate: Flow<String?> =
        dataStore.data.map { it[KEY_SUMMARY_DATE] }

    suspend fun save(text: String, date: String) {
        dataStore.edit {
            it[KEY_SUMMARY_TEXT] = text
            it[KEY_SUMMARY_DATE] = date
        }
    }

    suspend fun currentSummary(): String? = latestSummary.first()

    companion object {
        private val KEY_SUMMARY_TEXT = stringPreferencesKey("latest_summary_text")
        private val KEY_SUMMARY_DATE = stringPreferencesKey("latest_summary_date")
    }
}
