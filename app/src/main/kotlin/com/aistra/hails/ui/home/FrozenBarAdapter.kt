package com.aistra.hails.ui.home

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hails.R
import com.aistra.hails.app.AppInfo
import com.aistra.hails.app.HailData
import com.aistra.hails.utils.AppIconCache
import com.aistra.hails.utils.HPackages.myUserId
import kotlinx.coroutines.Job

/** 首页底部常驻「冻结」区的小图标列表 */
class FrozenBarAdapter(
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, FrozenBarAdapter.ViewHolder>(Diff) {
    private var loadIconJob: Job? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_frozen_bar, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = currentList[position]
        holder.itemView.run {
            setOnClickListener { onClick(info) }
            setOnLongClickListener {
                onLongClick(info)
                true
            }
            findViewById<ImageView>(R.id.app_icon).run {
                info.applicationInfo?.let {
                    loadIconJob = AppIconCache.loadIconBitmapAsync(context, it, myUserId, this, false)
                } ?: setImageDrawable(context.packageManager.defaultActivityIcon)
            }
            findViewById<TextView>(R.id.app_name).run {
                text = info.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, HailData.homeFontSize.coerceAtMost(14f))
            }
        }
    }

    fun onDestroy() {
        if (loadIconJob?.isActive == true) loadIconJob?.cancel()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

    private object Diff : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem == newItem

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem.name == newItem.name
    }
}
