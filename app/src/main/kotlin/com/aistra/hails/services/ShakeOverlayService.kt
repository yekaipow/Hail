package com.aistra.hails.services

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.aistra.hails.R
import com.aistra.hails.app.AppManager
import com.aistra.hails.app.HailData
import com.aistra.hails.ui.main.MainActivity
import com.aistra.hails.ui.overlay.ShakeOverlayHost
import com.aistra.hails.utils.HActions
import com.aistra.hails.utils.HTarget
import com.aistra.hails.utils.HUI
import com.aistra.hails.utils.ShakeDetector
import kotlin.math.abs

/**
 * 摇一摇悬浮窗的常驻前台服务。
 *
 * 只在「亮屏」期间注册传感器，灭屏立即注销，把耗电控制在可接受范围；
 * 摇晃命中后弹出 [ShakeOverlayHost]，内容为首页应用列表的紧凑面板。
 */
class ShakeOverlayService : Service() {
    private val channelId = javaClass.simpleName
    private lateinit var overlay: ShakeOverlayHost
    private var detector: ShakeDetector? = null
    private var sensorWarningShown = false
    private var floatingBall: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    companion object {
        /** 设置页实时通知运行中的服务挂 / 摘悬浮球 */
        var instance: ShakeOverlayService? = null
            private set

        private const val NOTIFICATION_ID = 101
        private const val BALL_SIZE_DP = 44
        private const val BALL_MARGIN_DP = 16
        private const val BALL_LONG_PRESS_MS = 500L

        /** 半透明深灰圆底，深浅主题下都能看清 */
        private const val BALL_BACKGROUND = 0x99333333.toInt()
    }

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
        instance = this
        overlay = ShakeOverlayHost(this)
        if (HailData.shakeFloatingBall) attachFloatingBall()
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
        // 从设置页重启服务时屏幕可能已经亮着
        if (isInteractive()) startListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        detachFloatingBall()
        instance = null
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
        overlay.show()
    }

    /** 设置页实时切换悬浮球 */
    fun setFloatingBallEnabled(enabled: Boolean) =
        if (enabled) attachFloatingBall() else detachFloatingBall()

    private fun attachFloatingBall() {
        val current = floatingBall
        if (current != null) return
        if (!Settings.canDrawOverlays(this)) return
        val density = resources.displayMetrics.density
        val size = (BALL_SIZE_DP * density).toInt()
        val metrics = resources.displayMetrics
        val ball = ImageView(this).apply {
            setImageDrawable(applicationInfo.loadIcon(packageManager))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BALL_BACKGROUND)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(size / 5, size / 5, size / 5, size / 5)
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (HTarget.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (HailData.shakeFloatingBallX != Int.MIN_VALUE) HailData.shakeFloatingBallX
            else metrics.widthPixels - size - (BALL_MARGIN_DP * density).toInt()
            y = if (HailData.shakeFloatingBallY != Int.MIN_VALUE) HailData.shakeFloatingBallY
            else metrics.heightPixels / 3
        }
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var moved = false
        var longPressed = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressRunnable = Runnable {
            if (!moved) {
                longPressed = true
                onFloatingBallLongPress()
            }
        }
        ball.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longPressed = false
                    mainHandler.postDelayed(longPressRunnable, BALL_LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(ball, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (moved) {
                            HailData.shakeFloatingBallX = params.x
                            HailData.shakeFloatingBallY = params.y
                        } else if (!longPressed) {
                            // 点击悬浮球 = 悬浮窗面板的开关
                            if (overlay.isShowing) overlay.hide() else overlay.show()
                        }
                    }
                }
            }
            true
        }
        runCatching {
            windowManager.addView(ball, params)
            floatingBall = ball
        }
    }

    private fun detachFloatingBall() {
        val ball = floatingBall ?: return
        floatingBall = null
        runCatching { windowManager.removeView(ball) }
    }

    /** 长按悬浮球：按设置执行全部冻结或锁屏，阻塞调用放在后台线程 */
    private fun onFloatingBallLongPress() {
        Thread {
            when (HailData.shakeFloatingBallAction) {
                HailData.ACTION_LOCK -> HActions.lockScreen()
                else -> {
                    val frozen = HActions.freezeAll()
                    if (frozen > 0) mainHandler.post { HUI.showToast(R.string.msg_freeze_all) }
                }
            }
        }.start()
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
}
