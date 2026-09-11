package com.autobile.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autobile.runtime.AutobileRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that the app starts and stays usable on a device with nothing granted and no
 * on-device model, which is the state every new install begins in.
 *
 * These would all have been caught the first time anyone opened the app, which is
 * exactly why they are worth catching here instead.
 */
@RunWith(AndroidJUnit4::class)
class ApplicationStartupTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theApplicationPublishesItsServicesOnStartup() {
        // Background entry points read the runtime through this holder; if startup fails
        // to install it, scheduled automations silently never run.
        assertNotNull("The runtime was not installed during startup", AutobileRuntime.services)
    }

    @Test
    fun theMainActivityLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun theMainActivitySurvivesRecreation() {
        // Teaching sessions span a rotation or a return from another app, so losing state
        // here would lose the demonstration the user had just performed.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun capabilityDetectionCompletesWithNothingGranted() = runBlocking {
        val graph = (context.applicationContext as AutobileApplication).graph
        val profile = withTimeout(CAPABILITY_TIMEOUT_MS) { graph.capabilityDetector.detect() }

        assertTrue("Android version was not detected", profile.apiLevel > 0)
        // Nothing is granted in an instrumentation run, so the profile has to say so
        // rather than optimistically reporting the app as ready.
        assertTrue("Screen control was reported without accessibility access", !profile.canControlScreen)
        assertTrue("No limitation was reported despite nothing being granted", profile.restrictions.isNotEmpty())
    }

    @Test
    fun theAccessibilityServiceIsInstallableByTheSystem() {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val declared = manager.getInstalledAccessibilityServiceList()
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }

        assertTrue("Android does not list the accessibility service as installable", declared)
    }

    @Test
    fun theAccessibilityServiceRequestsTheCapabilitiesTheAgentNeeds() {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val service = manager.getInstalledAccessibilityServiceList()
            .firstOrNull { it.resolveInfo.serviceInfo.packageName == context.packageName }

        assertNotNull("The accessibility service was not found", service)
        val capabilities = service!!.capabilities
        assertTrue(
            "Window content retrieval is not requested",
            capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0,
        )
        assertTrue(
            "Gesture dispatch is not requested",
            capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0,
        )
        assertTrue(
            "Screen capture is not requested",
            capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0,
        )
    }

    @Test
    fun theLauncherEntryPointResolves() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val matches = context.packageManager.queryIntentActivities(intent, 0)

        assertTrue("The app has no launcher entry", matches.isNotEmpty())
    }

    @Test
    fun theStarterAutomationIsCreatedOnDemand() = runBlocking {
        val graph = (context.applicationContext as AutobileApplication).graph

        val skill = graph.starterSkill()

        assertTrue("The starter automation has no steps", skill.steps.isNotEmpty())
        // Onboarding runs this before the user has taught anything, so it has to be
        // safe to run unattended and impossible to get wrong.
        assertTrue("The starter automation is not low risk", skill.riskPolicy.categories.isEmpty())
    }

    @Test
    fun cloudAccessIsOffUntilTheUserTurnsItOn() {
        val graph = (context.applicationContext as AutobileApplication).graph
        val privacy = graph.settings.privacy()

        assertTrue("Cloud access was enabled by default", !privacy.cloudEnabled)
        assertTrue("Screenshot upload was enabled by default", !privacy.allowScreenshotToCloud)
        assertTrue("Sensitive text masking was off by default", privacy.maskSensitiveFields)
    }

    private companion object {
        const val CAPABILITY_TIMEOUT_MS = 20_000L
    }
}
