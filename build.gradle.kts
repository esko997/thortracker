import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
}

// Configure both native targets in the plugin's own classloader, not an isolated applied script.
// The applied deck/native-build.gradle.kts retains the single C/C++ engine source list.
listOf(project(":deck"), project(":brick")).forEach { nativeProject ->
    nativeProject.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        val brick = nativeProject.name == "brick"
        val frontend = rootProject.layout.projectDirectory.dir("deck")
        val engineSrc = rootProject.layout.projectDirectory.dir("app/src/main/cpp")
        val deps = nativeProject.layout.projectDirectory.dir("deps")
        val sdlInclude = deps.dir(if (brick) "SDL2-2.30.8/include" else "SDL3-3.2.18/include")
        nativeProject.extensions.configure<KotlinMultiplatformExtension> {
            val platform = if (brick) linuxArm64() else linuxX64()
            platform.compilations.getByName("main").cinterops {
                create(if (brick) "sdl2" else "sdl3") {
                    definitionFile.set(nativeProject.file(if (brick) "src/nativeInterop/cinterop/sdl2.def" else "src/nativeInterop/cinterop/sdl3.def"))
                    includeDirs(sdlInclude)
                }
                create("decknative") {
                    definitionFile.set(frontend.file("src/nativeInterop/cinterop/decknative.def").asFile)
                    includeDirs(frontend.dir("native"), engineSrc, engineSrc.dir("opl3"), engineSrc.dir("ym"), engineSrc.dir("nes"), engineSrc.dir("sms"),
                        engineSrc.dir("ay"), engineSrc.dir("pce"), engineSrc.dir("scc"), engineSrc.dir("opm"), engineSrc.dir("opn"), engineSrc.dir("pokey"), engineSrc.dir("ws"), engineSrc.dir("vsu"), engineSrc.dir("saa"))
                    extraOpts("-libraryPath", nativeProject.layout.buildDirectory.dir("native").get().asFile.absolutePath)
                }
            }
            platform.binaries.executable {
                baseName = "thortracker"
                entryPoint = "org.thortracker.deck.main"
                // AppImage bundles SDL3; Brick uses vendor SDL2. Both hosts supply libstdc++.
                // Do not retain unused Kotlin/Native default libraries (crypt/util/resolv) on the minimal firmware.
                if (brick) linkerOpts("--as-needed")
                linkerOpts("-L${deps.dir("lib").asFile}", if (brick) "-lSDL2" else "-lSDL3", "-lstdc++", "-lm", "--allow-shlib-undefined", "-rpath=\$ORIGIN/lib")
            }
            // Compile shared frontend files once, against each target's concrete SDL ABI and Platform class.
            sourceSets.configureEach {
                if (name == "nativeMain") kotlin.setSrcDirs(emptyList<String>())
            }
            sourceSets.getByName(if (brick) "linuxArm64Main" else "linuxX64Main").apply {
                kotlin.srcDir(frontend.dir("src/nativeMain/kotlin"))
                dependencies { implementation(project(":core")) }
            }
        }
    }
}
