package com.aistra.hails.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.drawable.toBitmap
import com.aistra.hails.BuildConfig
import com.aistra.hails.R
import com.aistra.hails.app.AppManager
import com.aistra.hails.app.HailData
import com.aistra.hails.app.UnfrozenStore
import com.aistra.hails.utils.HUI
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 面板里一条应用记录，图标已预先解码成 Bitmap，避免滚动时反复加载 */
private data class OverlayApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val frozen: Boolean,
    val pinned: Boolean
)

/**
 * 摇一摇后弹出的悬浮面板（纯 View 实现）。
 *
 * 这里刻意不用 Compose：Compose 在 WindowManager 里必须手动提供 ViewTreeLifecycleOwner，
 * 而本项目没有直接依赖 lifecycle-runtime，编译期拿不到这个类（CI 已因此失败过一次）。
 * 纯 View 只用到项目已有的 Material 组件，风险最低。
 */
class ShakeOverlayPanel(
    context: Context,
    private val onClose: () -> Unit,
    private val onOpenApp: (String?) -> Unit,
    private val onDrag: (Float, Float) -> Unit
) : MaterialCardView(ContextThemeWrapper(context, R.style.Theme_Hail)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listContainer = LinearLayout(context)
    private var apps: List<OverlayApp> = emptyList()

    private val colorOnSurface by lazy { color(com.google.android.material.R.attr.colorOnSurface) }
    private val colorOnSurfaceVariant by lazy { color(com.google.android.material.R.attr.colorOnSurfaceVariant) }
    private val colorPrimary by lazy { color(com.google.android.material.R.attr.colorPrimary) }
    private val grayscale by lazy { ColorMatrixColorFilter(ColorMatrix().apply { setToSaturation(0f) }) }

    init {
        radius = dp(24).toFloat()
        cardElevation = dp(16).toFloat()
        setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        addView(buildContent(), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        reload()
    }

    /** 窗口被移除时调用，停止后台协程 */
    fun release() {
        scope.cancel()
    }

    // region 视图搭建

    private fun buildContent(): View {
        listContainer.orientation = LinearLayout.VERTICAL
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildHeader(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(buildDivider())
            addView(
                ScrollView(context).apply {
                    addView(listContainer, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            )
            addView(buildDivider())
            addView(buildFooter(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(4), dp(8))
        // 按住标题栏拖动窗口
        setOnTouchListener(DragListener())

        addView(ImageView(context).apply {
            setImageDrawable(context.applicationInfo.loadIcon(context.packageManager))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
        addView(TextView(context).apply {
            text = context.getString(R.string.app_name)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorOnSurface)
            setPadding(dp(8), 0, 0, 0)
        })
        addView(View(context), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(textButton(context.getString(R.string.shake_open_app), 13f) { onOpenApp(null) })
        addView(textButton("✕", 16f) { onClose() })
    }

    private fun buildFooter(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(
            textButton(context.getString(R.string.action_freeze_all), 14f) { setAll(true) },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(
            textButton(context.getString(R.string.action_unfreeze_all), 14f) { setAll(false) },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    private fun buildDivider() = View(context).apply {
        setBackgroundColor(colorOnSurfaceVariant)
        alpha = 0.2f
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1)
    }

    private fun textButton(text: String, textSize: Float, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        this.textSize = textSize
        setTextColor(colorPrimary)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setOnClickListener { onClick() }
    }

    // endregion

    // region 数据

    private fun reload() {
        scope.launch {
            apps = withContext(Dispatchers.IO) { loadApps(context) }
            render()
        }
    }

    private fun render() {
        listContainer.removeAllViews()
        if (apps.isEmpty()) {
            listContainer.addView(TextView(context).apply {
                text = context.getString(R.string.shake_overlay_empty)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(colorOnSurfaceVariant)
                setPadding(dp(24), dp(32), dp(24), dp(32))
            })
            return
        }
        apps.forEach { app -> listContainer.addView(buildRow(app)) }
    }

    private fun buildRow(app: OverlayApp): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(4), dp(6))
        isClickable = true
        setOnClickListener { launchApp(app) }

        addView(ImageView(context).apply {
            val bitmap = app.icon
            if (bitmap != null) {
                setImageBitmap(bitmap)
                // 冻结应用显示为灰度，与首页一致
                if (app.frozen) colorFilter = grayscale
            } else {
                setImageResource(R.drawable.ic_round_frozen)
                setColorFilter(colorOnSurfaceVariant)
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        })

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
            addView(TextView(context).apply {
                text = app.label
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (app.frozen) colorOnSurfaceVariant else colorOnSurface)
            })
            addView(TextView(context).apply {
                text = context.getString(if (app.frozen) R.string.shake_state_frozen else R.string.shake_state_unfrozen)
                textSize = 11f
                setTextColor(if (app.frozen) colorPrimary else colorOnSurfaceVariant)
            })
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })

        addView(textButton(
            context.getString(if (app.frozen) R.string.action_unfreeze else R.string.action_freeze), 13f
        ) { toggle(app) })
    }

    private fun toggle(app: OverlayApp) {
        scope.launch {
            val frozen = !app.frozen
            val result = withContext(Dispatchers.IO) { AppManager.setAppFrozen(app.packageName, frozen) }
            if (result) reload() else notifyFailure()
        }
    }

    private fun setAll(frozen: Boolean) {
        scope.launch {
            val targets = apps.filter { it.frozen != frozen }.map { it.packageName }
            if (targets.isEmpty()) return@launch
            val changed = withContext(Dispatchers.IO) {
                targets.count { AppManager.setAppFrozen(it, frozen) }
            }
            if (changed == 0) notifyFailure() else reload()
        }
    }

    /** 冻结的应用没有可用的启动 Intent，先解冻再启动，并记入首页「冻结」栏 */
    private fun launchApp(app: OverlayApp) {
        scope.launch {
            if (app.frozen) {
                val unfrozen = withContext(Dispatchers.IO) {
                    AppManager.setAppFrozen(app.packageName, false).also {
                        if (it) UnfrozenStore.add(app.packageName)
                    }
                }
                if (!unfrozen) {
                    notifyFailure()
                    return@launch
                }
            }
            onOpenApp(app.packageName)
        }
    }

    /** 统一失败提示：未选工作模式时不要误报成「权限不足」 */
    private fun notifyFailure() {
        HUI.showToast(
            if (HailData.workingMode == HailData.MODE_DEFAULT) R.string.msg_guide else R.string.permission_denied
        )
    }

    // endregion

    private fun color(attrRes: Int): Int = MaterialColors.getColor(this, attrRes, Color.BLACK)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        /** 图标解码尺寸上限，过大只会浪费内存 */
        const val ICON_SIZE = 72

        fun loadApps(context: Context): List<OverlayApp> {
            val packageManager = context.packageManager
            return HailData.checkedList.mapNotNull { info ->
                if (info.packageName == BuildConfig.APPLICATION_ID) return@mapNotNull null
                val applicationInfo = info.applicationInfo ?: return@mapNotNull null
                val icon = runCatching {
                    applicationInfo.loadIcon(packageManager).toBitmap(ICON_SIZE, ICON_SIZE)
                }.getOrNull()
                OverlayApp(
                    packageName = info.packageName,
                    label = applicationInfo.loadLabel(packageManager).toString(),
                    icon = icon,
                    frozen = AppManager.isAppFrozen(info.packageName),
                    pinned = info.pinned
                )
            }.sortedWith(compareByDescending<OverlayApp> { it.pinned }.thenBy { it.label })
        }
    }
}
