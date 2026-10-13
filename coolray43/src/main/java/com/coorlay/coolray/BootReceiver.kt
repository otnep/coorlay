package com.coorlay.coolray

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts coorlay with the head unit; the activity moves itself to the background until CarPlay shows video. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!CoolrayActivity.autoConnectEnabled(context)) return
        context.startActivity(Intent(context, CoolrayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CoolrayActivity.EXTRA_AUTOSTART, true))
    }
}
