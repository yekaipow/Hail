package com.aistra.hail.services

import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.ui.overlay.OverlayPanelManager
import com.aistra.hail.ui.overlay.ShakeOverlayHost
import com.aistra.hail.utils.HTarget
import com.aistra.hail.utils.HUI
import com.aistra.hail.utils.ShakeDetector

/**
 * 摇一摇悬浮窗的常驻前台服务。
 *
 * 只在「亮屏」期间注册传感器，灭屏立即注销，把耗电控制在可接受范围；
 * 摇晃命中后弹出 [ShakeOverlayHost]，内容为首页应用列表的紧凑面板。
 */
class ShakeOverlayService : Service() {
    private val channelId = javaClass.simpleName
    /** 全局唯一面板宿主：两个服务共用，杜绝多窗口 */
    private lateinit var overlay: ShakeOverlayHost
    private var detector: ShakeDetector? = null
    private var sensorWarningShown = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            // 采样率改了就重新注册传感器，否则要等下次亮屏才会生效
            HailData.SHAKE_SAMPLE_INTERVAL -> if (isInteractive()) {
                stopListening()
                startListening()
            }
            // 开关被关掉：不管 stopService 有没有生效，自己收起面板并退出
            HailData.SHAKE_OVERLAY -> if (!HailData.shakeOverlay) {
                mainHandler.post {
                    runCatching { overlay.hide() }
                    stopSelf()
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                /*
                 * 亮屏就启动，不判断是否解锁。
                 * 之前等 ACTION_USER_PRESENT 再启动，但息屏较久后该广播可能延迟/丢失，
                 * 传感器就一直没注册，表现为「亮屏后摇一摇失效」。
                 * 锁屏期间的少量采样换可靠性是值得的；start() 本身幂等，重复调用无害。
                 */
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> startListening()
                Intent.ACTION_SCREEN_OFF -> {
                    stopListening()
                    overlay.hide()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayPanelManager.get(this)
        detector = ShakeDetector(this, sensitivity = { HailData.shakeSensitivity }) { onShake() }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefListener)
        if (isInteractive()) startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (HTarget.U) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        // 通知上的「关闭」：落盘开关再退出，回 App 也不会自启
        if (intent?.action == ACTION_DISABLE) {
            HailData.shakeOverlay = false
            stopListening()
            runCatching { overlay.hide() }
            stopSelf()
            return START_NOT_STICKY
        }
        // 开关已经关掉（系统重启、残留启动等）时不要再活过来
        if (!HailData.shakeOverlay) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 从设置页重启服务时屏幕可能已经亮着
        if (isInteractive()) startListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { stopListening() }
        runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        runCatching { unregisterReceiver(screenReceiver) }
        // 面板是全局共用的：只有两个服务都不要了才真正摘窗口
        runCatching {
            if (!HailData.shakeOverlay && !HailData.shakeFloatingBall) OverlayPanelManager.release()
        }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun isInteractive(): Boolean = getSystemService<PowerManager>()?.isInteractive == true

    private fun startListening() {
        val shakeDetector = detector ?: return
        if (!shakeDetector.isSupported && !sensorWarningShown) {
            sensorWarningShown = true
            HUI.showToast(R.string.shake_overlay_no_sensor)
            return
        }
        shakeDetector.start()
    }

    private fun stopListening() {
        detector?.stop()
    }

    private fun onShake() {
        if (!isInteractive()) return
        // 锁屏界面不弹面板（否则会盖在锁屏上）
        if (getSystemService<KeyguardManager>()?.isKeyguardLocked == true) return
        /*
         * 面板是开关语义，且与悬浮球共用同一块屏幕位置：
         * 任意一方已经显示 → 收起；都没有 → 先收起别人的再显示自己的，避免两层叠在一起。
         */
        if (overlay.isShowing) overlay.hide() else overlay.show()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, channelId)
        .setContentTitle(getString(R.string.shake_overlay))
        .setContentText(getString(R.string.shake_overlay_notification_desc))
        .setSmallIcon(R.drawable.ic_round_frozen)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .addAction(
            0,
            getString(R.string.action_disable),
            PendingIntent.getService(
                this,
                1,
                Intent(this, ShakeOverlayService::class.java).setAction(ACTION_DISABLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.shake_overlay))
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private companion object {
        const val NOTIFICATION_ID = 101

        /** 通知上的「关闭」按钮 */
        const val ACTION_DISABLE = "com.aistra.hail.action.DISABLE_SHAKE_OVERLAY"
    }
}
