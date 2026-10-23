package com.aistra.hails.utils

import com.aistra.hails.app.AppManager
import com.aistra.hails.app.HailData
import com.aistra.hails.app.UnfrozenStore

/** 悬浮球长按、悬浮窗底栏等共享的批量动作 */
object HActions {
    /** 冻结「冻结」栏里所有未冻结的应用，返回成功数量 */
    fun freezeAll(): Int = UnfrozenStore.packages()
        .count { !AppManager.isAppFrozen(it) && AppManager.setAppFrozen(it, true) }

    /** 锁屏：设备管理员 lockNow 优先，root / Shizuku 下发电源键；都不可用时返回 false */
    fun lockScreen(): Boolean = when {
        HPolicy.isAdminActive -> HPolicy.lockScreen
        HailData.workingMode.startsWith(HailData.SU) -> HShell.lockScreen
        HailData.workingMode.startsWith(HailData.SHIZUKU) ->
            HShizuku.execute("input keyevent KEYCODE_POWER").first == 0
        else -> false
    }
}
