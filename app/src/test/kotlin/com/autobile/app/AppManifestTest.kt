package com.autobile.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.autobile.runtime.AutobileRuntime
import com.autobile.runtime.trigger.BootReceiver
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `application installs the shared runtime graph`() {
        assertThat(context).isInstanceOf(AutobileApplication::class.java)
        assertThat(AutobileRuntime.services).isSameInstanceAs((context as AutobileApplication).graph)
    }

    @Test
    fun `launcher activity is exported and resolvable`() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val matches = context.packageManager.queryIntentActivities(intent, 0)

        assertThat(matches.map { it.activityInfo.name }).contains(MainActivity::class.java.name)
        assertThat(matches.first { it.activityInfo.name == MainActivity::class.java.name }.activityInfo.exported).isTrue()
    }

    @Test
    fun `boot receiver is registered and enabled`() {
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, BootReceiver::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertThat(info.enabled).isTrue()
        assertThat(info.exported).isTrue()
    }
}
