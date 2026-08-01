package com.example.assistant.core.vision

import android.net.Uri
import com.example.assistant.core.agent.ScreenAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 识屏授权请求：聊天指令 / 磁贴 → MainActivity 发起 MediaProjection 授权 */
data class ScreenSenseRequest(
    val action: ScreenAction,
    val requestId: Long
)

/** 识屏完成结果：悬浮小窗「在 App 中继续」→ 聊天页展示（截图 + 已得结果） */
data class ScreenSenseResult(
    val imagePath: String,
    val resultText: String,
    val requestId: Long
)

/** 外部分享/聊天上传的图片（附件模式，等待用户输入文字一起发送） */
data class ImageShare(val uri: Uri)

/**
 * 识屏事件总线（AppContainer 单例）：跨组件传事件，避免互相直接持有引用。
 * - requests：ChatViewModel 下达识屏指令 → MainActivity 弹系统授权
 * - results：小窗「在 App 中继续」→ ChatViewModel 追加聊天消息
 * - imageShares：MainActivity 收到分享图片 → ChatViewModel 加入附件
 * - textShares：MainActivity 收到分享文本 → ChatViewModel 预填输入框
 *
 * 用 extraBufferCapacity：事件在订阅者建立前发出时先缓冲，不丢（分享进来时
 * ChatScreen 可能尚未创建 ViewModel）。
 */
class ScreenSenseController {

    private val _requests = MutableSharedFlow<ScreenSenseRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<ScreenSenseRequest> = _requests

    private val _results = MutableSharedFlow<ScreenSenseResult>(extraBufferCapacity = 4)
    val results: SharedFlow<ScreenSenseResult> = _results

    private val _imageShares = MutableSharedFlow<ImageShare>(extraBufferCapacity = 4)
    val imageShares: SharedFlow<ImageShare> = _imageShares

    private val _textShares = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val textShares: SharedFlow<String> = _textShares

    private var counter = 0L

    /** 请求发起一次识屏授权（截屏后默认动作在小窗里由用户选择） */
    fun requestScreenSense(action: ScreenAction) {
        _requests.tryEmit(ScreenSenseRequest(action, counter++))
    }

    /** 识屏结果（小窗「在 App 中继续」后由 MainActivity 转发） */
    fun postResult(imagePath: String, resultText: String) {
        _results.tryEmit(ScreenSenseResult(imagePath, resultText, counter++))
    }

    /** 分享图片进入聊天附件 */
    fun postImageShare(uri: Uri) {
        _imageShares.tryEmit(ImageShare(uri))
    }

    /** 分享文本预填聊天输入框 */
    fun postTextShare(text: String) {
        _textShares.tryEmit(text)
    }
}
