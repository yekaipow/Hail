package com.aistra.hails.app

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.aistra.hails.HailApp.Companion.app

/**
 * 最近打开的应用记录，供摇一摇悬浮窗做数据源。
 *
 * 只保留最近 [MAX_SIZE] 个，重复打开会置顶；用包名存储，不缓存名称和图标，
 * 卸载/重装后也能自动反映最新状态。
 */
object RecentApps {
    private const val MAX_SIZE = 20
    private const val KEY_RECENT_APPS = "recent_apps"
    private const val SEPARATOR = "\n"

    private val sp get() = PreferenceManager.getDefaultSharedPreferences(app)

    fun list(): List<String> = sp.getString(KEY_RECENT_APPS, null)
        ?.split(SEPARATOR)
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    /** 记录一次打开，置顶并裁剪到 [MAX_SIZE] */
    fun add(packageName: String) {
        val list = list().toMutableList()
        list.remove(packageName)
        list.add(0, packageName)
        sp.edit { putString(KEY_RECENT_APPS, list.take(MAX_SIZE).joinToString(SEPARATOR)) }
    }

    fun remove(packageName: String) {
        val list = list()
        if (packageName !in list) return
        sp.edit {
            putString(KEY_RECENT_APPS, list.filterNot { it == packageName }.joinToString(SEPARATOR))
        }
    }
}
