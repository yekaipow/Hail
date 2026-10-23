package com.aistra.hails.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.aistra.hails.R
import com.aistra.hails.app.AppInfo
import com.aistra.hails.app.HailData.tags
import com.aistra.hails.databinding.FragmentHomeBinding
import com.aistra.hails.extensions.applyDefaultInsetter
import com.aistra.hails.extensions.isLandscape
import com.aistra.hails.extensions.isRtl
import com.aistra.hails.extensions.paddingRelative
import com.aistra.hails.ui.main.MainFragment
import com.google.android.material.tabs.TabLayoutMediator

class HomeFragment : MainFragment() {
    var multiselect: Boolean = false
    val selectedList: MutableList<AppInfo> = mutableListOf()
    private var _binding: FragmentHomeBinding? = null
    val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        binding.pager.adapter = HomeAdapter(this)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            // 最后一个页签是固定的「冻结」页
            tab.text = tags.getOrNull(position)?.first ?: getString(R.string.label_unfrozen)
        }.attach()
        binding.tabs.applyDefaultInsetter { paddingRelative(isRtl, start = !activity.isLandscape, end = true) }
        return binding.root
    }

    override fun onDestroyView() {
        multiselect = false
        selectedList.clear()
        super.onDestroyView()
        _binding = null
    }
}