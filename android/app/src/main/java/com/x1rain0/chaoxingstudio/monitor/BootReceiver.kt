package com.x1rain0.chaoxingstudio.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.x1rain0.chaoxingstudio.ChaoxingStudioApplication
import com.x1rain0.chaoxingstudio.data.shouldRestoreMonitorOnBoot

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? ChaoxingStudioApplication ?: return
        val config = app.monitorRuntime.preferences.config()
        if (!shouldRestoreMonitorOnBoot(config)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Boot restore skipped because notification permission is not granted")
            return
        }

        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        } catch (error: Exception) {
            // Android version / OEM restrictions may reject a boot-time FGS launch.
            Log.w(TAG, "Boot restore could not start the monitor service", error)
        }
    }

    private companion object {
        const val TAG = "ChaoxingStudioBoot"
    }
}
