package com.aistra.hail.ui.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.utils.HIsland
import com.aistra.hail.utils.HTarget
import com.aistra.hail.utils.HUI

/**
 * 摇一摇悬浮窗的宿主。
 *
 * 职责：持有 WindowManager 窗口、记住上次的拖动位置、把面板回调接到应用启动逻辑上。
 * 悬浮窗内容是 [ShakeOverlayPanel]（纯 View 实现）。
 *
 * 关键约束：**一个宿主永远只持有一个窗口**。
 * 面板视图只创建一次，显示/隐藏通过切换可见性 + 把窗口缩到 1px 并挪到屏幕角落实现。
 * 之前隐藏走 removeView、下次显示再 addView，removeView 一旦失败/滞后就会在屏幕上留下多个面板，
 * 现在物理上不可能出现第二个窗口。
 */
class ShakeOverlayHost(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 唯一的窗口内容，懒创建后一直复用 */
    private var panel: ShakeOverlayPanel? = null
    private var params: WindowManager.LayoutParams? = null
    private var showing = false
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    val isShowing: Boolean get() = showing

    init {
        ShakeOverlayHosts.register(this)
    }

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

        p.width = minOf((PANEL_WIDTH_DP * density).toInt(), metrics.widthPixels - (32 * density).toInt())
        p.height = LayoutParams.WRAP_CONTENT
        p.flags = VISIBLE_FLAGS
        p.x = if (lastX != Int.MIN_VALUE) lastX else (metrics.widthPixels - p.width) / 2
        p.y = if (lastY != Int.MIN_VALUE) lastY else (metrics.heightPixels * 0.15f).toInt()

        panelView.visibility = View.VISIBLE
        showing = true
        return try {
            if (panelView.parent == null) windowManager.addView(panelView, p)
            else windowManager.updateViewLayout(panelView, p)
            // 每次打开都刷新一次数据（上次关闭后冻结状态可能已经变了）
            panelView.refresh()
            true
        } catch (e: Exception) {
            // 权限被回收或窗口被系统拒绝时不要把服务搞崩
            showing = false
            panelView.visibility = View.GONE
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
        // 不销毁窗口：藏起来 + 缩成 1px 挪到屏幕右上角，既不可见也不挡触摸
        panelView.visibility = View.GONE
        p.width = 1
        p.height = 1
        p.x = context.resources.displayMetrics.widthPixels - 1
        p.y = 0
        p.flags = HIDDEN_FLAGS
        runCatching { windowManager.updateViewLayout(panelView, p) }
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
        ShakeOverlayHosts.unregister(this)
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
            // 只有显示中才调整高度；隐藏时窗口固定 1px
            if (showing) {
                val p = params ?: return@onContentHeightChanged
                runCatching {
                    p.height = heightPx
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
        runCatching {
            if (packageName == null) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@runCatching
            }
            if (HailData.workingMode == HailData.MODE_ISLAND_HIDE) HIsland.ensureLaunchIntentExists(packageName)
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

        /** 隐藏时再叠加不可触摸，1px 的角落窗口完全不参与交互 */
        val HIDDEN_FLAGS = VISIBLE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
}
