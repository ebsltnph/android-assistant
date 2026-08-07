package com.example.assistant.service

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.assistant.core.vision.ScreenSenseStarter

/**
 * 识屏授权中转 Activity（P6）：透明无 UI，独立 task。
 * 悬浮球场景下从任意 App 弹系统授权框，**不闪现助手 App 界面**；
 * 授权成功后统一走 [ScreenSenseStarter.finishAuth]（提示 → 退后台 → 截屏）。
 *
 * 荣耀坑（与 MainActivity 原实现一致的应对，勿删）：
 * - 授权返回后**固定请求一次** FOREGROUND_SERVICE_MEDIA_PROJECTION 权限
 *   （荣耀把该权限当运行时权限且授权后异步撤销，固定请求避开撤销竞态）
 * - 横屏环境锁定横屏，防止授权框随旋转丢失
 */
class MediaProjectionPermissionActivity : ComponentActivity() {

    /** 待启动截屏服务的授权数据（resultCode + data），权限请求通过后使用 */
    private var pendingCaptureResult: Pair<Int, Intent>? = null

    /** 横屏环境触发识屏时锁定的横屏方向（授权流程结束后恢复） */
    private var landscapeLocked = false

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                pendingCaptureResult = result.resultCode to result.data!!
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    mediaProjectionPermissionLauncher.launch(
                        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
                    )
                } else {
                    finishAuthAndFinish(result.resultCode, result.data!!)
                }
            } else {
                // 用户取消授权：恢复悬浮球显示
                ScreenSenseStarter.abort(this)
                finish()
            }
        }

    private val mediaProjectionPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingCaptureResult
            pendingCaptureResult = null
            if (granted && pending != null) {
                finishAuthAndFinish(pending.first, pending.second)
            } else {
                ScreenSenseStarter.abort(this)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统自动旋转关闭时锁定当前方向（荣耀 ROM 的 sensor 不尊重旋转锁）；
        // 横屏环境（视频/游戏等横屏 App）锁横屏，授权框不随旋转丢失
        if (!com.example.assistant.core.OrientationUtils.isAutoRotateEnabled(this)) {
            com.example.assistant.core.OrientationUtils.lockToCurrentOrientation(this)
        } else {
            val rotation = getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
            if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                landscapeLocked = true
            }
        }
        // 透明无 UI：直接弹系统授权框
        val mpm = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /** 授权通过：统一流程（提示 → 退后台 → 启动截屏服务），然后关闭本中转页。
     * 悬浮球入口通知栏是收起的，截屏延迟用短档（1.2s） */
    private fun finishAuthAndFinish(resultCode: Int, data: Intent) {
        ScreenSenseStarter.finishAuth(this, resultCode, data, delayMs = ScreenCaptureService.BALL_DELAY_MS)
        finish()
    }
}
