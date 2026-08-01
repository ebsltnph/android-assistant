# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概况

**随身助手**：一个安卓 AI 助手 App（用户自用），核心功能：
1. 语音唤起与输入（分阶段：先悬浮球、后真唤醒词）
2. 询问模式（语音/文字 → LLM 回答，可选 TTS）
3. 日记与长期记忆（多日记本、每日整理、记忆抽取注入）
4. 事件提醒（定时提醒 + 新闻类事件周期监控）
5. 智能识屏（MediaProjection 截屏 → 视觉模型 OCR/翻译/对话）

关键约束（用户明确要求）：
- **OpenAI 兼容 API 为主**，用户应用内填 base URL + API Key；**对话与识屏可分开配置不同提供商/API Key**（能力级配置）
- **语音输入优先复用手机输入法语音**（免麦克风权限），`SpeechRecognizer` 仅作备选
- **后台尽量省电**：默认无常驻麦克风、WorkManager 合并任务、非关键提醒用低功耗闹钟
- **提示词结构服务缓存命中率**（见下）
- 用户是**非专业程序员**，代码应保持简单、注释清楚、中文注释
- 测试设备：**荣耀 X50 GT**（国内版，无 GMS——不用 ML Kit 等 GMS 依赖；OCR 走视觉模型）
- 用户要求：善用 GitHub 开源实现复用；项目托管在用户 GitHub 账号

## 常用命令

（开发机 Windows：**JAVA_HOME 未设到 PATH**，构建前必须 `export JAVA_HOME="/c/Users/98662/Tools/jdk-21.0.12+8"`（gradle.properties 已写 org.gradle.java.home，但 gradlew 启动仍需环境变量）；**adb 不在 PATH**，用全路径 `C:\Users\98662\AppData\Local\Android\Sdk\platform-tools\adb.exe`）

```bash
export JAVA_HOME="/c/Users/98662/Tools/jdk-21.0.12+8"
./gradlew assembleDebug        # 构建调试 APK
./gradlew assembleRelease      # 构建发布 APK
./gradlew test                 # 运行单元测试
./gradlew lintDebug            # 静态检查
ADB="C:/Users/98662/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$ADB install -r app/build/outputs/apk/debug/app-debug.apk   # 装机
$ADB logcat --pid=$($ADB shell pidof com.example.assistant) # 看 App 日志
$ADB shell run-as com.example.assistant cat files/datastore/settings.preferences_pb | od -c  # 查设置值（末字节 10 进制=小时）
$ADB shell dumpsys jobscheduler | grep -A20 "JOB androidx.work.systemjobscheduler:u0a291" | grep -E "Minimum latency|Enqueue"  # 验证周期任务排程
```

## 技术栈与版本

- Kotlin 2.1 + Jetpack Compose（Material 3），单模块 `:app`
- Gradle 8.11.1 / AGP 8.9.2 / compileSdk 35 / minSdk 26 / targetSdk 35
- 依赖统一在 `gradle/libs.versions.toml`（版本目录）管理
- 后续阶段会加入：Room + KSP、WorkManager、Retrofit/OkHttp + kotlinx-serialization、DataStore、EncryptedSharedPreferences、Navigation Compose

## 架构

包结构（`app/src/main/java/com/example/assistant/`）：

```
AssistantApplication.kt / MainActivity.kt   # 单 Activity + 底部导航 5 页
di/AppContainer.kt                          # 手动 DI（所有单例在此创建，未用 Hilt）
core/network/   # 网络层（OpenAI 兼容客户端，能力级提供商 ProviderRegistry）
core/agent/     # Agent 编排：意图路由、PromptBuilder、记忆注入
core/speech/    # 语音（输入法语音引导 + SpeechRecognizer 备选 + TTS）
core/storage/   # SettingsStore / SecretStore(加密) / PromptStore
core/notification/ # 通知渠道
data/db/ + data/repo/   # Room 持久化
feature/        # home/chat/diary/memory/reminder/screensense/settings/wake
service/        # ScreenCaptureService、FloatingBubbleService、WakeWordService(后期)
receiver/       # ReminderReceiver、BootReceiver
worker/         # DailySummaryWorker、MorningBriefingWorker、EventPollWorker
share/ tiles/   # 分享到助手、快捷设置磁贴
```

