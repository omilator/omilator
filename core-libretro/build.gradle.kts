plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    jvm("desktop") {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "CoreLibretro"
            isStatic = true
        }
        iosTarget.compilations.getByName("main").cinterops {
            val vulkanHeaders = project.file("src/nativeInterop/cinterop/vulkan")
            create("libretro") {
                defFile(project.file("src/nativeInterop/cinterop/libretro.def"))
                includeDirs(project.file("src/nativeInterop/cinterop"))
            }
            // Vulkan HW render (MoltenVK) — required for PSP/GameCube/Wii cores.
            // Only enabled if the vulkan headers are vendored (run ./setup-moltenvk.sh).
            if (vulkanHeaders.exists() && vulkanHeaders.listFiles()?.any { it.extension == "h" } == true) {
                create("vulkan") {
                    defFile(project.file("src/nativeInterop/cinterop/vulkan.def"))
                    // vulkan_core.h #includes "vk_video/..." relative to its own dir.
                    includeDirs(vulkanHeaders, project.file("src/nativeInterop/cinterop"))
                    compilerOpts("-DVK_USE_PLATFORM_METAL", "-DVK_NO_PROTOTYPES")
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.touchlab.kermit)
        }
        val desktopMain by getting {
            dependencies {
                implementation("org.lwjgl:lwjgl:3.3.4")
                implementation("org.lwjgl:lwjgl-opengl:3.3.4")
                implementation("org.lwjgl:lwjgl-glfw:3.3.4")
                implementation("org.lwjgl:lwjgl-vulkan:3.3.4")
                val lwjglNatives = when {
                    org.gradle.internal.os.OperatingSystem.current().isWindows -> "natives-windows"
                    org.gradle.internal.os.OperatingSystem.current().isLinux -> "natives-linux"
                    System.getProperty("os.arch") == "aarch64" -> "natives-macos-arm64"
                    else -> "natives-macos"
                }
                runtimeOnly("org.lwjgl:lwjgl:3.3.4:$lwjglNatives")
                runtimeOnly("org.lwjgl:lwjgl-opengl:3.3.4:$lwjglNatives")
                runtimeOnly("org.lwjgl:lwjgl-glfw:3.3.4:$lwjglNatives")
                runtimeOnly("org.lwjgl:lwjgl-vulkan:3.3.4:$lwjglNatives")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.omilator.core.libretro"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * Compile the tiny C bridge (omilator_log_bridge.c) to a .dylib via clang.
 * Bypasses FFM's variadic upcall limitation on macOS arm64 by providing
 * a stable C ABI for libretro's variadic log callback.
 *
 * Output: <projectDir>/cores/libomilator_log.dylib — loaded at runtime via dlopen.
 */
tasks.register<Exec>("compileLogBridge") {
    group = "native"
    description = "Compile the C log bridge for libretro"

    val source = file("src/desktopMain/cpp/omilator_log_bridge.c")
    val outDir = rootProject.file("cores")
    val outFile = file("${outDir}/libomilator_log.dylib")

    inputs.file(source)
    outputs.file(outFile)

    doFirst { outDir.mkdirs() }

    commandLine(
        "clang",
        "-shared", "-fPIC", "-O2", "-DNDEBUG",
        "-std=c11",
        source.absolutePath,
        "-o", outFile.absolutePath,
    )
}

tasks.named("compileKotlinDesktop") { dependsOn("compileLogBridge") }

tasks.register<JavaExec>("phase1Verify") {
    group = "verification"
    description = "Run Phase 1 libretro FFM bridge verification"
    dependsOn("compileLogBridge")

    val desktopTarget = kotlin.targets.getByName("desktop")
    val desktopCompilation = desktopTarget.compilations.getByName("main") as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation

    classpath = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
    mainClass.set("com.omilator.core.libretro.jvm.Phase1VerificationKt")
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("phase2Verify") {
    group = "verification"
    description = "Run Phase 2 video rendering verification"
    dependsOn("compileLogBridge")

    val desktopTarget = kotlin.targets.getByName("desktop")
    val desktopCompilation = desktopTarget.compilations.getByName("main") as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation

    classpath = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
    mainClass.set("com.omilator.core.libretro.jvm.Phase2VerificationKt")
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("phase5Verify") {
    group = "verification"
    description = "Run Phase 5 save state verification"
    dependsOn("compileLogBridge")

    val desktopTarget = kotlin.targets.getByName("desktop")
    val desktopCompilation = desktopTarget.compilations.getByName("main") as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation

    classpath = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
    mainClass.set("com.omilator.core.libretro.jvm.Phase5VerificationKt")
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("coreLoaderTest") {
    group = "verification"
    description = "Load every bundled libretro core and report system info"
    dependsOn("compileLogBridge")

    val desktopTarget = kotlin.targets.getByName("desktop")
    val desktopCompilation = desktopTarget.compilations.getByName("main") as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation

    classpath = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
    mainClass.set("com.omilator.core.libretro.jvm.CoreLoaderTestKt")
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("hwRenderProbe") {
    group = "verification"
    description = "Probe PPSSPP/Dolphin for HW render env requirements"
    dependsOn("compileLogBridge")

    val desktopTarget = kotlin.targets.getByName("desktop")
    val desktopCompilation = desktopTarget.compilations.getByName("main") as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation

    classpath = desktopCompilation.output.allOutputs + desktopCompilation.runtimeDependencyFiles
    mainClass.set("com.omilator.core.libretro.jvm.HwRenderProbeKt")
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
