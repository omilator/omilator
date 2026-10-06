import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop") {
        compilations.all { kotlinOptions { jvmTarget = "17" } }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":ui-shared"))
                implementation(project(":core-libretro"))
                implementation(project(":data-library"))
                implementation(project(":data-settings"))
                implementation(project(":data-launcher"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.foundation)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.omilator.app.MainKt"

        // Note: -XstartOnFirstThread would be needed for LWJGL GLFW on macOS,
        // but it breaks Compose Desktop's window initialization. Instead we
        // disable LWJGL's thread check at runtime in Main.kt via
        // Configuration.GLFW_CHECK_THREAD0.set(false). This may fail at
        // glfwCreateWindow time; if so, PSP/GameCube HW render requires a
        // separate process or CGL backend.

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Omilator"
            packageVersion = "1.0.0"

            // LWJGL and coroutines need sun.misc.Unsafe — include the module
            // in the stripped JRE that Compose Desktop creates via jlink.
            modules("jdk.unsupported")
            modules("java.management")

            macOS {
                bundleID = "com.omilator.app"
                dockName = "Omilator"
            }
            windows {
                packageName = "Omilator"
            }
            linux {
                packageName = "omilator"
            }
        }
    }
}

// NOTE: Compose Desktop's `packageReleaseDmg` task runs ProGuard which chokes
// on LWJGL's references to javax.annotation.Nullable (a findbugs-only
// annotation that isn't on the runtime classpath). Rather than maintain
// -dontwarn rules, ship the debug build via `createDistributable` (which
// skips ProGuard) and create the DMG via hdiutil. See release-dmg.sh.
