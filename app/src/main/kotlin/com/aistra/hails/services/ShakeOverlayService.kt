package com.aistra.hails.services

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.aistra.hails.R
import com.aistra.hails.app.HailData
import com.aistra.hails.ui.main.MainActivity
import com.aistra.hails.ui.overlay.FrozenOverlayHost
import com.aistra.hails.utils.HTarget
import com.aistra.hails.utils.HUI
import com.aistra.hails.utils.ShakeDetector

/**
 * 摇一摇常驻前台服务。
 *
 * 只在「亮屏」期间注册传感器，灭屏立即注销以省电；摇晃命中后弹出「冻结」悬浮窗
 * （内容与首页底部 frozen_list 一致），再摇一次会留在原窗口上，点 ✕ 关闭。
 */
class ShakeOverlayService : Service() {
    private val channelId = javaClass.simpleName
    private lateinit var overlay: FrozenOverlayHost
    private var detector: ShakeDetector? = null
    private var sensorWarningShown = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> startListening()
                Intent.ACTION_SCREEN_OFF -> {
                    stopListening()
                    overlay.hide()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlay = FrozenOverlayHost(this)
        detector = ShakeDetector(this, sensitivity = { HailData.shakeSensitivity }) { onShake() }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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
        // 从设置页启动时屏幕可能已经亮着
        if (isInteractive()) startListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopListening()
        runCatching { unregisterReceiver(screenReceiver) }
        overlay.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun isInteractive(): Boolean = getSystemService<PowerManager>()?.isInteractive == true

    private fun startListening() {
        val shakeDetector = detector ?: return
        if (!shakeDetector.isSupported && !sensorWarningShown) {
            sensorWarningShown = true
            HUI.showToast(R.string.shake_no_sensor)
            return
        }
        shakeDetector.start()
    }

    private fun stopListening() {
        detector?.stop()
    }

    private fun onShake() {
        if (!isInteractive()) return
        overlay.show()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, channelId)
        .setContentTitle(getString(R.string.shake_overlay))
        .setContentText(getString(R.string.shake_overlay_notification_desc))
        .setSmallIcon(R.drawable.ic_round_frozen)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
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
    }
}