设计要点：
- **单一 API 契约**：所有功能复用同一套 OpenAI 兼容接口；`ProviderRegistry` 按能力（对话/识屏/分类）解析提供商档案，按 baseUrl 缓存 Retrofit 实例
- **提示词缓存结构**（`PromptBuilder` 保证）：
  `messages[0]` 静态系统提示词（缓存）→ `messages[1]` 长期记忆块（缓存）→ `messages[2]` 当前上下文（日期时间等易变内容，**绝不放系统提示词里**）→ `messages[3..n]` 对话尾部（截断只删尾部）
- **意图路由**：关键词快速路由（记录/提醒/识屏/搜索）→ LLM 分类兜底 → 聊天兜底

## 开发阶段状态

- [x] P0 环境搭建（Android Studio 安装中）
- [x] P1 骨架（底部导航 5 页）
- [x] P2 设置 + 文字聊天（LLM 管线：流式回复、多轮上下文、思考过程分段显示）
- [x] P3 日记 + 长期记忆（多日记本、记忆抽取注入、每日小结）
  - [x] P3 增强：每日小结可配置时间（设置页 0-23 点）、历史落库 Room（每天唯一）、镜像系统日历（全天事件）、通知点击看完整小结（已推送 GitHub）
  - [x] P3 体验修复（2026-08-01 真机验证通过）：① 每日小结改 **24h 滑动窗口**（总结时刻往前 24h，避免设 0 点时无数据）；② 记录判断交给 LLM——说"记录…"不再拦截聊天，**聊天照常回复 + 同步写原文入日记**（AgentResult.ChatRequested 带 recordHint），无关键词的消息由 LLM 分类判定；③ **记忆重要性过滤**——LLM 给候选记忆评 1-10 分，**≥7 才存**（防"今天在和代码搏斗"进长期记忆），阈值在 MemoryExtractor.IMPORTANCE_THRESHOLD；④ **全对话后台记忆抽取**（不只记录类消息，防"你要记得"漏掉）；⑤ 系统提示词默认值更新 + 提示词编辑框 bug 修复（打开时读已存值）
  - [x] P3 高级设置（2026-08-01）：**思考开关 + 思考深度**（设置页「高级设置」分区；ProviderRegistry.thinkingParams() 统一应用，ChatRequest 加 thinking/reasoning_effort 字段，只在该项非"default"时发送；用户实测 v4 flash 关思考质量不差、速度更快）
- [x] **P4 提醒 + 对话搜索 + 事件监控 + 清晨简报**（2026-08-01 完成并真机验证）
  - P4 增强（2026-08-01）：**提醒需手动确认**——提醒触发后通知可点击（进 App 弹「提醒确认」窗），**未确认每 5 分钟重复通知**（ReminderScheduler.scheduleAckRepeat 独立 requestCode；ReminderReceiver 首次触发写日记/重排 + **续排 5min——重复触发分支也要续排，否则只重复一次**；确认后 ack + cancelAckRepeat + 一次性 markFired）；DB v4（ackedAtEpochMillis 列）；僵尸清理只清已确认的；启动/Boot 恢复未确认提醒的重复闹钟；重复提醒每天触发后需重新确认
  - **对话搜索**：SearchClient 接口 + Tavily（keyless 免注册兜底/填 key 1000 次每月）；全 LLM 判断触发（SearchJudger，所有聊天消息先判断）；结果注入 PromptBuilder extraContext（不破坏缓存前缀）
  - **定时提醒**：聊天"提醒我X"→ ReminderTimeParser **结构化解析+本地算时间戳**（模型日期算术不可靠，曾算出 7月3日）；AlarmManager 精确闹钟（未授权 setWindow 兜底+提醒页引导授权）；重复提醒（daily/weekly 触发后重排）；BootReceiver 开机重排；**触发自动写日记**（source=reminder）；僵尸提醒自动清理（启动时）
  - **事件监控**：EventPollWorker 周期 6h（事件级 pollHours 过滤）；Tavily news 搜索 + LLM 命中判断（有 conditionKeywords 走本地关键词）；24h 通知去重；**自定义规则 customRule + 限定域名 includeDomains**（Room v3 迁移；聊天创建自动提取，本地 URL 正则兜底）；周期数字输入框（1-168h）；提醒页「立即检查」
  - **清晨简报**：MorningBriefingWorker（默认 7:30 分钟可配）；**昨日**小结（严格取昨天）+ 今日提醒 → LLM 组装；**落库首页随时可看** + 通知点击弹窗
  - **免打扰**：默认 23:00-07:00 分钟可配；提醒静默渠道、事件命中跳过
  - 所有时间设置**精确到分钟**（MinutePicker 组件：时+分下拉）
