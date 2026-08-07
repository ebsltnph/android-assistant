package com.example.assistant.core

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.Surface

/**
 * 方向工具（v1.2.1）：处理「系统自动旋转关闭时界面仍自动旋转」。
 *
 * 背景：manifest 用 `screenOrientation="sensor"` 本应尊重系统「自动旋转」开关，
 * 但荣耀/华为 ROM 的 sensor 实现不尊重旋转锁（实测关掉自动旋转后 App 仍转）。
 * 对策：**主动读取 Settings.System.ACCELEROMETER_ROTATION**，关闭时手动把方向
 * 锁定为「当前方向」（锁当前而非固定竖屏——横屏游戏里启动无旋转动画，识屏授权不受影响）。
 */
object OrientationUtils {

    /** 系统「自动旋转」是否开启（默认开启；读取失败按开启处理） */
    fun isAutoRotateEnabled(context: Context): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1

    /**
     * 把 Activity 锁定为当前显示方向（自动旋转关闭时调用）。
     * 锁"当前方向"而非固定竖屏：从横屏 App（视频/游戏）进入时以横屏加载，无旋转动画，
     * 不打断 MediaProjection 授权框（P5 坑）；竖屏环境则锁竖屏。
     */
    fun lockToCurrentOrientation(activity: Activity) {
        val rotation = activity.display?.rotation ?: Surface.ROTATION_0
        activity.requestedOrientation = when (rotation) {
            Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /** 统一入口：自动旋转关闭时锁定当前方向（onCreate 调用） */
    fun applyIfRotationLocked(activity: Activity) {
        if (!isAutoRotateEnabled(activity)) {
            lockToCurrentOrientation(activity)
        }
    }
}
