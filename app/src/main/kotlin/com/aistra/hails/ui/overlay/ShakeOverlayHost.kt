package com.aistra.hails.ui.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.aistra.hails.R
import com.aistra.hails.app.HailData
import com.aistra.hails.ui.main.MainActivity
import com.aistra.hails.utils.HIsland
import com.aistra.hails.utils.HTarget
import com.aistra.hails.utils.HUI
import kotlin.math.min

/**
 * 摇一摇悬浮窗的宿主。
 *
 * 职责：持有 WindowManager 窗口、记住上次的拖动位置、把面板回调接到应用启动逻辑上。
 * 悬浮窗内容是 [ShakeOverlayPanel]（纯 View 实现）。
 */
class ShakeOverlayHost(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ShakeOverlayPanel? = null
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
        val panel = ShakeOverlayPanel(
            context,
            onClose = { hide() },
            onOpenApp = { packageName ->
                hide()
                openApp(packageName)
            },
            onDrag = { dx, dy -> moveBy(dx, dy) }
        )
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
        params.x = (params.x + dx).toInt()
            .coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = (params.y + dy).toInt()
            .coerceIn(0, (metrics.heightPixels - params.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(current, params) }
        lastX = params.x
        lastY = params.y
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val width = min((320 * density).toInt(), metrics.widthPixels - (32 * density).toInt())
        // 固定高度：最多三行图标，留出滚动余量，避免依赖 WRAP_CONTENT 的测量结果
        val height = min((360 * density).toInt(), (metrics.heightPixels * 0.6f).toInt())
        return WindowManager.LayoutParams(
            width,
            height,
            if (HTarget.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            // 不抢焦点：不打断前台应用的输入；面板内仍可正常点击和滚动
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (lastX != Int.MIN_VALUE) lastX else (metrics.widthPixels - width) / 2
            y = if (lastY != Int.MIN_VALUE) lastY else (metrics.heightPixels * 0.15f).toInt()
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
}
