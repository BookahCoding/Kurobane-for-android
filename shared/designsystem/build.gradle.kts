plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "dev.kurobanekotlin.designsystem"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            api(project.dependencies.platform(libs.androidx.compose.bom))
            api(libs.androidx.compose.ui.graphics)
        }
    }
}
