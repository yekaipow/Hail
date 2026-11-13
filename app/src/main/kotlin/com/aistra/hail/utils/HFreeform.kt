package com.aistra.hail.utils

import android.content.Context
import com.aistra.hail.app.HailData
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 以自由窗口（FREEFORM）打开应用。
 *
 * 横屏下点开应用时，全屏应用会把悬浮窗和面板整个盖住；这里改用 freeform 小窗 +
 * 指定窗口矩形，让应用只占屏幕中间一块，悬浮窗还能露在外面。
 *
 * 只在 root / Shizuku 模式可用：`am start --windowingMode` 与 `am task resize`
 * 都要 shell 权限。所有方法都会阻塞，不要在主线程调用。
 */
object HFreeform {

    /** 窗口比例：对齐 1080×2400 上实测的 86 / 288 / 994 / 1968（宽 84%、高 70%、上边留 12%） */
    private const val WIDTH_RATIO = 0.84f
    private const val HEIGHT_RATIO = 0.70f
    private const val TOP_RATIO = 0.12f

    /** freeform 的两个全局开关只需要设置一次 */
    private val settingsApplied = AtomicBoolean(false)

    fun available(): Boolean = privileged

    /** 当前是否横屏。竖屏仍然全屏打开，只有横屏才需要小窗让位 */
    fun isLandscape(context: Context): Boolean {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels > metrics.heightPixels
    }

    /**
     * 打开应用并把窗口摆到屏幕中间。
     *
     * 应用已有任务时直接调到前台（不 force-stop，现场不丢），没有才以 FREEFORM 新建。
     *
     * @return false 表示整条链路失败，调用方应回落到普通 startActivity
     */
    fun open(context: Context, packageName: String): Boolean {
        if (!privileged) return false
        ensureSettings()

        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * WIDTH_RATIO).toInt()
        val height = (metrics.heightPixels * HEIGHT_RATIO).toInt()
        val left = (metrics.widthPixels - width) / 2
        val top = (metrics.heightPixels * TOP_RATIO).toInt()
        val rect = "$left $top ${left + width} ${top + height}"

        val existing = findTaskId(packageName)
        if (existing != null && shell("am task move-to-front $existing") != null) {
            // 已经在最近任务里：切到前台即可，窗口尺寸跟着当前屏幕重算
            resize(existing, rect)
            return true
        }

        val component = resolveComponent(packageName) ?: return false
        // 已有任务时 am start 也是复用它，不会重启进程
        if (shell("am start --windowingMode 5 -n $component") == null) return false
        // 新窗口刚建，taskId 要等一下才查得到；查不到也算打开成功（窗口已经是 freeform）
        repeat(6) {
            Thread.sleep(300)
            val taskId = findTaskId(packageName)
            if (taskId != null) {
                resize(taskId, rect)
                return true
            }
        }
        return true
    }

    /** 设置 freeform 两个全局开关；只在进程内第一次调用时执行 */
    private fun ensureSettings() {
        if (!settingsApplied.compareAndSet(false, true)) return
        shell("settings put global enable_freeform_support 1")
        shell("settings put global force_resizable_activities 1")
    }

    /**
     * 查应用所在任务的 taskId。
     *
     * `am stack list` 的输出格式各版本不一，逐级退回；每级先找「同一行同时含 taskId= 与包名」的，
     * 再退一步在 taskId 行之后十几行内找 `包名/Activity`。
     */
    private fun findTaskId(packageName: String): Int? {
        for (command in listOf("am stack list", "am task list", "dumpsys activity activities")) {
            val lines = shell(command)?.lines() ?: continue
            // 先按「包名/Activity」严格匹配，避免 com.x 把 com.x.y 也认成自己；没有再做宽松匹配
            lines.lastOrNull { it.contains("taskId=") && it.contains("$packageName/") }
                ?.let { line -> taskIdOf(line)?.let { return it } }
            lines.lastOrNull { it.contains("taskId=") && it.contains(packageName) }
                ?.let { line -> taskIdOf(line)?.let { return it } }
            lines.forEachIndexed { index, line ->
                if (!line.contains("taskId=")) return@forEachIndexed
                val end = (index + 12).coerceAtMost(lines.size)
                if (lines.subList(index, end).any { it.contains("$packageName/") }) {
                    taskIdOf(line)?.let { return it }
                }
            }
        }
        return null
    }

    private fun taskIdOf(line: String): Int? =
        Regex("taskId=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()

    /** 解析启动 Activity 的组件名，形如 com.x/.MainActivity */
    private fun resolveComponent(packageName: String): String? =
        shell("cmd package resolve-activity --brief $packageName")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.contains('/') && !it.contains(' ') }

    private fun resize(taskId: Int, rect: String) {
        shell("am task resize $taskId $rect")
    }

    private val privileged: Boolean
        get() = HailData.workingMode.startsWith(HailData.SHIZUKU) ||
            HailData.workingMode.startsWith(HailData.SU)

    private fun shell(command: String): String? {
        val result = when {
            HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.execute(command, false)
            HailData.workingMode.startsWith(HailData.SU) -> HShell.execute(command, true)
            else -> return null
        }
        return if (result.first == 0) result.second else null
    }
}
