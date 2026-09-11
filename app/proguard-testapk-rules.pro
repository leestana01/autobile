# Rules for the instrumentation test APK only. The shipped app never sees this file.
#
# The test libraries reference optional classes that are not on the test classpath, which
# stops R8 before the test APK can be built at all.
-dontwarn androidx.concurrent.futures.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.junit.**
-dontwarn org.hamcrest.**
