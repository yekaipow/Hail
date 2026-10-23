package com.aistra.hails.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.aistra.hails.R
import com.aistra.hails.utils.HTarget
import com.aistra.hails.utils.HUI
import kotlin.math.min

/**
 * 「冻结」悬浮窗的宿主：负责 WindowManager 窗口的添加/移除、拖动与位置记忆。
 * 窗口内容是 [FrozenOverlayPanel]（即首页底部 frozen_list 的那套 UI）。
 */
class FrozenOverlayHost(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: FrozenOverlayPanel? = null
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    val isShowing: Boolean get() = view != null

    fun show(): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(context)) {
            HUI.showToast(R.string.shake_overlay_permission)
            return false
        }
        val params = createLayoutParams()
        val panel = FrozenOverlayPanel(
            context,
            onClose = { hide() },
            onDrag = { dx, dy -> moveBy(dx, dy) }
        )
        // 列表行数变化时窗口高度跟着变
        panel.onSizeChanged = {
            runCatching { windowManager.updateViewLayout(panel, params) }
        }
        return try {
            windowManager.addView(panel, params)
            view = panel
            true
        } catch (e: Exception) {
            // 权限被回收或窗口被系统拒绝时不要把服务搞崩
            HUI.showToast(R.string.shake_overlay_permission)
            false
        }
    }

    fun hide() {
        val current = view ?: return
        (current.layoutParams as? WindowManager.LayoutParams)?.let {
            lastX = it.x
            lastY = it.y
        }
        runCatching { windowManager.removeView(current) }
        current.release()
        view = null
    }

    /** 服务销毁时调用 */
    fun release() {
        hide()
    }

    private fun moveBy(dx: Float, dy: Float) {
        val current = view ?: return
        val params = current.layoutParams as? WindowManager.LayoutParams ?: return
        val metrics = context.resources.displayMetrics
        // 高度是 WRAP_CONTENT 时 params.height 是 -2，用视图实测尺寸代替
        val width = if (current.width > 0) current.width else params.width
        val height = if (current.height > 0) current.height else params.height
        params.x = (params.x + dx).toInt()
            .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        params.y = (params.y + dy).toInt()
            .coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(current, params) }
        lastX = params.x
        lastY = params.y
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val width = min((420 * density).toInt(), metrics.widthPixels - (32 * density).toInt())
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (HTarget.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            // 不抢焦点：不打断前台应用的输入；面板内仍可正常点击和滚动
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (lastX != Int.MIN_VALUE) lastX else (metrics.widthPixels - width) / 2
            // 默认落在屏幕下半部分，和首页底部「冻结」区的位置直觉一致
            y = if (lastY != Int.MIN_VALUE) lastY else (metrics.heightPixels * 0.55f).toInt()
        }
    }
}
