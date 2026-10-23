package com.aistra.hails.ui.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import com.aistra.hails.HailApp.Companion.app
import com.aistra.hails.R
import com.aistra.hails.app.AppInfo
import com.aistra.hails.app.AppManager
import com.aistra.hails.app.HailData
import com.aistra.hails.app.HailData.tags
import com.aistra.hails.app.UnfrozenStore
import com.aistra.hails.databinding.FragmentHomeBinding
import com.aistra.hails.extensions.applyDefaultInsetter
import com.aistra.hails.extensions.isLandscape
import com.aistra.hails.extensions.isRtl
import com.aistra.hails.extensions.paddingRelative
import com.aistra.hails.ui.main.MainFragment
import com.aistra.hails.utils.HUI
import com.aistra.hails.utils.NameComparator
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator

@SuppressLint("ClickableViewAccessibility")
class HomeFragment : MainFragment() {
    var multiselect: Boolean = false
    val selectedList: MutableList<AppInfo> = mutableListOf()

    /** 底部「冻结」区是否展开；每次进入页面默认折叠 */
    private var frozenExpanded = false
    private var frozenTouchY = 0f
    private var _binding: FragmentHomeBinding? = null
    val binding get() = _binding!!
    private lateinit var frozenAdapter: FrozenBarAdapter

