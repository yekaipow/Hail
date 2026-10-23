package com.aistra.hails.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.aistra.hails.HailApp.Companion.app
import com.aistra.hails.R
import com.aistra.hails.app.AppInfo
import com.aistra.hails.app.AppManager
import com.aistra.hails.app.HailData
import com.aistra.hails.app.UnfrozenStore
import com.aistra.hails.databinding.FragmentPagerBinding
import com.aistra.hails.extensions.applyDefaultInsetter
import com.aistra.hails.extensions.isLandscape
import com.aistra.hails.extensions.isRtl
import com.aistra.hails.extensions.marginRelative
import com.aistra.hails.extensions.paddingRelative
import com.aistra.hails.ui.main.MainFragment
import com.aistra.hails.utils.HUI
import com.aistra.hails.utils.NameComparator
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 首页末尾的「冻结」页。
 *
 * 列表来源：所有被手动解冻并打开过的应用（[UnfrozenStore]）。
 * 点击条目直接冻结，冻结成功后该应用会从列表移除。
 */
class UnfrozenFragment : MainFragment(), PagerAdapter.OnItemClickListener,
    PagerAdapter.OnItemLongClickListener {

    private var _binding: FragmentPagerBinding? = null
    private val binding get() = _binding!!
    private lateinit var pagerAdapter: PagerAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPagerBinding.inflate(inflater, container, false)
        pagerAdapter = PagerAdapter(listOf<AppInfo>()).apply {
            onItemClickListener = this@UnfrozenFragment
            onItemLongClickListener = this@UnfrozenFragment
        }
        binding.recyclerView.run {
            layoutManager = GridLayoutManager(
                activity, resources.getInteger(
                    if (HailData.compactIcon) R.integer.home_span_compact else R.integer.home_span
                )
            )
            adapter = pagerAdapter
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }
        }
        binding.refresh.apply {
            setOnRefreshListener {
                updateList()
                isRefreshing = false
            }
            applyDefaultInsetter { marginRelative(isRtl, start = !isLandscape, end = true) }
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        updateList()
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        activity.fab.setOnClickListener { freeze(pagerAdapter.currentList) }
        activity.fab.setOnLongClickListener(null)
    }

    override fun onDestroyView() {
        pagerAdapter.onDestroy()
        _binding = null
        super.onDestroyView()
    }

    private fun updateList() {
        val list = UnfrozenStore.packages().map { AppInfo(it) }.filter {
            // 应用已被卸载时顺手清理记录
            if (it.applicationInfo == null) UnfrozenStore.remove(it.packageName)
            it.applicationInfo != null
        }.sortedWith(NameComparator)
        binding.empty.isVisible = list.isEmpty()
        pagerAdapter.submitList(list)
    }

    /** 点击条目：直接冻结（AppManager 冻结成功后会把它移出本列表） */
    override fun onItemClick(info: AppInfo) = freeze(listOf(info))

    override fun onItemLongClick(info: AppInfo): Boolean {
        MaterialAlertDialogBuilder(activity).setTitle(info.name).setItems(
            arrayOf(
                getString(R.string.action_launch),
                getString(R.string.action_remove_from_list)
            )
        ) { _, which ->
            when (which) {
                0 -> app.packageManager.getLaunchIntentForPackage(info.packageName)?.let {
                    startActivity(it)
                } ?: HUI.showToast(R.string.activity_not_found)

                1 -> {
                    UnfrozenStore.remove(info.packageName)
                    updateList()
                }
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
        return true
    }

    private fun freeze(list: List<AppInfo>) {
        if (list.isEmpty()) return
        if (HailData.workingMode == HailData.MODE_DEFAULT) {
            MaterialAlertDialogBuilder(activity).setMessage(R.string.msg_guide)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val targets = list.filterNot { AppManager.isAppFrozen(it.packageName) }
        if (targets.isEmpty()) {
            updateList()
            return
        }
        when (val result = AppManager.setListFrozen(true, *targets.toTypedArray())) {
            null -> HUI.showToast(R.string.permission_denied)
            else -> {
                updateList()
                HUI.showToast(R.string.msg_freeze, result)
            }
        }
    }
}
