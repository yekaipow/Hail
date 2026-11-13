package com.aistra.hail.ui.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.utils.HFreeform
import com.aistra.hail.utils.HIsland
import com.aistra.hail.utils.HTarget
import com.aistra.hail.utils.HUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 摇一摇悬浮窗的宿主。
 *
 * 职责：持有 WindowManager 窗口、记住上次的拖动位置、把面板回调接到应用启动逻辑上。
 * 悬浮窗内容是 [ShakeOverlayPanel]（纯 View 实现）。
 *
 * 关键约束：**一个宿主永远只持有一个窗口**。
 * 面板视图复用一个实例；隐藏时同步摘窗口（removeViewImmediate），显示时如果窗口还在
 * （摘除失败/滞后）就直接更新这个窗口，绝不再 addView 一个新的，从根上避免窗口叠加。
 */
class ShakeOverlayHost(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 打开应用的协程作用域：freeform 链路要跑 shell + 等待，不能占主线程 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 唯一的窗口内容，懒创建后一直复用 */
    private var panel: ShakeOverlayPanel? = null
    private var params: WindowManager.LayoutParams? = null
    private var showing = false
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    val isShowing: Boolean get() = showing

    fun show(): Boolean {
        if (showing) return true
        if (!Settings.canDrawOverlays(context)) {
            HUI.showToast(R.string.shake_overlay_permission)
            return false
        }
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val p = params ?: createLayoutParams().also { params = it }
        val panelView = panel ?: createPanel().also { panel = it }

        val lastHeight = p.height
        p.width = minOf((PANEL_WIDTH_DP * density).toInt(), metrics.widthPixels - (32 * density).toInt())
        p.flags = VISIBLE_FLAGS
        p.x = if (lastX != Int.MIN_VALUE) lastX else (metrics.widthPixels - p.width) / 2
        p.y = if (lastY != Int.MIN_VALUE) lastY else (metrics.heightPixels * 0.15f).toInt()
        // 首帧就按上次高度把窗口夹在屏幕内，避免底部按钮先被顶出屏幕再跳回来
        if (lastHeight > 0) {
            p.x = p.x.coerceIn(0, (metrics.widthPixels - p.width).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (metrics.heightPixels - lastHeight).coerceAtLeast(0))
        }
        p.height = LayoutParams.WRAP_CONTENT

        showing = true
        return try {
            // 窗口还在（上次没摘干净）就更新它，绝不再 add 一个新窗口
            if (panelView.parent == null) windowManager.addView(panelView, p)
            else windowManager.updateViewLayout(panelView, p)
            // 每次打开都刷新一次数据（上次关闭后冻结状态可能已经变了）
            panelView.refresh()
            true
        } catch (e: Exception) {
            // 权限被回收或窗口被系统拒绝时不要把服务搞崩
            showing = false
            HUI.showToast(R.string.shake_overlay_permission)
            false
        }
    }

    fun hide() {
        val panelView = panel ?: return
        val p = params ?: return
        if (!showing) return
        showing = false
        lastX = p.x
        lastY = p.y
        // 同步摘除窗口；面板视图实例保留复用
        runCatching { windowManager.removeViewImmediate(panelView) }
    }

    /** 服务销毁时调用 */
    fun release() {
        hide()
        val panelView = panel
        if (panelView != null) {
            panel = null
            params = null
            runCatching { windowManager.removeViewImmediate(panelView) }
            runCatching { panelView.release() }
        }
        // 窗口清理完再收协程，避免正在进行的打开流程把已释放的宿主当活着的用
        scope.cancel()
    }

    private fun createPanel() = ShakeOverlayPanel(
        context,
        onClose = { hide() },
        onOpenApp = { packageName ->
            hide()
            openApp(packageName)
        },
        onDrag = { dx, dy -> moveBy(dx, dy) }
    ).apply {
        onContentHeightChanged = { heightPx ->
            // 只有显示中才调整窗口
            val p = params
            if (showing && p != null) {
                runCatching {
                    val metrics = context.resources.displayMetrics
                    val density = metrics.density
                    // 内容高度一变（含旋转/分辨率变化）就重算宽度并把窗口夹进屏幕，
                    // 保证标题栏和底部三个按钮在任何分辨率下都完整可见
                    p.width = minOf((PANEL_WIDTH_DP * density).toInt(), metrics.widthPixels - (32 * density).toInt())
                    p.height = heightPx
                    p.x = p.x.coerceIn(0, (metrics.widthPixels - p.width).coerceAtLeast(0))
                    p.y = p.y.coerceIn(0, (metrics.heightPixels - heightPx).coerceAtLeast(0))
                    lastX = p.x
                    lastY = p.y
                    windowManager.updateViewLayout(this@apply, p)
                }
            }
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        if (!showing) return
        val panelView = panel ?: return
        val p = params ?: return
        val metrics = context.resources.displayMetrics
        p.x = (p.x + dx).toInt()
            .coerceIn(0, (metrics.widthPixels - panelView.width).coerceAtLeast(0))
        p.y = (p.y + dy).toInt()
            .coerceIn(0, (metrics.heightPixels - panelView.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(panelView, p) }
        lastX = p.x
        lastY = p.y
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val width = minOf((PANEL_WIDTH_DP * metrics.density).toInt(), metrics.widthPixels - (32 * metrics.density).toInt())
        return WindowManager.LayoutParams(
            width,
            LayoutParams.WRAP_CONTENT,
            if (HTarget.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            VISIBLE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - width) / 2
            y = (metrics.heightPixels * 0.15f).toInt()
        }
    }

    /** @param packageName 为 null 时打开 Hail 主界面 */
    private fun openApp(packageName: String?) {
        if (packageName == null) {
            runCatching {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { HUI.showToast(R.string.activity_not_found) }
            return
        }
        if (HailData.workingMode == HailData.MODE_ISLAND_HIDE) HIsland.ensureLaunchIntentExists(packageName)
        /*
         * 横屏 + 有特权：全屏应用会把悬浮窗整个盖住，改用 freeform 小窗打开，
         * 让应用只占屏幕中间一块。已有任务时是调到前台，不会 force-stop。
         * 这条链路要跑几条 shell 命令并等待窗口建立，必须离开主线程。
         */
        if (HFreeform.available() && HFreeform.isLandscape(context)) {
            scope.launch {
                val opened = withContext(Dispatchers.IO) {
                    runCatching { HFreeform.open(context, packageName) }.getOrDefault(false)
                }
                if (!opened) startLaunchIntent(packageName)
            }
            return
        }
        startLaunchIntent(packageName)
    }

    private fun startLaunchIntent(packageName: String) {
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: throw IllegalStateException("no launch intent")
            context.startActivity(intent)
        }.onFailure {
            HUI.showToast(R.string.activity_not_found)
        }
    }

    private companion object {
        /** 面板宽度上限（dp） */
        const val PANEL_WIDTH_DP = 320

        /**
         * 不抢焦点：不打断前台应用的输入；面板内仍可正常点击和滚动。
         * WATCH_OUTSIDE_TOUCH：点面板外的任意位置会派发 ACTION_OUTSIDE，用来关闭面板。
         */
        val VISIBLE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }
}
