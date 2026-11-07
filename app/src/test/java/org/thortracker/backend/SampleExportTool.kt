package org.thortracker.backend

import org.thortracker.model.ProjectCodec
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesSystem
import java.io.File

/** Host utility; sample generation is explicit, never a unit-test side effect. */
object SampleExportTool {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Supply an output directory" }
        val directory = File(args[0]).apply { mkdirs() }
        val song = Songs.demo()
        File(directory, "thor-groove.thor").writeText(ProjectCodec.encode(song), Charsets.UTF_8)
        File(directory, "thor-groove.uge").writeBytes(UgeExporter.export(song))
        println("Exported ${song.title} to ${directory.absolutePath}")
        // The NES demo: project, both exports and the engine image (tools/audio/render_nes_image.sh renders it).
        val nes = NesSystem.demo()
        File(directory, "thor-quest.thor").writeText(ProjectCodec.encode(nes), Charsets.UTF_8)
        for (format in NesSystem.exports) File(directory, "thor-quest.${format.extension}").writeBytes(format.export(nes))
        File(directory, "thor-quest.nesimg").writeBytes(ByteArray(NesSystem.engine.imageSize).also { NesSystem.engine.lower(nes, it) })
        println("Exported ${nes.title} to ${directory.absolutePath}")
    }
}
