package com.example.assistant.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.assistant.core.ui.GlassCard

/**
 * 使用说明页（v1.4.0）：
 * 面向非专业用户的 App 功能总览。刻意不包含秘密功能/隐藏入口。
 */
@Composable
fun UsageGuidePage(onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("使用说明", style = MaterialTheme.typography.titleLarge)
            }
        }

        item { GuideSection("✨ 快速上手", listOf(
            "首次使用请先到「设置 → 模型配置」添加一个 OpenAI 兼容模型（填名称、Base URL、API Key、模型名），配好后即可开始使用全部功能。",
            "打开 App 后五大页面：首页、聊天、日记、提醒、设置。",
            "聊天页：直接打字提问；点键盘上的麦克风可语音输入；点 + 可上传图片一起分析。",
            "说「记录…」「提醒我…」「识屏…」「关注 XX 新闻」等，助手会自动执行对应功能。"
        )) }

        item { GuideSection("💬 聊天与搜索", listOf(
            "所有对话使用你配置的 OpenAI 兼容模型，可在设置 → 模型配置中更换。",
            "遇到新闻、价格、天气等实时问题，助手会自己判断并联网搜索；结果不够会自动补充搜索，还能打开搜索结果里的网页阅读全文（默认免费模式，也可填 Tavily Key）。",
            "提醒、记录、记忆、监控、搜索、查日记这些操作都由助手在聊天中自行判断完成：说「提醒我明天八点开会」会自动创建，时间不对它会自我纠正；说「帮我找找上周记的 XX」它会去检索你的日记。",
            "消息中的数学公式和基础 Markdown（加粗、列表、代码等）会友好显示；长按文字可划词复制。",
            "每条回复旁有小喇叭按钮，点击可朗读；对助手说「读出来」它也会自己朗读要点。",
            "发错话了？点你最后一条消息旁的 ✏️ 编辑图标，该轮回撤、文字回到输入框，改完再发送即可。"
        )) }

        item { GuideSection("📔 日记与长期记忆", listOf(
            "日记页可直接输入记录，也可通过聊天/浮动面板记录；支持多张图片。",
            "已记录的日记可单条编辑文字、删除、添加图片。",
            "「今日小结」把当天日记整理成小结；「期间总结」可自选日期汇总并复制/导出。",
            "「记忆」页是长期记忆：系统会自动抽取重要事实，你也可以手动添加、编辑、删除。"
        )) }

        item { GuideSection("📌 进行中的事", listOf(
            "这是介于长期记忆与日记之间的第三层：记住\"你正在做什么\"——正在推进的事、需要盯一阵子的状态（比如身体不舒服），会过期、会变。",
            "助手**只在对话窗口回落时**整理一次：把即将离开上下文的对话浓缩成条目（保留关键数据与专有名词），之后每轮对话都带着它；平时不会乱写。",
            "页面在首页 →「进行中的事」：可查看、手动新增/编辑、归档、取消归档、删除，也能一键「存入日记」让某条毕业。归档＝不再注入但保留数据，删除才是彻底删掉。",
            "你在页面上的改动会**立刻**以一条系统提示告知助手（下一轮就生效），注入文本则在下次整理时刷新——这样不会打断提示词缓存。",
            "助手记录的条目会挂上相关日记的编号：需要细节时它会自己去读那几篇日记，所以缓冲区里只留结论。",
            "等不到自动整理时，可以在「进行中的事」页面点「🔄 立即整理当前对话」手动触发一次（结果提示会出现在聊天页）。",
            "设置 →「进行中的事」可开关、调整注入上限（超限只是不注入，数据一条不删）与整理阈值。"
        )) }

        item { GuideSection("⏰ 提醒与事件监控", listOf(
            "聊天说「提醒我明天下午3点开会」即可创建；也可在提醒页手动添加日期、时间、重复。",
            "提醒触发后通知可点击确认；未确认会每 5 分钟提醒一次。",
            "事件监控：说「关注 XX」后，系统定期搜索并在有重要动态时提醒。",
            "免打扰时段内提醒静默、事件不打扰，可在设置中调整。"
        )) }

        item { GuideSection("👁️ 识屏 / 带图对话", listOf(
            "识屏可截取当前屏幕并进行提取文字、翻译、总结（也可直接输入自己的问题）。",
            "截屏后默认弹出框选层：拖动选框只识别框内区域（可拖四角微调）；不框选直接「确定」= 识别全屏；可在设置 → 「识屏后框选区域」关闭。",
            "入口：聊天指令 / 快捷设置磁贴 / 分享图片 / 聊天上传图片 / 悬浮球。",
            "带图消息与文字消息走**同一条通道**，只是换成「识屏（视觉）」指派的模型：所以带图轮同样记得上下文、能调用工具、显示思考过程，也能重做/编辑/删除。",
            "建议把「对话」与「识屏」指派成同一个支持图片的模型（设置 → 模型配置）：两边共用同一套提示词缓存，最省 token；该档案需勾选「支持图片输入」。",
            "三个快捷按钮（提取文字/翻译/总结内容）的提示词可在 设置 → 提示词 → 高级设置 里自定义。",
            "每次识屏需在弹出的系统授权框中允许，之后即可截屏。"
        )) }

        item { GuideSection("🫧 悬浮球", listOf(
            "在设置或首页开启后，悬浮球会常驻在其他应用上层；可在设置 → 悬浮球中更换图标（emoji 或相册图片）。",
            "点击悬浮球打开浮动面板，四个气泡分别是：识屏、提醒、记录、对话；输入框旁的金色 🎙️ 可直接语音输入。",
            "语音输入方式三选一（设置 → 悬浮球）：键盘语音（默认，弹键盘后点键盘上的麦克风）、系统听写（部分机型支持）、远程识别（录音上传到语音识别模型，说完自动发送）。",
            "远程识别需要在 设置 → 模型配置 → 能力指派 中把「语音识别」指派给支持音频转文字的模型（编辑提供商时打开「支持语音识别」开关）。",
            "需要「显示在其他应用上层」权限；语音输入需要麦克风权限；建议为 App 开启电池无限制和自启动。"
        )) }

        item { GuideSection("🌅 每日小结与清晨简报", listOf(
            "每日小结：每天设定时间自动汇总当天日记（可选同步系统日历），也可在日记页手动生成。",
            "清晨简报：每天早上推送今日提醒 + 昨日小结；首页可随时查看历史简报。",
            "两者均可在设置中独立开关，关闭后只停止自动任务，不删除已有内容。"
        )) }

        item { GuideSection("⚙️ 设置、模型与数据", listOf(
            "模型配置：添加 OpenAI 兼容提供商（DeepSeek、通义、Kimi 等），可测试连接、设置思考深度、指派对话/识屏/语音识别能力。",
            "提示词：助手系统提示词可直接编辑；其余提示词（含识屏三个快捷动作）在「高级设置」中查看，均可恢复默认。",
            "聊天上下文长度：可设**下限/上限**（到上限后回落到下限再重新累积，这样提示词缓存才能命中），另有字符软上限兜底；聊天页标题右侧会显示当前轮数/字数/缓存命中率。",
            "会话记录默认在本机保留 7 天（可改，0 = 不留存）：超过天数、并且已经滚出模型上下文的对话会自动删除（还在上下文里的先留着，免得破坏提示词缓存）；带图对话的历史图片默认只保留最近 1 张（可在同处调整）。",
            "数据备份与导入：可手动导出全部数据到 ZIP 文件，也可开启定期自动备份到下载目录；备份不含 API Key。"
        )) }

        item {
            Text(
                "提示：如果某个功能没反应，先去设置页检查对应开关、权限和模型配置是否完整。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GuideSection(title: String, lines: List<String>) {
    GlassCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            lines.forEach { line ->
                Text(
                    "• $line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
