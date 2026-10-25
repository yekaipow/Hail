package com.aistra.hail.ui.overlay

/**
 * 面板宿主仲裁。
 *
 * 摇一摇服务和悬浮球服务各自持有一个 [ShakeOverlayHost]，但屏幕上只应该有一个面板：
 * 这里登记所有存活宿主，任何一方要显示前先把另一方收起，避免两层面板叠在一起。
 */
object ShakeOverlayHosts {
    private val hosts = mutableListOf<ShakeOverlayHost>()

    fun register(host: ShakeOverlayHost) {
        if (!hosts.contains(host)) hosts.add(host)
    }

    fun unregister(host: ShakeOverlayHost) {
        hosts.remove(host)
    }

    /** 任意宿主的面板正在显示 */
    val anyShowing: Boolean get() = hosts.any { it.isShowing }

    fun hideAll() {
        hosts.toList().forEach { runCatching { it.hide() } }
    }

    /** 显示自己的面板前，先收起其它宿主的面板 */
    fun hideOthers(target: ShakeOverlayHost) {
        hosts.toList().filter { it !== target }.forEach { runCatching { it.hide() } }
    }
}
