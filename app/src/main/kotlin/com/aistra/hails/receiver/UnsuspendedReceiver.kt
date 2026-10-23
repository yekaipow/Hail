package com.aistra.hails.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistra.hails.HailApp.Companion.app
import com.aistra.hails.utils.HShizuku.setAppRestricted
import com.aistra.hails.utils.HTarget

class UnsuspendedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PACKAGE_UNSUSPENDED_MANUALLY) runCatching {
            if (HTarget.P) setAppRestricted(
                intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)!!,
                false
            )
            app.setAutoFreezeService()
        }
    }

    companion object {
        private const val ACTION_PACKAGE_UNSUSPENDED_MANUALLY =
            "android.intent.action.PACKAGE_UNSUSPENDED_MANUALLY"
    }
}