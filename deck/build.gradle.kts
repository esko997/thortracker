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
        engineSrc.file("nes/nes_n163.c").asFile,
        engineSrc.file("nes/nes_fds.c").asFile,
        engineSrc.file("nes/nes_s5b.c").asFile,
        engineSrc.file("nes/nes_epsm_unit.c").asFile,
        engineSrc.file("nes/nes_mmc5.c").asFile,
        engineSrc.file("nes/nes_mmc5_pcm.c").asFile,
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
        engineSrc.file("ws/ws_chip.c").asFile,
        engineSrc.file("ws/ws_machine.c").asFile,
        engineSrc.file("ws/ws_player.c").asFile,
        engineSrc.file("ws/ws_image.c").asFile,
        engineSrc.file("ws/ws_apu.c").asFile,
        engineSrc.file("ws/ws_vgm.c").asFile,
        engineSrc.file("saa/saa_chip.c").asFile,
        engineSrc.file("saa/saa_machine.c").asFile,
        engineSrc.file("saa/saa_player.c").asFile,
        engineSrc.file("saa/saa_image.c").asFile,
        engineSrc.file("saa/saa_vgm.c").asFile,
        engineSrc.file("saa/saa_core.c").asFile,
        engineSrc.file("cbm/cbm_chip.c").asFile,
        engineSrc.file("cbm/cbm_machine.c").asFile,
        engineSrc.file("cbm/cbm_player.c").asFile,
        engineSrc.file("cbm/cbm_tables.c").asFile,
        engineSrc.file("cbm/cbm_image.c").asFile,
        engineSrc.file("cbm/cbm_vic.c").asFile,
        engineSrc.file("cbm/cbm_ted.c").asFile,
        engineSrc.file("tia/tia_chip.c").asFile,
        engineSrc.file("tia/tia_machine.c").asFile,
        engineSrc.file("tia/tia_player.c").asFile,
        engineSrc.file("tia/tia_image.c").asFile,
        engineSrc.file("pokey/pokey_engine.c").asFile,
        engineSrc.file("pokey/pokey_machine.c").asFile,
        engineSrc.file("pokey/pokey_player.c").asFile,
        engineSrc.file("pokey/pokey_chip.c").asFile,
        engineSrc.file("pokey/pokey_image.c").asFile,
        engineSrc.file("pokey/pokey_vgm.c").asFile,
        engineSrc.file("ym2608_rhythm_rom.c").asFile,
        engineSrc.file("opn/opn_chip.c").asFile,
        engineSrc.file("opn/opn_machine.c").asFile,
        engineSrc.file("opn/opn_player.c").asFile,
        engineSrc.file("opn/opn_image.c").asFile,
        engineSrc.file("opn/opn_vgm.c").asFile,
        engineSrc.file("opm/opm_chip.c").asFile,
        engineSrc.file("opm/opm_machine.c").asFile,
        engineSrc.file("opm/opm_player.c").asFile,
        engineSrc.file("opm/opm_image.c").asFile,
        engineSrc.file("opm/opm_vgm.c").asFile,
        engineSrc.file("sms/sms_chip.c").asFile,
        engineSrc.file("sms/sms_machine.c").asFile,
        engineSrc.file("sms/sms_image.c").asFile,
        engineSrc.file("sms/sms_vgm.c").asFile,
        engineSrc.file("ay/ay_chip.c").asFile,
        engineSrc.file("ay/ay_machine.c").asFile,
        engineSrc.file("ay/ay_player.c").asFile,
        engineSrc.file("ay/ay_psg.c").asFile,
        engineSrc.file("ay/ay_image.c").asFile,
        engineSrc.file("ay/ay_vgm.c").asFile,
        engineSrc.file("pce/pce_chip.c").asFile,
        engineSrc.file("pce/pce_machine.c").asFile,
        engineSrc.file("pce/pce_player.c").asFile,
        engineSrc.file("pce/pce_image.c").asFile,
        engineSrc.file("pce/pce_vgm.c").asFile,
        engineSrc.file("scc/scc_chip.c").asFile,
        engineSrc.file("scc/scc_machine.c").asFile,
        engineSrc.file("scc/scc_player.c").asFile,
        engineSrc.file("scc/scc_image.c").asFile,
        engineSrc.file("scc/scc_vgm.c").asFile,
        engineSrc.file("scc/scc_k051649.c").asFile,
        layout.projectDirectory.file("native/deck_audio.c").asFile,
        layout.projectDirectory.file("native/deck_draw.c").asFile,
    )
    // ymfm (the YM2612, EPSM, OPN and OPNA): ym/ym_ymfm.cpp compiles ymfm_opn.cpp itself and instantiates the OPNA
    // templates nes/nes_epsm_ymfm.cpp and opn/opn_ymfm.cpp use; opm/opm_ymfm.cpp compiles ymfm_opm.cpp for the YM2151.
    // Mednafen's PSG (the PC Engine): pce/pce_psg.cpp compiles
    // third_party/mednafen-pce-psg/pce_psg.cpp itself against the shim.
    val cxxSources = listOf("sid/sid_resid.cpp", "ym/ym_ymfm.cpp", "nes/nes_epsm_ymfm.cpp", "opm/opm_ymfm.cpp", "opn/opn_ymfm.cpp", "third_party/ymfm/ymfm_adpcm.cpp",
        "third_party/ymfm/ymfm_ssg.cpp", "pce/pce_psg.cpp", "tia/tia_stella.cpp") +
        listOf("sid", "voice", "wave", "envelope", "filter", "dac", "extfilt", "pot").map { "third_party/resid-1.0/$it.cc" }
    val cxxFiles = cxxSources.map { engineSrc.file(it).asFile }
    val includeDirs = listOf(engineSrc.asFile) +
        listOf("third_party/binjgb/src", "nes", "sid", "opl3", "ym", "ws", "saa", "cbm", "pokey", "opn", "opm", "sms", "ay", "pce", "scc", "third_party/chips", "third_party/blip_buf",
            "third_party/resid-1.0", "third_party/nuked-opl3", "third_party/ymfm", "third_party/mednafen-pce-psg/shim",
            "tia", "third_party/stella-tia", "third_party/stella-tia/shim")
            .map { engineSrc.dir(it).asFile } +
        listOf(sdlInclude.asFile, layout.projectDirectory.dir("native").asFile)
    inputs.files(sources, cxxFiles, engineSrc.files("chip_engine.h", "chip_wav.h", "huge_machine.h", "nes/nes_machine.h", "nes/nes_image.h",
        "nes/nes_apu.h", "nes/nes_vrc6.h", "nes/nes_vrc7.h", "nes/nes_n163.h", "nes/nes_fds.h", "nes/nes_s5b.h", "nes/nes_epsm.h",
        "nes/nes_epsm_unit.h", "nes/nes_mmc5.h", "nes/nes_mmc5_pcm.h", "nes/nes_player.h",
        "generated/nes_player_image.h", "generated/nes_vrc6_player_image.h", "generated/nes_vrc7_player_image.h",
        "generated/nes_n163_1_player_image.h", "generated/nes_n163_2_player_image.h", "generated/nes_n163_3_player_image.h",
        "generated/nes_n163_4_player_image.h", "generated/nes_n163_5_player_image.h", "generated/nes_n163_6_player_image.h",
        "generated/nes_n163_7_player_image.h", "generated/nes_n163_8_player_image.h", "generated/nes_fds_player_image.h",
        "generated/nes_s5b_player_image.h", "generated/nes_mmc5_player_image.h", "generated/nes_epsm_player_image.h",
        "chip_opll.h", "third_party/emu2413/emu2413.h", "sid/sid_machine.h", "sid/sid_image.h", "sid/sid_resid.h",
        "generated/sid_player_image.h", "opl3/opl3_machine.h", "opl3/opl3_player.h", "opl3/opl3_image.h", "opl3/opl3_vgm.h", "opl3/opl3_export.h",
        "generated/opl3_tables.h", "third_party/nuked-opl3/opl3.h", "third_party/chips/m6502.h", "third_party/blip_buf/blip_buf.h",
        "vgm_gd3.h", "third_party/chips/z80.h", "ym/ym_image.h", "ym/ym_player.h", "ym/ym_xgm.h", "ym/ym_machine.h",
        "ws/ws_image.h", "ws/ws_player.h", "ws/ws_machine.h", "ws/ws_apu.h", "ws/ws_vgm.h", "ws/ws_export.h",
        "saa/saa_core.h", "saa/saa_image.h", "saa/saa_player.h", "saa/saa_machine.h", "saa/saa_vgm.h", "saa/saa_export.h",
        "generated/saa_tables.h",
        "cbm/cbm_image.h", "cbm/cbm_player.h", "cbm/cbm_machine.h", "cbm/cbm_vic.h", "cbm/cbm_ted.h",
        "tia/tia_image.h", "tia/tia_player.h", "tia/tia_machine.h", "tia/tia_stella.h",
        "pokey/pokey_chip.h", "pokey/pokey_image.h", "pokey/pokey_player.h", "pokey/pokey_machine.h", "pokey/pokey_vgm.h",
        "pokey/pokey_export.h",
        "ym2608_rhythm_rom.h", "opn/opn_image.h", "opn/opn_player.h", "opn/opn_ymfm.h", "opn/opn_machine.h", "opn/opn_vgm.h",
        "opn/opn_export.h",
        "ym/ym_psg.h", "ym/ym_ymfm.h", "ym/ym_vgm.h", "ym/ym_export.h", "generated/ym_tables.h", "generated/xgm2_driver_image.h",
        "opm/opm_image.h", "opm/opm_player.h", "opm/opm_machine.h", "opm/opm_ymfm.h", "opm/opm_vgm.h", "opm/opm_export.h",
        "sms/sms_image.h", "sms/sms_machine.h", "sms/sms_export.h", "generated/banjo_driver_image.h",
        "third_party/emu2413/emu2413.h",
        "ay/ay_image.h", "ay/ay_player.h", "ay/ay_psg.h", "ay/ay_machine.h", "ay/ay_vgm.h", "ay/ay_export.h", "generated/ay_tables.h",
        "pce/pce_image.h", "pce/pce_player.h", "pce/pce_machine.h", "pce/pce_psg.h", "pce/pce_vgm.h", "pce/pce_export.h",
        "scc/scc_k051649.h", "scc/scc_image.h", "scc/scc_player.h", "scc/scc_machine.h", "scc/scc_vgm.h", "scc/scc_export.h",
        "generated/scc_tables.h"),
        engineSrc.dir("third_party/ymfm").asFileTree,
        engineSrc.dir("third_party/resid-1.0").asFileTree.matching { include("*.h") },
        engineSrc.dir("third_party/mednafen-pce-psg").asFileTree,
        engineSrc.dir("third_party/stella-tia").asFileTree,
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
    // Feed the script through stdin: the growing engine list exceeds Linux's per-argument limit with bash -c.
    commandLine("bash", "-s")
    standardInput = script.byteInputStream()
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
                // ym2608_rhythm_rom.h: ym2608_rhythm_rom_install / ym2608_rhythm_rom_installed for the Deck's rhythm ROM;
                // opn/opn_export.h: the OPN / OPNA VGM export.
                includeDirs(layout.projectDirectory.dir("native"), engineSrc, engineSrc.dir("opl3"), engineSrc.dir("ym"), engineSrc.dir("nes"), engineSrc.dir("sms"),
                    engineSrc.dir("ay"), engineSrc.dir("pce"), engineSrc.dir("scc"), engineSrc.dir("opm"), engineSrc.dir("opn"), engineSrc.dir("pokey"), engineSrc.dir("ws"), engineSrc.dir("saa"))
                extraOpts("-libraryPath", nativeOut.get().asFile.absolutePath)
            }
        }
        binaries.executable {
            baseName = "thortracker"
            entryPoint = "org.thortracker.deck.main"
            // Links against deps/lib/libSDL3.so. The AppImage bundles that library in lib/ beside the executable, found
            // through the rpath; without it the system's SDL3 is used (SteamOS ships one).
            // libstdc++: reSID, ymfm, Mednafen's PSG and Stella's TIA (C++) inside libdeck_native.a; SteamOS ships the runtime (GCC 8.3 headers need GLIBCXX <= 3.4.25).
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
