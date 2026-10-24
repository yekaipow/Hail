package com.aistra.hail.ui.home

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.aistra.hail.app.HailData.tags

class HomeAdapter(fragment: HomeFragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = tags.size

    override fun createFragment(position: Int): Fragment = PagerFragment()

    /** 用标签 ID 作为稳定 ID：增删分组、后台恢复时页面与标签的对应关系不会错位 */
    override fun getItemId(position: Int): Long = tags[position].second.toLong()

    override fun containsItem(itemId: Long): Boolean = tags.any { it.second.toLong() == itemId }
}
