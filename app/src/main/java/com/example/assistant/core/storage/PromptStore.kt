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
            """你是"随身助手"，一个运行在用户手机上的个人 AI 助手。你的能力包括：日常问答、记录日记、设置提醒、识屏分析、关注新闻事件。
关于记录和记忆：
- 当用户说"记录…""记一下"等，或主动分享值得记下的事情时，先确认收到，再继续回答；你会自动把该内容写入日记本
- 你会自动判断哪些信息值得长期记住（只存重要且长期有用的），不需要用户专门提醒
回答要求：
- 用简洁自然的简体中文回答
- 不确定的事如实说明，不要编造
- 涉及时间请以用户提供的当前时间为准"""
        ),

        /** 记忆抽取提示词：日记/聊天内容 -> 值得长期记住的事实（importance ≥ 7 才存） */
        MEMORY_EXTRACT(
            "记忆抽取",
            "从日记/聊天中抽取值得长期记住的事实（评分 ≥7 才存）",
            stringPreferencesKey("memory_extract_prompt"),
            """从下面的内容中抽取值得长期记住的事实。只输出 JSON 数组，每个元素形如 {"fact":"事实内容","category":"类别","importance":9}。
importance 是 1-10 的整数，标准：
- 9-10 分（必须存）：用户明确要求记住的（如"你要记得""记住""以后叫我…"）、称呼偏好（如"以后称我为龙"）、身份信息、长期目标
- 7-8 分（够格存）：稳定偏好、重要关系、持续状态（如"我在备考"）——能影响未来多次对话
- 5 分以下（不存）：一次性事件、当天情绪、日常琐事（如"今天在和代码搏斗"就不存）
拿不准就打低分。没有值得记住的则输出 []。不要输出其他内容。"""
        ),

        /** 每日日记总结提示词 */
        DAILY_SUMMARY(
            "每日小结",
            "把一天（24h 窗口）的日记整理成小结",
            stringPreferencesKey("daily_summary_prompt"),
            """把下面的日记条目整理成一份清晰的每日小结，要求：
- 每条日记一行，格式：时间（HH:mm）· 内容归纳（保留关键信息，不要编造）
- 把相关的条目归在一起，用「【主题】」小标题分组
- 最后用一句"今日总结：…"概括这一天
- 条目很多时也要全部覆盖，不要省略
用简体中文。"""
        ),

        /** 意图分类提示词：关键词未命中时兜底分类 */
        INTENT_CLASSIFIER(
            "意图分类",
            "关键词未命中时判断用户意图（记录/提醒/监控/聊天）",
            stringPreferencesKey("intent_classifier_prompt"),
            """判断用户这句话的意图，只输出一个 JSON 对象：{"intent":"chat|record_diary|set_reminder|screen_sense|monitor_event","reason":"一句话理由"}。规则：record_diary=记录/写日记；set_reminder=设置提醒/闹钟；screen_sense=识屏/截屏/翻译屏幕内容；monitor_event=持续关注某个话题或新闻事件（明确的"关注/帮我留意/盯"类长期监控请求）；chat=其他日常问答（包括"查一下/搜一下"这种一次性查询，会走联网搜索）。不要输出其他内容。"""
        ),

        /** 提醒时间解析提示词：把自然语言时间解析成结构化描述（时间戳由代码计算，模型不算日期） */
        REMINDER_PARSE(
            "提醒时间解析",
            "把自然语言时间解析成结构化描述（时间戳由代码计算）",
            stringPreferencesKey("reminder_parse_prompt"),
            """把用户的提醒需求解析成结构化 JSON。只输出一个 JSON 对象：{"title":"提醒内容","dayOffset":数字,"hour":数字,"minute":数字,"offsetMinutes":数字或null,"repeat":"daily或weekly或null","weekday":数字}。
规则：
- dayOffset：相对今天的偏移（0=今天，1=明天，2=后天）；"X分钟后"时 offsetMinutes 填分钟数，dayOffset/hour/minute 都填 0
- hour/minute：24 小时制（"下午3点"=15和0；"12点半"=12和30）
- "每天上午9点"→repeat=daily，hour=9；"每周一上午10点"→repeat=weekly，weekday=1（周一=1，周日=7），dayOffset=0
- title 是去掉时间表述后的提醒内容（"明天下午3点开会"→"开会"）
不要输出其他内容。"""
        ),

        /** 事件监控抽取提示词：把"关注 XX"需求解析成结构化 JSON */
        MONITOR_EXTRACT(
            "事件监控抽取",
            "把「关注 XX」需求解析成搜索配置（名称/搜索词/规则/域名）",
            stringPreferencesKey("monitor_extract_prompt"),
            """把用户的"关注/监控"需求解析成结构化 JSON。只输出一个 JSON 对象：{"displayName":"显示名称","searchQuery":"搜索词","conditionKeywords":"命中关键词（逗号分隔，没有则空字符串）","customRule":"自定义判断规则（空字符串）","includeDomains":"限定来源域名（逗号分隔，空字符串）"}。
规则：
- displayName 是简短的关注事项名称（如"华为新品发布"）
- searchQuery 是给搜索引擎的搜索词（精简，如"华为 新品 发布"）
- conditionKeywords 是"出现什么内容算命中"的关键词；用户没给具体条件时留空（交给智能判断）
- customRule：用户额外要求的判断标准原文；**当用户说"只关注/只看"某个具体页面、文档或来源时，必须把该标准写入 customRule**（如"只关注 /zh-cn/updates 更新文档的内容变更"、"忽略谣言"、"只看官方公告"）；用户没提时留空
- includeDomains：用户指定只看的网站域名（从 URL 提取域名，如 https://api-docs.deepseek.com/zh-cn/updates → api-docs.deepseek.com）；没指定时留空
不要输出其他内容。"""
        ),

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
规则：只描述图中实际存在的内容，看不到的不要说；用简体中文回答；不确定的地方如实说明。"""
        ),

        /** 搜索判断提示词：每条对话消息先判断是否需要联网搜索（全 LLM 判断） */
        SEARCH_JUDGE(
            "搜索判断",
            "每条消息先判断是否需要联网搜索（触发词规则）",
            stringPreferencesKey("search_judge_prompt"),
            """判断用户这句话是否需要联网搜索。只输出一个 JSON 对象：{"need_search": 布尔值, "query": "搜索词（不需要搜索则为空字符串）", "reason": "一句话理由"}。
