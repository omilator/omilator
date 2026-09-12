plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
            baseName = "CoreInput"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":core-libretro"))
        }
        val desktopMain by getting {
            dependencies {
                implementation("org.lwjgl:lwjgl:3.3.4")
                implementation("org.lwjgl:lwjgl-glfw:3.3.4")
                val lwjglNatives = when {
                    org.gradle.internal.os.OperatingSystem.current().isWindows -> "natives-windows"
                    org.gradle.internal.os.OperatingSystem.current().isLinux -> "natives-linux"
                    System.getProperty("os.arch") == "aarch64" -> "natives-macos-arm64"
                    else -> "natives-macos"
                }
                runtimeOnly("org.lwjgl:lwjgl:3.3.4:$lwjglNatives")
                runtimeOnly("org.lwjgl:lwjgl-glfw:3.3.4:$lwjglNatives")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.omilator.core.input"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
