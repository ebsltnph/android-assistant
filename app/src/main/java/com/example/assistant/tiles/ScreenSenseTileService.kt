package com.example.assistant.tiles

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.assistant.MainActivity
import com.example.assistant.R
import com.example.assistant.core.notification.Notifier

/**
 * 快捷设置磁贴「识屏」：下拉通知栏一键触发识屏。
 * 点击 → 启动 MainActivity（带到 [ACTION_SCREEN_SENSE]）→ 走授权流程
 * （悬浮窗权限检查 + MediaProjection 授权 → 截屏 → 悬浮小窗）。
 * 磁贴本身不需要任何运行时权限。
 */
class ScreenSenseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.label = getString(R.string.tile_screen_sense)
            it.state = Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Notifier.EXTRA_ACTION, ACTION_SCREEN_SENSE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+：startActivityAndCollapse(Intent) 弃用并直接抛异常；
            // 官方替代（registerActivity + 无参 startActivityAndCollapse）是 API 36 才有。
            // 这里用 startActivity（App 有悬浮窗权限豁免后台启动限制）。
            // 通知栏无法编程收起（荣耀裁剪了 collapsePanels，见踩坑记录），
            // 交给「识屏准备中」提示通知引导用户关闭（截屏延迟 2.5s 留出时间）。
            startActivity(intent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** MainActivity.handleIntent 用（磁贴点击） */
        const val ACTION_SCREEN_SENSE = "tile_screen_sense"
    }
}
