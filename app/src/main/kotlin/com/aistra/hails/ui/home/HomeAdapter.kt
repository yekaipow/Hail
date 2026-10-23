package com.aistra.hails.ui.home

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.aistra.hails.app.HailData.tags

class HomeAdapter(fragment: HomeFragment) : FragmentStateAdapter(fragment) {
    /** 标签页 + 末尾固定的「冻结」页 */
    override fun getItemCount(): Int = tags.size + 1

    override fun createFragment(position: Int): Fragment =
        if (position < tags.size) PagerFragment() else UnfrozenFragment()

    /** 用标签 ID 作为稳定 ID，增删标签时不会重建其它页面 */
    override fun getItemId(position: Int): Long =
        tags.getOrNull(position)?.second?.toLong()?.shl(1) ?: Long.MIN_VALUE
}