- [x] P5 智能识屏 + 分享到助手（2026-08-01 完成并真机验证，已推送）
  - 识屏四入口：聊天指令/快捷磁贴/分享图片/聊天上传图片
  - **识屏悬浮小窗**：截屏后弹在任意 App 上层（提取文字/翻译/描述直接分析显示，不用回 App）；「在 App 中继续」回聊天**附件栏**等命令（用户确认的交互：截图不自动发消息）
  - 分享到助手：文本预填输入框；图片进附件栏，**文字+图片一起发给视觉模型**（不自动分析）
  - 识屏能力指派（Capability.VISION）+ 视觉模型引导 + SCREEN_SENSE 提示词（第 10 组可编辑）
  - 横屏识屏不旋转（fullSensor，见平台注意事项）
- [x] **P6 悬浮球 + 浮动界面**（2026-08-02 完成并真机验证，待推送）
  - **悬浮球**：FloatingBallService（FGS specialUse + PROPERTY_SPECIAL_USE_FGS_SUBTYPE，通知 3004）；OverlayWindow 通用悬浮窗（从 P5 小窗抽取 OverlayOwners/addView/moveBy）；玻璃拟态渐变球，拖拽贴边半隐藏（露出 32dp——**太窄会落在返回手势区点击无反应**）；点击/拖动用**总位移阈值**判定（detectDragGestures 的 onDragCancel 判定点击不可靠——荣耀触摸噪声会把点击判成拖动）
  - **浮动界面**：FloatingPanelActivity（透明 Activity + 独立 task `taskAffinity=""` + excludeFromRecents + fullSensor + adjustResize + configChanges）——系统返回手势原生退出回原 App；深墨夜景变暗层 + 噪点蒙层 + **7 个动态光斑**（顺时针/逆时针沿边缘游走 + Lissajous 穿越，大小/亮度/速度各异，小斑更亮）；四气泡（识图/提醒/记录/对话）显示于背景上（无大框）；glassmorphism 风格（白 5-12% 玻璃 + 1px 白描边 + 大圆角 + 香槟金 #E4B863 强调色）
  - **面板与聊天同一会话**：ChatViewModel 提升为 AppContainer 进程级共享单例（去 ViewModel 继承，自建 scope）；quickSend/createReminderNow/writeDiaryNow/quickSendVision（识图模式带图对话）/quickAnalyzeResult（按钮分析结果 = 截图+提示词+结果完整入聊天记录）
  - **识屏统一**：ScreenSenseStarter（requestCapture/finishAuth/abort/instructionFor）；P5 小窗退役删除；**悬浮球路径走独立 task 权限 Activity（MediaProjectionPermissionActivity，不闪现 App 界面）**；**聊天/磁贴路径走 MainActivity 内授权（主 task，moveTaskToBack 才正确回上一个 App）**——两者不能互换（独立 task 的 moveTaskToBack 退空 task，主 task 不动会截到助手自己）
  - **panelState 状态机**（HIDDEN/PANEL_OPEN/CAPTURING）：驱动悬浮球显隐（截屏期隐藏防截进截图）；**onDestroy 不一定执行**（系统回收 Activity）→ onStop 恢复状态 + FloatingPanelActivity.isPanelOpen 自愈 + CAPTURING 60s 超时兜底；**onDestroy 只重置自己置的状态**（无条件重置会覆盖 CAPTURING，悬浮球提前重现被截进截图）
  - 截屏延迟按入口区分：悬浮球 1.2s（通知栏已收起）/ 聊天磁贴 2.5s（通知栏可能展开）
  - 输入"识屏/识图"类指令：本地关键词直连 + **LLM 分类命中（screenSenseRequested 事件）也直连**浮动界面识图流程（不依赖后台 MainActivity 中转）
  - 设置页「悬浮球」开关卡片 + Application/BootReceiver 开机自启
