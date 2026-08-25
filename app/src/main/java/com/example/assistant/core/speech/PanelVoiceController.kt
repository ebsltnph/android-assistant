package com.example.assistant.core.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 浮动面板语音输入控制器（v1.5.x）：封装系统 SpeechRecognizer 听写。
 * 单次监听：开始 → 部分结果实时回调 → 说完/出错结束。
 *
 * 兼容性要点（真机踩坑）：
 * - 不设 EXTRA_SPEECH_INPUT_*_SILENCE_* 参数——部分厂商引擎设了会「永不结束」；
 * - 内置看门狗：引擎超过 8 秒毫无回调（荣耀无 GMS 时内联识别可能静默失效）
 *   则回调 onStalled，由面板自动降级弹系统语音识别窗（RecognizerIntent）。
 */
class PanelVoiceController(private val context: Context) {

    /** 本机是否有可用的语音识别服务 */
    val available: Boolean
        get() = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Exception) { false }

    /** 引擎无响应（看门狗触发）：面板据此切系统语音识别窗兜底 */
    var onStalled: (() -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastActivityAt = 0L
    private var watching = false

    private fun touch() { lastActivityAt = SystemClock.elapsedRealtime() }

    /** 看门狗：每秒检查一次，8 秒无任何引擎回调判定为无响应 */
    private fun startWatchdog() {
        watching = true
        touch()
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!watching) return
                if (SystemClock.elapsedRealtime() - lastActivityAt > STALL_TIMEOUT_MS) {
                    watching = false
                    destroyRecognizer()
                    onStalled?.invoke()
                    return
                }
                mainHandler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun stopWatchdog() {
        watching = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 开始一次听写。
     * @param onPartial 实时中间识别结果（可能为空串）
     * @param onFinish 结束回调：识别成功给文本（error 为 null）；失败给 error 提示文案（result 为 null）
     */
    fun start(
        onPartial: (String) -> Unit,
        onFinish: (result: String?, error: String?) -> Unit
    ) {
        stop() // 防重入：先清理上一次
        if (!available) {
            onFinish(null, "本机没有可用的语音识别服务，可点键盘上的麦克风用输入法语音")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: Exception) {
            onFinish(null, "语音识别启动失败，可点键盘上的麦克风用输入法语音")
            return
        }
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { touch() }
            override fun onBeginningOfSpeech() { touch() }
            override fun onRmsChanged(rmsdB: Float) { touch() }  // 喂狗：有声浪说明引擎活着
            override fun onBufferReceived(buffer: ByteArray?) { touch() }
            override fun onEndOfSpeech() { touch() }
            override fun onError(error: Int) {
                stopWatchdog()
                destroyRecognizer()
                onFinish(null, errorMessageFor(error))
            }
            override fun onResults(results: Bundle?) {
                stopWatchdog()
                destroyRecognizer()
                val best = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                if (best.isNullOrBlank()) onFinish(null, "没听清，请再试一次")
                else onFinish(best.trim(), null)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                touch()
                val p = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.lastOrNull { !it.isNullOrBlank() } ?: ""
                onPartial(p)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            sr.startListening(intent)
            startWatchdog()
        } catch (_: Exception) {
            stopWatchdog()
            destroyRecognizer()
            onFinish(null, "语音识别启动失败，可点键盘上的麦克风用输入法语音")
        }
    }

    /** 手动取消监听（取消按钮）：不产生结果回调 */
    fun stop() {
        stopWatchdog()
        try { recognizer?.stopListening() } catch (_: Exception) {}
        destroyRecognizer()
    }

    /** 彻底释放（Composable 离开时 DisposableEffect 调用） */
    fun destroy() = stop()

    private fun destroyRecognizer() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    /** 错误码 → 用户能看懂的中文提示（荣耀 logcat 不可见，错误必须落到界面） */
    private fun errorMessageFor(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再试一次"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到你说话，请再试一次"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限被拒绝，请在系统设置里允许"
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，请再试一次"
        else -> "识别出错，请再试一次"
    }

    companion object {
        /** 引擎无响应判定阈值：连续这么久没有任何回调（含声波强度）即判死 */
        private const val STALL_TIMEOUT_MS = 8_000L
    }
}