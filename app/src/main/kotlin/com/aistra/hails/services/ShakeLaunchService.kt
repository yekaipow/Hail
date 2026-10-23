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
import com.aistra.hails.utils.HFloatingWindow
import com.aistra.hails.utils.HTarget
import com.aistra.hails.utils.HUI
import com.aistra.hails.utils.ShakeDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 摇一摇常驻前台服务。
 *
 * 只在「亮屏」期间注册传感器，灭屏立即注销以省电；摇晃命中后用小窗方式打开 Hail 主界面
 * （见 [HFloatingWindow]，不支持自由窗口的设备退化为全屏）。
 */
class ShakeLaunchService : Service() {
    private val channelId = javaClass.simpleName
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var detector: ShakeDetector? = null
    private var sensorWarningShown = false

    /** 防止一次摇晃触发多次启动 */
    private var launching = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> startListening()
                Intent.ACTION_SCREEN_OFF -> stopListening()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
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
        scope.cancel()
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
        if (!isInteractive() || launching) return
        launching = true
        scope.launch {
            val floating = HFloatingWindow.launch(this@ShakeLaunchService)
            if (!floating) HUI.showToast(R.string.shake_no_freeform)
            // 与传感器的冷却时间配合，避免一次摇晃重复拉起窗口
            delay(1500)
            launching = false
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, channelId)
        .setContentTitle(getString(R.string.shake_to_open))
        .setContentText(getString(R.string.shake_notification_desc))
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
            .setName(getString(R.string.shake_to_open))
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private companion object {
        const val NOTIFICATION_ID = 101
    }
}
