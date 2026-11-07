// Steam Deck / desktop Linux frontend (spike): Kotlin/Native + SDL3 over the shared :core and the C chip engines.
// Needs deck/deps (SDL3 headers + a libSDL3.so to link against); see deck/tools/fetch-deps.sh.
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val sdlVersion = "3.2.18"
val deps = layout.projectDirectory.dir("deps")
val sdlInclude = deps.dir("SDL3-$sdlVersion/include")
val engineSrc = rootProject.layout.projectDirectory.dir("app/src/main/cpp")
val nativeOut = layout.buildDirectory.dir("native")

// The C engine is compiled with Kotlin/Native's own clang and sysroot (glibc 2.19), so the binary runs on any
// newer glibc (SteamOS) and the build needs no system compiler.
val konanDeps = File(System.getProperty("user.home"), ".konan/dependencies")
val buildEngine by tasks.registering(Exec::class) {
    val sources = listOf(
        engineSrc.file("chip_engine.c").asFile,
        engineSrc.file("huge_chip.c").asFile,
        engineSrc.file("huge_machine.c").asFile,
        engineSrc.file("third_party/binjgb/src/common.c").asFile,
        layout.projectDirectory.file("native/deck_audio.c").asFile,
        layout.projectDirectory.file("native/deck_draw.c").asFile,
    )
    inputs.files(sources, engineSrc.files("chip_engine.h", "huge_machine.h"), layout.projectDirectory.files("native/deck_audio.h", "native/deck_draw.h", "native/third_party/stb_truetype.h"))
    outputs.dir(nativeOut)
    doFirst { nativeOut.get().asFile.mkdirs() }
    val out = nativeOut.get().asFile
    val script = buildString {
        append("set -e\n")
        append("K='${konanDeps.absolutePath}'\n")
        append("CC=$(ls -d \"\$K\"/llvm-*/bin/clang | head -1)\n")
        append("AR=$(dirname \"\$CC\")/llvm-ar\n")
        append("SR=$(ls -d \"\$K\"/x86_64-unknown-linux-gnu-gcc-*-glibc-*/x86_64-unknown-linux-gnu/sysroot | head -1)\n")
        append("[ -x \"\$CC\" ] && [ -d \"\$SR\" ] || { echo 'Kotlin/Native toolchain not downloaded yet; build :core:compileKotlinLinuxX64 once' >&2; exit 1; }\n")
        for (src in sources) {
            append("\"\$CC\" --target=x86_64-unknown-linux-gnu --sysroot=\"\$SR\" -std=gnu11 -O2 -fPIC -DNDEBUG=1 -w ")
            append("-I'${engineSrc.asFile}' -I'${engineSrc.dir("third_party/binjgb/src").asFile}' -I'${sdlInclude.asFile}' -I'${layout.projectDirectory.dir("native").asFile}' ")
            append("-c '${src}' -o '${out}/${src.nameWithoutExtension}.o'\n")
        }
        append("rm -f '${out}/libdeck_native.a'\n")
        append("if [ -x \"\$AR\" ]; then \"\$AR\" rcs '${out}/libdeck_native.a' '${out}'/*.o; else ar rcs '${out}/libdeck_native.a' '${out}'/*.o; fi\n")
    }
    commandLine("bash", "-c", script)
}

kotlin {
    linuxX64 {
        compilations.getByName("main").cinterops {
            create("sdl3") {
                definitionFile.set(file("src/nativeInterop/cinterop/sdl3.def"))
                includeDirs(sdlInclude)
            }
            create("decknative") {
                definitionFile.set(file("src/nativeInterop/cinterop/decknative.def"))
                includeDirs(layout.projectDirectory.dir("native"))
                extraOpts("-libraryPath", nativeOut.get().asFile.absolutePath)
            }
        }
        binaries.executable {
            baseName = "thortracker"
            entryPoint = "org.thortracker.deck.main"
            // Links against deps/lib/libSDL3.so. The AppImage bundles that library in lib/ beside the executable, found
            // through the rpath; without it the system's SDL3 is used (SteamOS ships one).
            linkerOpts("-L${deps.dir("lib").asFile}", "-lSDL3", "-lm", "--allow-shlib-undefined", "-rpath=\$ORIGIN/lib")
        }
    }
    sourceSets {
        val linuxX64Main by getting {
            dependencies { implementation(project(":core")) }
        }
    }
}

tasks.matching { it.name == "cinteropDecknativeLinuxX64" }.configureEach { dependsOn(buildEngine) }

// The fonts and built-in tunings ship beside the executable (the program loads them from its own directory).
tasks.matching { it.name.startsWith("link") && it.name.endsWith("ExecutableLinuxX64") }.configureEach {
    doLast {
        val bin = outputs.files.files.first { it.isDirectory }
        copy { from(layout.projectDirectory.dir("fonts")); into(bin.resolve("fonts")) }
        copy { from(rootProject.layout.projectDirectory.dir("app/src/main/assets/tunings")); into(bin.resolve("tunings")) }
    }
}
