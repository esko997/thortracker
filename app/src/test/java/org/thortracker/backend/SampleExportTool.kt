package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Songs
import org.thortracker.system.cbm.CbmChip
import org.thortracker.system.cbm.CbmDemoSong
import org.thortracker.system.cbm.TedSystem
import org.thortracker.system.cbm.VicSystem
import org.thortracker.system.gba.GbaSystem
import org.thortracker.system.nes.EpsmPatch
import org.thortracker.system.fm.Ym2608RhythmRom
import org.thortracker.system.nes.FdsPresets
import org.thortracker.system.nes.FdsSound
import org.thortracker.system.nes.WavePreset
import org.thortracker.system.nes.N163Presets
import org.thortracker.system.nes.N163Wave
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEpsmDemoSong
import org.thortracker.system.nes.NesEpsmSystem
import org.thortracker.system.nes.NesFdsSystem
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesMmc5DemoSong
import org.thortracker.system.nes.NesMmc5System
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
import org.thortracker.system.saa.CmsSystem
import org.thortracker.system.saa.SaaDemoSong
import org.thortracker.system.saa.SaaVariant
import org.thortracker.system.saa.SamCoupeSystem
import org.thortracker.system.pokey.PokeyDemoSong
import org.thortracker.system.pokey.PokeySystem
import org.thortracker.system.opn.OpnChip
import org.thortracker.system.opn.OpnDemoSong
import org.thortracker.system.opn.OpnSystem
import org.thortracker.system.opn.OpnaSystem
import org.thortracker.system.pce.PceDemoSong
import org.thortracker.system.pce.PceSystem
import org.thortracker.system.scc.SccDemoSong
import org.thortracker.system.scc.SccPlusSystem
import org.thortracker.system.scc.SccSystem
import org.thortracker.system.scc.SccVariant
import org.thortracker.system.opm.OpmDemoSong
import org.thortracker.system.opm.OpmSystem
import org.thortracker.system.sid.SidSystem
import org.thortracker.system.ws.WsDemoSong
import org.thortracker.system.ws.WsSystem
import org.thortracker.system.ws.WscSystem
import org.thortracker.system.vsu.VsuDemoSong
import org.thortracker.system.vsu.VsuSystem
import org.thortracker.system.tia.TiaDemoSong
import org.thortracker.system.tia.TiaNtscSystem
import org.thortracker.system.tia.TiaPalSystem
import org.thortracker.system.ym2612.YmDemoSong
import org.thortracker.system.sms.SmsExport
import org.thortracker.system.sms.SmsSystem
import org.thortracker.system.ym2612.YmSystem
import org.thortracker.system.ay.AyVariant
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
        val gba = GbaSystem.demo()
        File(directory, "gba-demo.thor").writeText(ProjectCodec.encode(gba), Charsets.UTF_8)
        File(directory, "gba-demo.xm").writeBytes(GbaXmExporter.export(gba))
        File(directory, "gba-demo.gbaimg").writeBytes(ByteArray(GbaSystem.engine.imageSize).also { GbaSystem.engine.lower(gba, it) })
        val gbaShortest = GbaSystem.blank().let { base ->
            val pattern = base.patterns[0]
            base.copy(ticksPerRow = 1, patterns = listOf(pattern.copy(length = 1,
                tracks = pattern.tracks.mapIndexed { channel, cells -> cells.mapIndexed { row, cell ->
                    if (channel == 0 && row == 0) Cell(57, 1) else cell
                } })))
        }
        File(directory, "gba-shortest.gbaimg").writeBytes(ByteArray(GbaSystem.engine.imageSize).also { GbaSystem.engine.lower(gbaShortest, it) })
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
        // The NES + MMC5 demo (its image plays on "nes-famistudio-mmc5"), and the shortest valid MMC5 song (one one-row
        // order at speed 1, A-4 on MMC5 Pulse 1; mmc5-shortest, the one-row, one-tick edge case wav_render_test.c renders).
        val mmc5 = NesMmc5System.demo()
        File(directory, "tandem-skies.thor").writeText(ProjectCodec.encode(mmc5), Charsets.UTF_8)
        for (format in NesMmc5System.exports) File(directory, "tandem-skies.${format.extension}").writeBytes(format.export(mmc5))
        File(directory, "tandem-skies.nesimg").writeBytes(ByteArray(NesMmc5System.engine.imageSize).also { NesMmc5System.engine.lower(mmc5, it) })
        val mmc5Shortest = NesMmc5DemoSong.shortest()
        check(NesMmc5System.errors(mmc5Shortest).isEmpty()) { "the shortest MMC5 song is invalid: ${NesMmc5System.errors(mmc5Shortest)}" }
        File(directory, "mmc5-shortest.nesimg")
            .writeBytes(ByteArray(NesMmc5System.engine.imageSize).also { NesMmc5System.engine.lower(mmc5Shortest, it) })
        println("Exported ${mmc5.title} and the shortest MMC5 song to ${directory.absolutePath}")
        // The MMC5 PCM lane fixtures (the image extension of nes_mmc5_pcm.h after the seven-channel image): "Sample
        // Courier" (project and engine image only: .nsf and FamiStudio text refuse a playing PCM lane) and the shortest PCM
        // song (one one-row order at speed 1, the looping PCM bass on A-2; mmc5-pcm-shortest). The native tests
        // (nes_mmc5_pcm_test.c) and wav_render_test.c render both.
        val courier = NesMmc5DemoSong.pcmDemo()
        check(NesMmc5System.errors(courier).isEmpty()) { "the PCM fixture is invalid: ${NesMmc5System.errors(courier)}" }
        File(directory, "sample-courier.thor").writeText(ProjectCodec.encode(courier), Charsets.UTF_8)
        File(directory, "sample-courier.nesimg").writeBytes(ByteArray(NesMmc5System.engine.imageSize).also { NesMmc5System.engine.lower(courier, it) })
        val pcmShortest = NesMmc5DemoSong.pcmShortest()
        check(NesMmc5System.errors(pcmShortest).isEmpty()) { "the shortest PCM song is invalid: ${NesMmc5System.errors(pcmShortest)}" }
        File(directory, "mmc5-pcm-shortest.nesimg")
            .writeBytes(ByteArray(NesMmc5System.engine.imageSize).also { NesMmc5System.engine.lower(pcmShortest, it) })
        println("Exported ${courier.title} and the shortest MMC5 PCM song to ${directory.absolutePath}")
        // The NES + EPSM demo, the same way (its image plays on "nes-famistudio-epsm"; it has no drums, so it needs no
        // rhythm ROM), and the shortest valid EPSM song (one one-row order at speed 1, FM 1 holding A-4, looping to order
        // 0) whose image the native WAV export test renders through loops.
        val epsm = NesEpsmSystem.demo()
        File(directory, "expansion-port.thor").writeText(ProjectCodec.encode(epsm), Charsets.UTF_8)
        for (format in NesEpsmSystem.exports) File(directory, "expansion-port.${format.extension}").writeBytes(format.export(epsm))
        File(directory, "expansion-port.nesimg").writeBytes(ByteArray(NesEpsmSystem.engine.imageSize).also { NesEpsmSystem.engine.lower(epsm, it) })
        val shortest = NesEpsmDemoSong.shortest()
        check(NesEpsmSystem.errors(shortest).isEmpty()) { "the shortest EPSM song is invalid: ${NesEpsmSystem.errors(shortest)}" }
        File(directory, "epsm-shortest.nesimg").writeBytes(ByteArray(NesEpsmSystem.engine.imageSize).also { NesEpsmSystem.engine.lower(shortest, it) })
        println("Exported ${epsm.title} and the shortest EPSM song to ${directory.absolutePath}")
        // Identical EPSM copies (core's golden epsm_copies): FM, manual-period SSG and drum copies with legato loop entries
        // on the copies; FM and SSG only here (no drum notes), so the image plays without the rhythm ROM. The copy loads
        // keep their own slots (FM: `$80`, SSG: `$5F` with the keep-period bit) for native live-edit checks.
        val ecBase = NesEpsmSystem.blank()
        val ecBank = ecBase.nes.instruments.toMutableList()
        val ecLead = NesInstrument("FM lead", volume = NesEnvelope(listOf(15, 13, 12), loop = 2), chip = NesChip.EPSM,
            epsm = EpsmPatch(algorithm = 4, feedback = 5))
        val ecPad = NesInstrument("SSG tremolo", volume = NesEnvelope(listOf(13)), chip = NesChip.EPSM,
            s5b = S5bEnvelope(S5bShape.TRIANGLE, autoPitch = false, period = 4000))
        ecBank[0] = ecLead
        ecBank[1] = ecLead.copy(name = "FM lead, renamed")
        ecBank[2] = ecPad
        ecBank[3] = ecPad.copy(name = "SSG tremolo, renamed")
        fun ecPattern(name: String, cells: Map<Pair<Int, Int>, Cell>) = ecBase.patterns[0].let { p ->
            p.copy(name = name, length = 16, tracks = p.tracks.mapIndexed { ch, rows -> rows.mapIndexed { r, cell -> cells[ch to r] ?: cell } })
        }
        val fm1 = NesRules.EPSM_FM1
        val ssg1 = NesRules.EPSM_SSG1
        val ecA = ecPattern("EPSM copies A", mapOf(
            (fm1 to 0) to Cell(48, 1), (fm1 to 4) to Cell(52, 2), (fm1 to 8) to Cell(55, 0),
            (ssg1 to 0) to Cell(48, 3), (ssg1 to 4) to Cell(52, 4), (ssg1 to 8) to Cell(55, 0),
        ))
        val ecB = ecPattern("EPSM copies B", mapOf(
            (fm1 to 0) to Cell(60, 0), (fm1 to 8) to Cell(64, 1), (fm1 to 12) to Cell(62, 0),
            (ssg1 to 0) to Cell(60, 0), (ssg1 to 8) to Cell(64, 3), (ssg1 to 12) to Cell(62, 0),
        ))
        val epsmCopies = ecBase.copy(title = "EPSM copies", ticksPerRow = 3, loopOrder = 1,
            patterns = listOf(ecA, ecB), orders = listOf(0, 1), sound = ecBase.nes.copy(instruments = ecBank))
        check(NesEpsmSystem.errors(epsmCopies).isEmpty()) { "the EPSM copies song is invalid: ${NesEpsmSystem.errors(epsmCopies)}" }
        File(directory, "epsm-copies.nesimg")
            .writeBytes(ByteArray(NesEpsmSystem.engine.imageSize).also { NesEpsmSystem.engine.lower(epsmCopies, it) })
        // EPSM drums only with the user's genuine YM2608 rhythm ROM (THOR_RHYTHM_ROM, checked as the app checks an
        // import; never bundled or copied): all six drums, left/right/both pans and their first volumes, one order of 16
        // rows at speed 3 looping. Without the ROM nothing is written, as the app refuses to play drums without it.
        val romPath = System.getenv("THOR_RHYTHM_ROM")
        if (romPath == null) {
            println("THOR_RHYTHM_ROM not set: epsm-rhythm.nesimg not exported (the drums need the YM2608 rhythm ROM)")
        } else {
            val problem = Ym2608RhythmRom.problem(File(romPath).readBytes())
            check(problem == null) { "THOR_RHYTHM_ROM: $problem" }
            Ym2608RhythmRom.markInstalled() // the native renderer installs the same file before playing this image
            val drBase = NesEpsmSystem.blank()
            val drBank = drBase.nes.instruments.toMutableList()
            drBank[0] = NesInstrument("Drum both", volume = NesEnvelope(listOf(15)), chip = NesChip.EPSM)
            drBank[1] = NesInstrument("Drum left", volume = NesEnvelope(listOf(12)), chip = NesChip.EPSM, epsm = EpsmPatch(right = false))
            drBank[2] = NesInstrument("Drum right", volume = NesEnvelope(listOf(9)), chip = NesChip.EPSM, epsm = EpsmPatch(left = false))
            val drum = NesRules.EPSM_RHYTHM1
            val drCells = HashMap<Pair<Int, Int>, Cell>()
            for (d in 0 until 6) drCells[drum + d to 2 * d] = Cell(48, 1 + d % 3)
            drCells[drum to 12] = Cell(48, 1)
            drCells[drum + 3 to 14] = Cell(48, 2, NesRules.FX_VOLUME, 0x08)
            val rhythm = drBase.copy(title = "EPSM rhythm", ticksPerRow = 3, loopOrder = 0,
                patterns = listOf(drBase.patterns[0].let { p ->
                    p.copy(length = 16, tracks = p.tracks.mapIndexed { ch, rows -> rows.mapIndexed { r, cell -> drCells[ch to r] ?: cell } })
                }), orders = listOf(0), sound = drBase.nes.copy(instruments = drBank))
            check(NesEpsmSystem.errors(rhythm).isEmpty()) { "the EPSM rhythm song is invalid: ${NesEpsmSystem.errors(rhythm)}" }
            File(directory, "epsm-rhythm.nesimg")
                .writeBytes(ByteArray(NesEpsmSystem.engine.imageSize).also { NesEpsmSystem.engine.lower(rhythm, it) })
            println("Exported the EPSM rhythm image (the ROM itself stays at $romPath)")
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
        // The Mega Drive demo: project and engine image (its VGM/XGM2/XGC need the native player:
        // tools/audio/render_ym_image.sh neon-causeway.ymimg out.wav --vgm neon-causeway.vgm renders them).
        val ym = YmSystem.demo()
        File(directory, "neon-causeway.thor").writeText(ProjectCodec.encode(ym), Charsets.UTF_8)
        File(directory, "neon-causeway.ymimg").writeBytes(ByteArray(YmSystem.engine.imageSize).also { YmSystem.engine.lower(ym, it) })
        println("Exported ${ym.title} to ${directory.absolutePath}")
        // The WonderSwan and WonderSwan Color demos: projects and engine images (tools/audio/render_ws_image.sh
        // ws-demo.wsimg out.wav --vgm paper-crane-run.vgm renders one with its VGM; the Color demo plays Hyper Voice,
        // which VGM cannot carry, so it renders as WAV only), and the shortest valid song of each (one one-row order at
        // speed 1, A-4 held on CH1 with slot 01) whose images the native WAV export test renders.
        for ((system, image) in listOf(WsSystem to "ws", WscSystem to "wsc")) {
            val demo = system.demo()
            File(directory, "${demo.title.lowercase().replace(' ', '-')}.thor").writeText(ProjectCodec.encode(demo), Charsets.UTF_8)
            File(directory, "$image-demo.wsimg").writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(demo, it) })
            val shortest = WsDemoSong.shortest(color = system === WscSystem)
            check(system.errors(shortest).isEmpty()) { "the shortest ${system.name} song is invalid: ${system.errors(shortest)}" }
            File(directory, "$image-shortest.wsimg").writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(shortest, it) })
            println("Exported ${demo.title} and the shortest ${system.name} song to ${directory.absolutePath}")
        }
        // The Virtual Boy demo and the shortest valid VSU song: project and engine images for the native renderers
        // (wav_render_test.c; VGM needs the native player: tools/audio/render_vsu_image.sh crimson-stereoscope.vbimg
        // out.wav --vgm crimson-stereoscope.vgm).
        val vsu = VsuSystem.demo()
        File(directory, "crimson-stereoscope.thor").writeText(ProjectCodec.encode(vsu), Charsets.UTF_8)
        File(directory, "crimson-stereoscope.vbimg").writeBytes(ByteArray(VsuSystem.engine.imageSize).also { VsuSystem.engine.lower(vsu, it) })
        val vsuShortest = VsuDemoSong.shortest()
        File(directory, "vsu-shortest.vbimg").writeBytes(ByteArray(VsuSystem.engine.imageSize).also { VsuSystem.engine.lower(vsuShortest, it) })
        println("Exported ${vsu.title} and ${vsuShortest.title} to ${directory.absolutePath}")
        // The SAM Coupé and CMS demos: projects and engine images (their VGMs need the native player:
        // tools/audio/render_saa_image.sh saa-sam-demo.saaimg out.wav --vgm coupe-lights.vgm renders both), and the
        // shortest valid song of each (one one-row order at speed 1, A-4 held on lane 1) whose images the native WAV
        // export test renders through loops.
        for ((system, file) in listOf(SamCoupeSystem to "coupe-lights", CmsSystem to "blaster-skyline")) {
            val demo = system.demo()
            val image = if (system == SamCoupeSystem) "saa-sam-demo.saaimg" else "saa-cms-demo.saaimg"
            File(directory, "$file.thor").writeText(ProjectCodec.encode(demo), Charsets.UTF_8)
            File(directory, image).writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(demo, it) })
            println("Exported ${demo.title} to ${directory.absolutePath}")
        }
        for ((variant, file) in listOf(SaaVariant.SAM to "saa-sam-shortest.saaimg", SaaVariant.CMS to "saa-cms-shortest.saaimg")) {
            val shortest = SaaDemoSong.shortest(variant)
            val system = variant.system
            check(system.errors(shortest).isEmpty()) { "the shortest ${variant.label} song is invalid: ${system.errors(shortest)}" }
            File(directory, file).writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(shortest, it) })
        }
        println("Exported the shortest SAM Coupé and CMS songs to ${directory.absolutePath}")
        // The VIC-20 and C16/Plus/4 demos (project and engine image; no register-log export exists for these chips) and
        // each chip's shortest valid song (one one-row order at speed 6, A-4 held with slot 01): the native WAV export
        // test renders all four images (tools/audio/render_cbm_image.sh renders one).
        for ((system, chip, file) in listOf(Triple(VicSystem, CbmChip.VIC, "kernal-lights"), Triple(TedSystem, CbmChip.TED, "plus-four-parade"))) {
            val prefix = if (chip == CbmChip.VIC) "vic" else "ted"
            val demo = system.demo()
            File(directory, "$file.thor").writeText(ProjectCodec.encode(demo), Charsets.UTF_8)
            File(directory, "$prefix-demo.cbimg").writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(demo, it) })
            val shortest = CbmDemoSong.shortest(chip)
            check(system.errors(shortest).isEmpty()) { "the shortest ${system.name} song is invalid: ${system.errors(shortest)}" }
            File(directory, "$prefix-shortest.cbimg").writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(shortest, it) })
            println("Exported ${demo.title} and the shortest ${system.name} song to ${directory.absolutePath}")
        }
        // The Atari 2600 demos: NTSC and PAL projects and engine images (WAV only: the TIA has no VGM), and the
        // shortest valid song (one row, A-4 held on TIA 1 with a div 6 square: AUDF 11) whose image the native WAV
        // export test renders.
        val tia = TiaNtscSystem.demo()
        File(directory, "cartridge-dawn.thor").writeText(ProjectCodec.encode(tia), Charsets.UTF_8)
        File(directory, "tia-demo.tiaimg").writeBytes(ByteArray(TiaNtscSystem.engine.imageSize).also { TiaNtscSystem.engine.lower(tia, it) })
        val tiaPal = TiaPalSystem.demo()
        File(directory, "cartridge-dawn-pal.thor").writeText(ProjectCodec.encode(tiaPal), Charsets.UTF_8)
        File(directory, "tia-pal-demo.tiaimg").writeBytes(ByteArray(TiaPalSystem.engine.imageSize).also { TiaPalSystem.engine.lower(tiaPal, it) })
        val tiaShortest = TiaDemoSong.shortest()
        check(TiaNtscSystem.errors(tiaShortest).isEmpty()) { "the shortest Atari 2600 song is invalid: ${TiaNtscSystem.errors(tiaShortest)}" }
        File(directory, "tia-shortest.tiaimg").writeBytes(ByteArray(TiaNtscSystem.engine.imageSize).also { TiaNtscSystem.engine.lower(tiaShortest, it) })
        println("Exported ${tia.title} (NTSC, PAL) and the shortest Atari 2600 song to ${directory.absolutePath}")
        // The POKEY demo and the shortest valid POKEY song: project and engine images for the native renderers (VGM
        // needs the native player).
        val pokey = PokeySystem.demo()
        File(directory, "scanline-cruise.thor").writeText(ProjectCodec.encode(pokey), Charsets.UTF_8)
        File(directory, "scanline-cruise.pkimg").writeBytes(ByteArray(PokeySystem.engine.imageSize).also { PokeySystem.engine.lower(pokey, it) })
        val pokeyShortest = PokeyDemoSong.shortest()
        File(directory, "pokey-shortest.pkimg").writeBytes(ByteArray(PokeySystem.engine.imageSize).also { PokeySystem.engine.lower(pokeyShortest, it) })
        println("Exported ${pokey.title} and ${pokeyShortest.title} to ${directory.absolutePath}")
        // The PC-88/98 OPN (YM2203) and OPNA (YM2608) demos: projects and engine images (their VGMs need the native
        // player: tools/audio/render_opn_image.sh renders them). Neither demo has rhythm notes, so neither needs the
        // rhythm ROM. Then each chip's shortest valid song (one one-row order at speed 1, one held FM 1 note), whose
        // images the native WAV export test renders through loops.
        val opn = OpnSystem.demo()
        File(directory, "night-express.thor").writeText(ProjectCodec.encode(opn), Charsets.UTF_8)
        File(directory, "night-express.opnimg").writeBytes(ByteArray(OpnSystem.engine.imageSize).also { OpnSystem.engine.lower(opn, it) })
        val opna = OpnaSystem.demo()
        File(directory, "paper-lanterns.thor").writeText(ProjectCodec.encode(opna), Charsets.UTF_8)
        File(directory, "paper-lanterns.opnaimg").writeBytes(ByteArray(OpnaSystem.engine.imageSize).also { OpnaSystem.engine.lower(opna, it) })
        for ((chip, name) in listOf(OpnChip.YM2203 to "opn-shortest.opnimg", OpnChip.YM2608 to "opna-shortest.opnaimg")) {
            val shortest = OpnDemoSong.shortest(chip)
            val system = shortest.system
            check(system.errors(shortest).isEmpty()) { "the shortest ${chip.label} song is invalid: ${system.errors(shortest)}" }
            File(directory, name).writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(shortest, it) })
        }
        println("Exported ${opn.title}, ${opna.title} and the shortest OPN and OPNA songs to ${directory.absolutePath}")
        // The AY demo on each computer: project and engine image (its VGM needs the native player:
        // tools/audio/render_ay_image.sh copper-lane-zx128.ayimg out.wav --vgm copper-lane-zx128.vgm renders both).
        for (variant in AyVariant.entries) {
            val system = variant.system
            val ay = system.demo()
            File(directory, "copper-lane-${variant.fileKey}.thor").writeText(ProjectCodec.encode(ay), Charsets.UTF_8)
            File(directory, "copper-lane-${variant.fileKey}.ayimg").writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(ay, it) })
            println("Exported ${ay.title} (${variant.label}) to ${directory.absolutePath}")
        }
        // The XGM2 fixture "Night Ferry" (PCM kits on PCM 1-3, FM 3 special mode): project and image (render_ym_image.sh
        // night-ferry.ymimg out.wav --vgm/--xgm/--xgc renders its exports), and the shortest songs wav_render_test.c renders
        // (one one-row order at speed 1): A-4 on FM 1 (ym-shortest), the PCM bass kit's A-2 on PCM 1, restarted every tick
        // (ym-pcm-shortest), FM 3's special-mode chord (ym-fm3-shortest).
        val ferry = YmDemoSong.xgm2Demo()
        check(YmSystem.errors(ferry).isEmpty()) { "Night Ferry is invalid: ${YmSystem.errors(ferry)}" }
        File(directory, "night-ferry.thor").writeText(ProjectCodec.encode(ferry), Charsets.UTF_8)
        File(directory, "night-ferry.ymimg").writeBytes(ByteArray(YmSystem.engine.imageSize).also { YmSystem.engine.lower(ferry, it) })
        for ((name, song) in listOf("ym-shortest" to YmDemoSong.shortest(), "ym-pcm-shortest" to YmDemoSong.pcmShortest(),
            "ym-fm3-shortest" to YmDemoSong.fm3Shortest())) {
            check(YmSystem.errors(song).isEmpty()) { "$name is invalid: ${YmSystem.errors(song)}" }
            File(directory, "$name.ymimg").writeBytes(ByteArray(YmSystem.engine.imageSize).also { YmSystem.engine.lower(song, it) })
        }
        println("Exported ${ferry.title} and the shortest Mega Drive songs to ${directory.absolutePath}")
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
        // The PC Engine demo: project and engine image (its VGM needs the native player: tools/audio/render_pce_image.sh
        // pce-demo.pcimg out.wav --vgm turbo-lanterns.vgm renders both), and the shortest valid PC Engine song (one
        // one-row order at speed 1, A-4 held on PSG 1 with slot 01) whose image the native WAV export test renders.
        val pce = PceSystem.demo()
        File(directory, "turbo-lanterns.thor").writeText(ProjectCodec.encode(pce), Charsets.UTF_8)
        File(directory, "pce-demo.pcimg").writeBytes(ByteArray(PceSystem.engine.imageSize).also { PceSystem.engine.lower(pce, it) })
        val pceShortest = PceDemoSong.shortest()
        check(PceSystem.errors(pceShortest).isEmpty()) { "the shortest PC Engine song is invalid: ${PceSystem.errors(pceShortest)}" }
        File(directory, "pce-shortest.pcimg").writeBytes(ByteArray(PceSystem.engine.imageSize).also { PceSystem.engine.lower(pceShortest, it) })
        println("Exported ${pce.title} and the shortest PC Engine song to ${directory.absolutePath}")
        // The SCC and SCC+ demos: projects and engine images (their VGMs need the native player:
        // tools/audio/render_scc_image.sh scc-demo.sccimg out.wav --vgm cartridge-dusk.vgm renders both), and the
        // shortest valid song of each (one one-row order at speed 1, A-4 held on SCC 1) whose images the native WAV
        // export test renders through loops.
        for ((system, file) in listOf(SccSystem to "cartridge-dusk", SccPlusSystem to "prism-lane")) {
            val demo = system.demo()
            val image = if (system == SccSystem) "scc-demo.sccimg" else "scc-plus-demo.sccimg"
            File(directory, "$file.thor").writeText(ProjectCodec.encode(demo), Charsets.UTF_8)
            File(directory, image).writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(demo, it) })
            println("Exported ${demo.title} to ${directory.absolutePath}")
        }
        for ((variant, file) in listOf(SccVariant.SCC to "scc-shortest.sccimg", SccVariant.PLUS to "scc-plus-shortest.sccimg")) {
            val shortest = SccDemoSong.shortest(variant)
            val system = variant.system
            check(system.errors(shortest).isEmpty()) { "the shortest ${variant.label} song is invalid: ${system.errors(shortest)}" }
            File(directory, file).writeBytes(ByteArray(system.engine.imageSize).also { system.engine.lower(shortest, it) })
        }
        println("Exported the shortest SCC and SCC+ songs to ${directory.absolutePath}")
        // The OPM demo: project and engine image (its VGM needs the native player: tools/audio/render_opm_image.sh
        // opm-demo.omimg out.wav --vgm sharp-corners.vgm renders both), and the shortest valid OPM song (one one-row
        // order at speed 1, A-4 held on FM 1) whose image the native WAV export test renders through loops.
        val opm = OpmSystem.demo()
        File(directory, "sharp-corners.thor").writeText(ProjectCodec.encode(opm), Charsets.UTF_8)
        File(directory, "opm-demo.omimg").writeBytes(ByteArray(OpmSystem.engine.imageSize).also { OpmSystem.engine.lower(opm, it) })
        val opmShortest = OpmDemoSong.shortest()
        check(OpmSystem.errors(opmShortest).isEmpty()) { "the shortest OPM song is invalid: ${OpmSystem.errors(opmShortest)}" }
        File(directory, "opm-shortest.omimg").writeBytes(ByteArray(OpmSystem.engine.imageSize).also { OpmSystem.engine.lower(opmShortest, it) })
        println("Exported ${opm.title} and the shortest OPM song to ${directory.absolutePath}")
    }
}
