package com.example.assistant.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.assistant.MainActivity
import com.example.assistant.core.notification.Notifier
import com.example.assistant.core.vision.ImageUtils
import com.example.assistant.core.vision.ScreenSenseController
import com.example.assistant.core.vision.VisionAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 识屏悬浮小窗（TYPE_APPLICATION_OVERLAY）：
 * 截屏后弹在任意 App 上层，快捷按钮（提取文字/翻译/描述）直接分析并展示结果，
 * 无需回到助手 App；「在 App 中继续」才回聊天页带完整上下文。
 *
 * 生命周期：ScreenCaptureService 截屏后调用 [show]，小窗关闭（点 × / 继续）时
 * 回调 onClosed → 服务 stopSelf（省电）。
 * 窗口由 WindowManager 持有（独立于 Activity），进程存活期间小窗持续显示。
 *
 * 注意：悬浮窗不是 Activity 窗口，ComposeView 挂载时找不到 ViewTreeLifecycleOwner
 * 会崩溃（ViewTreeLifecycleOwner not found）——必须用 [OverlayOwners] 手动提供
 * Lifecycle/ViewModelStore/SavedStateRegistry 三个 Owner。
 */
class ScreenResultOverlay(
    private val context: Context,
    private val visionAnalyzer: VisionAnalyzer,
    private val controller: ScreenSenseController
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var onClosed: (() -> Unit)? = null
    private var imagePath: String? = null

    // Compose 状态（小窗内 UI 数据）
    private var thumbnail by mutableStateOf<android.graphics.Bitmap?>(null)
    private var analyzing by mutableStateOf(false)
    private var resultText by mutableStateOf("")

    /** 弹出小窗（截屏服务调用；必须在主线程执行，内部已 post） */
    fun show(imagePath: String, onClosed: () -> Unit) {
        mainHandler.post {
            if (overlayView != null) return@post // 已有小窗，忽略重复弹出
            this.imagePath = imagePath
            this.onClosed = onClosed
            thumbnail = ImageUtils.decodeThumbnail(imagePath)
            resultText = ""
            addOverlayView()
        }
    }

    /** 关闭小窗（点 × / 「在 App 中继续」） */
    fun hide() {
        mainHandler.post {
            removeOverlayView()
        }
    }

    private fun addOverlayView() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            notifyClosed()
            return
        }
        // 手动提供 Compose 所需的三个 Owner（否则挂载崩溃）
        val owners = OverlayOwners()
        owners.moveToStart()
        val view = ComposeView(context).apply {
            tag = owners // removeOverlayView 时销毁
            setViewTreeLifecycleOwner(owners)
            setViewTreeViewModelStoreOwner(owners)
            setViewTreeSavedStateRegistryOwner(owners)
            setContent {
                MaterialTheme {
                    OverlayContent(
                        thumbnail = thumbnail,
                        analyzing = analyzing,
                        resultText = resultText,
                        onAnalyze = { instruction -> analyze(instruction) },
                        onContinueInApp = { continueInApp() },
                        onClose = { removeOverlayView() },
                        onDrag = { dx, dy -> moveBy(this, wm, dx, dy) }
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }
        try {
            wm.addView(view, params)
            windowManager = wm
            overlayView = view
        } catch (e: Exception) {
            // 悬浮窗权限被拒等：不崩溃，通知用户开启权限
            owners.moveToDestroy()
            Notifier.notifyScreenSenseHint(context, "识屏需要「显示在其他应用上层」权限，请在系统设置中开启后重试")
            notifyClosed()
        }
    }

    private fun removeOverlayView() {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
        windowManager = null
        (view.tag as? OverlayOwners)?.moveToDestroy()
        notifyClosed()
    }

    /** 小窗关闭回调（服务据此 stopSelf） */
    private fun notifyClosed() {
        val cb = onClosed
        onClosed = null
        cb?.invoke()
    }

    private fun moveBy(view: ComposeView, wm: WindowManager, dx: Float, dy: Float) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        wm.updateViewLayout(view, params)
    }

    /** 快捷按钮：读图 → 视觉模型分析 → 结果区内显示（不离开当前 App） */
    private fun analyze(instruction: String) {
        if (analyzing) return
        val path = imagePath ?: return
        scope.launch {
            analyzing = true
            resultText = "分析中…"
            resultText = withContext(Dispatchers.IO) {
                try {
                    val base64 = ImageUtils.fileToBase64(path)
                    visionAnalyzer.analyze(base64, instruction)
                } catch (e: Exception) {
                    "⚠️ 识屏失败：${e.message}"
                }
            }
            analyzing = false
        }
    }

    /** 回助手 App：切聊天页并携带截图与已得结果，然后关闭小窗 */
    private fun continueInApp() {
        val path = imagePath ?: return
        val text = resultText.ifBlank { "" }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            .putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_SHOW_SCREEN_SENSE)
            .putExtra(EXTRA_SCREEN_IMAGE_PATH, path)
            .putExtra(EXTRA_SCREEN_RESULT, text)
        context.startActivity(intent)
        removeOverlayView()
    }

    companion object {
        const val EXTRA_SCREEN_IMAGE_PATH = "screen_image_path"
        const val EXTRA_SCREEN_RESULT = "screen_result"

        /** 小窗快捷按钮 → 视觉模型指令 */
        fun instructionFor(action: String): String = when (action) {
            "extract" -> "请提取这张图片上的全部文字，按原有顺序和布局整理输出"
            "translate" -> "请把这张图片上的文字内容翻译成简体中文"
            "describe" -> "请仔细观察这张图片，描述其中的内容"
            else -> action
        }
    }
}

/**
 * 悬浮窗 ComposeView 的宿主 Owner：同时充当 Lifecycle/ViewModelStore/
 * SavedStateRegistry 三个 Owner（悬浮窗不是 Activity 窗口，Compose 挂载时
 * 找不到 ViewTreeLifecycleOwner 会崩溃，必须手动提供）。
 */
private class OverlayOwners : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun moveToStart() {
        // performRestore 要求 lifecycle 仍处于 INITIALIZED（内部会 attach），
        // 因此必须**先 restore、再提升状态**（与标准 Activity 顺序一致）
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun moveToDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/** 小窗界面：标题栏（可拖拽）+ 截图缩略图 + 快捷按钮 + 结果区 + 「在 App 中继续」 */
@Composable
private fun OverlayContent(
    thumbnail: android.graphics.Bitmap?,
    analyzing: Boolean,
    resultText: String,
    onAnalyze: (String) -> Unit,
    onContinueInApp: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .width(340.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题行：拖拽把手
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "👁️ 识屏",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // 不要缩小 IconButton（默认 48dp 触控区）——之前 28dp 太小点不到
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 截图缩略图
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "屏幕截图",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            // 快捷动作按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                listOf("extract" to "提取文字", "translate" to "翻译", "describe" to "描述")
                    .forEach { (key, label) ->
                        OutlinedButton(
                            onClick = { onAnalyze(ScreenResultOverlay.instructionFor(key)) },
                            enabled = !analyzing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
            }

            // 结果区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                when {
                    analyzing -> CircularProgressIndicator(Modifier.size(24.dp))
                    resultText.isNotEmpty() -> Text(
                        resultText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    else -> Text(
                        "点上方按钮识别屏幕内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onContinueInApp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("在 App 中继续…")
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
