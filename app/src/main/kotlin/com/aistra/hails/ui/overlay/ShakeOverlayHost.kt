package com.aistra.hails.ui.overlay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import com.aistra.hails.R
import com.aistra.hails.app.HailData
import com.aistra.hails.ui.main.MainActivity
import com.aistra.hails.utils.HIsland
import com.aistra.hails.utils.HShell
import com.aistra.hails.utils.HShizuku
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
     * setLaunchWindowingMode 是 @hide API：Android 9+ 反射会被 hidden API 限制拦截，
     * 因此优先走 Shizuku / root 的 shell —— `am start --windowingMode 5`（Android 9+ 支持），
     * 不受 hidden API 限制。无特权时退回反射尝试，仍失败则全屏启动并提示。
     *
     * 另外最终能否小窗还取决于系统是否支持自由窗口
     * （开发者选项「强制允许自由窗口」或 ROM 自带），不支持时系统会静默回退全屏。
     */
    private fun startActivityFreeform(intent: Intent) {
        val component = intent.component?.flattenToShortString()
        val shellPath = component != null && (
            HailData.workingMode.startsWith(HailData.SHIZUKU) ||
                HailData.workingMode.startsWith(HailData.SU)
            )
        if (shellPath && component != null) {
            val command = "am start --user current --windowingMode 5 -n $component"
            val shizuku = HailData.workingMode.startsWith(HailData.SHIZUKU)
            Thread {
                val result = if (shizuku) HShizuku.execute(command) else HShell.execute(command, true)
                // 老系统不认识 --windowingMode 参数会导致启动失败，回退普通全屏启动
                if (result.first != 0) runOnMain { context.startActivity(intent) }
            }.start()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val options = ActivityOptions.makeBasic()
                ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .invoke(options, WINDOWING_MODE_FREEFORM)
                val metrics = context.resources.displayMetrics
                val width = metrics.widthPixels * 3 / 5
                val height = metrics.heightPixels * 3 / 5
                val left = (metrics.widthPixels - width) / 2
                val top = (metrics.heightPixels - height) / 4
                options.setLaunchBounds(Rect(left, top, left + width, top + height))
                context.startActivity(intent, options.toBundle())
                return
            } catch (e: Exception) {
                // hidden API 被系统拦截等，走下方兜底
            }
        }
        context.startActivity(intent)
        HUI.showToast(R.string.shake_freeform_unavailable)
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        Handler(Looper.getMainLooper()).post { block() }
    }

    private companion object {
        /** WindowConfiguration.WINDOWING_MODE_FREEFORM */
        const val WINDOWING_MODE_FREEFORM = 5
    }
}

