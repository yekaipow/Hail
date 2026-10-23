package com.aistra.hails.services

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
import com.aistra.hails.R
import com.aistra.hails.app.AppManager
import com.aistra.hails.app.HailData
import com.aistra.hails.app.UnfrozenStore
import com.aistra.hails.ui.main.MainActivity
import com.aistra.hails.utils.HShell
import com.aistra.hails.utils.HUI
import com.aistra.hails.utils.HShizuku
import com.aistra.hails.utils.HTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「划走最近任务即冻结」服务。
 *
 * 触发方式：定时轮询（Android 11 起系统不再发送 TASK_REMOVED 广播，广播监听仅作加速补充）。
 * 每次查询最近任务列表，与上一份快照比对，消失的任务对应的包名如果在「冻结」栏里
 * 且尚未冻结，就冻结它（未锁定的项冻结后自动移出列表）。
 * 任务列表通过 Shizuku / root 的 dumpsys 读取；只亮屏时轮询，灭屏即停。
 */
class RecentTaskFreezeService : Service() {
    private val channelId = javaClass.simpleName
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val handler = Handler(Looper.getMainLooper())
    private var snapshot: Set<String>? = null
    private var polling = false
    private var queryFailedNotified = false

    /** 轮询心跳：查一次任务列表并处理差集 */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            poll()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> startPolling()
                Intent.ACTION_SCREEN_OFF -> stopPolling()
            }
        }
    }

    private val taskRemovedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 部分 ROM 仍会发这个广播（Android 11 起 AOSP 已移除发送），收到就立刻查一次
            if (intent.action == ACTION_TASK_REMOVED) poll()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            taskRemovedReceiver,
            IntentFilter(ACTION_TASK_REMOVED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (HTarget.U) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        HUI.showToast(R.string.recent_task_freeze_debug_started)
        if (isInteractive()) startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPolling()
        scope.cancel()
        runCatching { unregisterReceiver(taskRemovedReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun isInteractive(): Boolean =
        (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive == true

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    /**
     * 查一次任务列表，与上一份快照比对：消失的任务若在「冻结」栏里且未冻结 → 冻结。
     * 首次只建立基线，避免启动瞬间把历史任务全部误判为"被划走"。
     */
    private fun poll() {
        scope.launch {
            // 「冻结」栏为空时没有可冻结对象，跳过查询省电
            if (withContext(Dispatchers.IO) { UnfrozenStore.packages().isEmpty() }) return@launch
            val running = withContext(Dispatchers.IO) { queryRunningPackages() }
            if (running == null) {
                if (!queryFailedNotified) {
                    queryFailedNotified = true
                    HUI.showToast(R.string.recent_task_freeze_debug_failed)
                }
                return@launch
            }
            queryFailedNotified = false
            val previous = snapshot
            snapshot = running
            if (previous == null) return@launch
            val disappeared = previous - running
            if (disappeared.isEmpty()) return@launch
            val targets = withContext(Dispatchers.IO) {
                disappeared.filter {
                    it != packageName && UnfrozenStore.contains(it) && !AppManager.isAppFrozen(it)
                }
            }
            if (targets.isEmpty()) return@launch
            withContext(Dispatchers.IO) {
                targets.forEach { AppManager.setAppFrozen(it, true) }
            }
            HUI.showToast(R.string.recent_task_freeze_debug_frozen, targets.joinToString(", "))
        }
    }

    /** 读取最近任务列表里的包名；无特权或执行失败返回 null */
    private fun queryRunningPackages(): Set<String>? {
        val command = "dumpsys activity recents"
        val result = when {
            HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.execute(command, false)
            HailData.workingMode.startsWith(HailData.SU) -> HShell.execute(command, true)
            else -> return null
        }
        if (result.first != 0) return null
        val output = result.second ?: return null
        // Android 10-：A=com.xxx；Android 11+：A=10086:com.xxx。另外兜底 baseIntent 里的 pkg=
        val fromAffinity = Regex("""A=(?:\d+:)?([\w.]+)""").findAll(output)
            .map { it.groupValues[1] }.toSet()
        val fromIntent = Regex("""pkg=([\w.]+)""").findAll(output)
            .map { it.groupValues[1] }.toSet()
        return fromAffinity + fromIntent
    }

    private fun buildNotification() = NotificationCompat.Builder(this, channelId)
        .setContentTitle(getString(R.string.recent_task_freeze))
        .setContentText(getString(R.string.recent_task_freeze_desc))
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
            .setName(getString(R.string.recent_task_freeze))
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private companion object {
        const val NOTIFICATION_ID = 103

        /** 系统隐藏广播：任务被移除（划走最近任务） */
        const val ACTION_TASK_REMOVED = "android.intent.action.TASK_REMOVED"

        /** 轮询间隔：2 秒，兼顾及时性与耗电 */
        const val POLL_INTERVAL_MS = 2000L
    }
}
