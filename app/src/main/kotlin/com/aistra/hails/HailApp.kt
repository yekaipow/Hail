package com.aistra.hails

import android.app.Application
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.aistra.hails.app.AppManager
import com.aistra.hails.app.HailData
import com.aistra.hails.services.AutoFreezeService
import com.aistra.hails.services.ShakeOverlayService
import com.aistra.hails.utils.HDhizuku
import com.aistra.hails.utils.HTarget
import com.aistra.hails.utils.HUI

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

    fun setShakeOverlayService(enabled: Boolean = HailData.shakeOverlay, context: Context = app) {
        val intent = Intent(app, ShakeOverlayService::class.java)
        if (!enabled) {
            context.stopService(intent)
            return
        }
        // 后台启动前台服务可能被系统拒绝（Android 12+），失败时提示而不是崩溃
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { HUI.showToast(R.string.shake_overlay_start_failed) }
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
    }
}