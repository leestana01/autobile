package com.autobile.runtime.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autobile.core.common.Logx
import com.autobile.runtime.AutobileRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Restores user-created schedules after Android has rebuilt its job state. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val services = AutobileRuntime.services ?: return@launch
                services.triggerScheduler.rescheduleAll(services.skillStore.listEnabledSkills())
            } catch (error: Throwable) {
                Logx.w("Could not restore automation schedules", error)
            } finally {
                pending.finish()
            }
        }
    }
}
