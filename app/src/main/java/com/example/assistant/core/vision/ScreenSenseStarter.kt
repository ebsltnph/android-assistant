package com.example.assistant.core.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import com.example.assistant.AssistantApplication
import com.example.assistant.core.notification.Notifier
import com.example.assistant.di.AppContainer
import com.example.assistant.service.MediaProjectionPermissionActivity
import com.example.assistant.service.ScreenCaptureService

/**
 * 识屏统一启动器（P6）：悬浮球 / 聊天 / 磁贴三条入口共用。
 *
 * - [requestCapture]：启动透明权限 Activity（独立 task）弹系统授权——
 *   悬浮球场景下不闪现 App 界面
 * - [finishAuth]：授权成功后「提示关闭通知栏 → 恢复方向 → 退后台 → 启动截屏服务」
 *   （截屏目标是用户当前屏幕；授权后前台是助手，必须退后台再延迟截屏，见 ScreenCaptureService）
 * - [abort]：授权取消/失败时恢复悬浮球显示（panelState=HIDDEN）
 */
object ScreenSenseStarter {

    /**
     * 发起识屏：启动 MediaProjectionPermissionActivity（有 SYSTEM_ALERT_WINDOW 权限，
     * 豁免后台启动限制）。任意入口（悬浮球/面板/聊天/磁贴）统一先置 CAPTURING——
     * 悬浮球服务据此隐藏悬浮球（防被截进截图）。
     */
    fun requestCapture(context: Context) {
        val app = context.applicationContext as AssistantApplication
        app.container.panelState.value = AppContainer.PanelState.CAPTURING
        context.startActivity(
            Intent(context, MediaProjectionPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * 授权成功：准备截屏（通知栏提示 → 恢复方向 → 退后台 → 启动截屏服务）。
     * @param delayMs 截屏等待时长：悬浮球入口 1.2s（通知栏已收起）；
     *                聊天/磁贴入口 2.5s（通知栏可能展开，需时间收起）
     */
    fun finishAuth(activity: Activity, resultCode: Int, data: Intent, delayMs: Long = ScreenCaptureService.DEFAULT_DELAY_MS) {
        // 提示用户关闭通知栏（荣耀无法编程收起，截屏会带上它）；截屏完成后服务自动取消
        Notifier.notifyScreenSensePreparing(activity)
        // 授权流程结束：恢复 manifest 的 fullSensor（跟随传感器）
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        // 退后台：截屏目标是"用户当前看到的屏幕"（其他 App），授权后前台是助手——
        // 退后台后服务延迟截屏，截到的是上一个 App 的画面
        activity.moveTaskToBack(true)
        ScreenCaptureService.start(activity, resultCode, data, delayMs)
    }

    /** 授权取消/失败：恢复悬浮球显示 */
    fun abort(context: Context) {
        val app = context.applicationContext as AssistantApplication
        app.container.panelState.value = AppContainer.PanelState.HIDDEN
    }

    /** 识屏动作 → 视觉模型指令（迁移自 P5 的 ScreenResultOverlay，勿删） */
    fun instructionFor(action: String): String = when (action) {
        "extract" -> "请提取这张图片上的全部文字，按原有顺序和布局整理输出"
        "translate" -> "请把这张图片上的文字内容翻译成简体中文"
        "describe" -> "请仔细观察这张图片，描述其中的内容"
        else -> action
    }
}
