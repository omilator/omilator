plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions { jvmTarget = "17" } }
    }
    jvm("desktop") {
        compilations.all { kotlinOptions { jvmTarget = "17" } }
    }
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "UiShared"
            isStatic = true
            // Export all dependencies so the iOS app links a single framework
            export(project(":core-libretro"))
            export(project(":core-render"))
            export(project(":core-audio"))
            export(project(":core-input"))
            export(project(":data-library"))
            export(project(":data-settings"))
            export(project(":data-saves"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.atomicfu)

            // API deps — exported into the UiShared.framework for iOS
            api(project(":core-libretro"))
            api(project(":core-render"))
            api(project(":core-audio"))
            api(project(":core-input"))
            api(project(":data-library"))
            api(project(":data-settings"))
            api(project(":data-saves"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.omilator.ui.shared"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
