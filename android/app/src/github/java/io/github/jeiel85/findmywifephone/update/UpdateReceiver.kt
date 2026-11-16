package io.github.jeiel85.findmywifephone.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Install-session results, and "the app was just replaced". */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> Updater.onReplaced(context)
            Updater.ACTION_STATUS -> {
                val version = intent.getStringExtra("version").orEmpty()
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        if (confirm != null) Updater.onNeedsConfirm(context, version, confirm)
                        else Updater.onFailed(context, status, "no confirmation intent")
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit // MY_PACKAGE_REPLACED follows in the new process
                    else -> Updater.onFailed(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                }
            }
        }
    }
}
