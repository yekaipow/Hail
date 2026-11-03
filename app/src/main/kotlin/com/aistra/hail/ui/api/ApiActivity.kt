package com.aistra.hail.ui.api

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.app.UnfrozenStore
import com.aistra.hail.databinding.ItemAboutLinkBinding
import com.aistra.hail.utils.*
import com.aistra.hail.work.HWork.setAutoFreeze
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ApiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            if (handleAction(intent.action)) finish()
        }.onFailure(::setErrorDialog)
    }

    private fun handleAction(action: String?): Boolean {
        when (action) {
            Intent.ACTION_SHOW_APP_INFO -> {
                showRedirectSheet(requirePackage)
                return false
            }

            Intent.ACTION_VIEW -> return handleSchema(intent.data)

            HailApi.ACTION_LAUNCH -> launchApp(requirePackage, runCatching { requireTagId }.getOrNull())
            HailApi.ACTION_FREEZE -> setAppFrozen(requirePackage, true)
            HailApi.ACTION_UNFREEZE -> setAppFrozen(requirePackage, false)
            HailApi.ACTION_FREEZE_TAG -> setListFrozen(
                true, HailData.checkedList.filter { requireTagId in it.tagIdList }, true
            )

            HailApi.ACTION_UNFREEZE_TAG -> setListFrozen(
                false, HailData.checkedList.filter { requireTagId in it.tagIdList })

            HailApi.ACTION_FREEZE_ALL -> setListFrozen(true)
            HailApi.ACTION_UNFREEZE_ALL -> setListFrozen(false)
            HailApi.ACTION_FREEZE_NON_WHITELISTED -> setListFrozen(true, skipWhitelisted = true)
            HailApi.ACTION_FREEZE_AUTO -> setAutoFreeze(false)
            HailApi.ACTION_LOCK -> lockScreen(false)
            HailApi.ACTION_LOCK_FREEZE -> lockScreen(true)
            else -> throw IllegalArgumentException("Unknown action:\n$action")
        }
        return true
    }

    /**
     * Handle schema actions
     *
     * hail://launch?package=xxx
     * hail://freeze?package=xxx
     * hail://unfreeze?package=xxx
     * hail://freeze_tag?tag=xxx
     * hail://unfreeze_tag?tag=xxx
     * hail://freeze_all
     * hail://unfreeze_all
     * hail://freeze_non_whitelisted
     * hail://freeze_auto
     * hail://lock
     * hail://lock_freeze
     */
    private fun handleSchema(uri: Uri?): Boolean {
        if (uri?.scheme != "hail") throw IllegalArgumentException("Unknown scheme:\n${uri?.scheme}")
        return handleAction(
            when (uri.host) {
                "launch" -> HailApi.ACTION_LAUNCH
                "freeze" -> HailApi.ACTION_FREEZE
                "unfreeze" -> HailApi.ACTION_UNFREEZE
                "freeze_tag" -> HailApi.ACTION_FREEZE_TAG
                "unfreeze_tag" -> HailApi.ACTION_UNFREEZE_TAG
                "freeze_all" -> HailApi.ACTION_FREEZE_ALL
                "unfreeze_all" -> HailApi.ACTION_UNFREEZE_ALL
                "freeze_non_whitelisted" -> HailApi.ACTION_FREEZE_NON_WHITELISTED
                "freeze_auto" -> HailApi.ACTION_FREEZE_AUTO
                "lock" -> HailApi.ACTION_LOCK
                "lock_freeze" -> HailApi.ACTION_LOCK_FREEZE
                else -> throw IllegalArgumentException("Unknown host:\n${uri.host}")
            }
        )
    }

    private fun setErrorDialog(t: Throwable) {
        MaterialAlertDialogBuilder(this)
            .setMessage(t.message ?: t.stackTraceToString())
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** 点图标后的操作面板：启动 / 冻结 / 解冻 */
    private fun showRedirectSheet(pkg: String) {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val paddingH = resources.getDimensionPixelSize(R.dimen.padding_medium)
        val paddingV = resources.getDimensionPixelSize(R.dimen.padding_small)
        val label = HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(packageManager)?.toString() ?: pkg

        container.addView(TextView(this).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
            setPadding(paddingH, paddingV, paddingH, paddingV)
        })
        addSheetItem(container, dialog, R.drawable.ic_about_launch, R.string.action_launch) {
            launchApp(pkg)
        }
        addSheetItem(container, dialog, R.drawable.ic_about_ac_unit, R.string.action_freeze) {
            if (!HailData.isChecked(pkg)) HailData.addCheckedApp(pkg)
            setAppFrozen(pkg, true)
        }
        addSheetItem(container, dialog, R.drawable.ic_about_brightness_low, R.string.action_unfreeze) {
            setAppFrozen(pkg, false)
        }

        dialog.setContentView(container)
        dialog.show()
    }

    private fun addSheetItem(
        container: LinearLayout,
        dialog: BottomSheetDialog,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        onClick: () -> Unit
    ) {
        val item = ItemAboutLinkBinding.inflate(layoutInflater, container, false)
        item.icon.setImageResource(icon)
        item.title.setText(title)
        item.root.setOnClickListener {
            runCatching {
                onClick()
                dialog.dismiss()
                finish()
            }.onFailure(::setErrorDialog)
        }
        container.addView(item.root)
    }

    private val requirePackage: String
        get() = intent.run {
            if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailData.KEY_PACKAGE)
            else getStringExtra(
                if (action != Intent.ACTION_SHOW_APP_INFO) HailData.KEY_PACKAGE
                else if (HTarget.N) Intent.EXTRA_PACKAGE_NAME
                else "android.intent.extra.PACKAGE_NAME"
            )
        }?.also {
            HPackages.getApplicationInfoOrNull(it) ?: throw NameNotFoundException(getString(R.string.app_not_installed))
        } ?: throw IllegalArgumentException("Package must not be null")

    private val requireTagId: Int
        get() = intent.run {
            if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailData.KEY_TAG)
            else getStringExtra(HailData.KEY_TAG)
        }?.let {
            HailData.tags.find { tag -> tag.first == it }?.second
                ?: throw IllegalStateException("Tag unavailable:\n$it")
        } ?: throw IllegalArgumentException("Tag must not be null")

    private fun launchApp(pkg: String, tagId: Int? = null) {
        if (tagId != null) setListFrozen(false, HailData.checkedList.filter { tagId in it.tagIdList })
        if (AppManager.isAppFrozen(pkg) && AppManager.setAppFrozen(pkg, false)) {
            UnfrozenStore.add(pkg)
            app.setAutoFreezeService()
        }
        if (HailData.workingMode == HailData.MODE_ISLAND_HIDE) {
            HIsland.ensureLaunchIntentExists(packageName)
        }
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            HShortcuts.addDynamicShortcut(pkg)
            startActivity(it)
        } ?: throw ActivityNotFoundException(getString(R.string.activity_not_found))
    }

    private fun setAppFrozen(pkg: String, frozen: Boolean) = when {
        frozen && !HailData.isChecked(pkg) -> throw SecurityException("Package not checked")
        AppManager.isAppFrozen(pkg) != frozen && !AppManager.setAppFrozen(
            pkg, frozen
        ) -> throw IllegalStateException(getString(R.string.permission_denied))

        else -> {
            HUI.showToast(
                if (frozen) R.string.msg_freeze else R.string.msg_unfreeze,
                HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(packageManager) ?: pkg
            )
            app.setAutoFreezeService()
        }
    }

    private fun setListFrozen(
        frozen: Boolean, list: List<AppInfo> = HailData.checkedList, skipWhitelisted: Boolean = false
    ) {
        val filtered =
            list.filter { AppManager.isAppFrozen(it.packageName) != frozen && !(skipWhitelisted && it.whitelisted) }
        when (val result = AppManager.setListFrozen(frozen, *filtered.toTypedArray())) {
            null -> throw IllegalStateException(getString(R.string.permission_denied))
            else -> {
                HUI.showToast(
                    if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, result
                )
                app.setAutoFreezeService()
            }
        }
    }

    private fun lockScreen(freezeAll: Boolean) {
        if (freezeAll) setListFrozen(true)
        if (AppManager.lockScreen.not()) throw IllegalStateException(getString(R.string.permission_denied))
    }
}