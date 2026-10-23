package com.aistra.hails.ui.overlay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
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
            onOpenApp = { packageName, freeform ->
                hide()
                openApp(packageName, freeform)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (lastX != Int.MIN_VALUE) lastX else (metrics.widthPixels - width) / 2
            y = if (lastY != Int.MIN_VALUE) lastY else (metrics.heightPixels * 0.15f).toInt()
        }
    }

    /**
     * @param packageName 为 null 时打开 Hail 主界面
     * @param freeform true 时以自由窗口方式打开（设备不支持时系统自动回退全屏）
     */
    private fun openApp(packageName: String?, freeform: Boolean) {
        runCatching {
            if (packageName == null) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@runCatching
            }
            if (HailData.workingMode == HailData.MODE_ISLAND_HIDE) HIsland.ensureLaunchIntentExists(packageName)
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: throw IllegalStateException("no launch intent")
            if (freeform) startActivityFreeform(intent) else context.startActivity(intent)
        }.onFailure {
            HUI.showToast(R.string.activity_not_found)
        }
    }

    /**
     * 以自由窗口（Freeform）打开。
     *
     * 两个必要条件：
     * 1. Intent 加 FLAG_ACTIVITY_MULTIPLE_TASK——否则目标应用已有的全屏 task 会被复用，
     *    窗口模式参数被忽略（表现为"小窗没生效"）。
     * 2. 反射调用 setLaunchWindowingMode(5) 指定窗口模式（@hide API，部分 ROM 会拦截）。
     *
     * 反射失败或设备不支持自由窗口时回退全屏启动。
     */
    private fun startActivityFreeform(intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            context.startActivity(intent)
            return
        }
        try {
            /*
             * 关键：必须加 MULTIPLE_TASK。
             * 目标应用通常已经有一个全屏 task，只加 NEW_TASK 会把它调到前台复用，
             * 窗口模式参数被忽略，表现就是"小窗没生效"。MULTIPLE_TASK 强制新建 task，
             * setLaunchWindowingMode 才会作用在新窗口上。
             */
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val options = ActivityOptions.makeBasic()
            // setLaunchWindowingMode 是 @hide API，反射调用；部分 ROM 会拦截，失败则回退全屏
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, WINDOWING_MODE_FREEFORM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val metrics = context.resources.displayMetrics
                val width = metrics.widthPixels * 3 / 5
                val height = metrics.heightPixels * 3 / 5
                val left = (metrics.widthPixels - width) / 2
                val top = (metrics.heightPixels - height) / 4
                options.setLaunchBounds(Rect(left, top, left + width, top + height))
            }
            context.startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            // 反射被拦截或窗口模式不被支持：回退普通全屏启动
            context.startActivity(intent)
            HUI.showToast(R.string.shake_freeform_unavailable)
        }
    }

    private companion object {
        /** WindowConfiguration.WINDOWING_MODE_FREEFORM */
        const val WINDOWING_MODE_FREEFORM = 5
    }
}

