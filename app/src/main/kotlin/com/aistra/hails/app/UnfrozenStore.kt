package com.aistra.hails.app

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.aistra.hails.HailApp.Companion.app

/**
 * 记录「手动解冻并打开」过的应用，供首页「冻结」页快速冻结。
 *
 * 写入：用户点击应用图标 / 快捷方式 / 通知动作触发解冻并启动时。
 * 移除：应用被冻结成功时（无论来自哪条路径）。
 */
object UnfrozenStore {
    private const val KEY = "unfrozen_packages"

    private val sp by lazy { PreferenceManager.getDefaultSharedPreferences(app) }

    fun packages(): List<String> =
        sp.getString(KEY, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    fun contains(packageName: String): Boolean = packageName in packages()

    @Synchronized
    fun add(packageName: String) {
        val list = packages().toMutableList()
        list.remove(packageName)
        list.add(packageName)
        save(list)
    }

    @Synchronized
    fun remove(packageName: String) {
        val list = packages().toMutableList()
        if (list.remove(packageName)) save(list)
    }

    fun clear() = sp.edit { remove(KEY) }

    private fun save(list: List<String>) = sp.edit { putString(KEY, list.joinToString("\n")) }
}
