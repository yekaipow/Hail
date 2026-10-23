package com.aistra.hails.ui.home

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator

class HomeFragment : MainFragment() {
    var multiselect: Boolean = false
    val selectedList: MutableList<AppInfo> = mutableListOf()
    private var _binding: FragmentHomeBinding? = null
    val binding get() = _binding!!
    private lateinit var frozenAdapter: FrozenBarAdapter

    /** 防止「解冻列表变化」回调整理未安装应用时递归触发 */
    private var updatingBar = false
    private val sp by lazy { PreferenceManager.getDefaultSharedPreferences(app) }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == UnfrozenStore.KEY) updateFrozenBar()
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

        // 底部常驻「冻结」区
        frozenAdapter = FrozenBarAdapter(::freezeApp, ::showFrozenMenu)
        binding.frozenList.apply {
            layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            adapter = frozenAdapter
        }
        binding.frozenBar.applyDefaultInsetter {
            paddingRelative(isRtl, start = !isLandscape, end = true, bottom = true)
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
            binding.frozenBar.isVisible = visible
            binding.frozenTitle.text =
                if (visible) "${getString(R.string.label_unfrozen)} (${list.size})"
                else getString(R.string.label_unfrozen)
            frozenAdapter.submitList(list)
            // 底部区出现时把 FAB 上移，避免遮挡小图标
            binding.frozenBar.post { adjustFab(visible) }
        } finally {
            updatingBar = false
        }
    }

    private fun adjustFab(barVisible: Boolean) {
        val binding = _binding ?: return
        val offset = if (barVisible) binding.frozenBar.height else 0
        activity.fab.translationY = -offset.toFloat()
    }

    /** 点击底部小图标：直接冻结，冻结成功后自动从该区域消失 */
    private fun freezeApp(info: AppInfo) {
        if (HailData.workingMode == HailData.MODE_DEFAULT) {
            MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.msg_guide)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        if (AppManager.setAppFrozen(info.packageName, true)) {
            HUI.showToast(R.string.msg_freeze, info.name)
        } else {
            HUI.showToast(R.string.permission_denied)
        }
    }

    private fun showFrozenMenu(info: AppInfo) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(info.name).setItems(
            arrayOf(
                getString(R.string.action_launch),
                getString(R.string.action_remove_from_list)
            )
        ) { _, which ->
            when (which) {
                0 -> app.packageManager.getLaunchIntentForPackage(info.packageName)?.let {
                    startActivity(it)
                } ?: HUI.showToast(R.string.activity_not_found)

                1 -> UnfrozenStore.remove(info.packageName)
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
