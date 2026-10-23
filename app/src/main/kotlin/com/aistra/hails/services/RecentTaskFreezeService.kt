package com.aistra.hails.services

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
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
 * 监听系统隐藏广播 TASK_REMOVED 作为触发时机：收到后查询「冻结」栏里尚未冻结的应用
 * 是否还有任务在最近任务列表，没有则冻结（未锁定的项冻结后自动移出列表）。
 * 任务列表通过 Shizuku / root 的 dumpsys 读取。
 */
class RecentTaskFreezeService : Service() {
    private val channelId = javaClass.simpleName
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val taskRemovedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TASK_REMOVED) onTaskRemoved()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (HTarget.U) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(taskRemovedReceiver) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** 触发点：某个任务被划走，检查列表里未冻结的应用还有没有任务在 */
    private fun onTaskRemoved() {
        scope.launch {
            val running = withContext(Dispatchers.IO) { queryRunningPackages() } ?: return@launch
            val targets = withContext(Dispatchers.IO) {
                UnfrozenStore.packages().filter {
                    it != packageName && !AppManager.isAppFrozen(it) && it !in running
                }
            }
            if (targets.isEmpty()) return@launch
            withContext(Dispatchers.IO) {
                targets.forEach { AppManager.setAppFrozen(it, true) }
            }
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
    }
}
