package org.thortracker.backend

import org.thortracker.model.ProjectCodec
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEpsmDemoSong
import org.thortracker.system.nes.NesEpsmSystem
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.opl3.Opl3System
import org.thortracker.system.opm.OpmDemoSong
import org.thortracker.system.opm.OpmSystem
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
        // The NES + EPSM demo, the same way (its image plays on "nes-famistudio-epsm"; it has no drums, so it needs no
        // rhythm ROM), and the shortest valid EPSM song (one one-row order at speed 1) whose image the native WAV export
        // test renders through loops.
        val epsm = NesEpsmSystem.demo()
        File(directory, "expansion-port.thor").writeText(ProjectCodec.encode(epsm), Charsets.UTF_8)
        for (format in NesEpsmSystem.exports) File(directory, "expansion-port.${format.extension}").writeBytes(format.export(epsm))
        File(directory, "expansion-port.nesimg").writeBytes(ByteArray(NesEpsmSystem.engine.imageSize).also { NesEpsmSystem.engine.lower(epsm, it) })
        val shortest = NesEpsmDemoSong.shortest()
        check(NesEpsmSystem.errors(shortest).isEmpty()) { "the shortest EPSM song is invalid: ${NesEpsmSystem.errors(shortest)}" }
        File(directory, "epsm-shortest.nesimg").writeBytes(ByteArray(NesEpsmSystem.engine.imageSize).also { NesEpsmSystem.engine.lower(shortest, it) })
        println("Exported ${epsm.title} and the shortest EPSM song to ${directory.absolutePath}")
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
