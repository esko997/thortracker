package org.thortracker.backend

import org.thortracker.backend.FamiStudioEnvelopes.Kind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesRules
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
            // FamiStudio leaves out instruments only DPCM plays (their envelopes are never used).
            for ((c, ch) in fs.notes.withIndex()) if (c != NesRules.DPCM) for (n in ch) if (n != null && n.isMusical && n.instrument != 0) used += n.instrument
            val ours = ArrayList<Pair<String, ByteArray>>()
            for (slot in used) {
                val ins = fs.instruments[slot - 1]
                ours += "slot $slot volume" to FamiStudioEnvelopes.encode(ins.volume, Kind.VOLUME)
                ours += "slot $slot arpeggio" to FamiStudioEnvelopes.encode(ins.arpeggio, Kind.ARPEGGIO)
                // FamiStudio exports only the envelopes a sound's expansion has: no duty on the VRC7 or the 5B.
                if (ins.chip.hasDuty) ours += "slot $slot duty" to FamiStudioEnvelopes.encode(ins.duty, Kind.DUTY)
                ours += "slot $slot pitch" to FamiStudioEnvelopes.encode(ins.pitch, Kind.PITCH, ins.pitchRelative)
                if (ins.chip == NesChip.S5B) {
                    ours += "slot $slot mixer" to FamiStudioEnvelopes.encode(ins.mixer, Kind.MIXER)
                    ours += "slot $slot noise" to FamiStudioEnvelopes.encode(ins.noise, Kind.NOISE)
                }
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
            val lowered = FamiStudioLowering.lower(song)
            val ours = lowered.data
            // FamiStudio's sample bank (its .dmc) is our sample memory at $C000, byte for byte.
            val dmc = bytes("${name}_ca65.dmc") ?: ByteArray(0)
            assertContentEquals(dmc, lowered.samples, "$name: sample memory differs from FamiStudio's .dmc")
            // Two passes plus the loop again, at the slowest speed the song uses.
            val maxSpeed = (fs.notes.flatMap { it.toList() }.mapNotNull { it?.speed?.takeIf { s -> s > 0 } } + fs.speed).max()
            val loopRows = fs.totalRows - fs.orderStart[fs.loopOrder]
            val frames = (fs.totalRows + 2 * loopRows) * maxSpeed + 120
            val a = NesTracer(theirs, lowered.player)
            val b = NesTracer(ours, lowered.player)
            var writes = 0
            var dpcmStarts = 0
            var vrc6 = 0
            var s5b = 0
            for (f in 0 until frames) {
                val ta = a.frame()
                val tb = b.frame()
                if (!ta.contentEquals(tb)) {
                    fail("$name: frame $f differs: FamiStudio ${trace(ta)} vs ours ${trace(tb)}")
                }
                writes += ta.size
                dpcmStarts += ta.count { it == (0x4015 shl 8 or 0x1f) }
                vrc6 += ta.count { (it shr 8) in 0x9000..0xBFFF }
                s5b += ta.count { (it shr 8) >= 0xC000 }
            }
            assertTrue(writes > frames, "$name: the player wrote only $writes APU registers in $frames frames")
            if (dmc.isNotEmpty()) {
                // The trace covers DPCM: sample starts write $4012/$4013/$4010 and start the DMC through $4015.
                assertTrue(dpcmStarts > 0, "$name: no DPCM sample started")
            }
            if (lowered.player == NesPlayer.VRC6) {
                // The trace covers the VRC6: its pulses and saw are written (and compared) every frame they sound.
                assertTrue(vrc6 > frames, "$name: only $vrc6 VRC6 register writes in $frames frames")
            }
            if (lowered.player == NesPlayer.S5B) {
                // The trace covers the 5B: every frame its squares sound, register selects and data writes (compared).
                assertTrue(s5b > frames, "$name: only $s5b Sunsoft 5B register writes in $frames frames")
            }
        }
    }

    private fun trace(t: IntArray) = NesTracer.text(t)

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
