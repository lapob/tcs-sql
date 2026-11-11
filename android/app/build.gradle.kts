import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing key lives next to the project (D:\TopCashSQL\android). Keep it: updates must use the same key.
val keystoreFile = rootProject.file("topcash-release.keystore")
val keystorePassword = rootProject.file("keystore-password.txt").takeIf { it.exists() }?.readText()?.trim()

android {
    namespace = "it.topcash.studio"
    compileSdk = 37

    defaultConfig {
        applicationId = "it.topcash.studio"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = "2.8.6"
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = "topcash"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin") }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    // installs the Compose libraries' ready-made performance profiles on sideloaded APKs: smooth from the first launch
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigationevent.compose)   // reads the predictive back gesture (BackMotion.kt)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.materials)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
}
