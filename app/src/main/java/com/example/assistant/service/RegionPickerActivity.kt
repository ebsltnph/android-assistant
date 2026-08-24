package com.example.assistant.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.assistant.AssistantApplication
import com.example.assistant.core.OrientationUtils
import com.example.assistant.core.vision.ImageUtils
import com.example.assistant.di.AppContainer
import com.example.assistant.feature.floating.EdgeGlow
import com.example.assistant.feature.floating.FloatingPanelActivity
import com.example.assistant.feature.floating.NightBase
import com.example.assistant.feature.floating.NightDeep
import com.example.assistant.feature.floating.NoiseLayer
import com.example.assistant.ui.theme.AssistantTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 识屏框选层（v1.4.1）：截屏完成后弹出，显示整张截图，
 * 用户按住拖动画一个矩形框，确认后 **只裁剪框内区域** 送入识图流程。
 *
 * 交互：
 * - 截图显示在顶部提示与底部按钮之间；背景与浮动界面同款（深墨夜景 + 光斑）
 * - 无选区时按住拖动 = 画第一个框；已有选区后**只有框角可拖**（调整大小），
 *   按空白处不响应（防误触丢框）
 * - 拖动位置始终钳制在截图范围内（超出时固定在截图边缘）
 * - 不框选直接点「确定」= 识别整张截图；「取消」删临时文件、恢复悬浮球
 *
 * 流程（由 ScreenCaptureService 在框选开关开启时拉起）：
 * 截屏存盘（region_pending_*.png）→ 本层显示整图 → 用户框选 → 「确定」裁剪
 * （存 region_*.png）→ 启动浮动界面（SCREEN_SENSE 模式，传裁剪图）。
 *
 * 心跳：本层显示期间每 2 秒刷新 [AppContainer.regionPickerHeartbeatAt]，
 * 悬浮球服务的 CAPTURING 自愈据此延长等待（用户框选超过 60 秒球不会提前重现）。
 */
class RegionPickerActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as AssistantApplication).container

    /** 本层自己的协程域：心跳 + 裁剪落盘都在这里；onDestroy 统一取消 */
    private val pickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 正常路径（确认/取消）已完成状态管理，onDestroy 不再干预 panelState */
    private var finishedCleanly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与截图一致的旋转方向（系统自动旋转关闭时锁当前方向，避免中途旋转错乱）
        OrientationUtils.applyIfRotationLocked(this)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        // 心跳：通知悬浮球服务「框选层还活着」（CAPTURING 自愈 60s 不强行恢复球）
        pickerScope.launch {
            while (isActive) {
                container.regionPickerHeartbeatAt = System.currentTimeMillis()
                delay(2_000)
            }
        }

        setContent {
            AssistantTheme {
                RegionPicker(
                    path = path,
                    onCancel = { cancel(path) },
                    onConfirm = { rect -> confirmAndContinue(path, rect) }
                )
            }
        }
    }

    /** 取消：删临时整图 + 恢复悬浮球（HIDDEN）+ 关闭 */
    private fun cancel(path: String) {
        finishedCleanly = true
        container.panelState.value = AppContainer.PanelState.HIDDEN
        runCatching { File(path).delete() }
        finish()
    }

    /** 确定：后台裁剪 + 等比缩放（宽 ≤ 1280）→ 存 region_*.png → 启动浮动界面（识图模式）→ 关闭 */
    private fun confirmAndContinue(path: String, rect: IntArray) {
        pickerScope.launch {
            val cropped = withContext(Dispatchers.IO) {
                try {
                    val bmp = BitmapFactory.decodeFile(path) ?: return@withContext null
                    val c = Bitmap.createBitmap(bmp, rect[0], rect[1], rect[2], rect[3])
                    val scaled = if (c.width > ImageUtils.MAX_WIDTH) ImageUtils.scaleBitmap(c) else c
                    ImageUtils.saveToCache(
                        this@RegionPickerActivity, scaled, "region_" + System.currentTimeMillis() + ".png"
                    )
                } catch (e: Exception) {
                    null
                }
            }
            if (cropped == null) {
                Toast.makeText(this@RegionPickerActivity, "截取失败，请重试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            finishedCleanly = true
            runCatching { File(path).delete() }
            // 浮动界面 onCreate 会把 panelState 置为 PANEL_OPEN（悬浮球隐藏）
            startActivity(
                FloatingPanelActivity.intentFor(
                    this@RegionPickerActivity,
                    FloatingPanelActivity.PanelMode.SCREEN_SENSE,
                    cropped
                )
            )
            finish()
        }
    }

    override fun onDestroy() {
        pickerScope.cancel()
        container.regionPickerHeartbeatAt = 0L
        // 非正常关闭（系统回收/异常）且状态还是 CAPTURING → 恢复悬浮球，防球永久隐藏；
        // 正常确认/取消路径 finishedCleanly=true（面板/取消逻辑已管状态），不干预
        if (!finishedCleanly && container.panelState.value != AppContainer.PanelState.HIDDEN) {
            container.panelState.value = AppContainer.PanelState.HIDDEN
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PATH = "region_path"

        /** 最小选区边长（图片像素） */
        const val MIN_EDGE_PX = 20

        fun intentFor(context: android.content.Context, path: String) =
            android.content.Intent(context, RegionPickerActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * 选区层界面：三段布局——顶部提示 / 中间画布（截图 + 框选）/ 底部按钮。
 * 背景与浮动界面完全一致：深墨夜景渐变变暗层 + 噪点蒙层 + 边缘多色光斑。
 */
@Composable
private fun RegionPicker(
    path: String,
    onCancel: () -> Unit,
    onConfirm: (IntArray) -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 选区（视图坐标）：随拖动实时更新
    var sel by remember { mutableStateOf<Rect?>(null) }
    // 新建选区的起点（null = 本次拖动不是新建）
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    // 正在拖动的框角索引：0=左上 1=右上 2=左下 3=右下（-1 = 非拖角）
    var dragCorner by remember { mutableIntStateOf(-1) }

    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                BitmapFactory.decodeFile(path)
            } catch (e: Exception) {
                null
            }
        }
        if (bitmap == null) {
            Toast.makeText(context, "截图读取失败，请重新识屏", Toast.LENGTH_SHORT).show()
            onCancel()
        }
    }

    // 框角命中半径（触摸容差）
    val cornerTouchPx = with(LocalDensity.current) { 26.dp.toPx() }

    // 把任意触点钳制到截图显示区域内（超出即固定在截图边缘）
    fun clampToImage(pos: Offset): Offset {
        val bm = bitmap ?: return pos
        if (viewSize.width <= 0 || viewSize.height <= 0) return pos
        val scale = min(viewSize.width.toFloat() / bm.width, viewSize.height.toFloat() / bm.height)
        val dispW = bm.width * scale
        val dispH = bm.height * scale
        val ox = (viewSize.width - dispW) / 2f
        val oy = (viewSize.height - dispH) / 2f
        return Offset(pos.x.coerceIn(ox, ox + dispW), pos.y.coerceIn(oy, oy + dispH))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 背景：与浮动界面同款（深墨夜景渐变 + 噪点 + 光斑）----
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            NightDeep.copy(alpha = 0.88f),
                            NightBase.copy(alpha = 0.68f)
                        )
                    )
                )
        )
        NoiseLayer()
        EdgeGlow()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ---- 顶部提示 ----
            Text(
                "拖动选框；不框选则识别全屏",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )

            // ---- 中间画布：整图 + 框选遮罩（占满提示与按钮之间的空间）----
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val s = sel
                                if (s == null) {
                                    // 尚无选区：任意位置按下开始画第一个框（钳制在截图内）
                                    dragCorner = -1
                                    val p = clampToImage(pos)
                                    dragStart = p
                                    sel = Rect(p.x, p.y, p.x, p.y)
                                } else {
                                    // 已有选区：只响应框角拖动；按空白处不反应（防误触丢框）
                                    val corner = hitCorner(pos, s, cornerTouchPx)
                                    if (corner >= 0) {
                                        dragCorner = corner
                                        dragStart = null
                                    }
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val s0 = sel ?: return@detectDragGestures
                                val pos = clampToImage(change.position)
                                if (dragCorner >= 0) {
                                    // 拖角：对角顶点固定，随手指实时归一化（防翻转）
                                    sel = when (dragCorner) {
                                        0 -> Rect(min(pos.x, s0.right), min(pos.y, s0.bottom), s0.right, s0.bottom)
                                        1 -> Rect(s0.left, min(pos.y, s0.bottom), maxOf(pos.x, s0.left), s0.bottom)
                                        2 -> Rect(min(pos.x, s0.right), s0.top, s0.right, maxOf(pos.y, s0.top))
                                        else -> Rect(s0.left, s0.top, maxOf(pos.x, s0.left), maxOf(pos.y, s0.top))
                                    }
                                } else {
                                    val start = dragStart ?: return@detectDragGestures
                                    sel = Rect(
                                        min(start.x, pos.x),
                                        min(start.y, pos.y),
                                        maxOf(start.x, pos.x),
                                        maxOf(start.y, pos.y)
                                    )
                                }
                            },
                            onDragEnd = {
                                // 新建模式下拖出的太小（≈ 误触点击）清掉；拖角模式保留结果
                                val s = sel
                                if (dragCorner < 0 && dragStart != null &&
                                    s != null && s.width < 12f && s.height < 12f
                                ) sel = null
                                dragCorner = -1
                                dragStart = null
                            }
                        )
                    }
            ) {
                val bm = bitmap
                if (bm != null && size.width > 0 && size.height > 0) {
                    val bmpW = bm.width.toFloat()
                    val bmpH = bm.height.toFloat()
                    val scale = min(size.width / bmpW, size.height / bmpH)
                    val dispW = bmpW * scale
                    val dispH = bmpH * scale
                    val ox = (size.width - dispW) / 2f
                    val oy = (size.height - dispH) / 2f
                    // 整图与选区覆盖层统一裁剪到截图显示区域内。
                    // 不裁剪时：角手柄/描边会画出图片边界约一个手柄宽度——上下方向恰好贴着
                    // 画布边缘被裁掉看不出来，左右方向截图两侧有留白就露出来了
                    //（表现为"选框横向超出截图一点"）
                    clipRect(ox, oy, ox + dispW, oy + dispH) {
                        // 整图
                        drawImage(
                            bm.asImageBitmap(),
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bm.width, bm.height),
                            dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                            dstSize = IntSize(dispW.roundToInt(), dispH.roundToInt()),
                            filterQuality = FilterQuality.Low
                        )
                        // 选区外遮暗（4 块矩形包住选区）+ 金色边框 + 四角手柄
                        val s = sel
                        if (s != null && s.width > 8f && s.height > 8f) {
                            val dark = Color.Black.copy(alpha = 0.55f)
                            drawRect(dark, topLeft = Offset(0f, 0f), size = Size(size.width, s.top))
                            drawRect(dark, topLeft = Offset(0f, s.bottom), size = Size(size.width, size.height - s.bottom))
                            drawRect(dark, topLeft = Offset(0f, s.top), size = Size(s.left, s.height))
                            drawRect(dark, topLeft = Offset(s.right, s.top), size = Size(size.width - s.right, s.height))
                            val border = Color(0xFFE4B863)
                            drawRect(
                                border,
                                topLeft = Offset(s.left, s.top),
                                size = Size(s.width, s.height),
                                style = Stroke(width = 2.dp.toPx())
                            )
                            val hpx = 7.dp.toPx()
                            listOf(
                                Offset(s.left, s.top), Offset(s.right, s.top),
                                Offset(s.left, s.bottom), Offset(s.right, s.bottom)
                            ).forEach { c ->
                                drawRect(border, topLeft = Offset(c.x - hpx, c.y - hpx), size = Size(hpx * 2, hpx * 2))
                            }
                        }
                    }
                }
            }

            // ---- 底部按钮 ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("取消", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                }
                Button(
                    onClick = {
                        val bm = bitmap ?: return@Button
                        val s = sel
                        if (s == null) {
                            // 未框选：直接识别整张截图
                            Toast.makeText(context, "未框选：识别整个屏幕", Toast.LENGTH_SHORT).show()
                            onConfirm(intArrayOf(0, 0, bm.width, bm.height))
                            return@Button
                        }
                        val rect = toImageRect(s, viewSize, bm)
                        if (rect == null) {
                            Toast.makeText(context, "选区太小，请重新框选", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        onConfirm(rect)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE4B863),
                        contentColor = Color(0xFF0B1322)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text("确定", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 点按位置命中哪个框角？返回 0=左上 1=右上 2=左下 3=右下；-1 = 未命中 */
private fun hitCorner(pos: Offset, r: Rect, radius: Float): Int {
    val corners = listOf(
        Offset(r.left, r.top), Offset(r.right, r.top),
        Offset(r.left, r.bottom), Offset(r.right, r.bottom)
    )
    for (i in corners.indices) {
        val dx = pos.x - corners[i].x
        val dy = pos.y - corners[i].y
        if (dx * dx + dy * dy <= radius * radius) return i
    }
    return -1
}

/**
 * 视图坐标选区 → 图片像素选区 [left, top, width, height]。
 * 校验：每边 ≥ [RegionPickerActivity.MIN_EDGE_PX] 像素（防误触）；越界自动裁剪到图片范围内。
 */
private fun toImageRect(s: Rect, viewSize: IntSize, bitmap: Bitmap): IntArray? {
    if (viewSize.width <= 0 || viewSize.height <= 0) return null
    val bmpW = bitmap.width
    val bmpH = bitmap.height
    val scale = min(viewSize.width.toFloat() / bmpW, viewSize.height.toFloat() / bmpH)
    val dispW = bmpW * scale
    val dispH = bmpH * scale
    val ox = (viewSize.width - dispW) / 2f
    val oy = (viewSize.height - dispH) / 2f
    val left = ((s.left - ox) / scale).roundToInt().coerceIn(0, bmpW)
    val top = ((s.top - oy) / scale).roundToInt().coerceIn(0, bmpH)
    val right = ((s.right - ox) / scale).roundToInt().coerceIn(0, bmpW)
    val bottom = ((s.bottom - oy) / scale).roundToInt().coerceIn(0, bmpH)
    val w = right - left
    val h = bottom - top
    if (w < RegionPickerActivity.MIN_EDGE_PX || h < RegionPickerActivity.MIN_EDGE_PX) return null
    return intArrayOf(left, top, w, h)
}
