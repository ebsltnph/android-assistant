package com.example.assistant.core

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Surface

/**
 * 方向工具（v1.2.1 引入，v1.5.x 增强）：处理「系统自动旋转开关」与界面朝向的关系。
 *
 * 背景：manifest 用 `screenOrientation="sensor"` 本应尊重系统「自动旋转」开关，
 * 但荣耀/华为 ROM 的 sensor 实现不尊重旋转锁（实测关掉自动旋转后 App 仍转）。
 * 对策：主动读取 Settings.System.ACCELEROMETER_ROTATION，关闭时手动锁定方向。
 *
 * v1.5.x 增强（浮动界面专用）：
 * ① 锁定方向可指定来源 rotation（打开面板时刻前台应用的朝向），不再只锁"自己当前"；
 * ② registerAutoRotateObserver 实时监听系统自动旋转开关——面板开着时拉控制中心
 *    切换开关，面板立刻跟随（原实现只在 onCreate 判断一次）。
 */
object OrientationUtils {

    /** 系统「自动旋转」是否开启（默认开启；读取失败按开启处理） */
    fun isAutoRotateEnabled(context: Context): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1

    /** rotation 常量 → requestedOrientation 值 */
    fun orientationForRotation(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /**
     * 把 Activity 锁定为当前显示方向（自动旋转关闭时调用）。
     * 锁"当前方向"而非固定竖屏：从横屏 App（视频/游戏）进入时以横屏加载，无旋转动画，
     * 不打断 MediaProjection 授权框（P5 坑）；竖屏环境则锁竖屏。
     */
    fun lockToCurrentOrientation(activity: Activity) {
        val rotation = activity.display?.rotation ?: Surface.ROTATION_0
        activity.requestedOrientation = orientationForRotation(rotation)
    }

    /** 统一入口：自动旋转关闭时锁定当前方向（onCreate 调用） */
    fun applyIfRotationLocked(activity: Activity) {
        if (!isAutoRotateEnabled(activity)) {
            lockToCurrentOrientation(activity)
        }
    }

    /**
     * 浮动界面朝向总控（v1.5.x）：
     * - 自动旋转开 → SENSOR（随时跟随物理旋转）；
     * - 自动旋转关 → 锁定 preferredRotation 指定的方向
     *   （= 打开面板那一刻前台应用/屏幕的朝向；传 null 则取当前显示方向）。
     */
    fun applyPanelOrientation(activity: Activity, preferredRotation: Int?) {
      if (isAutoRotateEnabled(activity)) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
      } else {
        val r = preferredRotation ?: (activity.display?.rotation ?: Surface.ROTATION_0)
        activity.requestedOrientation = orientationForRotation(r)
      }
    }

    /**
     * 监听系统「自动旋转」开关变化（浮动界面用）：开关一切换就回调 onChanged
     * （主线程）。返回的 observer 用 unregisterAutoRotateObserver 注销。
     */
    fun registerAutoRotateObserver(
        context: Context,
        onChanged: () -> Unit
    ): ContentObserver {
        val uri = Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChanged()
            }
        }
        context.contentResolver.registerContentObserver(uri, false, observer)
        return observer
    }

    /** 注销 registerAutoRotateObserver 返回的监听（重复注销安全） */
    fun unregisterAutoRotateObserver(context: Context, observer: ContentObserver?) {
        try {
            observer?.let { context.contentResolver.unregisterContentObserver(it) }
        } catch (_: Exception) {
        }
    }

}