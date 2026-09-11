plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.autobile.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.autobile"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * Which variant instrumentation tests run against.
     *
     * Defaults to debug. Pass `-PautobileTestBuildType=releaseTest` to run the same
     * tests against a build carrying the real release shrinking rules, which is the only
     * way to catch a missing keep rule: stored automations decode through reflection, so
     * a wrong rule fails at run time on a user's device rather than at build time here.
     */
    testBuildType = (project.findProperty("autobileTestBuildType") as String?) ?: "debug"

    /**
     * Release signing is supplied from the environment so no key material lives in the
     * repository. An unsigned build is still produced when the variables are absent,
     * which keeps a plain `assembleRelease` working for contributors.
     */
    val keystorePath = System.getenv("AUTOBILE_KEYSTORE")
    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("AUTOBILE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AUTOBILE_KEY_ALIAS")
                keyPassword = System.getenv("AUTOBILE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }

        /**
         * The shipped build, made installable and instrumentable.
         *
         * Shrinking is where stored automations break: their serializers are reached
         * reflectively, so a missing keep rule compiles cleanly, passes every unit test,
         * and then fails on a user's phone. Running the instrumentation suite against a
         * build that carries the real release rules is the only way to catch that here.
         *
         * It differs from `release` only by being debug-signed and by keeping the test
         * harness alive; the rules under test are identical.
         */
        create("releaseTest") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            proguardFile("proguard-test-rules.pro")
            testProguardFiles("proguard-testapk-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ai"))
    implementation(project(":runtime"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
}
