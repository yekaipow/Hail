package com.aistra.hail.ui.overlay

import android.content.Context

/**
 * 全局唯一的面板宿主。
 *
 * 摇一摇服务和悬浮球服务都要弹出同一个面板，但服务可能因为开关连点、系统重建等原因
 * 出现启停交错、新旧实例短暂并存的情况。如果每个服务各自 new 一个 [ShakeOverlayHost]，
 * 就会出现多个窗口同时挂在屏幕上（表现为「一摇冒出好几个小窗口」）。
 *
 * 这里只保留一个宿主实例（用 Application context 创建，窗口归进程所有），
 * 两个服务都向它取用，物理上保证永远只有一个面板窗口。
 *
 * 采用引用计数管理生命周期：每次 [get] +1，每次 [release] -1，
 * 只有引用归零（两个服务都销毁）时才真正摘窗口。
 * 这比依据偏好开关判断更准确：偏好反映的是「用户想不想开」，而计数反映的是
 * 「服务此刻是否真的还在用面板」，能避免启停交错时面板被提前释放。
 */
object OverlayPanelManager {
    private var instance: ShakeOverlayHost? = null
    private var refs = 0

    /** 取（必要时创建）唯一宿主；每次取用 +1 引用 */
    fun get(context: Context): ShakeOverlayHost {
        refs++
        return instance ?: ShakeOverlayHost(context.applicationContext).also { instance = it }
    }

    /** 已存在则返回，不会创建 */
    fun peek(): ShakeOverlayHost? = instance

    /** 释放一个引用；引用归零（两个服务都销毁）时才彻底摘掉窗口 */
    fun release() {
        if (refs > 0) refs--
        if (refs == 0) {
            instance?.release()
            instance = null
        }
    }
}
