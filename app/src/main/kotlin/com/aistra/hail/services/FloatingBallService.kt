package com.aistra.hail.services

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
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.ui.overlay.OverlayPanelManager
import com.aistra.hail.ui.overlay.ShakeOverlayHost
import com.aistra.hail.utils.HActions
import com.aistra.hail.utils.HTarget
import com.aistra.hail.utils.HUI
import kotlin.math.abs

/**
 * 悬浮球前台服务：独立于摇一摇，只负责悬浮球的创建与手势。
 *
 * 点击悬浮球开关自己的 [ShakeOverlayHost] 面板（不依赖摇一摇服务是否运行），
 * 长按执行设置所选的动作（全部冻结 / 锁屏）。拖动调位置并记忆。
 */
class FloatingBallService : Service() {
    private val channelId = javaClass.simpleName
    /** 全局唯一面板宿主：与摇一摇共用，杜绝多窗口 */
    private lateinit var panelHost: ShakeOverlayHost
    private var floatingBall: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 灭屏时收起面板；悬浮球本体保留（锁屏界面上本就不显示 overlay 窗口）
            if (intent.action == Intent.ACTION_SCREEN_OFF) panelHost.hide()
        }
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            // 大小改变后重建悬浮球，位置从 HailData 读回，不会跳位
            HailData.FLOATING_BALL_SIZE -> {
                // 始终先拆后建：隐藏状态下改回大尺寸也要重新出现
                detachFloatingBall()
                attachFloatingBall()
            }
            // 开关被关掉：不管 stopService 有没有生效，这里先摘窗口再自杀，杜绝残留
            // （偏好回调可能不在主线程，窗口操作统一切到主线程）
            HailData.SHAKE_FLOATING_BALL -> if (!HailData.shakeFloatingBall) {
                mainHandler.post {
                    detachFloatingBall()
                    panelHost.hide()
                    stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        panelHost = OverlayPanelManager.get(this)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 服务被系统重建时也要尊重开关：关掉就不建球（onStartCommand 随后 stopSelf）
        if (HailData.shakeFloatingBall) attachFloatingBall()
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (HTarget.U) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        // 通知上的「关闭」：落盘开关、摘窗口再退出
        if (intent?.action == ACTION_DISABLE) {
            HailData.shakeFloatingBall = false
            detachFloatingBall()
            runCatching { panelHost.hide() }
            stopSelf()
            return START_NOT_STICKY
        }
        // 开关已经关掉（系统重启、残留启动等）时不要再活过来
        if (!HailData.shakeFloatingBall) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 悬浮窗权限被回收时直接停掉，避免无效常驻
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (floatingBall == null) attachFloatingBall()
        return START_STICKY
    }

    override fun onDestroy() {
        // 窗口归进程所有：服务停了进程还在时，没摘掉的窗口会一直留在屏幕上，所以第一件事就摘
        runCatching { detachFloatingBall() }
        runCatching { panelHost.hide() }
        runCatching {
            if (!HailData.shakeOverlay && !HailData.shakeFloatingBall) OverlayPanelManager.release()
        }
        runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun attachFloatingBall() {
        if (floatingBall != null) return
        if (!Settings.canDrawOverlays(this)) return
        val density = resources.displayMetrics.density
        val size = (HailData.floatingBallSize * density).toInt().coerceAtLeast(1)
        val metrics = resources.displayMetrics
        // 尺寸小于阈值：窗口推到屏幕外；必须配 FLAG_LAYOUT_NO_LIMITS，否则系统会把越界坐标限制回屏内
        val offscreen = HailData.floatingBallSize < MIN_VISIBLE_SIZE_DP
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
            if (offscreen) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (offscreen) metrics.widthPixels + OFFSCREEN_OFFSET_PX
            else if (HailData.shakeFloatingBallX != Int.MIN_VALUE) HailData.shakeFloatingBallX
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
                            /*
                             * 点击悬浮球 = 面板开关，且与摇一摇共用同一块面板位：
                             * 任意一方已经显示 → 收起；都没有 → 先收起别人的再显示自己的。
                             */
                            if (panelHost.isShowing) panelHost.hide() else panelHost.show()
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
        .setContentTitle(getString(R.string.shake_floating_ball))
        .setContentText(getString(R.string.shake_floating_ball_desc))
        .setSmallIcon(R.drawable.ic_round_frozen)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .addAction(
            0,
            getString(R.string.action_disable),
            PendingIntent.getService(
                this,
                2,
                Intent(this, FloatingBallService::class.java).setAction(ACTION_DISABLE),
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
            .setName(getString(R.string.shake_floating_ball))
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private companion object {
        const val NOTIFICATION_ID = 102
        const val BALL_MARGIN_DP = 16

        /** 悬浮球尺寸小于该值（dp）时把窗口移出屏幕外 */
        const val MIN_VISIBLE_SIZE_DP = 5

        /** 移出屏幕时的横向偏移（px），足够远以保证任何 ROM 都不会把它拉回可见区域 */
        const val OFFSCREEN_OFFSET_PX = 500
        const val BALL_LONG_PRESS_MS = 500L

        /** 半透明深灰圆底，深浅主题下都能看清 */
        const val BALL_BACKGROUND = 0x99333333.toInt()

        /** 通知上的「关闭」按钮 */
        const val ACTION_DISABLE = "com.aistra.hail.action.DISABLE_FLOATING_BALL"
    }
}
