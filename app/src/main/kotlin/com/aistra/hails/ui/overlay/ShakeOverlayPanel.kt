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
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.drawable.toBitmap
import com.aistra.hails.R
import com.aistra.hails.app.AppManager
import com.aistra.hails.app.AppInfo
import com.aistra.hails.app.HailData
import com.aistra.hails.app.UnfrozenStore
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

/** 面板里一条应用记录，图标已预先解码成 Bitmap，避免反复加载 */
private data class OverlayApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val frozen: Boolean
)

/**
 * 摇一摇后弹出的悬浮面板（纯 View 实现）。
 *
 * 内容就是首页底部那块「冻结」区（fragment_home.xml 里的 frozen_list）：
 * 数据取自 UnfrozenStore，最多 10 个、四列网格；点击冻结项解冻并打开，点击未冻结项直接冻结。
 *
 * 注意：这里是自己构建格子 + 直接 loadIcon，没有复用 FrozenBarAdapter / AppIconCache /
 * item_frozen_bar。那套 UI 是给 Activity 用的，搬进 Service 悬浮窗会在运行时崩溃。
 *
 * 另外也没有用 Compose：Compose 在 WindowManager 里必须手动提供 ViewTreeLifecycleOwner，
 * 而本项目没有直接依赖 lifecycle-runtime，编译期拿不到这个类（CI 已因此失败过一次）。
 */
