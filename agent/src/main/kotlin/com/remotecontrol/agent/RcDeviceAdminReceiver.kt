package com.remotecontrol.agent

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/** Device Admin component (Session 7). Enabled once, manually, from [WakeSetupActivity]. */
class RcDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("RemoteAgent", "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("RemoteAgent", "Device admin disabled")
    }

    companion object {
        fun component(context: Context) = ComponentName(context.applicationContext, RcDeviceAdminReceiver::class.java)
    }
}
