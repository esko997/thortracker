package org.thortracker.backend

import org.thortracker.model.ProjectCodec
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesSystem
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
            println("Exported ${song.title} (${sms.name}) to ${directory.absolutePath}")
        }
    }
}
