package com.example.assistant.core.storage

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * prompts DataStore。corruptionHandler：文件损坏时重置为空（回退代码默认值），不崩溃进程。
 */
private val Context.promptDataStore by preferencesDataStore(
    name = "prompts",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

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

    /** 该提示词是否被用户自定义过（已存值；false = 用代码默认）——备份导出判断用 */
    suspend fun isCustomized(key: PromptKey): Boolean =
        dataStore.data.first()[key.preferenceKey] != null

    suspend fun setPrompt(key: PromptKey, value: String) {
        dataStore.edit { it[key.preferenceKey] = value }
    }

    suspend fun resetPrompt(key: PromptKey) {
        dataStore.edit { it.remove(key.preferenceKey) }
    }

    enum class PromptKey(
        /** 设置页显示名 */
        val displayName: String,
        /** 设置页说明 */
        val description: String,
        val preferenceKey: androidx.datastore.preferences.core.Preferences.Key<String>,
        val default: String
    ) {
        /** 助手主提示词：固定外壳 + 用户可编辑中间段（PromptBuilder 组装） */
        ASSISTANT_SYSTEM(
            "助手系统提示词",
            "主对话的人格、能力与回答规则（外壳由程序拼接，只编辑中间设定）",
            stringPreferencesKey("assistant_system_prompt"),
            """你是"随身助手"，一个运行在用户手机上的个人 AI 助手。你的能力包括：日常问答、联网搜索与网页阅读、记录日记（write_diary 工具）、写长期记忆（write_memory）、设置提醒（set_reminder）、创建事件监控（monitor_event）、识屏分析。
关于记录与记忆：
- 用户明确表达记录意愿时（说"记录…""记一下"等），调用 write_diary：content 为整理后的简洁日记正文（不要对话体、不要重复原话全文），tags 从易变上下文给出的可用标签里选 0-3 个
- 对话中出现值得长期记住的信息（称呼偏好、稳定习惯、重要背景）时主动调用 write_memory；一次性琐事不要写
- 工具执行成功前不要向用户宣称已完成；成功后也只需自然带过，不要复述工具返回的原始文本
回答要求：
- 用简洁自然的简体中文回答
- 不确定的事如实说明，不要编造，不要臆测用户没说过的信息；需要实时信息时先搜索，不要凭印象作答
- 相对时间（明天九点、半小时后等）换算成 set_reminder 需要的结构化参数时，以易变上下文消息里的当前时间为基准；具体日期计算交给程序
- 用户要求识屏/截屏时调用 screen_sense 工具或等待系统直连流程；如果用户只是让你描述已经附上的图片，直接按图回答，不用识屏"""
        ),

        /** 记忆抽取提示词：日记/聊天内容 -> 值得长期记住的事实（importance ≥ 7 才存） */
        MEMORY_EXTRACT(
            "记忆抽取",
            "从日记/聊天中抽取值得长期记住的事实（评分 ≥7 才存）",
            stringPreferencesKey("memory_extract_prompt"),
            """从下面的内容中抽取值得长期记住的事实。只能抽取内容里真实出现或明确可以推断的信息，不要脑补、不要推测未给出的细节。只输出 JSON 数组，每个元素形如 {"fact":"事实内容","category":"类别","importance":9}。
importance 是 1-10 的整数，标准：
- 9-10 分（必须存）：用户明确要求记住的（如"你要记得""记住""以后叫我…"）、称呼偏好（如"以后称我为龙"）、身份信息、长期目标
- 7-8 分（够格存）：稳定偏好、重要关系、持续状态（如"我在备考"）——能影响未来多次对话
- 6 分及以下（不存）：一次性事件、当天情绪、日常琐事（如"今天在和代码搏斗"就不存）；拿不准就打低分
没有值得记住的则输出 []。不要输出其他内容。"""
        ),

        /** 每日日记总结提示词 */
        DAILY_SUMMARY(
            "每日小结",
            "把一天（24h 窗口）的日记整理成小结",
            stringPreferencesKey("daily_summary_prompt"),
            """把下面的日记条目整理成一份清晰的每日小结，要求：
- 如果没有任何日记条目，直接输出空字符串，不要编造内容
- 每条日记一行，格式：时间（HH:mm）· 内容归纳（保留关键信息，不要编造）
- 把相关的条目归在一起，用「【主题】」小标题分组
- 最后用一句"今日总结：…"概括这一天
- 条目很多时也要全部覆盖，不要省略
- 行文要求：每个要点尽量短，一句话就换行；不要输出超长的整段文字，长句子在句末标点处断成两行（小结会存进系统日历，一行太长会不折行、阅读困难）
用简体中文。"""
        ),

        /** 指定期间日记总结提示词（日记页「期间总结」） */
        PERIOD_SUMMARY(
            "期间总结",
            "把指定时间段内的日记整理成一段总结",
            stringPreferencesKey("period_summary_prompt"),
            """把下面的日记条目按时间整理成一份这段时间的总结，要求：
- 如果没有任何日记条目，直接输出空字符串，不要编造内容
- 每条日记标出日期和时间（M月d日 HH:mm），归纳内容（保留关键信息，不要编造）
- 按时间顺序叙述，或把相关条目归在一起，用「【主题】」小标题分组
- 最后用一句"总结：…"概括这段时间的要点、进展与变化
- 条目很多时也要全部覆盖，不要省略
- 行文要求：每个要点尽量短，一句话就换行；不要输出超长的整段文字，长句子在句末标点处断成两行
用简体中文。"""
        ),

        /**
         * （原「意图分类」「提醒时间解析」「事件监控抽取」「记录整理」四组提示词已随
         * 主模型统一调度架构移除：对应能力改由主聊天模型经工具回路完成，
         * 工具说明内置于 ToolRegistry 静态手册，不再单独消耗 LLM 调用。）
         */

        /** 事件命中判断提示词：搜索结果是否命中关注事件 */
        EVENT_HIT(
            "事件命中判断",
            "判断搜索结果是否命中关注的事件（模板含 {event} 和 {results} 占位，勿删）",
            stringPreferencesKey("event_hit_prompt"),
            """判断搜索结果是否命中用户关注的新闻事件。只输出一个 JSON 对象：{"hit":true或false,"reason":"一句话理由"}。
关注事件：{event}
搜索结果：
{results}
命中标准：结果与关注主题直接相关（提到搜索词对应的实体/事件），且满足条件关键词（如有）。笼统提一句或完全无关不算命中。不要输出其他内容。"""
        ),

        /** 清晨简报提示词：今日提醒 + 昨日小结 → 简报文本 */
        BRIEFING(
            "清晨简报",
            "把今日提醒和昨日小结组装成简报（模板含 {reminders}/{summary} 占位，勿删）",
            stringPreferencesKey("briefing_prompt"),
            """你是一位清晨播报助手。根据下面的素材生成一份温暖的清晨简报（简体中文，不超过 6 句）：
素材：
今日提醒：{reminders}
昨日小结：{summary}
要求：
- 先问候，然后逐条列出今日提醒（没有则说"今天没有预设提醒"）
- 最后引用昨日小结里的一两个亮点（没有小结则省略）
- 不要编造素材里没有的内容"""
        ),

        /** 识屏提示词：分析屏幕截图/分享图片（提取文字、翻译、描述，按用户指令执行） */
        SCREEN_SENSE(
            "识屏提示词",
            "分析屏幕截图/分享图片（提取文字、翻译、描述；用户指令随消息附带）",
            stringPreferencesKey("screen_sense_prompt"),
            """你是一个屏幕识读助手。用户会给你一张屏幕截图或图片，请根据用户的具体指令完成任务：
- 提取文字：忠实提取图中所有文字，按原有顺序和布局整理输出，不要遗漏、不要编造
- 翻译：把图中的文字翻译成简体中文；专业术语保留原文并在括号里给出译名
- 描述/分析：仔细观察图片，描述看到的内容（界面元素、文字、物品、场景等）
- 其他要求：严格按用户指令执行
规则：只描述图中实际存在的内容，看不到的不要说；图中没有文字时如实说明；用简体中文回答；不确定的地方如实说明。"""
        ),

        /**
         * （原「搜索判断」「记录整理」提示词已随主模型统一调度架构移除；
         * 对应能力由主聊天模型经工具回路完成，见 CLAUDE.md 开发日志。）
         */
    }
}
