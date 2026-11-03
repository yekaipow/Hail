package com.aistra.hail.ui.about

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.FragmentAboutBinding
import com.aistra.hail.databinding.ItemAboutLinkBinding
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HUI
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import java.text.SimpleDateFormat

class AboutFragment : MainFragment() {
    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val installTime = HPackages.getUnhiddenPackageInfoOrNull(app.packageName)?.firstInstallTime ?: 0L

        binding.appName.text = getString(R.string.app_name)
        binding.appSlogan.text = getString(R.string.app_slogan)
        binding.appIcon.setImageResource(R.drawable.ic_launcher_foreground)
        binding.cardHeader.setOnClickListener { HUI.openLink(HailData.URL_WHY_FREE_SOFTWARE) }

        addItem(
            binding.containerInfo, R.drawable.ic_about_update, R.string.label_version, HailData.VERSION
        ) { HUI.openLink(HailData.URL_RELEASES) }
        addItem(
            binding.containerInfo,
            R.drawable.ic_about_install_mobile,
            R.string.label_time,
            SimpleDateFormat.getDateInstance().format(installTime)
        ) { HUI.showToast("\uD83E\uDD76\uD83D\uDCA8\uD83D\uDC09") }

        addItem(binding.containerCommunity, R.drawable.ic_about_send, R.string.action_telegram) {
            HUI.openLink(HailData.URL_TELEGRAM)
        }
        addItem(binding.containerCommunity, R.drawable.ic_about_group, R.string.action_qq) {
            HUI.openLink(HailData.URL_QQ)
        }
        addItem(binding.containerCommunity, R.drawable.ic_about_local_mall, R.string.action_fdroid) {
            HUI.openLink(HailData.URL_FDROID)
        }
        addItem(binding.containerCommunity, R.drawable.ic_about_coffee, R.string.action_donate) {
            openDonateDialog()
        }

        addItem(binding.containerOpensource, R.drawable.ic_about_code, R.string.action_github) {
            HUI.openLink(HailData.URL_GITHUB)
        }
        addItem(binding.containerOpensource, R.drawable.ic_about_translate, R.string.action_translate) {
            HUI.openLink(HailData.URL_TRANSLATE)
        }
        addItem(binding.containerOpensource, R.drawable.ic_about_description, R.string.action_licenses) {
            showLicenseDialog()
        }
    }

    /** 卡片里的一行：图标 + 标题（+ 描述），整行可点 */
    private fun addItem(
        container: LinearLayout,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        desc: String? = null,
        onClick: () -> Unit
    ) {
        val item = ItemAboutLinkBinding.inflate(layoutInflater, container, false)
        item.icon.setImageResource(icon)
        item.title.setText(title)
        if (desc != null) {
            item.desc.text = desc
            item.desc.visibility = View.VISIBLE
        }
        item.root.setOnClickListener { onClick() }
        container.addView(item.root)
    }

    /** 许可证：每行「名称: 链接」，名称可点开对应链接 */
    private fun showLicenseDialog() {
        val context = requireContext()
        val linkColor = MaterialColors.getColor(
            requireView(), com.google.android.material.R.attr.colorPrimary
        )
        val text = SpannableStringBuilder()
        runCatching {
            resources.openRawResource(R.raw.licenses).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEachIndexed { index, line ->
                    if (index > 0) text.append("\n\n")
                    val url = line.substringAfter(": ")
                    val start = text.length
                    text.append(line.substringBefore(": "))
                    text.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            HUI.openLink(url)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = linkColor
                        }
                    }, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        val textView = TextView(context).apply {
            setText(text)
            movementMethod = LinkMovementMethod.getInstance()
            setTextIsSelectable(true)
            val padding = resources.getDimensionPixelOffset(R.dimen.padding_large)
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(context).apply {
            addView(
                textView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.action_licenses)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openDonateDialog() {
        MaterialAlertDialogBuilder(activity).setTitle(R.string.title_donate)
            .setSingleChoiceItems(R.array.donate_payment_entries, 0) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> if (!HUI.openLink(HailData.URL_ALIPAY_API)) {
                        HUI.openLink(HailData.URL_ALIPAY)
                    }

                    1 -> MaterialAlertDialogBuilder(activity).setTitle(R.string.title_donate)
                        .setView(ShapeableImageView(activity).apply {
                            val padding = resources.getDimensionPixelOffset(R.dimen.padding_large)
                            setPadding(0, padding, 0, padding)
                            setImageResource(R.mipmap.qr_wechat)
                        }).setPositiveButton(R.string.donate_wechat_scan) { _, _ ->
                            app.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.let {
                                it.putExtra("LauncherUI.From.Scaner.Shortcut", true)
                                startActivity(it)
                            } ?: HUI.showToast(R.string.app_not_installed)
                        }.setNegativeButton(android.R.string.cancel, null).show()

                    2 -> HUI.openLink(HailData.URL_KOFI)
                    3 -> HUI.openLink(HailData.URL_LIBERAPAY)
                    4 -> HUI.openLink(HailData.URL_PAYPAL)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
