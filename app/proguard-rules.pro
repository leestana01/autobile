# Kotlin serialization keeps the generated serializers by referencing them reflectively
# from the companion of each @Serializable class. Without these rules the Semantic Skill
# documents already on a user's device fail to decode after an upgrade to a minified
# build, which silently destroys every automation they have taught.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations, AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The polymorphic action, trigger and binding hierarchies are resolved by their
# serial names rather than by a direct code reference, so nothing keeps the subclasses
# alive on its own.
-keep class com.autobile.core.model.** { *; }

# Android instantiates these by name from the manifest.
-keep class com.autobile.runtime.accessibility.AutobileAccessibilityService { *; }
-keep class com.autobile.runtime.trigger.NotificationTriggerService { *; }
-keep class com.autobile.runtime.trigger.BootReceiver { *; }
-keep class com.autobile.runtime.background.AgentForegroundService { *; }
-keep class com.autobile.app.TeachingForegroundService { *; }

# WorkManager constructs workers reflectively.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# The on-device generative APIs are optional at run time. Their absence is detected by
# catching linkage errors, so the probe must survive shrinking intact.
-dontwarn com.google.mlkit.genai.**
-keep class com.google.mlkit.genai.** { *; }

# Keep source file and line numbers in crash reports from a public build.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
