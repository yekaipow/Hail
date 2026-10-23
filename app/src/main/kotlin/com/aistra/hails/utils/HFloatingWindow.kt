package com.aistra.hails.utils

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import com.aistra.hails.app.AppManager
import com.aistra.hails.app.HailData
import com.aistra.hails.ui.main.MainActivity

/**
 * 以小窗（自由窗口）方式打开 Hail 主界面。
 *
 * 系统限制：第三方应用无法强制进入「小窗」，只有设备支持自由窗口（freeform）时才成立。
 * 因此这里分三层降级：
 * 1. 有 Shizuku / root：直接 `am start --windowingMode 5`，成功率最高
 * 2. 设备声明支持 freeform：用 ActivityOptions 指定窗口模式与尺寸
 * 3. 都不行：普通全屏启动，返回 false 让调用方提示用户
 */
object HFloatingWindow {
    /** WindowConfiguration.WINDOWING_MODE_FREEFORM，值自 Android 8 起稳定 */
    private const val WINDOWING_MODE_FREEFORM = 5

    private const val WIDTH_RATIO = 0.86f
    private const val HEIGHT_RATIO = 0.68f
    private const val TOP_RATIO = 0.12f

    /** @return true 表示以小窗方式打开；false 表示退化为全屏 */
    suspend fun launch(context: Context): Boolean {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val bounds = bounds(context)
        if (launchWithShell(bounds, context.packageName)) return true
        if (launchWithOptions(context, intent, bounds)) return true
        // 兜底：至少把主界面打开
        runCatching { context.startActivity(intent) }
        return false
    }

    /** 通过 Shizuku / root 的 shell 执行 am 命令，最可靠 */
    private suspend fun launchWithShell(bounds: Rect, packageName: String): Boolean {
        if (!HTarget.Q) return false
        val workingMode = HailData.workingMode
        val shellAvailable = workingMode.startsWith(HailData.SHIZUKU) ||
            workingMode.startsWith(HailData.SU) ||
            workingMode.startsWith(HailData.JAILBREAK)
        if (!shellAvailable) return false

        val component = "$packageName/${MainActivity::class.java.name}"
        val command = "am start --windowingMode $WINDOWING_MODE_FREEFORM" +
            " --bounds ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}" +
            " -n $component"
        val (code, output) = AppManager.execute(command)
        return code == 0 && output?.contains("Error", ignoreCase = true) != true
    }

    /** 设备声明支持自由窗口时，用标准 API 指定窗口模式与尺寸 */
    private fun launchWithOptions(context: Context, intent: Intent, bounds: Rect): Boolean {
        if (!HTarget.Q || !supportsFreeform(context)) return false
        return runCatching {
            val options = ActivityOptions.makeBasic().apply {
                setLaunchBounds(bounds)
                // API 30 起是公开 API，更早版本为 SystemApi，用反射避免版本判断
                runCatching {
                    javaClass.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType!!)
                        .invoke(this, WINDOWING_MODE_FREEFORM)
                }
            }
            context.startActivity(intent, options.toBundle())
        }.isSuccess
    }

    private fun supportsFreeform(context: Context): Boolean = runCatching {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
    }.getOrDefault(false)

    private fun bounds(context: Context): Rect {
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * WIDTH_RATIO).toInt()
        val height = (metrics.heightPixels * HEIGHT_RATIO).toInt()
        val left = (metrics.widthPixels - width) / 2
        val top = (metrics.heightPixels * TOP_RATIO).toInt()
        return Rect(left, top, left + width, top + height)
    }
}
