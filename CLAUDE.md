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
- [ ] P4 提醒 + 新闻事件 + 清晨简报（**暂停中，用户未放行**；新闻搜索 API 待定，计划推荐 Tavily，代码层走 SearchClient 接口）
- [ ] P5 智能识屏 + 分享到助手
- [ ] P6 悬浮球 + 磁贴 + 小部件
- [ ] P7 真·唤醒词（可选）

GitHub：https://github.com/ebsltnph/android-assistant（master，用户要求每个功能阶段完成后推送）
详细计划见 `C:\Users\98662\.claude\plans\indexed-booping-mccarthy.md`。

**下次会话待办**：① 每日小结定时触发已全链路验证（2026-07-31 晚 23:00 自动通知收到）；② P4（提醒 + 新闻事件 + 清晨简报）等用户放行后开始。

## 平台注意事项（荣耀 X50 GT / MagicOS）

- **USB 驱动（已踩坑解决）**：Windows 上荣耀手机必须装「荣耀手机助理 HonorSuite」（官网 honor.com/cn/tech/honor-suite/）才有正确的 ADB 驱动（VID_339B）。Google USB Driver 的 INF 不含荣耀 VID，Microsoft 通用 WinUSB 驱动枚举正常但 adb 认不到（接口 GUID 不匹配）。装 HonorSuite 后 `adb devices` 即可识别，平时无需打开该软件。另需关闭开发者选项的 HDB 开关（HDB 会干扰 ADB 通道）。安装 APK 时开发者选项需开「USB 安装」开关，否则报 INSTALL_FAILED_ABORTED: User rejected permissions
- 无 GMS：不用 ML Kit；TTS 需用户启用荣耀/华为语音引擎
- `SCHEDULE_EXACT_ALARM` 默认拒绝：先 `canScheduleExactAlarms()`，否则跳设置页申请，兜底 `setWindow`
- `POST_NOTIFICATIONS` 需运行时申请（否则提醒/简报静默失败）
- MediaProjection（API 34+）：FGS 必须声明 `foregroundServiceType="mediaProjection"` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限
- MagicOS 后台限制：引导用户给 App 开"电池无限制 + 自启动"；后台功能要测强杀场景
- **系统日历（CalendarContract，已踩坑）**：① 写事件要求 **READ_CALENDAR + WRITE_CALENDAR 两个权限同时授予**（只给 WRITE 会 SecurityException 静默失败），授权按钮用 RequestMultiplePermissions 同时请求；② **全天事件必须用 UTC 毫秒存储**（`EVENT_TIMEZONE="UTC"`，DTSTART/DTEND 用 UTC 时区构建当天 0 点），对本地毫秒做 ±时区偏移很容易把日期算到昨天；③ 荣耀日历 App 按 UTC 日期渲染，验证用 `run-as` + logcat（`adb shell content query` 被 Android 13 权限拒绝）
- **WorkManager 周期任务重排（已踩坑）**：① 不要用 `cancelUniqueWork` + `enqueueUniquePeriodicWork(KEEP)`——cancel 是异步的，KEEP 会先看到旧任务而拒绝替换，竞态导致改时间不生效；用 **REPLACE 策略**（原子替换）；② 重排时不要把 DataStore 异步读写在同一个 coroutine 链里（读到旧值），由 UI 直接把用户刚选的值传入；③ 验证排程：`dumpsys jobscheduler` 看 `Minimum latency` 推算触发时刻，设置值查 DataStore 文件字节
