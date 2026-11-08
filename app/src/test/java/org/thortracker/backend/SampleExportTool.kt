package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Songs
import org.thortracker.system.nes.N163Preset
import org.thortracker.system.nes.N163Presets
import org.thortracker.system.nes.N163Wave
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesN163System
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.nes
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.nes.NesVrc7System
import org.thortracker.system.opl3.Opl3System
import org.thortracker.system.sid.SidSystem
import org.thortracker.system.ym2612.YmSystem
import java.io.File

/** Host utility; sample generation is explicit, never a unit-test side effect. */
object SampleExportTool {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Supply an output directory" }
        val directory = File(args[0]).apply { mkdirs() }
        val song = Songs.demo()
        File(directory, "thor-groove.thor").writeText(ProjectCodec.encode(song), Charsets.UTF_8)
        File(directory, "thor-groove.uge").writeBytes(UgeExporter.export(song))
        // Every demo's engine image: the native WAV export test (app/src/test/cpp/wav_render_test.c) renders them.
        File(directory, "thor-groove.gbimg").writeBytes(ByteArray(song.system.engine.imageSize).also { song.system.engine.lower(song, it) })
        println("Exported ${song.title} to ${directory.absolutePath}")
        // The NES demo: project, both exports and the engine image (tools/audio/render_nes_image.sh renders it).
        val nes = NesSystem.demo()
        File(directory, "thor-quest.thor").writeText(ProjectCodec.encode(nes), Charsets.UTF_8)
        for (format in NesSystem.exports) File(directory, "thor-quest.${format.extension}").writeBytes(format.export(nes))
        File(directory, "thor-quest.nesimg").writeBytes(ByteArray(NesSystem.engine.imageSize).also { NesSystem.engine.lower(nes, it) })
        println("Exported ${nes.title} to ${directory.absolutePath}")
        // The NES + VRC6 demo, the same way (its image plays on "nes-famistudio-vrc6"; render_nes_image.sh picks it).
        val vrc6 = NesVrc6System.demo()
        File(directory, "saw-sunrise.thor").writeText(ProjectCodec.encode(vrc6), Charsets.UTF_8)
        for (format in NesVrc6System.exports) File(directory, "saw-sunrise.${format.extension}").writeBytes(format.export(vrc6))
        File(directory, "saw-sunrise.nesimg").writeBytes(ByteArray(NesVrc6System.engine.imageSize).also { NesVrc6System.engine.lower(vrc6, it) })
        println("Exported ${vrc6.title} to ${directory.absolutePath}")
        // The NES + VRC7 demo (its image plays on "nes-famistudio-vrc7"; render_nes_image.sh picks it).
        val vrc7 = NesVrc7System.demo()
        File(directory, "lantern-point.thor").writeText(ProjectCodec.encode(vrc7), Charsets.UTF_8)
        for (format in NesVrc7System.exports) File(directory, "lantern-point.${format.extension}").writeBytes(format.export(vrc7))
        File(directory, "lantern-point.nesimg").writeBytes(ByteArray(NesVrc7System.engine.imageSize).also { NesVrc7System.engine.lower(vrc7, it) })
        println("Exported ${vrc7.title} to ${directory.absolutePath}")
        // The NES + N163 demo for every channel count (wavetable-garden-<n>ch; each image plays on "nes-famistudio-n163-<n>").
        for (system in NesN163System.ALL) {
            val n163 = system.demo()
            val name = "wavetable-garden-${system.n163Channels}ch"
            File(directory, "$name.thor").writeText(ProjectCodec.encode(n163), Charsets.UTF_8)
            for (format in system.exports) File(directory, "$name.${format.extension}").writeBytes(format.export(n163))
            File(directory, "$name.nesimg").writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(n163, it) })
            println("Exported ${n163.title} (${system.name}) to ${directory.absolutePath}")
            // The shortest song on that build: one order of one row at speed 1, an N163 sine A-4 on N163 1
            // (n163-shortest-<n>ch; wav_render_test.c renders the one-row, one-tick edge case).
            val blank = system.blank()
            val bank = blank.nes.instruments.toMutableList()
            bank[0] = NesInstrument("Sine", chip = NesChip.N163, wave = N163Wave(waves = listOf(N163Presets.wave(N163Preset.SINE, 16, 0, 1))))
            val first = blank.patterns[0]
            val shortest = blank.copy(
                ticksPerRow = 1, orders = listOf(0), loopOrder = 0,
                patterns = listOf(first.copy(length = 1, tracks = first.tracks.mapIndexed { ch, rows ->
                    if (ch == NesRules.N163_FIRST) rows.toMutableList().also { it[0] = Cell(57, 1) } else rows
                })),
                sound = blank.nes.copy(instruments = bank),
            )
            File(directory, "n163-shortest-${system.n163Channels}ch.nesimg")
                .writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(shortest, it) })
        }
        // The SID demo: project, .sid/.prg/.sng and the engine image (tools/audio/render_sid_image.sh renders it).
        val sid = SidSystem.demo()
        File(directory, "raster-run.thor").writeText(ProjectCodec.encode(sid), Charsets.UTF_8)
        for (format in SidSystem.exports) File(directory, "raster-run.${format.extension}").writeBytes(format.export(sid))
        File(directory, "raster-run.sidimg").writeBytes(ByteArray(SidSystem.engine.imageSize).also { SidSystem.engine.lower(sid, it) })
        println("Exported ${sid.title} to ${directory.absolutePath}")
        // The OPL3 demo: project and engine image (its VGM needs the native player: tools/audio/render_opl3_image.sh
        // glass-harbour.o3img out.wav --vgm glass-harbour.vgm renders both).
        val opl3 = Opl3System.demo()
        File(directory, "glass-harbour.thor").writeText(ProjectCodec.encode(opl3), Charsets.UTF_8)
        File(directory, "glass-harbour.o3img").writeBytes(ByteArray(Opl3System.engine.imageSize).also { Opl3System.engine.lower(opl3, it) })
        println("Exported ${opl3.title} to ${directory.absolutePath}")
        // The Mega Drive demo: project and engine image (its VGM/XGM/XGC need the native player:
        // tools/audio/render_ym_image.sh neon-causeway.ymimg out.wav --vgm neon-causeway.vgm renders them).
        val ym = YmSystem.demo()
        File(directory, "neon-causeway.thor").writeText(ProjectCodec.encode(ym), Charsets.UTF_8)
        File(directory, "neon-causeway.ymimg").writeBytes(ByteArray(YmSystem.engine.imageSize).also { YmSystem.engine.lower(ym, it) })
        println("Exported ${ym.title} to ${directory.absolutePath}")
    }
}
