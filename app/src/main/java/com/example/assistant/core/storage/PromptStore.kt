package com.example.assistant.core.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.promptDataStore by preferencesDataStore(name = "prompts")

/**
 * 可编辑内置提示词存储（DataStore）。
 * 四组提示词均有默认值，用户在设置页可编辑。
 * 注意：主对话提示词 = 固定模板外壳 + 用户可编辑中间段（见 PromptBuilder），
 * 外壳保持稳定以保证缓存前缀不变。
 */
class PromptStore(context: Context) {

    private val dataStore = context.applicationContext.promptDataStore

    fun promptFlow(key: PromptKey): Flow<String> =
        dataStore.data.map { it[key.preferenceKey] ?: key.default }

    suspend fun prompt(key: PromptKey): String = promptFlow(key).first()

    suspend fun setPrompt(key: PromptKey, value: String) {
        dataStore.edit { it[key.preferenceKey] = value }
    }

    suspend fun resetPrompt(key: PromptKey) {
        dataStore.edit { it.remove(key.preferenceKey) }
    }

    enum class PromptKey(
        val preferenceKey: androidx.datastore.preferences.core.Preferences.Key<String>,
        val default: String
    ) {
        /** 助手主提示词：固定外壳 + 用户可编辑中间段（PromptBuilder 组装） */
        ASSISTANT_SYSTEM(
            stringPreferencesKey("assistant_system_prompt"),
            """你是"随身助手"，一个运行在用户手机上的个人 AI 助手。你的能力包括：日常问答、写日记、设置提醒、识屏分析。
回答要求：
- 用简洁自然的简体中文回答
- 不确定的事如实说明，不要编造
- 涉及时间请以用户提供的当前时间为准"""
        ),

        /** 记忆抽取提示词：日记/聊天内容 -> 值得长期记住的事实 */
        MEMORY_EXTRACT(
            stringPreferencesKey("memory_extract_prompt"),
            """从下面的内容中抽取值得长期记住的事实（如：用户的重要信息、偏好、关系、正在做的事）。只输出 JSON 数组，每个元素形如 {"fact":"事实内容","category":"类别"}。没有值得记住的则输出 []。不要输出其他内容。"""
        ),

        /** 每日日记总结提示词 */
        DAILY_SUMMARY(
            stringPreferencesKey("daily_summary_prompt"),
            """把今天的所有日记条目整理成一份简洁的每日小结：按主题归纳、每条一句话，最后用一句话概括今天。用简体中文。"""
        ),

        /** 意图分类提示词：关键词未命中时兜底分类 */
        INTENT_CLASSIFIER(
            stringPreferencesKey("intent_classifier_prompt"),
            """判断用户这句话的意图，只输出一个 JSON 对象：{"intent":"chat|record_diary|set_reminder|screen_sense|monitor_event","reason":"一句话理由"}。规则：record_diary=记录/写日记；set_reminder=设置提醒/闹钟；screen_sense=识屏/截屏/翻译屏幕内容；monitor_event=搜索/关注某个话题或新闻事件；chat=其他日常问答。不要输出其他内容。"""
        )
    }
}
