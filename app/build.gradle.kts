import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/**
 * Release signing is read from keystore.properties, which is intentionally NOT
 * committed. When the file is missing (for example on a CI runner without the
 * signing secrets) the release build falls back to the debug signing config so
 * that `assembleRelease` still produces an installable artifact for testing.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.nardech.vdoplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nardech.vdoplayer"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "1.5.3"
        vectorDrawables { useSupportLibrary = true }

        // The instrumented smoke test in app/src/androidTest drives the real player on a
        // real Android runtime; the JVM tests next door cannot prove that a decoder runs.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    testOptions {
        unitTests {
            // Media3's TrackGroup calls TextUtils.isEmpty() while it is constructed, and
            // the unit tests build real TrackGroup/Tracks objects to check the audio
            // track picker. Without this, every such test dies with "Method isEmpty in
            // android.text.TextUtils not mocked". Defaults are only returned for Android
            // platform calls; the assertions themselves stay about our own code.
            isReturnDefaultValues = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 shrinks and renames the shipped build. The dex files unpack to
            // ~52 MB — more than twice the APK — and most of that is library code
            // this app never calls. proguard-rules.pro names what is reached by name
            // only — NextLib's JNI classes and every native method.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    /**
     * The FFmpeg decoders are ~55 MB of native code across four ABIs. Publishing
     * per-architecture APKs keeps a phone download around half of the universal
     * one, while the universal APK stays available for anything unusual.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        // Media3 with ExoPlayer is still annotated @UnstableApi almost everywhere,
        // including everything this app has to touch (renderers, track selection).
        freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.nextlib.media3ext)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    testImplementation(libs.junit)

    // Playback is the one thing the JVM tests cannot reach: MediaCodec, the bundled
    // FFmpeg libraries and the decoder switch all need an Android runtime.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    // The player's snackbar host lives outside the app's Scaffold, so only a Compose
    // test on a device can show that an announcement really reaches the screen.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
