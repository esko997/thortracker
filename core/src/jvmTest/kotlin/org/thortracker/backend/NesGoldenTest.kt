package org.thortracker.backend

import org.thortracker.backend.FamiStudioEnvelopes.Kind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden comparison with FamiStudio 4.5.3 itself. For each [NesGoldenSongs] song, `src/jvmTest/resources/nes-golden`
 * (in `core/`) holds our FamiStudio text export (`<name>.txt`), FamiStudio's own ca65 export of that text
 * (`<name>_ca65.s`, `FamiStudio <name>.txt famistudio-asm-export … -famistudio-asm-format:ca65`) and those bytes
 * assembled at the song base (`<name>.bin`); `tools/audio/nes_golden.py` makes them.
 *
 * FamiStudio lays its data out differently (instruments sorted by use and merged when identical, envelopes ordered
 * by CRC, streams compressed by a size search, no per-order references) and our layout must keep stable slots for the
 * engine's live edits, so the bytes are not compared as a whole. Instead:
 * - the text export must equal the fixture (so the fixture is FamiStudio's reading of today's export);
 * - every envelope we encode must be one FamiStudio encoded (byte-identical envelope data);
 * - FamiStudio's data and ours, each run by the same embedded player on a 6502, must write the same APU registers
 *   with the same values in the same order on every frame, through two passes of the song and its loop.
 *
 * Run with `THOR_UPDATE_NES_GOLDEN=1` to rewrite the `.txt` fixtures, then `python3 tools/audio/nes_golden.py`.
 */
class NesGoldenTest {
    private val dir = File("src/jvmTest/resources/nes-golden")
    private val update = System.getenv("THOR_UPDATE_NES_GOLDEN") == "1"

    private fun resource(name: String): String? = javaClass.getResource("/nes-golden/$name")?.readText()
    private fun bytes(name: String): ByteArray? = javaClass.getResource("/nes-golden/$name")?.readBytes()

    @Test
    fun textExportsMatchTheFixtures() {
        for ((name, build) in NesGoldenSongs.ALL) {
            val text = FamiStudioText.text(build())
            if (update) {
                dir.mkdirs()
                File(dir, "$name.txt").writeText(text)
                continue
            }
            assertEquals(resource("$name.txt") ?: fail("missing fixture $name.txt"), text, "$name: text export changed; regenerate the golden fixtures")
        }
    }

    @Test
    fun envelopesAreFamiStudiosBytes() {
        if (update) return
        for ((name, build) in NesGoldenSongs.ALL) {
            val asm = resource("${name}_ca65.s") ?: fail("missing fixture ${name}_ca65.s")
            val theirs = famiStudioEnvelopes(asm).map { it.toList() }.toSet()
            val fs = FsSong.of(build())
            val used = HashSet<Int>()
            for (ch in fs.notes) for (n in ch) if (n != null && n.isMusical && n.instrument != 0) used += n.instrument
            val ours = ArrayList<Pair<String, ByteArray>>()
            for (slot in used) {
                val ins = fs.instruments[slot - 1]
                ours += "slot $slot volume" to FamiStudioEnvelopes.encode(ins.volume, Kind.VOLUME)
                ours += "slot $slot arpeggio" to FamiStudioEnvelopes.encode(ins.arpeggio, Kind.ARPEGGIO)
                ours += "slot $slot duty" to FamiStudioEnvelopes.encode(ins.duty, Kind.DUTY)
                ours += "slot $slot pitch" to FamiStudioEnvelopes.encode(ins.pitch, Kind.PITCH, ins.pitchRelative)
            }
            for (ch in fs.notes) for (n in ch) {
                if (n == null) continue
                if (n.isMusical && n.arpeggio >= 0) ours += "arpeggio ${n.arpeggio}" to FamiStudioEnvelopes.arpeggio(n.arpeggio)
                if (n.vibrato > 0 && n.vibrato shr 4 != 0 && n.vibrato and 0xF != 0) ours += "vibrato ${n.vibrato}" to FamiStudioEnvelopes.vibrato(n.vibrato)
            }
            for ((what, b) in ours) {
                assertTrue(b.toList() in theirs, "$name: $what encodes as ${b.joinToString(",") { "%02x".format(it) }}, which FamiStudio's export does not contain")
            }
        }
    }

    @Test
    fun apuTracesMatchFamiStudiosExport() {
        if (update) return
        for ((name, build) in NesGoldenSongs.ALL) {
            val theirs = bytes("$name.bin") ?: fail("missing fixture $name.bin")
            val song = build()
            val fs = FsSong.of(song)
            val ours = FamiStudioLowering.lower(song).data
            // Two passes plus the loop again, at the slowest speed the song uses.
            val maxSpeed = (fs.notes.flatMap { it.toList() }.mapNotNull { it?.speed?.takeIf { s -> s > 0 } } + fs.speed).max()
            val loopRows = fs.totalRows - fs.orderStart[fs.loopOrder]
            val frames = (fs.totalRows + 2 * loopRows) * maxSpeed + 120
            val a = NesTracer(theirs)
            val b = NesTracer(ours)
            var writes = 0
            for (f in 0 until frames) {
                val ta = a.frame()
                val tb = b.frame()
                if (!ta.contentEquals(tb)) {
                    fail("$name: frame $f differs: FamiStudio ${trace(ta)} vs ours ${trace(tb)}")
                }
                writes += ta.size
            }
            assertTrue(writes > frames, "$name: the player wrote only $writes APU registers in $frames frames")
        }
    }

    private fun trace(t: IntArray) = t.joinToString(" ") { "%04x=%02x".format(0x4000 + (it shr 8), it and 0xff) }

    /** The `@envN:` byte rows of a FamiStudio ca65 export. */
    private fun famiStudioEnvelopes(asm: String): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val lines = asm.lines()
        for ((i, line) in lines.withIndex()) {
            if (!Regex("""^@env\d+:""").containsMatchIn(line.trim())) continue
            val data = lines[i + 1].trim().removePrefix(".byte").split(",").map { it.trim().removePrefix("$").toInt(16).toByte() }
            out += data.toByteArray()
        }
        return out
    }
}