- [ ] P7 真·唤醒词（可选）

GitHub：https://github.com/ebsltnph/android-assistant（master，用户要求每个功能阶段完成后推送）
详细计划见 `C:\Users\98662\.claude\plans\indexed-booping-mccarthy.md`。

**下次会话待办**：① P6 已完成并真机验证（2026-08-02），待推送 GitHub；② P7 真·语音唤醒词（可选，真机验证语音方案）；③ 通知栏收起改进（升级 SDK 36 后用 registerActivity 官方 API，见平台注意事项）；④ 桌面小部件（原 P6 范围，用户决定本次不做，留待后续）。

## 平台注意事项（荣耀 X50 GT / MagicOS）

- **USB 驱动（已踩坑解决）**：Windows 上荣耀手机必须装「荣耀手机助理 HonorSuite」（官网 honor.com/cn/tech/honor-suite/）才有正确的 ADB 驱动（VID_339B）。Google USB Driver 的 INF 不含荣耀 VID，Microsoft 通用 WinUSB 驱动枚举正常但 adb 认不到（接口 GUID 不匹配）。装 HonorSuite 后 `adb devices` 即可识别，平时无需打开该软件。另需关闭开发者选项的 HDB 开关（HDB 会干扰 ADB 通道）。安装 APK 时开发者选项需开「USB 安装」开关，否则报 INSTALL_FAILED_ABORTED: User rejected permissions
- 无 GMS：不用 ML Kit；TTS 需用户启用荣耀/华为语音引擎
- `SCHEDULE_EXACT_ALARM` 默认拒绝：先 `canScheduleExactAlarms()`，否则跳设置页申请，兜底 `setWindow`
- `POST_NOTIFICATIONS` 需运行时申请（否则提醒/简报静默失败）
- MediaProjection（API 34+）：FGS 必须声明 `foregroundServiceType="mediaProjection"` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限
- MagicOS 后台限制：引导用户给 App 开"电池无限制 + 自启动"；后台功能要测强杀场景
- **系统日历（CalendarContract，已踩坑）**：① 写事件要求 **READ_CALENDAR + WRITE_CALENDAR 两个权限同时授予**（只给 WRITE 会 SecurityException 静默失败），授权按钮用 RequestMultiplePermissions 同时请求；② **全天事件必须用 UTC 毫秒存储**（`EVENT_TIMEZONE="UTC"`，DTSTART/DTEND 用 UTC 时区构建当天 0 点），对本地毫秒做 ±时区偏移很容易把日期算到昨天；③ 荣耀日历 App 按 UTC 日期渲染，验证用 `run-as` + logcat（`adb shell content query` 被 Android 13 权限拒绝）
- **WorkManager 周期任务重排（已踩坑）**：① 不要用 `cancelUniqueWork` + `enqueueUniquePeriodicWork(KEEP)`——cancel 是异步的，KEEP 会先看到旧任务而拒绝替换，竞态导致改时间不生效；用 **REPLACE 策略**（原子替换）；② 重排时不要把 DataStore 异步读写在同一个 coroutine 链里（读到旧值），由 UI 直接把用户刚选的值传入；③ 验证排程：`dumpsys jobscheduler` 看 `Minimum latency` 推算触发时刻，设置值查 DataStore 文件字节
- **logcat 被 ROM 屏蔽（已踩坑）**：荣耀 MagicOS 默认 `persist.log.tag=M`，**App 进程的 Log.i/Log.w 在 adb logcat 里完全不可见**（只能看到系统注入的 RtgSched 等），`setprop log.tag.XXX VERBOSE` 也救不回已运行进程。调试信息要**显示到 App 界面**（如今日小结兜底文本带失败原因、事件创建回复显示提取结果），验证靠拉数据库（`run-as` cat + Python sqlite3，**DataStore 的 int 值存成 varint 且外层套 Value message：键后 = 0x12 <len> 0x18 <varint>**）
- **推理模型非流式请求（已踩坑）**：deepseek-v4-flash 是推理模型，**reasoning 思考过程占 max_tokens 配额**——maxTokens 小了会 `finish_reason=length` 且 content 为空（内容全在思考里）。非流式请求 maxTokens 要给足：小结 4096、记忆抽取 1024、意图分类/搜索判断/时间解析 1024、测试连接 512（流式聊天 2048 够用）
- **v4 flash 的 JSON 输出（已踩坑）**：① `response_format=json_object` 支持不稳定——曾输出自相矛盾的 JSON（理由说要搜索却 need_search=false）和空 content；② 日期算术不可靠（"2分钟后"算成 7月3日）。对策：**JsonExtract 健壮解析**（剥围栏/提取 {} 子串/容忍字符串数字）+ **时间戳本地计算**（模型只输出结构化描述 dayOffset/hour/minute）+ **启发式兜底**（query 非空视为要搜索）
- **force-stop 清闹钟（踩坑）**：`adb shell am force-stop` 会清掉 App 已排的 AlarmManager 闹钟（DB 里还是 pending）——装机验证提醒时**不要 force-stop**（`install -r` 本身够，会杀进程但保留闹钟）
- **TileService 磁贴（踩坑）**：① API 34+ `startActivityAndCollapse(Intent)` 弃用并**直接抛 UnsupportedOperationException**（点击磁贴即崩溃、表现为"磁贴无法点击"）；官方替代（`registerActivity(PendingIntent)` + 无参 `startActivityAndCollapse()`）**API 36 才有**（compileSdk 35 编译不过）；② 荣耀系统类**裁剪了 `collapsePanels()`**（反射 NoSuchMethodException），普通 App 无法编程收起通知栏——磁贴触发识屏时通知栏停在屏幕上，截屏会带上它；③ 当前方案：截屏延迟 2.5s + 「识屏准备中」通知（Notifier.notifyScreenSensePreparing，截屏后自动取消）引导用户手动关闭通知栏；④ **待改进**：升级 compileSdk/targetSdk 36 后用官方 API（registerActivity）自动收起通知栏
- **MediaProjection 权限（荣耀踩坑）**：荣耀把 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 当运行时权限且**授权 MediaProjection 后/服务停止后不定时撤销**（dumpsys 查询时可能在、服务启动瞬间已没了）——服务 startForeground 直接 SecurityException 崩溃（进程死、App 退桌面）。对策：**授权返回后固定再请求一次该权限**（标准 Android 上已授予不弹框），服务内 startForeground 再 try-catch 兜底通知；另外 **MediaProjection 授权后前台仍是助手 App**，要识别"上一个 App"必须授权后 `moveTaskToBack` + 延迟截屏（服务延迟 2.5s），聊天触发时若从桌面打开助手会退回桌面（正确姿势是磁贴：在目标 App 里点磁贴，助手任务压在目标 App 之上，退后台自然回到目标 App）
- **悬浮窗 ComposeView（踩坑）**：WindowManager overlay 里用 ComposeView 会崩 `ViewTreeLifecycleOwner not found`（overlay 不是 Activity 窗口）——必须手动 `setViewTreeLifecycleOwner/setViewTreeViewModelStoreOwner/setViewTreeSavedStateRegistryOwner`（OverlayOwners 类，ScreenResultOverlay.kt）；且 `SavedStateRegistryController.performRestore` 要求 lifecycle 在 INITIALIZED 时调用（先 restore 再提升状态，顺序见 OverlayOwners.moveToStart）
- **横屏环境识屏（踩坑）**：横屏 App（视频/游戏锁横屏）里点磁贴触发识屏时，MainActivity（默认方向）启动会把屏幕拉回竖屏——**旋转动画会打断 MediaProjection 授权框**（丢失、需重新点击）。onCreate 里 `setRequestedOrientation` 无效（系统在 Activity 创建前就按 manifest 解析方向开始旋转，onCreate 时 Display.rotation 已读成新方向）。正解：**MainActivity 声明 `android:screenOrientation="fullSensor"`**（跟随传感器：横拿=横屏加载无动画，竖拿=竖屏正常），onCreate 里检测到横屏再 `setRequestedOrientation(SENSOR_LANDSCAPE)` 锁稳授权流程，授权结束恢复 FULL_SENSOR
- **视觉模型流式（踩坑）**：识屏视觉调用流式分支 maxTokens 也要 4096（2048 会被推理模型思考过程吃光、content 为空返回"模型没有返回内容"）；非流式小窗按钮 4096 已对
- **悬浮球点击无反应（P6 踩坑）**：① 贴边半隐藏露出太窄（<32dp）时，可见区域落在荣耀**返回手势区**——触摸被系统手势吃掉，点击永远无反应（露出 32dp 以上才可靠）；② 点击判定别用 detectDragGestures 的 onDragCancel（荣耀触摸采样噪声大，点击微动会被判成拖动把球拖走），用**按下到抬起总位移 < touchSlop** 判定点击
- **独立 task 授权 Activity 的 moveTaskToBack（P6 踩坑）**：悬浮球识图走独立 task 的 MediaProjectionPermissionActivity 是对的（不闪现 App 界面）；但**聊天/磁贴必须走 MainActivity 主 task 内授权**——独立 task 的 moveTaskToBack 退的是自己的空 task，主 task 不动，截屏会截到助手自己（用户反馈"磁贴识图后回到 app 聊天"）
- **onDestroy 不一定执行（P6 踩坑）**：系统回收 Activity 时只有 onStop 保证回调——依赖 onDestroy 做状态恢复会卡死（panelState 停在 PANEL_OPEN → 悬浮球永久隐藏，开关重开也没用）。对策：onStop 恢复状态 + isPanelOpen 自愈标记 + CAPTURING 60s 超时兜底；**onDestroy 只重置自己置的状态**（无条件重置会覆盖识屏中的 CAPTURING，悬浮球提前重现被截进截图）
- **adb input 注入触摸到不了 overlay 悬浮窗（P6 踩坑）**：`adb shell input tap/swipe` 的注入事件不投递给 TYPE_APPLICATION_OVERLAY 窗口——悬浮球点击没法自动化验证，必须真人手点
- **窗口级模糊在荣耀不可用（P6 踩坑）**：FLAG_BLUR_BEHIND + 反射 setBlurBehindRadius 均无效（荣耀没开放）——用「深墨变暗层 + 噪点蒙层 + 动态光晕」模拟毛玻璃氛围
- **FGS(specialUse) 后台启动 Activity（P6 已验证可用）**：悬浮球服务（FGS specialUse）startActivity 开浮动界面，SYSTEM_ALERT_WINDOW 权限豁免后台启动限制（荣耀上有效）；BOOT_COMPLETED 启动 FGS 是豁免场景
