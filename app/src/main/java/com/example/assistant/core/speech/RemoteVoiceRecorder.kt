package com.example.assistant.core.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import kotlin.math.sqrt

/**
 * 远程识别用录音器：16kHz 单声道 PCM 采集 → WAV 文件（上传给识别 API）。
 * 内置简单能量检测：听到说话后连续静音 1.4 秒自动结束；总时长上限 45 秒；
 * 外部也可随时 finish()（手动结束）或 cancel()（丢弃不回调）。
 * 回调在录音线程触发，面板侧自行 launch 到主线程更新 UI。
 */
class RemoteVoiceRecorder(private val context: Context) {

    private var thread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var finishRequested = false
    @Volatile private var cancelled = false   // 取消 = 丢弃内容且不触发任何回调

    /** 开始录音。onFinish(file) 正常产出；onError(msg) 失败（含「没听到说话」） */
    fun start(onFinish: (File) -> Unit, onError: (String) -> Unit) {
        cancel()
        stopRequested = false
        finishRequested = false
        cancelled = false
        thread = Thread {
            var record: AudioRecord? = null
            try {
                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 8192)
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    onError("录音初始化失败（麦克风被占用或权限不足）")
                    return@Thread
                }
                val pcm = java.io.ByteArrayOutputStream()
                val buf = ShortArray(1600)   // 100ms 一块（16kHz）
                // 预录缓冲：说话检测触发前的 0.6 秒也保留，防第一个字被截断
                val preRoll = ArrayDeque<ByteArray>()
                record.startRecording()
                var totalMs = 0L
                var speechSeen = false
                var silenceMs = 0L
                while (!stopRequested && !finishRequested && totalMs < MAX_MS) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { sum += (buf[i] * buf[i]).toDouble() }
                    val rms = sqrt(sum / n)
                    val chunk = toBytes(buf, n)
                    totalMs += 100
                    if (rms > SPEECH_RMS) {
                        if (!speechSeen) {
                            speechSeen = true
                            for (c in preRoll) pcm.write(c, 0, c.size)  // 补回说话前的缓冲
                            preRoll.clear()
                        }
                        silenceMs = 0
                        pcm.write(chunk, 0, chunk.size)
                    } else if (speechSeen) {
                        pcm.write(chunk, 0, chunk.size)
                        silenceMs += 100
                        if (silenceMs >= SILENCE_MS) break   // 说完停顿够久 → 自动结束
                    } else {
                        preRoll.addLast(chunk)
                        if (preRoll.size > PREROLL_CHUNKS) preRoll.removeFirst()
                    }
                }
                record.stop()
                if (cancelled) return@Thread   // 取消：丢弃，不产出文件不回调
                val data = pcm.toByteArray()
                if (!speechSeen || data.size < 8000) {
                    onError("没听到你说话，请再试一次")
                    return@Thread
                }
                val f = File(context.cacheDir, "asr_" + System.currentTimeMillis() + ".wav")
                f.writeBytes(wavHeader(data.size) + data)
                onFinish(f)
            } catch (e: Exception) {
                onError("录音出错：" + (e.message ?: "未知错误"))
            } finally {
                try { record?.release() } catch (_: Exception) {}
            }
        }.also { it.start() }
    }

    /** 手动完成：停止采集并产出已录内容 */
    fun finish() { finishRequested = true }

    /** 取消：丢弃内容，不产生任何回调 */
    fun cancel() {
        if (thread != null) cancelled = true
        stopRequested = true
        try { thread?.interrupt() } catch (_: Exception) {}
        thread = null
    }

    private fun toBytes(src: ShortArray, n: Int): ByteArray {
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            out[i * 2] = (src[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((src[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** 标准 44 字节 WAV 头（PCM 16bit 单声道 16kHz） */
    private fun wavHeader(dataLen: Int): ByteArray {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + dataLen); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(16000); h.putInt(32000); h.putShort(16); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataLen)
        return h.array()
    }

    companion object {
        private const val MAX_MS = 45_000L      // 单次录音上限
        private const val SILENCE_MS = 1_400L   // 说完后静音这么久判定结束
        private const val SPEECH_RMS = 1_200.0  // 超过视为「在说话」（偏灵敏，配预录缓冲）
        private const val PREROLL_CHUNKS = 6    // 预录缓冲块数（6×100ms）
    }
}