# Extra keeps for the instrumentation harness, applied only to the `releaseTest` build.
#
# The shipped `release` build never sees this file. Its purpose is to let the test APK
# start at all: the runner resolves parts of the Kotlin standard library and the test
# infrastructure out of the app APK rather than bundling its own copies, and shrinking
# removes whatever the app itself happens not to use.
#
# Nothing here relaxes the rules that are actually under test. The serialization and
# reflection keeps that stored automations depend on live in proguard-rules.pro and
# apply identically to both builds, so a missing rule still fails this run.
-dontwarn androidx.concurrent.futures.**
-dontwarn com.google.errorprone.annotations.**

-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.test.** { *; }
-keep class androidx.tracing.** { *; }
-keep class androidx.startup.** { *; }
-keep class androidx.profileinstaller.** { *; }
-keep class org.junit.** { *; }
-keep class org.hamcrest.** { *; }

# The instrumentation reaches into the application object and the stores it owns, and is
# compiled against their real names. Obfuscating them would only prove that renaming
# works, which is not what this run is for.
#
# The serialization keeps that are actually under test stay in proguard-rules.pro and are
# unaffected by anything here: the generated serializers are still resolved exactly as
# they are in the shipped build, so a missing rule still fails.
-keep class com.autobile.app.AutobileApplication { *; }
-keep class com.autobile.app.AppGraph { *; }
-keep class com.autobile.app.MainActivity { *; }
-keep class com.autobile.core.data.** { *; }
-keep class com.autobile.runtime.AutobileRuntime { *; }
-keep class com.autobile.runtime.AutobileServices { *; }
-keep class com.autobile.runtime.capability.CapabilityDetector { *; }
