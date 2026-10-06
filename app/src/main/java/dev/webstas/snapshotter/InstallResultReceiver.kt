package dev.webstas.snapshotter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat

/** Receives the installer's verdict: shows the system confirmation when needed, reports failures. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

            PackageInstaller.STATUS_SUCCESS -> Unit

            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, context.getString(R.string.update_failed, reason), Toast.LENGTH_LONG).show()
            }
        }
    }
}