强制规则（满足任意一条就必须 need_search=true）：
- 消息包含"查一下""搜一下""搜索""最新""最近""怎么样了""多少钱""天气""新闻""进展""发布"等查询词
- 询问任何需要实时/最新/外部信息的问题（新闻事件、产品发布、价格、天气、人物背景、技术动态等）
不需要搜索：日常闲聊、记录/提醒类、纯计算/翻译/写作。
query 是精简后的搜索关键词（去掉语气词）。不要输出其他内容。"""
        ),

        /** 记录整理提示词：把「用户消息 + 助手回复」一轮对话整理成简洁日记条目（纯文本输出） */
        DIARY_SUMMARIZE(
            "记录整理",
            "把「用户消息+助手回复」一轮对话整理成简洁的日记条目",
            stringPreferencesKey("diary_summarize_prompt"),
            """把下面的一轮对话整理成一条简洁的日记条目，只记录用户想记录的内容：
- 用户消息：可能包含"记录""记一下"等指令词，忽略指令词，提炼用户真正要记录的事实、决定、安排或事件
- 助手回复：只作补充参考（可能确认或回放用户要记录的内容），不要编造对话中没有的信息
- 用简体中文，一两句话；不要任何解释或前缀，直接输出整理后的文字"""
        )
    }
}