    /** 防止「解冻列表变化」回调整理未安装应用时递归触发 */
    private var updatingBar = false
    private val sp by lazy { PreferenceManager.getDefaultSharedPreferences(app) }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == UnfrozenStore.KEY || key == UnfrozenStore.KEY_LOCKED) updateFrozenBar()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        if (tags.size == 1) binding.tabs.isVisible = false
        binding.pager.adapter = HomeAdapter(this)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = tags[position].first
        }.attach()
        binding.tabs.applyDefaultInsetter { paddingRelative(isRtl, start = !activity.isLandscape, end = true) }

        // 底部常驻「冻结」区，多行排布
        frozenAdapter = FrozenBarAdapter(::onFrozenItemClick, ::toggleLock)
        binding.frozenList.apply {
            layoutManager = GridLayoutManager(activity, frozenSpanCount)
            adapter = frozenAdapter
            // 局部重绑（notifyItemChanged）的交叉淡入动画会表现为闪烁，关掉
            (itemAnimator as? DefaultItemAnimator)?.supportsChangeAnimations = false
        }
        // 标题栏手势：上滑展开 / 下拉折叠；列表滚动到顶时下拉也能折叠
        binding.frozenTitle.setOnTouchListener(frozenTitleTouchListener)
        binding.frozenList.setOnTouchListener(frozenListTouchListener)
        binding.frozenBar.applyDefaultInsetter {
            paddingRelative(isRtl, start = !isLandscape, end = true, bottom = true)
        }
        // 底部区高度变化时校正 FAB 位置，保证悬浮按钮不被遮挡
        binding.frozenBar.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (top != oldTop || bottom != oldBottom) adjustFab(v.isVisible)
        }
        sp.registerOnSharedPreferenceChangeListener(prefListener)
        updateFrozenBar()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        updateFrozenBar()
    }

    override fun onDestroyView() {
        multiselect = false
        selectedList.clear()
        sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        frozenAdapter.onDestroy()
        activity.fab.translationY = 0f
        super.onDestroyView()
        _binding = null
    }

    private val density get() = resources.displayMetrics.density

    /** 按屏宽计算每行图标数 */
    private val frozenSpanCount: Int
        get() = (resources.displayMetrics.widthPixels / (76 * density)).toInt().coerceAtLeast(4)

    private fun updateFrozenBar() {
        val binding = _binding ?: return
        if (updatingBar) return
        updatingBar = true
        try {
            val list = UnfrozenStore.packages().map { AppInfo(it) }.filter {
                // 应用已被卸载时顺手清理记录
                if (it.applicationInfo == null) UnfrozenStore.remove(it.packageName)
                it.applicationInfo != null
            }.sortedWith(NameComparator)
            val visible = list.isNotEmpty()
            updateFrozenTitle(binding, list.size)
            if (visible) applyFrozenListHeight(list.size)
            binding.frozenBar.isVisible = visible
            frozenAdapter.submitList(list)
        } finally {
            updatingBar = false
        }
    }

    /** 多行显示，最多 3 行，超出后在区域内滚动；折叠时高度为 0 */
    private fun applyFrozenListHeight(count: Int) {
        val rows = ((count + frozenSpanCount - 1) / frozenSpanCount).coerceAtMost(MAX_ROWS)
        binding.frozenList.layoutParams = binding.frozenList.layoutParams.apply {
            height = if (frozenExpanded) (rows * ITEM_HEIGHT_DP * density).toInt() else 0
        }
    }

    /** 标题文本：名称 + 数量 + 状态箭头（▴ 可上滑展开，▾ 可下拉收起） */
    private fun updateFrozenTitle(binding: FragmentHomeBinding, count: Int) {
        binding.frozenTitle.text = buildString {
            append(
                if (count > 0) "${getString(R.string.label_unfrozen)} ($count)"
                else getString(R.string.label_unfrozen)
            )
            append(if (frozenExpanded) " ▾" else " ▴")
        }
    }

    /** 展开 / 折叠底部列表，高度动画过渡；FAB 位置由 frozenBar 的布局监听自动校正 */
    private fun animateFrozenList(expand: Boolean) {
        val binding = _binding ?: return
        val count = frozenAdapter.itemCount
        if (frozenExpanded == expand || count == 0) return
        frozenExpanded = expand
        val rows = ((count + frozenSpanCount - 1) / frozenSpanCount).coerceAtMost(MAX_ROWS)
        val target = if (expand) (rows * ITEM_HEIGHT_DP * density).toInt() else 0
        val list = binding.frozenList
        ValueAnimator.ofInt(list.layoutParams.height, target).apply {
            duration = 180
            addUpdateListener {
                list.layoutParams = list.layoutParams.apply { height = it.animatedValue as Int }
            }
            start()
        }
        updateFrozenTitle(binding, count)
    }

    /** 标题栏：上滑展开，下拉折叠 */
    private val frozenTitleTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> frozenTouchY = event.rawY
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - frozenTouchY
                if (abs(dy) > SWIPE_DP * density) {
                    frozenTouchY = event.rawY
                    animateFrozenList(expand = dy < 0)
                }
            }
        }
        true
    }

    /** 列表：滚动到顶时向下拉折叠；其余手势交给列表正常滚动与点击 */
    private val frozenListTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> frozenTouchY = event.rawY
            MotionEvent.ACTION_MOVE -> {
                val binding = _binding ?: return@OnTouchListener false
                val dy = event.rawY - frozenTouchY
                if (dy > SWIPE_DP * density && !binding.frozenList.canScrollVertically(-1)) {
                    animateFrozenList(false)
                    return@OnTouchListener true
                }
            }
        }
        false
    }

    private fun adjustFab(barVisible: Boolean) {
        val binding = _binding ?: return
        val offset = if (barVisible) binding.frozenBar.height else 0
        // 只上移，不改变层级：FAB 本身位于内容层之上，始终不被底部区遮挡
        activity.fab.translationY = -offset.toFloat()
    }

    /**
     * 点击：已冻结的（锁定项）解冻并启动；未冻结的直接冻结。
     * 与悬浮窗一致：冻结/解冻的阻塞调用放在 IO 线程，成功后主动刷新列表。
     */
    private fun onFrozenItemClick(info: AppInfo) {
        viewLifecycleOwner.lifecycleScope.launch {
            val frozen = withContext(Dispatchers.IO) { AppManager.isAppFrozen(info.packageName) }
            if (frozen) {
                val unfrozen = withContext(Dispatchers.IO) { AppManager.setAppFrozen(info.packageName, false) }
                if (!unfrozen) {
                    HUI.showToast(R.string.permission_denied)
                    return@launch
                }
                UnfrozenStore.add(info.packageName)
                frozenAdapter.refreshItem(info.packageName)
                app.packageManager.getLaunchIntentForPackage(info.packageName)?.let {
                    startActivity(it)
                } ?: HUI.showToast(R.string.activity_not_found)
                return@launch
            }
            if (HailData.workingMode == HailData.MODE_DEFAULT) {
                MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.msg_guide)
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            // 冻结成功后：未锁定的由 AppManager 移出列表，锁定的保留
            val ok = withContext(Dispatchers.IO) { AppManager.setAppFrozen(info.packageName, true) }
            if (ok) {
                HUI.showToast(R.string.msg_freeze, info.name)
                // 只重绑这一格让图标变灰；未锁定项随后由 SP 监听整表更新移除
                frozenAdapter.refreshItem(info.packageName)
            } else {
                HUI.showToast(R.string.permission_denied)
            }
        }
    }

    /** 长按：锁定 / 解锁，锁定后冻结不再移出底部区 */
    private fun toggleLock(info: AppInfo) {
        val locked = UnfrozenStore.isLocked(info.packageName)
        UnfrozenStore.setLocked(info.packageName, !locked)
        HUI.showToast(if (locked) R.string.msg_unlocked else R.string.msg_locked, info.name)
    }

    private companion object {
        const val MAX_ROWS = 3
        const val ITEM_HEIGHT_DP = 72

        /** 触发展开 / 折叠的滑动距离 */
        const val SWIPE_DP = 48
    }
}