class ShakeOverlayPanel(
    context: Context,
    private val onClose: () -> Unit,
    private val onOpenApp: (String?) -> Unit,
    private val onDrag: (Float, Float) -> Unit
) : MaterialCardView(ContextThemeWrapper(context, R.style.Theme_Hail)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gridContainer = LinearLayout(context)
    private val titleView = TextView(context)
    private var apps: List<OverlayApp> = emptyList()

    /**
     * 内容高度变化后回调，参数是面板应有的精确像素高度。
     * 高度全部由固定尺寸部件算出，不依赖系统对窗口的自适应测量。
     */
    var onContentHeightChanged: ((Int) -> Unit)? = null

    private val colorOnSurface by lazy { color(com.google.android.material.R.attr.colorOnSurface) }
    private val colorOnSurfaceVariant by lazy { color(com.google.android.material.R.attr.colorOnSurfaceVariant) }
    // colorPrimary 是 AppCompat 定义的属性，Material 的 R 里没有
    private val colorPrimary by lazy { color(androidx.appcompat.R.attr.colorPrimary) }
    private val grayscale by lazy { ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }

    init {
        radius = dp(24).toFloat()
        cardElevation = dp(16).toFloat()
        setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        addView(buildContent(), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        reload()
    }

    /** 窗口被移除时调用，停止后台协程 */
    fun release() {
        scope.cancel()
    }

    // region 视图搭建

    private fun buildContent(): View {
        gridContainer.orientation = LinearLayout.VERTICAL
        gridContainer.setPadding(dp(6), 0, dp(6), 0)
        gridContainer.gravity = Gravity.CENTER
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildHeader(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(HEADER_DP)))
            addView(buildDivider())
            addView(gridContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(EMPTY_HEIGHT_DP)))
            addView(buildDivider())
            addView(buildFooter(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(FOOTER_DP)))
        }
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(4), 0)
        // 按住标题栏拖动窗口
        setOnTouchListener(DragListener())

        addView(ImageView(context).apply {
            setImageDrawable(context.applicationInfo.loadIcon(context.packageManager))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
        addView(titleView.apply {
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
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
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

    /** 网格里的一个应用格子：图标 + 单行名称 */
    private fun buildCell(app: OverlayApp) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(6), dp(4), dp(6))
        isClickable = true
        setOnClickListener { onCellClick(app) }

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
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        })
        addView(TextView(context).apply {
            text = app.label
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            setTextColor(if (app.frozen) colorOnSurfaceVariant else colorOnSurface)
        })
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
        gridContainer.removeAllViews()
        val rows = apps.chunked(COLUMNS)
        if (rows.isEmpty()) {
            gridContainer.addView(TextView(context).apply {
                text = context.getString(R.string.shake_overlay_empty)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(colorOnSurfaceVariant)
            })
        } else {
            /*
             * 每行固定 [COLUMNS] 个固定尺寸格子，不用 weight。
             * 之前用 weight=1 平分宽度，格子测量宽度先按 0 处理、布局阶段再分配，
             * 在 WindowManager 宿主里测量时序不可控，会出现所有格子挤在一行的问题。
             */
            val cell = dp(CELL_SIZE_DP)
            rows.forEach { rowApps ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                rowApps.forEach { app ->
                    row.addView(buildCell(app), LinearLayout.LayoutParams(cell, cell))
                }
                // 不足一行时用透明占位补齐，保证整行总宽恒定
                repeat(COLUMNS - rowApps.size) {
                    row.addView(View(context), LinearLayout.LayoutParams(cell, cell))
                }
                gridContainer.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, cell))
            }
        }
        titleView.text = if (apps.isEmpty()) context.getString(R.string.label_unfrozen)
        else "${context.getString(R.string.label_unfrozen)} (${apps.size})"

        /*
         * 窗口高度不交给系统自适应：悬浮窗首次挂载时网格还是空的，某些 ROM 上
         * 内容变高后窗口不会跟着长，后面的行全被窗口边界裁掉。
         * 这里所有部件都是固定高度，总高度直接算出来显式固化给窗口。
         */
        val gridHeight = if (rows.isEmpty()) dp(EMPTY_HEIGHT_DP) else rows.size * dp(CELL_SIZE_DP)
        (gridContainer.layoutParams as LinearLayout.LayoutParams).height = gridHeight
        gridContainer.requestLayout()
        val totalHeight = dp(HEADER_DP) + dp(FOOTER_DP) + gridHeight + 2 // + 两条 1px 分隔线
        post { onContentHeightChanged?.invoke(totalHeight) }
    }

    /**
     * 点击语义：冻结 → 解冻并打开；未冻结 → 直接冻结（不打开）。
     * 只有真正打开应用时才计入「最近打开」。
     */
    private fun onCellClick(app: OverlayApp) {
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
                onOpenApp(app.packageName)
            } else {
                val frozen = withContext(Dispatchers.IO) { AppManager.setAppFrozen(app.packageName, true) }
                if (frozen) reload() else notifyFailure()
            }
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
        /** 网格列数，10 个应用正好排成三行 */
        const val COLUMNS = 4

        /** 格子边长（dp），与首页网格的行高一致 */
        const val CELL_SIZE_DP = 76

        /** 标题栏 / 底部按钮栏的固定高度（dp） */
        const val HEADER_DP = 44
        const val FOOTER_DP = 44

        /** 列表为空时网格区的固定高度（dp） */
        const val EMPTY_HEIGHT_DP = 80

        /** 面板最多展示的应用数量 */
        const val MAX_ITEMS = 10

        /** 图标解码尺寸上限，过大只会浪费内存 */
        const val ICON_SIZE = 72

        /**
         * 读取首页「冻结」区的同一份数据：UnfrozenStore 里待处理的应用。
         * 卸载的记录顺手清理，按名称排序后最多取 [MAX_ITEMS] 个。
         */
        fun loadApps(context: Context): List<OverlayApp> {
            val packageManager = context.packageManager
            return UnfrozenStore.packages()
                .map { AppInfo(it) }
                .filter {
                    if (it.applicationInfo == null) UnfrozenStore.remove(it.packageName)
                    it.applicationInfo != null
                }
                .sortedWith(NameComparator)
                .take(MAX_ITEMS)
                .mapNotNull { info ->
                    val applicationInfo = info.applicationInfo ?: return@mapNotNull null
                    OverlayApp(
                        packageName = info.packageName,
                        label = applicationInfo.loadLabel(packageManager).toString(),
                        icon = runCatching {
                            applicationInfo.loadIcon(packageManager).toBitmap(ICON_SIZE, ICON_SIZE)
                        }.getOrNull(),
                        frozen = AppManager.isAppFrozen(info.packageName)
                    )
                }
        }
    }
}
