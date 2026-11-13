package com.aistra.hail.utils

import com.aistra.hail.app.HailData

/**
 * 进程存活查询。
 *
 * Android 5+ 的 ActivityManager.getRunningAppProcesses() 只返回本进程，
 * 想知道别的应用是否真的在跑，只能走特权 shell（root / Shizuku）。
 */
object HProcess {

    private val spaces = Regex("\\s+")

    /**
     * 当前运行中的包名集合（进程名取 "包名" 或 "包名:子进程" 的前缀）。
     *
     * 无特权、或 ps 输出解析不出任何包名时返回 null ——
     * 调用方据此隐藏「正在运行」区，而不是把它当成「一个都没在跑」。
     */
    fun runningPackages(): Set<String>? {
        if (!privileged) return null
        // toybox 的 ps 支持 -o NAME，输出只有一列，最容易解析；
        // 老系统的 toolbox 不认这些参数，逐级退回
        val output = run("ps -A -o NAME")?.takeIf { it.isNotBlank() }
            ?: run("ps -A")?.takeIf { it.isNotBlank() }
            ?: run("ps")
            ?: return null
        val packages = output.lineSequence()
            .mapNotNull { line ->
                val name = line.trim().split(spaces).lastOrNull()?.trim()
                // 内核线程写成 [kworker/0:1] 这种，不是包名形态，跳过
                name?.takeIf { it.isNotEmpty() && !it.startsWith("[") }?.substringBefore(':')
            }
            // 包名一定带点（至少 com.xxx）；表头 NAME、USER 列等不含点，一并滤掉
            .filterTo(mutableSetOf()) { it.contains('.') }
        return packages.takeIf { it.isNotEmpty() }
    }

    private val privileged: Boolean
        get() = HailData.workingMode.startsWith(HailData.SHIZUKU) ||
            HailData.workingMode.startsWith(HailData.SU)

    /** 执行特权命令，失败返回 null */
    private fun run(command: String): String? {
        val result = when {
            HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.execute(command, false)
            HailData.workingMode.startsWith(HailData.SU) -> HShell.execute(command, true)
            else -> return null
        }
        return if (result.first == 0) result.second else null
    }
}
