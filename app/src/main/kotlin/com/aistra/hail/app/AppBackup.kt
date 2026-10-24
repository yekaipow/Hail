package com.aistra.hail.app

import com.aistra.hail.utils.HPackages
import org.json.JSONArray
import org.json.JSONObject

/**
 * 首页应用列表的导入导出。
 *
 * 导出格式（v2，含分组）：
 * ```
 * {
 *   "version": 2,
 *   "tags": [{"id": 0, "name": "默认"}, {"id": 123, "name": "游戏"}],
 *   "apps": [{"package": "com.foo", "tags": [0, 123], "pinned": false, "whitelisted": true}]
 * }
 * ```
 * 同时兼容旧格式（`["pkg1","pkg2"]` 或单个包名字符串），旧格式导入时归入当前分组。
 */
object AppBackup {
    private const val VERSION = 2
    private const val KEY_VERSION = "version"
    private const val KEY_TAGS = "tags"
    private const val KEY_APPS = "apps"
    private const val KEY_TAG_ID = "id"
    private const val KEY_TAG_NAME = "name"
    private const val KEY_PACKAGE = "package"
    private const val KEY_APP_TAGS = "tags"
    private const val KEY_PINNED = "pinned"
    private const val KEY_WHITELISTED = "whitelisted"

    data class Result(val added: Int, val updated: Int, val tagsAdded: Int) {
        val changed get() = added + updated
    }

    /** 导出应用列表，附带所有分组定义 */
    fun export(list: List<AppInfo>): String = JSONObject().apply {
        put(KEY_VERSION, VERSION)
        put(KEY_TAGS, JSONArray().apply {
            HailData.tags.forEach { (name, id) ->
                put(JSONObject().put(KEY_TAG_ID, id).put(KEY_TAG_NAME, name))
            }
        })
        put(KEY_APPS, JSONArray().apply {
            list.forEach { info ->
                put(
                    JSONObject()
                        .put(KEY_PACKAGE, info.packageName)
                        .put(KEY_APP_TAGS, JSONArray(info.tagIdList))
                        .put(KEY_PINNED, info.pinned)
                        .put(KEY_WHITELISTED, info.whitelisted)
                )
            }
        })
    }.toString()

    /**
     * 导入并同步到本地配置：补齐缺失分组、合并已有应用的分组归属、更新置顶与白名单状态。
     * [defaultTagId] 用于旧格式或缺失分组时的兜底分组。
     */
    fun restore(text: String, defaultTagId: Int): Result {
        val trimmed = text.trim()
        return if (trimmed.startsWith("{")) restoreBackup(trimmed, defaultTagId)
        else restorePlain(trimmed, defaultTagId)
    }

    private fun restoreBackup(text: String, defaultTagId: Int): Result {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return Result(0, 0, 0)

        // 1) 同步分组定义
        var tagsAdded = 0
        root.optJSONArray(KEY_TAGS)?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString(KEY_TAG_NAME)
                if (name.isEmpty()) continue
                val id = obj.optInt(KEY_TAG_ID, name.hashCode())
                if (HailData.tags.none { it.first == name || it.second == id }) {
                    HailData.tags.add(name to id)
                    tagsAdded++
                }
            }
        }
        if (tagsAdded > 0) HailData.saveTags()

        // 2) 同步应用（分组归属、置顶、白名单）
        var added = 0
        var updated = 0
        root.optJSONArray(KEY_APPS)?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val pkg = obj.optString(KEY_PACKAGE)
                if (pkg.isEmpty() || HPackages.getApplicationInfoOrNull(pkg) == null) continue

                // 只接受本地存在的分组，避免出现「孤儿」归属导致应用不显示
                val imported = obj.optJSONArray(KEY_APP_TAGS)?.let { a ->
                    List(a.length()) { a.getInt(it) }
                } ?: emptyList()
                val validTags = imported.filter { id -> HailData.tags.any { it.second == id } }
                val tagIdList =
                    (if (validTags.isEmpty()) listOf(defaultTagId) else validTags).toMutableList()

                val exist = HailData.checkedList.find { it.packageName == pkg }
                if (exist == null) {
                    HailData.checkedList.add(
                        AppInfo(pkg, obj.optBoolean(KEY_PINNED), obj.optBoolean(KEY_WHITELISTED), tagIdList)
                    )
                    added++
                } else {
                    var changed = false
                    tagIdList.forEach { id ->
                        if (id !in exist.tagIdList) {
                            exist.tagIdList.add(id)
                            changed = true
                        }
                    }
                    val pinned = obj.optBoolean(KEY_PINNED)
                    if (exist.pinned != pinned) {
                        exist.pinned = pinned
                        changed = true
                    }
                    val whitelisted = obj.optBoolean(KEY_WHITELISTED)
                    if (exist.whitelisted != whitelisted) {
                        exist.whitelisted = whitelisted
                        changed = true
                    }
                    if (changed) updated++
                }
            }
        }
        if (added + updated > 0) HailData.saveApps()
        return Result(added, updated, tagsAdded)
    }

    /** 旧格式：纯包名数组或单个包名 */
    private fun restorePlain(text: String, defaultTagId: Int): Result {
        val json = if (text.contains('[')) runCatching {
            JSONArray(text.substring(text.indexOf('[')..text.lastIndexOf(']')))
        }.getOrNull() ?: return Result(0, 0, 0)
        else JSONArray().put(text)

        var added = 0
        for (index in 0 until json.length()) {
            val pkg = json.optString(index)
            if (pkg.isEmpty() || HPackages.getApplicationInfoOrNull(pkg) == null) continue
            if (!HailData.isChecked(pkg)) {
                HailData.addCheckedApp(pkg, defaultTagId, false)
                added++
            }
        }
        if (added > 0) HailData.saveApps()
        return Result(added, 0, 0)
    }
}
