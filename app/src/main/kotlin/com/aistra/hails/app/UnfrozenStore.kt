package com.aistra.hails.app

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.aistra.hails.HailApp.Companion.app

/**
 * 记录「手动解冻并打开」过的应用，供首页底部「冻结」栏快速冻结。
 *
 * 写入：用户点击应用图标 / 快捷方式 / 通知动作触发解冻并启动时。
 * 移除：应用被冻结成功时（锁定的应用除外）。
 */
object UnfrozenStore {
    /** SharedPreferences 中的键，监听此键可实时刷新界面 */
    const val KEY = "unfrozen_packages"

    /** 锁定键：锁定后冻结不会把应用移出底部栏 */
    const val KEY_LOCKED = "unfrozen_packages_locked"

    private val sp by lazy { PreferenceManager.getDefaultSharedPreferences(app) }

    fun packages(): List<String> = readList(KEY)

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
        // 一并清掉锁定状态，避免残留
        setLocked(packageName, false)
    }

    fun lockedPackages(): List<String> = readList(KEY_LOCKED).ifEmpty { emptyList() }

    fun isLocked(packageName: String): Boolean = packageName in readList(KEY_LOCKED)

    @Synchronized
    fun setLocked(packageName: String, locked: Boolean) {
        val list = readList(KEY_LOCKED).toMutableList()
        if (locked) {
            if (packageName in list) return
            list.add(packageName)
        } else if (!list.remove(packageName)) return
        sp.edit { putString(KEY_LOCKED, list.joinToString("\n")) }
    }

    fun clear() = sp.edit {
        remove(KEY)
        remove(KEY_LOCKED)
    }

    private fun readList(key: String): List<String> =
        sp.getString(key, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun save(list: List<String>) = sp.edit { putString(KEY, list.joinToString("\n")) }
}
