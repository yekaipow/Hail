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
import com.aistra.hail.utils.HIsland
import com.aistra.hail.utils.HTarget
import com.aistra.hail.utils.HUI
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

    init {
        ShakeOverlayHosts.register(this)
    }

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
        panel.onContentHeightChanged = { heightPx ->
            runCatching {
                // 面板把内容精确高度算好了，直接固化，窗口不可能再裁掉内容
                params.height = heightPx
                windowManager.updateViewLayout(panel, params)
            }
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
        ShakeOverlayHosts.unregister(this)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val current = view ?: return
        val params = current.layoutParams as? WindowManager.LayoutParams ?: return
        val metrics = context.resources.displayMetrics
        val viewWidth = current.width
        val viewHeight = current.height
        params.x = (params.x + dx).toInt()
            .coerceIn(0, (metrics.widthPixels - viewWidth).coerceAtLeast(0))
        params.y = (params.y + dy).toInt()
            .coerceIn(0, (metrics.heightPixels - viewHeight).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(current, params) }
        lastX = params.x
        lastY = params.y
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val width = min((320 * density).toInt(), metrics.widthPixels - (32 * density).toInt())
        val height = LayoutParams.WRAP_CONTENT
        return WindowManager.LayoutParams(
            width,
            height,
            if (HTarget.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            // 不抢焦点：不打断前台应用的输入；面板内仍可正常点击和滚动
            // WATCH_OUTSIDE_TOUCH：点面板外的任意位置会派发 ACTION_OUTSIDE，用来关闭面板
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
