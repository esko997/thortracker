package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Songs
import org.thortracker.system.nes.FdsPresets
import org.thortracker.system.nes.FdsSound
import org.thortracker.system.nes.WavePreset
import org.thortracker.system.nes.N163Presets
import org.thortracker.system.nes.N163Wave
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesFdsSystem
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesN163System
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesS5bSystem
import org.thortracker.system.nes.nes
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.nes.NesVrc7System
import org.thortracker.system.nes.Vrc7Patch
import org.thortracker.system.opl3.Opl3System
import org.thortracker.system.sid.SidSystem
import org.thortracker.system.sms.SmsExport
import org.thortracker.system.sms.SmsSystem
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
        // The NES + FDS demo (its image plays on "nes-famistudio-fds"; the native WAV export test renders side-b.nesimg).
        val fds = NesFdsSystem.demo()
        File(directory, "side-b.thor").writeText(ProjectCodec.encode(fds), Charsets.UTF_8)
        for (format in NesFdsSystem.exports) File(directory, "side-b.${format.extension}").writeBytes(format.export(fds))
        File(directory, "side-b.nesimg").writeBytes(ByteArray(NesFdsSystem.engine.imageSize).also { NesFdsSystem.engine.lower(fds, it) })
        println("Exported ${fds.title} to ${directory.absolutePath}")
        // The shortest FDS song: one order of one row at speed 1, an FDS sine A-4 (FamiStudio's default FDS sound) on the
        // FDS channel (fds-shortest; the one-row, one-tick edge case on "nes-famistudio-fds").
        val fdsBlank = NesFdsSystem.blank()
        val fdsBank = fdsBlank.nes.instruments.toMutableList()
        fdsBank[0] = NesInstrument("Sine", chip = NesChip.FDS, volume = NesEnvelope(listOf(15)))
        val fdsFirst = fdsBlank.patterns[0]
        val fdsShortest = fdsBlank.copy(
            ticksPerRow = 1, orders = listOf(0), loopOrder = 0,
            patterns = listOf(fdsFirst.copy(length = 1, tracks = fdsFirst.tracks.mapIndexed { ch, rows ->
                if (ch == NesRules.FDS) rows.toMutableList().also { it[0] = Cell(57, 1) } else rows
            })),
            sound = fdsBlank.nes.copy(instruments = fdsBank),
        )
        File(directory, "fds-shortest.nesimg")
            .writeBytes(ByteArray(NesFdsSystem.engine.imageSize).also { NesFdsSystem.engine.lower(fdsShortest, it) })
        // The golden FDS copy/loop cases as native images: the first legato note must select the original slot without
        // replacing the loop's held modulation with the different intro values.
        for (autoMod in listOf(false, true)) {
            val bank = fdsBlank.nes.instruments.toMutableList()
            bank[0] = NesInstrument("Original", chip = NesChip.FDS, volume = NesEnvelope(listOf(14)),
                fds = FdsSound(modulation = FdsPresets.modulation(WavePreset.TRIANGLE),
                    modSpeed = 0x100, modDepth = 12, modDelay = 3, autoMod = autoMod, autoModNumer = 1, autoModDenom = 2))
            bank[1] = bank[0].copy(name = "Renamed copy")
            fun pattern(name: String, cells: Map<Int, Cell>) = fdsFirst.copy(name = name, length = 8,
                tracks = fdsFirst.tracks.mapIndexed { ch, rows ->
                    if (ch == NesRules.FDS) rows.mapIndexed { r, cell -> cells[r] ?: cell } else rows
                })
            val intro = mutableMapOf(0 to Cell(48, 1), 3 to Cell(Cell.EMPTY, 0, NesRules.FX_FDS_MOD_DEPTH, 8))
            val loop = mutableMapOf(0 to Cell(60), 4 to Cell(64, 2), 7 to Cell(Cell.EMPTY, 0, NesRules.FX_FDS_MOD_DEPTH, 24))
            if (!autoMod) {
                intro[1] = Cell(Cell.EMPTY, 0, NesRules.FX_FDS_MOD_SPEED_HI, 1)
                intro[2] = Cell(Cell.EMPTY, 0, NesRules.FX_FDS_MOD_SPEED_LO, 0x20)
                loop[5] = Cell(Cell.EMPTY, 0, NesRules.FX_FDS_MOD_SPEED_HI, 2)
                loop[6] = Cell(Cell.EMPTY, 0, NesRules.FX_FDS_MOD_SPEED_LO, 0x60)
            }
            val copies = fdsBlank.copy(title = if (autoMod) "FDS auto copies" else "FDS copies", ticksPerRow = 3, loopOrder = 1,
                patterns = listOf(pattern("Intro", intro), pattern("Loop", loop)), orders = listOf(0, 1),
                sound = fdsBlank.nes.copy(instruments = bank))
            val name = if (autoMod) "fds-auto-copies" else "fds-copies"
            File(directory, "$name.nesimg")
                .writeBytes(ByteArray(NesFdsSystem.engine.imageSize).also { NesFdsSystem.engine.lower(copies, it) })
        }
        // Held identical copies: native live edits must follow the played bank slot, not an equal earlier slot.
        val copyBase = NesVrc7System.blank()
        val copyBank = copyBase.nes.instruments.toMutableList()
        copyBank[0] = NesInstrument("Preset original", chip = NesChip.VRC7,
            volume = NesEnvelope(listOf(15, 12, 9, 6), loop = 3), fm = Vrc7Patch().withPreset(1))
        copyBank[1] = copyBank[0].copy(name = "Preset copy")
        copyBank[2] = copyBank[0].copy(name = "Unused custom original",
            fm = Vrc7Patch(0, listOf(0x21, 0x61, 0x1A, 0x07, 0xF0, 0x80, 0x0F, 0x26)))
        copyBank[3] = copyBank[2].copy(name = "Held custom copy")
        val copyPattern = copyBase.patterns[0].copy(tracks = copyBase.patterns[0].tracks.mapIndexed { ch, rows ->
            rows.toMutableList().also {
                when (ch) {
                    NesRules.VRC7_FM1 -> it[0] = Cell(57, 1)
                    NesRules.VRC7_FM1 + 1 -> it[0] = Cell(64, 2)
                    NesRules.VRC7_FM1 + 2 -> it[0] = Cell(60, 4)
                }
            }
        })
        val copies = copyBase.copy(patterns = listOf(copyPattern), orders = listOf(0), loopOrder = 0,
            sound = copyBase.nes.copy(instruments = copyBank))
        File(directory, "vrc7-held-copies.nesimg")
            .writeBytes(ByteArray(NesVrc7System.engine.imageSize).also { NesVrc7System.engine.lower(copies, it) })
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
            bank[0] = NesInstrument("Sine", chip = NesChip.N163, wave = N163Wave(waves = listOf(N163Presets.sine(16))))
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
        // The NES + Sunsoft 5B demo (its image plays on "nes-famistudio-s5b"; render_nes_image.sh picks it).
        val s5b = NesS5bSystem.demo()
        File(directory, "copper-kite.thor").writeText(ProjectCodec.encode(s5b), Charsets.UTF_8)
        for (format in NesS5bSystem.exports) File(directory, "copper-kite.${format.extension}").writeBytes(format.export(s5b))
        File(directory, "copper-kite.nesimg").writeBytes(ByteArray(NesS5bSystem.engine.imageSize).also { NesS5bSystem.engine.lower(s5b, it) })
        println("Exported ${s5b.title} to ${directory.absolutePath}")
        // The shortest 5B song: one order of one row at speed 1, a plain 5B tone A-4 on 5B Square 1
        // (s5b-shortest; wav_render_test.c renders the one-row, one-tick edge case).
        val s5bBlank = NesS5bSystem.blank()
        val s5bBank = s5bBlank.nes.instruments.toMutableList()
        s5bBank[0] = NesInstrument("Tone", chip = NesChip.S5B, volume = NesEnvelope(listOf(15)))
        val s5bFirst = s5bBlank.patterns[0]
        val s5bShortest = s5bBlank.copy(
            ticksPerRow = 1, orders = listOf(0), loopOrder = 0,
            patterns = listOf(s5bFirst.copy(length = 1, tracks = s5bFirst.tracks.mapIndexed { ch, rows ->
                if (ch == NesRules.S5B_SQUARE1) rows.toMutableList().also { it[0] = Cell(57, 1) } else rows
            })),
            sound = s5bBlank.nes.copy(instruments = s5bBank),
        )
        File(directory, "s5b-shortest.nesimg")
            .writeBytes(ByteArray(NesS5bSystem.engine.imageSize).also { NesS5bSystem.engine.lower(s5bShortest, it) })
        // Identical manual-period copies (core's golden s5b_manual_copies): Square 1 switches to the renamed copy while
        // Square 2's manual saw holds the shared period; the switch loads the copy's own slot ($5F) keeping that period.
        val mcBase = NesS5bSystem.blank()
        val mcBank = mcBase.nes.instruments.toMutableList()
        val tremolo = NesInstrument("Manual tremolo", volume = NesEnvelope(listOf(13)), chip = NesChip.S5B,
            s5b = S5bEnvelope(S5bShape.TRIANGLE, autoPitch = false, period = 900))
        mcBank[0] = tremolo
        mcBank[1] = tremolo.copy(name = "Manual tremolo, renamed")
        mcBank[2] = NesInstrument("Manual saw", chip = NesChip.S5B, s5b = S5bEnvelope(S5bShape.SAW_DOWN, autoPitch = false, period = 2000))
        mcBank[3] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(1)))
        val sq1 = NesRules.S5B_SQUARE1
        fun mcPattern(name: String, cells: Map<Pair<Int, Int>, Cell>) = mcBase.patterns[0].let { p ->
            p.copy(name = name, length = 16, tracks = p.tracks.mapIndexed { ch, rows ->
                rows.mapIndexed { r, cell -> cells[ch to r] ?: cell }
            })
        }
        val mcA = mcPattern("Manual copies A", mapOf(
            (sq1 to 0) to Cell(48, 1), // C-4, the original
            (sq1 + 1 to 4) to Cell(40, 3), // E-3, Square 2's manual saw: period 2000
            (sq1 to 8) to Cell(52, 2), // E-4, the renamed copy with an attack
            (sq1 + 2 to 10) to Cell(Cell.EMPTY, 0, NesRules.FX_ENV_PERIOD_LO, 0x40),
            (sq1 to 12) to Cell(55, 0), // G-4 legato
            (0 to 0) to Cell(52, 4),
        ))
        val mcB = mcPattern("Manual copies B", mapOf(
            (sq1 to 0) to Cell(60, 0), // C-5 legato
            (sq1 + 1 to 4) to Cell(38, 3), // D-3, the saw reloads 2000
            (sq1 to 8) to Cell(64, 1), // E-5, back to the original with an attack
            (sq1 to 12) to Cell(62, 0), // D-5 legato
            (0 to 0) to Cell(57, 4),
        ))
        val manualCopies = mcBase.copy(title = "5B manual copies", ticksPerRow = 3, loopOrder = 1,
            patterns = listOf(mcA, mcB), orders = listOf(0, 1), sound = mcBase.nes.copy(instruments = mcBank))
        File(directory, "s5b-manual-copies.nesimg")
            .writeBytes(ByteArray(NesS5bSystem.engine.imageSize).also { NesS5bSystem.engine.lower(manualCopies, it) })
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
        // The Master System demos, one per layout: project, the Banjo sources (.asm WLA-DX, .s SDAS) and the engine
        // image (its VGM needs the native machine: tools/audio/render_sms_image.sh harbour-lights-sms-psg.smsimg out.wav
        // --vgm out.vgm renders both).
        for (sms in SmsSystem.ALL) {
            val song = sms.demo()
            val base = "harbour-lights-${sms.id}"
            File(directory, "$base.thor").writeText(ProjectCodec.encode(song), Charsets.UTF_8)
            File(directory, "$base.asm").writeBytes(SmsExport.export(song, SmsExport.Format.WLA_DX))
            File(directory, "$base.s").writeBytes(SmsExport.export(song, SmsExport.Format.SDAS))
            File(directory, "$base.smsimg").writeBytes(ByteArray(sms.engine.imageSize).also { sms.engine.lower(song, it) })
            val first = song.patterns.first()
            val shortest = song.copy(
                ticksPerRow = 1, orders = listOf(0), loopOrder = 0,
                patterns = listOf(first.copy(length = 1, tracks = first.tracks.mapIndexed { ch, rows ->
                    rows.mapIndexed { row, _ -> if (ch == 0 && row == 0) Cell(57, 1) else Cell() }
                })),
            )
            File(directory, "sms-shortest-${sms.id}.smsimg")
                .writeBytes(ByteArray(sms.engine.imageSize).also { sms.engine.lower(shortest, it) })
            println("Exported ${song.title} (${sms.name}) to ${directory.absolutePath}")
        }
    }
}
