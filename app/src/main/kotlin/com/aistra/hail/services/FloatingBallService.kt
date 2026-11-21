package com.aistra.hail.services

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import android.widget.FrameLayout
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
 * 贴边悬浮条服务：独立于摇一摇，只负责屏幕右边缘那条细线的创建与手势。
 *
 * 形态是一条贴在屏幕右边缘的细线（长度由设置里的「悬浮球大小」控制），
 * 纵向拖动挪位置；向内侧滑（向左）或点击开关自己的 [ShakeOverlayHost] 面板；
 * 长按执行设置所选的动作（全部冻结 / 锁屏）。
 * 打开面板前会把悬浮条重新挂到 WindowManager，抢占 overlay 层最上面。
 */
class FloatingBallService : Service() {
    private val channelId = javaClass.simpleName
    /** 全局唯一面板宿主：与摇一摇共用，杜绝多窗口 */
    private lateinit var panelHost: ShakeOverlayHost
    private var floatingBall: View? = null

    /** 悬浮条当前的窗口参数（bringBallToFront 重新挂载时要用） */
    private var ballParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rebuildBallRunnable: Runnable? = null
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 灭屏时收起面板；悬浮球本体保留（锁屏界面上本就不显示 overlay 窗口）
            if (intent.action == Intent.ACTION_SCREEN_OFF) panelHost.hide()
        }
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            // 大小改变后重建悬浮球（防抖合并连续变化，拖动滑杆不会反复拆建）
            HailData.FLOATING_BALL_SIZE -> {
                rebuildBallRunnable?.let { mainHandler.removeCallbacks(it) }
                rebuildBallRunnable = Runnable { rebuildFloatingBall() }
                    .also { mainHandler.postDelayed(it, REBUILD_DEBOUNCE_MS) }
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

    /**
     * 横竖屏切换、分屏、折叠屏展开都会走到这里：屏幕尺寸变了，
     * 记忆坐标就可能越界，重建一次让 [buildBallParams] 重新夹回屏内。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (HailData.shakeFloatingBall && floatingBall != null) {
            mainHandler.post { runCatching { rebuildFloatingBall() } }
        }
    }

    override fun onDestroy() {
        // 清理未决的防抖重建，避免服务销毁后回调仍触发
        rebuildBallRunnable?.let { mainHandler.removeCallbacks(it) }
        // 窗口归进程所有：服务停了进程还在时，没摘掉的窗口会一直留在屏幕上，所以第一件事就摘
        runCatching { detachFloatingBall() }
        runCatching { panelHost.hide() }
        // 释放面板引用：引用计数归零（两个服务都销毁）时才真正摘窗口
        runCatching { OverlayPanelManager.release() }
        runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun attachFloatingBall() {
        if (!Settings.canDrawOverlays(this)) return
        val density = resources.displayMetrics.density
        val metrics = resources.displayMetrics
        val barWidth = (EDGE_BAR_WIDTH_DP * density).toInt().coerceAtLeast(2)
        val barLength = (HailData.floatingBallSize * density).toInt().coerceAtLeast(1)
        // 触摸热区比视觉细条宽得多：4dp 宽的线手指根本摁不准，
        // 所以窗口做宽一点，里面只有贴着边缘的那 [EDGE_BAR_WIDTH_DP] 是可见的
        val touchWidth = (TOUCH_WIDTH_DP * density).toInt().coerceAtLeast(barWidth)
        // 长度小于阈值：窗口推到屏幕外；必须配 FLAG_LAYOUT_NO_LIMITS，否则系统会把越界坐标限制回屏内
        val offscreen = HailData.floatingBallSize < MIN_VISIBLE_SIZE_DP
        val params = buildBallParams(touchWidth, barLength, metrics, offscreen)
        ballParams = params
        // 已有悬浮条（上次拆建失败等残留）则复用更新，绝不再 add 一个新窗口，避免多条叠加
        val existing = floatingBall
        if (existing != null) {
            if (existing.parent != null) runCatching { windowManager.updateViewLayout(existing, params) }
            else runCatching { windowManager.addView(existing, params) }
            return
        }
        val ball = FrameLayout(this).apply {
            addView(
                View(this@FloatingBallService).apply {
                    background = GradientDrawable().apply {
                        // 细长圆角条：圆角取半宽，两端收成半圆，看起来就是贴在边上的一条线
                        cornerRadius = barWidth / 2f
                        setColor(BALL_BACKGROUND)
                    }
                },
                FrameLayout.LayoutParams(barWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.RIGHT)
            )
        }
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        var swiping = false
        var longPressed = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val swipeThreshold = (SWIPE_OPEN_DP * density).toInt().coerceAtLeast(touchSlop)
        val longPressRunnable = Runnable {
            if (!dragging && !swiping) {
                longPressed = true
                onFloatingBallLongPress()
            }
        }
        ball.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startY = params.y
                    dragging = false
                    swiping = false
                    longPressed = false
                    mainHandler.postDelayed(longPressRunnable, BALL_LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && !swiping && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        // 以竖直为主 → 拖动挪位置；以水平为主 → 侧滑（不跟手，松手后判定）
                        if (abs(dy) >= abs(dx)) dragging = true else swiping = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(ball, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        // 松手后夹回屏内并记住位置；横向始终贴边，只记忆纵向
                        val maxY = (metrics.heightPixels - params.height).coerceAtLeast(0)
                        params.y = params.y.coerceIn(0, maxY)
                        runCatching { windowManager.updateViewLayout(ball, params) }
                        HailData.shakeFloatingBallY = params.y
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP && !longPressed) {
                        when {
                            // 贴在右边缘，向内侧滑就是向左
                            swiping && event.rawX - downRawX <= -swipeThreshold -> openPanel()
                            dragging -> Unit
                            else -> togglePanel()
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

    /** 重建悬浮条：始终先拆后建，隐藏状态下改回可见长度也要重新出现 */
    private fun rebuildFloatingBall() {
        detachFloatingBall()
        attachFloatingBall()
    }

    /**
     * 计算悬浮条窗口参数：横向固定贴右边缘（x = 屏宽 - 条宽），纵向沿用记忆位置。
     */
    private fun buildBallParams(
        width: Int,
        height: Int,
        metrics: android.util.DisplayMetrics,
        offscreen: Boolean
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width,
        height,
        if (HTarget.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        if (offscreen) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (offscreen) {
            // 隐藏态：两个方向都推到屏幕外的负坐标
            x = -OFFSCREEN_OFFSET_PX
            y = -OFFSCREEN_OFFSET_PX
        } else {
            // 贴边不留边距，视觉上就是贴着屏幕右边缘的一条线
            x = metrics.widthPixels - width
            val maxY = (metrics.heightPixels - height).coerceAtLeast(0)
            y = if (HailData.shakeFloatingBallY != Int.MIN_VALUE) {
                HailData.shakeFloatingBallY.coerceIn(0, maxY)
            } else {
                (metrics.heightPixels - height) / 3
            }
        }
    }

    /** 点击悬浮条：面板开关 */
    private fun togglePanel() {
        if (panelHost.isShowing) panelHost.hide() else openPanel()
    }

    /**
     * 打开面板，并把悬浮条提到 overlay 层最上面。
     *
     * 同一应用的 overlay 窗口按添加顺序叠放，重新挂载一次就排到最上层，面板随后添加又在它之上。
     * 注意：这只能压过同类 overlay 窗口，无法越过系统窗口（状态栏、锁屏）或系统签名的更高层窗口。
     */
    private fun openPanel() {
        bringBallToFront()
        panelHost.show()
    }

    /** 把悬浮条重新挂到 WindowManager（remove + add），抢占 overlay 层的最上层 */
    private fun bringBallToFront() {
        val ball = floatingBall ?: return
        val params = ballParams ?: return
        runCatching {
            if (ball.parent != null) windowManager.removeViewImmediate(ball)
            windowManager.addView(ball, params)
        }
    }

    private fun detachFloatingBall() {
        val ball = floatingBall ?: return
        // 先摘窗口再清字段：只有确认窗口已不在屏上（摘除成功或本就不在）才置 null。
        // 若摘除抛异常且窗口确实还在，则保留 floatingBall 引用，让下一次 detach 继续重试，
        // 避免「字段已空、窗口残留」导致关闭后悬浮球永远销毁不掉。
        runCatching {
            if (ball.parent != null) windowManager.removeViewImmediate(ball)
        }
        if (ball.parent == null) {
            floatingBall = null
            ballParams = null
        }
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

        /** 悬浮条可见部分的宽度（dp）：一条贴边的细线，不再显示圆球 */
        const val EDGE_BAR_WIDTH_DP = 4

        /** 触摸热区宽度（dp）：窗口比可见细条宽，保证手指能摁住 */
        const val TOUCH_WIDTH_DP = 24

        /** 侧滑打开面板的水平距离阈值（dp），从右边缘向内（向左）滑 */
        const val SWIPE_OPEN_DP = 24

        /** 悬浮条长度小于该值（dp）时把窗口移出屏幕外 */
        const val MIN_VISIBLE_SIZE_DP = 5

        /** 移出屏幕时的偏移（px），x/y 同时取负值，足够远以保证任何 ROM 都不会把它拉回可见区域 */
        const val OFFSCREEN_OFFSET_PX = 500
        const val BALL_LONG_PRESS_MS = 500L

        /** 尺寸变化重建的防抖窗口（ms），合并滑杆拖动期间的连续变化 */
        const val REBUILD_DEBOUNCE_MS = 300L

        /** 半透明深灰底色：贴边细条用，深浅主题下都能看清 */
        const val BALL_BACKGROUND = 0x99333333.toInt()

        /** 通知上的「关闭」按钮 */
        const val ACTION_DISABLE = "com.aistra.hail.action.DISABLE_FLOATING_BALL"
    }
}
