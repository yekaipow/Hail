package com.aistra.hail

import android.app.Application
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.services.AutoFreezeService
import com.aistra.hail.services.FloatingBallService
import com.aistra.hail.services.RecentTaskFreezeService
import com.aistra.hail.services.ShakeOverlayService
import com.aistra.hail.ui.overlay.OverlayPanelManager
import com.aistra.hail.utils.HDhizuku
import com.aistra.hail.utils.HTarget
import com.aistra.hail.utils.HUI

class HailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        // DirtyDataUpdater.update(app)
        if (!HTarget.S) setAppTheme(HailData.appTheme)
        if (HailData.workingMode.startsWith(HailData.DHIZUKU)) HDhizuku.init()
    }

    fun setAutoFreezeService(autoFreezeAfterLock: Boolean = HailData.autoFreezeAfterLock, context: Context = app) {
        val start = autoFreezeAfterLock && HailData.checkedList.any {
            it.packageName != packageName && it.applicationInfo != null && !AppManager.isAppFrozen(it.packageName) && !it.whitelisted
        }
        val intent = Intent(app, AutoFreezeService::class.java)
        if (start) {
            setAutoFreezeServiceEnabled(true)
            ContextCompat.startForegroundService(context, intent)
        } else {
            stopService(intent)
            setAutoFreezeServiceEnabled(false)
        }
    }

    fun setAutoFreezeServiceEnabled(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            ComponentName(app, AutoFreezeService::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    /** 划走最近任务即冻结 */
    fun setRecentTaskFreezeService(enabled: Boolean = HailData.recentTaskFreeze, context: Context = app) {
        val intent = Intent(app, RecentTaskFreezeService::class.java)
        if (enabled) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
    }

    /** 悬浮球独立服务，与摇一摇互不影响 */
    fun setFloatingBallService(enabled: Boolean = HailData.shakeFloatingBall, context: Context = app) {
        val intent = Intent(app, FloatingBallService::class.java)
        if (!enabled) {
            // 先把开关落盘并立刻收起共用面板：不等服务销毁，避免启停交错时窗口残留
            HailData.shakeFloatingBall = false
            OverlayPanelManager.peek()?.hide()
            stopServiceNow(intent, context)
            return
        }
        cancelPendingStop(intent)
        // 后台启动前台服务可能被系统拒绝（Android 12+），失败时提示而不是崩溃
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { HUI.showToast(R.string.shake_overlay_start_failed) }
    }

    fun setShakeOverlayService(enabled: Boolean = HailData.shakeOverlay, context: Context = app) {
        val intent = Intent(app, ShakeOverlayService::class.java)
        if (!enabled) {
            // 先把开关落盘并立刻收起共用面板：不等服务销毁，避免启停交错时窗口残留
            HailData.shakeOverlay = false
            OverlayPanelManager.peek()?.hide()
            stopServiceNow(intent, context)
            return
        }
        cancelPendingStop(intent)
        // 后台启动前台服务可能被系统拒绝（Android 12+），失败时提示而不是崩溃
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { HUI.showToast(R.string.shake_overlay_start_failed) }
    }

    /** 每个服务待执行的延迟 stop，按服务类名登记 */
    private val pendingStops = HashMap<String, Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 关闭开关时真正销毁服务。
     *
     * 开关连点会让 startForegroundService 请求排在 stopService 之后生效，服务被重新拉起；
     * 这里补一次延迟 stop，保证关闭动作的最终结果是「服务不存在」。
     * 但延迟 stop 必须可取消：若 400ms 内用户又打开开关，启动前会先取消未决 stop，
     * 否则新启动的服务会被残留的延迟 stop 立即杀掉，造成服务反复创建销毁。
     * 服务侧也有双保险：onStartCommand 发现开关已关会 stopSelf。
     */
    private fun stopServiceNow(intent: Intent, context: Context) {
        context.stopService(intent)
        val key = intent.component?.className ?: return
        val stopRunnable = Runnable {
            pendingStops.remove(key)
            app.stopService(intent)
        }
        pendingStops[key]?.let { mainHandler.removeCallbacks(it) }
        pendingStops[key] = stopRunnable
        mainHandler.postDelayed(stopRunnable, DELAYED_STOP_MS)
    }

    /** 启动服务前取消该服务的未决延迟 stop，避免「重开后又被延迟 stop 杀掉」的竞态 */
    private fun cancelPendingStop(intent: Intent) {
        val key = intent.component?.className ?: return
        pendingStops[key]?.let {
            mainHandler.removeCallbacks(it)
            pendingStops.remove(key)
        }
    }

    fun setAppTheme(theme: String) {
        if (HTarget.S) getSystemService<UiModeManager>()!!.setApplicationNightMode(
            when (theme) {
                HailData.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
                HailData.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
        )
        else AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                HailData.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                HailData.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }


    companion object {
        lateinit var app: HailApp private set

        /** 延迟 stop 的窗口，关闭开关后等待这么久再补一次 stop */
        private const val DELAYED_STOP_MS = 400L
    }
}