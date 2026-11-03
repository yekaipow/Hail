package com.aistra.hail.ui.main

import android.app.ActivityManager
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.aistra.hail.R
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ActivityMainBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.ui.about.AboutFragment
import com.aistra.hail.ui.apps.AppsFragment
import com.aistra.hail.ui.home.HomeFragment
import com.aistra.hail.ui.settings.SettingsFragment
import com.aistra.hail.utils.HPolicy
import com.aistra.hail.utils.HUI
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity() {
    lateinit var fab: ExtendedFloatingActionButton
    lateinit var appbar: AppBarLayout

    private lateinit var binding: ActivityMainBinding

    /** 当前显示的顶级页面（底部导航 / 侧边导航的 item id） */
    private var currentDestination = R.id.nav_home

    override fun onResume() {
        super.onResume()
        // 每次回到前台同步「从最近任务隐藏」设置（task 可能变化）
        runCatching {
            getSystemService(ActivityManager::class.java)
                .appTasks.firstOrNull()
                ?.setExcludeFromRecents(HailData.hideFromRecents)
        }
        // 保活：服务被系统/ROM 修掉后，回到 App 时重新拉起（重复启动是幂等的）
        if (Settings.canDrawOverlays(this)) {
            // 双向同步：开关关掉后如果服务还活着（含残留窗口），这里会把它停掉
            app.setShakeOverlayService(HailData.shakeOverlay)
            app.setFloatingBallService(HailData.shakeFloatingBall)
            if (HailData.recentTaskFreeze) app.setRecentTaskFreezeService(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = initView()
        // 手写导航：首屏默认首页；重建时 Fragment 由 FragmentManager 恢复，这里只同步 UI 状态
        if (savedInstanceState == null) {
            showDestination(R.id.nav_home)
        } else {
            currentDestination = savedInstanceState.getInt(KEY_DESTINATION, R.id.nav_home)
            applyDestinationUi(currentDestination)
        }
        // 服务被系统回收后，回到应用时顺手把摇一摇悬浮窗恢复起来
        if (HailData.shakeOverlay && Settings.canDrawOverlays(this)) app.setShakeOverlayService(true)
        if (HailData.shakeFloatingBall && Settings.canDrawOverlays(this)) app.setFloatingBallService(true)
        if (HailData.recentTaskFreeze) app.setRecentTaskFreezeService(true)
        if (!HailData.biometricLogin || BiometricManager.from(this)
                .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS
        ) return
        binding.root.isVisible = false
        val biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    HUI.showToast(errString)
                    finishAndRemoveTask()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    binding.root.isVisible = true
                }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.action_biometric))
            .setSubtitle(getString(R.string.msg_biometric)).setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        biometricPrompt.authenticate(promptInfo)
    }

    private fun initView() = ActivityMainBinding.inflate(layoutInflater).apply {
        setContentView(root)
        setSupportActionBar(appBarMain.toolbar)
        fab = appBarMain.fab
        appbar = appBarMain.appBarLayout

        // 手写导航：底部 / 侧边导航栏直接切换 Fragment，不再依赖 navigation 组件
        bottomNav?.setOnItemSelectedListener { item ->
            showDestination(item.itemId)
            true
        }
        navRail?.setOnItemSelectedListener { item ->
            showDestination(item.itemId)
            true
        }
        // 返回键：不在首页时先回到首页，在首页时正常退出
        onBackPressedDispatcher.addCallback(this@MainActivity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentDestination != R.id.nav_home) {
                    showDestination(R.id.nav_home)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val isRtl = isRtl
        val isLandscape = isLandscape
        appBarMain.appBarLayout.applyDefaultInsetter {
            paddingRelative(isRtl, start = !isLandscape, end = true, top = true)
        }
        bottomNav?.applyDefaultInsetter { paddingRelative(isRtl, start = true, end = true, bottom = true) }
        navRail?.applyDefaultInsetter { paddingRelative(isRtl, start = true, top = true, bottom = true) }
        fab.applyDefaultInsetter { marginRelative(isRtl, end = true, bottom = isLandscape) }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.let { MenuCompat.setGroupDividerEnabled(it, true) }
        return super.onCreateOptionsMenu(menu)
    }

    fun ownerRemoveDialog() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.title_remove_owner).setMessage(R.string.msg_remove_owner)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                HPolicy.setOrganizationName()
                HPolicy.removeDeviceOwner()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /* override fun onStop() {
        super.onStop()
        if (HailData.biometricLogin) finishAndRemoveTask()
    } */

    /** 切换顶级页面：同一时间只添加一个 Fragment，其余隐藏 */
    private fun showDestination(id: Int) {
        val tag = id.toString()
        val manager = supportFragmentManager
        // 先落地未完成的事务，否则快速连点会因 findFragmentByTag 返回 null 而重复 add
        runCatching { manager.executePendingTransactions() }
        val existing = manager.findFragmentByTag(tag)
        if (id == currentDestination && existing != null) return

        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        manager.fragments.forEach { transaction.hide(it) }
        if (existing != null) {
            transaction.show(existing)
        } else {
            transaction.add(R.id.nav_host_fragment, createFragment(id), tag)
        }
        transaction.commit()

        currentDestination = id
        applyDestinationUi(id)
    }

    /** 同步标题、FAB 显隐和导航栏选中态（listener 里会因 id 相同直接返回，不会递归） */
    private fun applyDestinationUi(id: Int) {
        if (binding.bottomNav?.selectedItemId != id) binding.bottomNav?.selectedItemId = id
        if (binding.navRail?.selectedItemId != id) binding.navRail?.selectedItemId = id
        supportActionBar?.setTitle(labelOf(id))
        fab.tag = id == R.id.nav_home
        if (fab.tag == true) fab.show() else fab.hide()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DESTINATION, currentDestination)
    }

    private fun createFragment(id: Int): Fragment = when (id) {
        R.id.nav_apps -> AppsFragment()
        R.id.nav_settings -> SettingsFragment()
        R.id.nav_about -> AboutFragment()
        else -> HomeFragment()
    }

    private fun labelOf(id: Int): Int = when (id) {
        R.id.nav_apps -> R.string.title_apps
        R.id.nav_settings -> R.string.title_settings
        R.id.nav_about -> R.string.title_about
        else -> R.string.app_name
    }

    private companion object {
        const val KEY_DESTINATION = "main_destination"
    }
}
