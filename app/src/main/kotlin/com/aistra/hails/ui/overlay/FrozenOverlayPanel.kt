package com.aistra.hails.ui.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hails.R
import com.aistra.hails.app.AppInfo
import com.aistra.hails.app.AppManager
import com.aistra.hails.app.HailData
import com.aistra.hails.app.UnfrozenStore
import com.aistra.hails.ui.home.FrozenBarAdapter
import com.aistra.hails.utils.HUI
import com.aistra.hails.utils.NameComparator
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 摇一摇弹出的悬浮窗，内容就是首页底部那块「冻结」区（fragment_home.xml 里的 frozen_list）：
 * 标题是待处理应用数量，下面是同一套 FrozenBarAdapter 网格，点击/长按行为与首页保持一致。
 *
 * 这里刻意不用 Compose：Compose 在 WindowManager 里必须手动提供 ViewTreeLifecycleOwner，
 * 而本项目没有直接依赖 lifecycle-runtime，编译期拿不到这个类。
 */
class FrozenOverlayPanel(
    context: Context,
    private val onClose: () -> Unit,
    private val onDrag: (Float, Float) -> Unit
) : MaterialCardView(ContextThemeWrapper(context, R.style.Theme_Hail)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val titleView = TextView(context)
    private val emptyView = TextView(context)
    private val listView = RecyclerView(context)
    private val adapter = FrozenBarAdapter(::onItemClick, ::onItemLongClick)
    private val spanCount = computeSpanCount()

    /** 内容高度变化后通知宿主重新测量窗口（窗口高度是 WRAP_CONTENT） */
    var onSizeChanged: (() -> Unit)? = null

    private val colorOnSurface by lazy { color(com.google.android.material.R.attr.colorOnSurface) }
    private val colorOnSurfaceVariant by lazy { color(com.google.android.material.R.attr.colorOnSurfaceVariant) }
    // colorPrimary 是 AppCompat 定义的属性，Material 的 R 里没有
    private val colorPrimary by lazy { color(androidx.appcompat.R.attr.colorPrimary) }

    init {
        radius = dp(24).toFloat()
        cardElevation = dp(16).toFloat()
        setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        addView(buildContent(), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refresh()
    }

    /** 窗口被移除时调用，停止后台协程并释放图标任务 */
    fun release() {
        scope.cancel()
        adapter.onDestroy()
    }

    // region 视图搭建

    private fun buildContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(buildHeader(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(listView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0))
        addView(emptyView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        emptyView.apply {
            text = context.getString(R.string.shake_overlay_empty)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(colorOnSurfaceVariant)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            isVisible = false
        }
        listView.apply {
            layoutManager = GridLayoutManager(context, spanCount)
            adapter = this@FrozenOverlayPanel.adapter
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), dp(8))
        }
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(4), dp(4))
        // 按住标题栏拖动窗口
        setOnTouchListener(DragListener())

        addView(titleView.apply {
            textSize = 14f
            setTextColor(colorPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(colorPrimary)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClose() }
        })
    }

    // endregion

    // region 数据

    private fun refresh() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { loadList() }
            adapter.submitList(list)
            val visible = list.isNotEmpty()
            titleView.text = if (visible) {
                "${context.getString(R.string.label_unfrozen)} (${list.size})"
            } else {
                context.getString(R.string.label_unfrozen)
            }
            emptyView.isVisible = !visible
            listView.isVisible = visible
            applyListHeight(list.size)
            // 内容高度变了，等这一帧布局完成再让宿主重新测量窗口
            post { onSizeChanged?.invoke() }
        }
    }

    /** 与首页一致：最多显示三行，超出后列表内部滚动 */
    private fun applyListHeight(count: Int) {
        val rows = if (count == 0) 0 else ((count + spanCount - 1) / spanCount).coerceAtMost(MAX_ROWS)
        listView.layoutParams = (listView.layoutParams as LinearLayout.LayoutParams).apply {
            height = rows * dp(ITEM_HEIGHT_DP)
        }
    }

    private fun loadList(): List<AppInfo> = UnfrozenStore.packages().map { AppInfo(it) }.filter {
        // 应用已被卸载时顺手清理记录
        if (it.applicationInfo == null) UnfrozenStore.remove(it.packageName)
        it.applicationInfo != null
    }.sortedWith(NameComparator)

    /**
     * 点击：未冻结的直接冻结；已冻结的（锁定项）解冻并启动。与首页「冻结」区行为一致。
     */
    private fun onItemClick(info: AppInfo) {
        scope.launch {
            val frozen = withContext(Dispatchers.IO) { AppManager.isAppFrozen(info.packageName) }
            if (frozen) {
                val unfrozen = withContext(Dispatchers.IO) {
                    AppManager.setAppFrozen(info.packageName, false).also {
                        if (it) UnfrozenStore.add(info.packageName)
                    }
                }
                if (unfrozen) launchApp(info.packageName) else HUI.showToast(R.string.permission_denied)
            } else {
                if (HailData.workingMode == HailData.MODE_DEFAULT) {
                    HUI.showToast(R.string.msg_guide)
                    return@launch
                }
                // 冻结成功后：未锁定的由 AppManager 移出列表，锁定的保留
                val frozenNow = withContext(Dispatchers.IO) { AppManager.setAppFrozen(info.packageName, true) }
                if (frozenNow) HUI.showToast(R.string.msg_freeze, info.name)
                else HUI.showToast(R.string.permission_denied)
            }
            refresh()
        }
    }

    /** 长按：锁定 / 解锁，锁定后冻结不再移出列表 */
    private fun onItemLongClick(info: AppInfo) {
        val locked = UnfrozenStore.isLocked(info.packageName)
        UnfrozenStore.setLocked(info.packageName, !locked)
        HUI.showToast(if (locked) R.string.msg_unlocked else R.string.msg_locked, info.name)
        refresh()
    }

    private fun launchApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            HUI.showToast(R.string.activity_not_found)
            return
        }
        runCatching { context.startActivity(intent) }
            .onFailure { HUI.showToast(R.string.activity_not_found) }
    }

    // endregion

    private fun color(attrRes: Int): Int = MaterialColors.getColor(this, attrRes, Color.BLACK)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 按可用宽度算网格列数，与首页底部区的公式保持一致 */
    private fun computeSpanCount(): Int =
        ((resources.displayMetrics.widthPixels - dp(32)) / (76 * resources.displayMetrics.density))
            .toInt().coerceAtLeast(4)

    /** 按住标题栏拖动整个窗口 */
    private inner class DragListener : OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    onDrag(event.rawX - lastX, event.rawY - lastY)
                    lastX = event.rawX
                    lastY = event.rawY
                    return true
                }
            }
            return false
        }
    }

    private companion object {
        const val MAX_ROWS = 3
        const val ITEM_HEIGHT_DP = 72
    }
}
