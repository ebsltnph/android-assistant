package com.example.assistant.core.floating

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
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
import kotlin.math.roundToInt

/**
 * 通用悬浮窗助手（TYPE_APPLICATION_OVERLAY）：
 * 在任意 App 上层挂一个 ComposeView，负责窗口创建/销毁/拖拽，
 * 内容由调用方传入。悬浮球（FloatingBallService）与识屏小窗共用。
 *
 * 注意：悬浮窗不是 Activity 窗口，ComposeView 挂载时找不到
 * ViewTreeLifecycleOwner 会崩溃——内部用 [OverlayOwners] 手动提供
 * Lifecycle/ViewModelStore/SavedStateRegistry 三个 Owner。
 */
class OverlayWindow(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null

    /** 当前是否已显示 */
    var isShowing: Boolean = false
        private set

    /**
     * 显示悬浮窗。
     * @param params 窗口参数（TYPE_APPLICATION_OVERLAY 等，位置/尺寸由调用方配置）
     * @param content 窗口内容（Compose）
     * @param onAddError 窗口创建失败回调（如悬浮窗权限未开启；调用方在此提示用户）
     */
    fun show(
        params: WindowManager.LayoutParams,
        content: @Composable () -> Unit,
        onAddError: (() -> Unit)? = null
    ) {
        mainHandler.post {
            if (overlayView != null) return@post // 已显示，忽略重复弹出
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm == null) {
                onAddError?.invoke()
                return@post
            }
            // 手动提供 Compose 所需的三个 Owner（否则挂载崩溃）
            val owners = OverlayOwners()
            owners.moveToStart()
            val view = ComposeView(context).apply {
                tag = owners // hide() 时据此销毁 Owner
                setViewTreeLifecycleOwner(owners)
                setViewTreeViewModelStoreOwner(owners)
                setViewTreeSavedStateRegistryOwner(owners)
                setContent { content() }
            }
            try {
                wm.addView(view, params)
                windowManager = wm
                overlayView = view
                isShowing = true
            } catch (e: Exception) {
                // 悬浮窗权限被拒等：不崩溃，交给调用方提示
                owners.moveToDestroy()
                onAddError?.invoke()
            }
        }
    }

    /** 关闭并移除悬浮窗（同时销毁内部 Owner） */
    fun hide() {
        mainHandler.post {
            val view = overlayView ?: return@post
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
            overlayView = null
            windowManager = null
            isShowing = false
            (view.tag as? OverlayOwners)?.moveToDestroy()
        }
    }

    /** 拖拽移动：更新窗口位置（拖拽手势每帧调用） */
    fun moveBy(dx: Float, dy: Float) {
        val view = overlayView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        windowManager?.updateViewLayout(view, params)
    }

    /** 当前窗口参数（贴边吸附、位置记忆等需要读取位置时用） */
    fun currentParams(): WindowManager.LayoutParams? =
        overlayView?.layoutParams as? WindowManager.LayoutParams

    /** 直接设置窗口位置（贴边吸附用；像素坐标，相对 LayoutParams.gravity） */
    fun setPosition(x: Int, y: Int) {
        val view = overlayView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = x
        params.y = y
        windowManager?.updateViewLayout(view, params)
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
