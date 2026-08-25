package com.example.assistant.core.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * TTS 语音朗读管理（进程级单例，AppContainer 创建）：
 * 封装系统 TextToSpeech 引擎（荣耀自带引擎，中文可用）。
 * - speak() 随时可调：引擎未初始化好会先记住内容，就绪后自动读；
 * - QUEUE_FLUSH：新朗读自动打断上一次（点别的气泡的喇叭即切换）；
 * - speaking StateFlow 给 UI 用：正在朗读时喇叭按钮高亮/可点击停止。
 */
class TtsManager(private val context: Context) {

    private val _speaking = MutableStateFlow(false)
    /** 当前是否正在朗读（UI 观察用） */
    val speaking: StateFlow<Boolean> = _speaking

    private var tts: TextToSpeech? = null
    private var ready = false
    /** 引擎就绪前收到的朗读请求（只保留最新一条） */
    private var pendingText: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var utteranceIdCounter = 0

    private fun ensureInitialized() {
        if (tts != null) return
        // TextToSpeech 初始化是异步的；统一在主线程创建，回调里处理就绪/失败
        mainHandler.post {
            if (tts != null) return@post
            try {
                tts = TextToSpeech(context) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    if (!ready) {
                        // 引擎不可用（极少数无 TTS 的设备）：清掉等待中的请求
                        pendingText = null
                        _speaking.value = false
                        return@TextToSpeech
                    }
                    try {
                        // 中文优先；个别引擎不支持时返回值<0 也继续用默认语言
                        tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) { _speaking.value = true }
                            override fun onDone(utteranceId: String?) { _speaking.value = false }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) { _speaking.value = false }
                        })
                    } catch (_: Exception) {
                    }
                    pendingText?.let { p -> pendingText = null; doSpeak(p) }
                }
            } catch (_: Exception) {
                ready = false
            }
        }
    }

    /**
     * 朗读一段文本。返回 false 表示参数为空（引擎未就绪不算失败，会等就绪再读）。
     * 新调用会打断上一次未读完的内容。
     */
    fun speak(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        ensureInitialized()
        if (ready) doSpeak(t) else pendingText = t
        return true
    }

    private fun doSpeak(t: String) {
        val engine = tts ?: return
        try {
            utteranceIdCounter++
            engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, "assistant_$utteranceIdCounter")
        } catch (_: Exception) {
            _speaking.value = false
        }
    }

    /** 停止当前朗读 */
    fun stop() {
        pendingText = null
        try { tts?.stop() } catch (_: Exception) {}
        _speaking.value = false
    }

    /** 彻底释放引擎（App 进程退出时由系统回收即可，暂无显式调用点） */
    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }

    companion object {
        /** 供工具描述/UI 文案使用的能力提示 */
        const val UNAVAILABLE_HINT = "本机语音引擎不可用，请到系统设置检查「文字转语音（TTS）」是否有可用引擎（荣耀手机自带）。"
    }
}