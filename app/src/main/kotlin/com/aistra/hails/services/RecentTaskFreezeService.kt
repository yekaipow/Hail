package com.aistra.hails.services

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
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
import com.aistra.hails.utils.HLog
import com.aistra.hails.utils.HShell
import com.aistra.hails.utils.HShizuku
import org.lsposed.hiddenapibypass.HiddenApiBypass
import com.aistra.hails.utils.HTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Proxy

/**
 * 「划走最近任务即冻结」服务。
 *
 * 触发方式二选一（设置里可切换）：
 * 1. 轮询：定时检查（2-10 秒）
 * 2. 任务栈监听：Shizuku 注册系统 TaskStackListener，任务被移除时实时回调
 *
 * 每次触发都查询「冻结」栏里未冻结的应用是否还在最近任务列表，不在的冻结。
 * 每次查询最近任务列表，与上一份快照比对，消失的任务对应的包名如果在「冻结」栏里
 * 且尚未冻结，就冻结它（未锁定的项冻结后自动移出列表）。
 * 任务列表通过 Shizuku / root 的 dumpsys 读取；只亮屏时轮询，灭屏即停。
 */
class RecentTaskFreezeService : Service() {
    private val channelId = javaClass.simpleName
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var taskStackBinder: Binder? = null

    /** 轮询心跳：查一次任务列表并处理差集 */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            poll()
            // 每轮重新读取设置，改间隔后无需重启服务即可生效
            handler.postDelayed(this, HailData.recentTaskFreezeInterval.toPollIntervalMs())
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

    override fun onCreate() {
        super.onCreate()
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
        if (isInteractive()) startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPolling()
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun Float.toPollIntervalMs(): Long =
        (this * 1000).toLong().coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)

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
     * 查一次：把「冻结」栏里尚未冻结、但已经不在最近任务列表的应用冻结。
     * （不依赖前后快照差集，任务被划走和自然结束都会被判定为"不在列表"）
     */
    private fun poll() {
        scope.launch {
            val unfrozen = withContext(Dispatchers.IO) {
                UnfrozenStore.packages().filter { it != packageName && !AppManager.isAppFrozen(it) }
            }
            // 没有可冻结对象时跳过查询，省电
            if (unfrozen.isEmpty()) return@launch
            val running = withContext(Dispatchers.IO) { queryRunningPackages() } ?: return@launch
            val targets = unfrozen.filter { it !in running }
            if (targets.isEmpty()) return@launch
            withContext(Dispatchers.IO) {
                targets.forEach { AppManager.setAppFrozen(it, true) }
            }
            HUI.showToast(R.string.recent_task_freeze_debug_frozen, targets.joinToString(", "))
        }
    }

    /**
     * 通过 Shizuku 注册系统 TaskStackListener。
     *
     * ITaskStackListener 是 hidden AIDL 接口，无法直接继承 Stub，这里用两步绕过：
     * 1. 用 [Proxy] 生成一个实现该接口的对象，拦截 asBinder() 返回自己的 Binder
     *    （注册端会调用 listener.asBinder() 取 binder，类型检查通过）
     * 2. 自定义 Binder.onTransact 接收系统回调：onTaskRemoved(taskId) 的 data 只剩一个 int
     */
    private fun registerTaskStackListener(): Boolean {
        if (!HailData.workingMode.startsWith(HailData.SHIZUKU)) return false
        return runCatching {
            val taskManager = HShizuku.getActivityTaskManager()
            val listenerClass = Class.forName("android.app.ITaskStackListener")
            val binder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    return try {
                        data.enforceInterface(TASK_STACK_LISTENER_DESCRIPTOR)
                        // onTaskRemoved(int taskId)：参数只有一个 int
                        if (data.dataAvail() == 4) {
                            data.readInt()
                            // Binder 线程没有 Looper，toast 必须回主线程
                            mainHandler.post {
                                HUI.showToast(R.string.recent_task_freeze_debug_callback)
                            }
                            // 只在亮屏时响应，避免灭屏期间系统清理导致误冻
                            if (isInteractive()) poll()
                        }
                        reply?.writeNoException()
                        true
                    } catch (_: Throwable) {
                        super.onTransact(code, data, reply, flags)
                    }
                }
            }
            val proxy = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, _ -> if (method.name == "asBinder") binder else null }
            HiddenApiBypass.invoke(
                taskManager.javaClass, taskManager, "registerTaskStackListener", proxy
            )
            taskStackBinder = binder
            true
        }.getOrElse {
            HLog.e(it)
            false
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

        /** ITaskStackListener 的 AIDL 描述符 */
        const val TASK_STACK_LISTENER_DESCRIPTOR = "android.app.ITaskStackListener"

        /** 轮询间隔下限 / 上限（毫秒），对应设置里的 2-10 秒 */
        const val MIN_POLL_INTERVAL_MS = 2000L
        const val MAX_POLL_INTERVAL_MS = 10000L
    }
}
