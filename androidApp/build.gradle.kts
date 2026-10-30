import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// A release sets these from its tag (-PappVersion=0.5.0) and repo (-PreleasesRepo=owner/name); see
// .github/workflows/release.yml. A local build is 0.5.0 and never looks for updates.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.5.0"
val releasesRepo = (findProperty("releasesRepo") as String?).orEmpty()

android {
    namespace = "dev.kurobanekotlin.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.kurobane.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        val (major, minor, patch) = appVersion.substringBefore('-').split('.').map { it.toInt() }.plus(listOf(0, 0)).take(3)
        require(minor < 100 && patch < 100) { "appVersion $appVersion: minor and patch must stay under 100" }
        versionCode = major * 10_000 + minor * 100 + patch
        versionName = appVersion
        buildConfigField("String", "RELEASES_REPO", "\"$releasesRepo\"")
    }

    // Release signing comes from an untracked keystore.properties at the repo root
    // (storeFile, storePassword, keyAlias, keyPassword); without it release builds use the debug key.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    if (keystore == null && releasesRepo.isNotEmpty()) error("releasesRepo is set but keystore.properties is missing")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(projects.shared.core)
    implementation(projects.shared.designsystem)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.markdown.renderer)
    implementation(libs.koin.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    debugImplementation(libs.compose.ui.tooling.preview)
}
