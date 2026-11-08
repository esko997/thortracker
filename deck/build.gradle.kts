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

// The C engines are compiled with Kotlin/Native's own clang and sysroot (glibc 2.19), so the binary runs on any
// newer glibc (SteamOS) and the build needs no system compiler. reSID (the SID chip) is C++17: its sources and the C
// interface over it (sid/sid_resid.cpp) build with the same toolchain's clang++ against the sysroot's GCC 8.3
// libstdc++, which the executable links (-lstdc++); everything the hosts call stays C.
val konanDeps = File(System.getProperty("user.home"), ".konan/dependencies")
val buildEngine by tasks.registering(Exec::class) {
    val sources = listOf(
        engineSrc.file("chip_engine.c").asFile,
        engineSrc.file("chip_wav.c").asFile,
        engineSrc.file("cpu6502.c").asFile,
        engineSrc.file("huge_chip.c").asFile,
        engineSrc.file("huge_machine.c").asFile,
        engineSrc.file("third_party/binjgb/src/common.c").asFile,
        engineSrc.file("nes/nes_chip.c").asFile,
        engineSrc.file("nes/nes_machine.c").asFile,
        engineSrc.file("nes/nes_image.c").asFile,
        engineSrc.file("nes/nes_apu.c").asFile,
        engineSrc.file("nes/nes_vrc6.c").asFile,
        engineSrc.file("nes/nes_vrc7.c").asFile,
        engineSrc.file("third_party/emu2413/emu2413.c").asFile,
        engineSrc.file("chip_opll.c").asFile,
        engineSrc.file("nes/nes_players.c").asFile,
        engineSrc.file("third_party/blip_buf/blip_buf.c").asFile,
        engineSrc.file("sid/sid_chip.c").asFile,
        engineSrc.file("sid/sid_machine.c").asFile,
        engineSrc.file("sid/sid_image.c").asFile,
        engineSrc.file("opl3/opl3_chip.c").asFile,
        engineSrc.file("opl3/opl3_machine.c").asFile,
        engineSrc.file("opl3/opl3_player.c").asFile,
        engineSrc.file("opl3/opl3_image.c").asFile,
        engineSrc.file("opl3/opl3_vgm.c").asFile,
        engineSrc.file("third_party/nuked-opl3/opl3.c").asFile,
        engineSrc.file("vgm_gd3.c").asFile,
        engineSrc.file("cpuz80.c").asFile,
        engineSrc.file("ym/ym_chip.c").asFile,
        engineSrc.file("ym/ym_machine.c").asFile,
        engineSrc.file("ym/ym_player.c").asFile,
        engineSrc.file("ym/ym_image.c").asFile,
        engineSrc.file("ym/ym_xgm.c").asFile,
        engineSrc.file("ym/ym_vgm.c").asFile,
        engineSrc.file("ym/ym_psg.c").asFile,
        engineSrc.file("vsu/vsu_engine.c").asFile,
        engineSrc.file("vsu/vsu_machine.c").asFile,
        engineSrc.file("vsu/vsu_player.c").asFile,
        engineSrc.file("vsu/vsu_chip.c").asFile,
        engineSrc.file("vsu/vsu_image.c").asFile,
        engineSrc.file("vsu/vsu_vgm.c").asFile,
        layout.projectDirectory.file("native/deck_audio.c").asFile,
        layout.projectDirectory.file("native/deck_draw.c").asFile,
    )
    // ymfm (the YM2612): ym/ym_ymfm.cpp compiles ymfm_opn.cpp itself.
    val cxxSources = listOf("sid/sid_resid.cpp", "ym/ym_ymfm.cpp", "third_party/ymfm/ymfm_adpcm.cpp", "third_party/ymfm/ymfm_ssg.cpp") +
        listOf("sid", "voice", "wave", "envelope", "filter", "dac", "extfilt", "pot").map { "third_party/resid-1.0/$it.cc" }
    val cxxFiles = cxxSources.map { engineSrc.file(it).asFile }
    val includeDirs = listOf(engineSrc.asFile) +
        listOf("third_party/binjgb/src", "nes", "sid", "opl3", "ym", "vsu", "third_party/chips", "third_party/blip_buf",
            "third_party/resid-1.0", "third_party/nuked-opl3", "third_party/ymfm")
            .map { engineSrc.dir(it).asFile } +
        listOf(sdlInclude.asFile, layout.projectDirectory.dir("native").asFile)
    inputs.files(sources, cxxFiles, engineSrc.files("chip_engine.h", "chip_wav.h", "huge_machine.h", "nes/nes_machine.h", "nes/nes_image.h",
        "nes/nes_apu.h", "nes/nes_vrc6.h", "nes/nes_vrc7.h", "nes/nes_player.h", "generated/nes_player_image.h",
        "generated/nes_vrc6_player_image.h", "generated/nes_vrc7_player_image.h", "third_party/emu2413/emu2413.h",
        "sid/sid_machine.h", "sid/sid_image.h", "sid/sid_resid.h",
        "generated/sid_player_image.h", "opl3/opl3_machine.h", "opl3/opl3_player.h", "opl3/opl3_image.h", "opl3/opl3_vgm.h", "opl3/opl3_export.h",
        "generated/opl3_tables.h", "third_party/nuked-opl3/opl3.h", "third_party/chips/m6502.h", "third_party/blip_buf/blip_buf.h",
        "vgm_gd3.h", "third_party/chips/z80.h", "ym/ym_image.h", "ym/ym_player.h", "ym/ym_xgm.h", "ym/ym_machine.h",
        "ym/ym_psg.h", "ym/ym_ymfm.h", "ym/ym_vgm.h", "ym/ym_export.h", "generated/ym_tables.h", "generated/xgm_driver_image.h",
        "vsu/vsu_chip.h", "vsu/vsu_image.h", "vsu/vsu_player.h", "vsu/vsu_machine.h", "vsu/vsu_vgm.h", "vsu/vsu_export.h"),
        engineSrc.dir("third_party/ymfm").asFileTree,
        engineSrc.dir("third_party/resid-1.0").asFileTree.matching { include("*.h") },
        layout.projectDirectory.files("native/deck_audio.h", "native/deck_draw.h", "native/third_party/stb_truetype.h"))
    outputs.dir(nativeOut)
    doFirst { nativeOut.get().asFile.mkdirs() }
    val out = nativeOut.get().asFile
    val script = buildString {
        append("set -e\n")
        append("K='${konanDeps.absolutePath}'\n")
        append("CC=$(ls -d \"\$K\"/llvm-*/bin/clang | head -1)\n")
        append("CXX=$(dirname \"\$CC\")/clang++\n")
        append("AR=$(dirname \"\$CC\")/llvm-ar\n")
        append("SR=$(ls -d \"\$K\"/x86_64-unknown-linux-gnu-gcc-*-glibc-*/x86_64-unknown-linux-gnu/sysroot | head -1)\n")
        append("GT=$(dirname \"$(dirname \"\$SR\")\")\n")
        append("[ -x \"\$CC\" ] && [ -x \"\$CXX\" ] && [ -d \"\$SR\" ] || { echo 'Kotlin/Native toolchain not downloaded yet; build :core:compileKotlinLinuxX64 once' >&2; exit 1; }\n")
        val includes = includeDirs.joinToString(" ", postfix = " ") { "-I'$it'" }
        for (src in sources) {
            append("\"\$CC\" --target=x86_64-unknown-linux-gnu --sysroot=\"\$SR\" -std=gnu11 -O2 -fPIC -DNDEBUG=1 -w ")
            append(includes)
            append("-c '${src}' -o '${out}/${src.nameWithoutExtension}.o'\n")
        }
        for (src in cxxFiles) {
            append("\"\$CXX\" --target=x86_64-unknown-linux-gnu --sysroot=\"\$SR\" --gcc-toolchain=\"\$GT\" -std=c++17 -O2 -fPIC -DNDEBUG=1 -w ")
            append(includes)
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
                includeDirs(layout.projectDirectory.dir("native"), engineSrc, engineSrc.dir("opl3"), engineSrc.dir("ym"), engineSrc.dir("vsu"))
                extraOpts("-libraryPath", nativeOut.get().asFile.absolutePath)
            }
        }
        binaries.executable {
            baseName = "thortracker"
            entryPoint = "org.thortracker.deck.main"
            // Links against deps/lib/libSDL3.so. The AppImage bundles that library in lib/ beside the executable, found
            // through the rpath; without it the system's SDL3 is used (SteamOS ships one).
            // libstdc++: reSID and ymfm (C++) inside libdeck_native.a; SteamOS ships the runtime (GCC 8.3 headers need GLIBCXX <= 3.4.25).
            linkerOpts("-L${deps.dir("lib").asFile}", "-lSDL3", "-lstdc++", "-lm", "--allow-shlib-undefined", "-rpath=\$ORIGIN/lib")
        }
    }
    sourceSets {
        val linuxX64Main by getting {
            dependencies { implementation(project(":core")) }
        }
    }
}

// cinterop embeds libdeck_native.a (staticLibraries) into its klib: it must rerun when the engine changes,
// not only run after it, or the executable links a stale engine.
tasks.matching { it.name == "cinteropDecknativeLinuxX64" }.configureEach {
    dependsOn(buildEngine)
    inputs.files(buildEngine)
}

// The fonts and built-in tunings ship beside the executable (the program loads them from its own directory).
tasks.matching { it.name.startsWith("link") && it.name.endsWith("ExecutableLinuxX64") }.configureEach {
    doLast {
        val bin = outputs.files.files.first { it.isDirectory }
        copy { from(layout.projectDirectory.dir("fonts")); into(bin.resolve("fonts")) }
        copy { from(rootProject.layout.projectDirectory.dir("app/src/main/assets/tunings")); into(bin.resolve("tunings")) }
    }
}
