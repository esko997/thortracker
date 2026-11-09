package org.thortracker.backend

import org.thortracker.backend.FamiStudioEnvelopes.Kind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.nes
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
 * - For [NesGoldenSongs.ALL], FamiStudio's data and ours, run by the same embedded player on a 6502, must write the
 *   same registers with the same values in the same order on every frame, through repeated song/loop passes.
 *   Rewriting an unchanged VRC7 custom-patch register is idempotent, unlike key-on/envelope registers: those
 *   redundant patch writes are omitted on both sides. Loop restoration conservatively reloads custom patches
 *   because delayed notes can change the final writer; the real-driver loop regressions cover that correction.
 *   Likewise on the EPSM: our streams load each note's own slot and re-send an FM channel's sound per order, so its
 *   channel's patch registers (`$30-$9F`, `$B0-$B6`) and the LFO (`$22`) are rewritten with the values they hold;
 *   unchanged writes to exactly those registers are omitted on both sides, key on/off (`$28`), frequencies, the SSG and
 *   the drums are always compared.
 * - [NesGoldenSongs.CORRECTIONS] independently captures upstream export bugs: its VRC7 fixture must demonstrate
 *   both upstream's mid-song legato retrigger and missed loop patch, versus our held envelope and restored patch; its
 *   FDS witness expresses manual mod speeds with a low byte of $80-$FF through `Jxx`, which upstream exports
 *   correctly, and must play exactly what our lowering plays for the same speeds held in the sounds.
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
        for ((name, build) in NesGoldenSongs.ALL + NesGoldenSongs.CORRECTIONS) {
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
        for ((name, build) in NesGoldenSongs.ALL + NesGoldenSongs.CORRECTIONS) {
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
                // FamiStudio exports only the envelopes a sound's expansion has: no duty on the VRC7, the 5B or the EPSM.
                if (ins.chip.hasDuty) ours += "slot $slot duty" to FamiStudioEnvelopes.encode(ins.duty, Kind.DUTY)
                ours += "slot $slot pitch" to FamiStudioEnvelopes.encode(ins.pitch, Kind.PITCH, ins.pitchRelative)
                if (ins.chip == NesChip.N163) {
                    // FamiStudio stores an N163 sound's wave-index envelope and each distinct wave among its envelopes.
                    ours += "slot $slot wave index" to FamiStudioEnvelopes.waveIndex(ins.wave)
                    for ((i, w) in FamiStudioEnvelopes.n163Waves(ins.wave).stored.withIndex()) {
                        ours += "slot $slot wave $i" to FamiStudioEnvelopes.n163WaveBytes(w)
                    }
                }
                if (ins.chip == NesChip.FDS) {
                    // FamiStudio stores an FDS sound's wave-index envelope, each distinct wave and its modulation table
                    // among its envelopes (`FamitoneMusicFile.OutputInstruments`).
                    ours += "slot $slot wave index" to FamiStudioEnvelopes.waveIndex(ins.fds)
                    for ((i, w) in FamiStudioEnvelopes.fdsWaves(ins.fds).stored.withIndex()) {
                        ours += "slot $slot wave $i" to FamiStudioEnvelopes.fdsWaveBytes(w)
                    }
                    ours += "slot $slot mod table" to FamiStudioEnvelopes.fdsModBytes(ins.fds)
                }
                // The 5B's and the EPSM SSG's mixer and noise envelopes.
                if (ins.chip.hasSsg) {
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
            var expansionWrites = 0
            var n163 = 0
            var fds = 0
            var s5b = 0
            var epsm = 0
            val patchA = IntArray(8) { -1 }
            val patchB = IntArray(8) { -1 }
            val epsmA = NesTracer.epsmPatchState()
            val epsmB = NesTracer.epsmPatchState()
            for (f in 0 until frames) {
                val ta = NesTracer.withoutUnchangedEpsmPatchWrites(withoutUnchangedPatchWrites(a.frame(), patchA), epsmA)
                val tb = NesTracer.withoutUnchangedEpsmPatchWrites(withoutUnchangedPatchWrites(b.frame(), patchB), epsmB)
                if (!ta.contentEquals(tb)) {
                    fail("$name: frame $f differs: FamiStudio ${trace(ta)} vs ours ${trace(tb)}")
                }
                writes += ta.size
                dpcmStarts += ta.count { it == (0x4015 shl 8 or 0x1f) }
                expansionWrites += ta.count { (it shr 8) >= 0x9000 }
                n163 += ta.count { (it shr 8) == 0x4800 }
                fds += ta.count { (it shr 8) in 0x4080..0x408A }
                s5b += ta.count { (it shr 8) >= 0xC000 }
                epsm += ta.count { (it shr 8) in NesTracer.EPSM_PORTS }
            }
            assertTrue(writes > frames, "$name: the player wrote only $writes APU registers in $frames frames")
            if (dmc.isNotEmpty()) {
                // The trace covers DPCM: sample starts write $4012/$4013/$4010 and start the DMC through $4015.
                assertTrue(dpcmStarts > 0, "$name: no DPCM sample started")
            }
            if (lowered.player == NesPlayer.VRC6 || lowered.player == NesPlayer.VRC7) {
                // The trace covers the expansion chip: the VRC6's pulses and saw, the VRC7's FM channels (a select and a
                // data write per register, patch loads included) are written and compared every frame they sound.
                assertTrue(expansionWrites > frames, "$name: only $expansionWrites expansion register writes in $frames frames")
            }
            if (lowered.player in NesPlayer.N163) {
                // The trace covers the N163: frequencies, volumes and wave uploads through its data port, every frame.
                assertTrue(n163 > frames, "$name: only $n163 N163 data writes in $frames frames")
            }
            if (lowered.player == NesPlayer.FDS) {
                // The trace covers the FDS: volume, frequency and modulation registers every frame, wave and table uploads.
                assertTrue(fds > frames, "$name: only $fds FDS register writes in $frames frames")
            }
            if (lowered.player == NesPlayer.S5B) {
                // The trace covers the 5B: every frame its squares sound, register selects and data writes (compared).
                assertTrue(s5b > frames, "$name: only $s5b Sunsoft 5B register writes in $frames frames")
            }
            if (lowered.player == NesPlayer.EPSM) {
                // The trace covers the YMF288: SSG and FM register selects and data (patch loads, LFO, key on/off, the
                // drums' levels and key bits) are written and compared every frame they sound.
                assertTrue(epsm > frames, "$name: only $epsm EPSM port writes in $frames frames")
            }
        }
    }

    /**
     * Manual FDS mod speeds with a low byte of $80-$FF play as themselves: our lowering of
     * [NesGoldenSongs.fdsManualSpeeds] (the speeds in its sounds) writes, frame by frame, the registers FamiStudio's own
     * export of [NesGoldenSongs.fdsManualSpeedWitness] writes (sounds with upstream-safe speeds, the intended ones set by
     * `Jxx` on every attack), through two loop passes; and that witness does play $080, $181 and $FFF.
     */
    @Test
    fun fdsManualSpeedsPlayAsTheUpstreamSafeWitness() {
        if (update) return
        val song = NesGoldenSongs.fdsManualSpeeds()
        assertEquals(emptyList(), org.thortracker.system.nes.NesFdsSystem.errors(song))
        val fs = FsSong.of(song)
        val lowered = FamiStudioLowering.lower(song)
        val upstream = NesTracer(bytes("fds_manual_speed_witness.bin") ?: fail("missing correction fixture"), lowered.player)
        val ours = NesTracer(lowered.data, lowered.player)
        val frames = (fs.totalRows + 2 * (fs.totalRows - fs.orderStart[fs.loopOrder])) * fs.speed + 60
        // $4086 holds the speed's low byte, $4087 its high nibble (bit 7: modulation halted).
        val speeds = HashSet<Int>()
        var low = -1
        for (f in 0 until frames) {
            val a = upstream.frame()
            val b = ours.frame()
            if (!a.contentEquals(b)) fail("frame $f differs: FamiStudio ${trace(a)} vs ours ${trace(b)}")
            for (w in a) when (w ushr 8) {
                0x4086 -> low = w and 0xff
                0x4087 -> if (low >= 0 && w and 0x80 == 0) speeds += (w and 0x0f) shl 8 or low
            }
        }
        for (s in listOf(0x080, 0x181, 0xFFF)) assertTrue(s in speeds, "the witness plays mod speed $s: $speeds")
    }

    /** Pin both intentional differences using FamiStudio's actual exported bytes, not a second sequencer model. */
    @Test
    fun vrc7PatchRestorationCorrectsUpstreamLegatoAndLoopExports() {
        if (update) return
        val song = NesGoldenSongs.vrc7PatchRestore()
        val fs = FsSong.of(song)
        val lowered = FamiStudioLowering.lower(song)
        val upstream = NesTracer(bytes("vrc7_patch_restore.bin") ?: fail("missing correction fixture"), lowered.player)
        val ours = NesTracer(lowered.data, lowered.player)
        val upstreamRegisters = IntArray(64)
        val ourRegisters = IntArray(64)
        val upstreamPatch = IntArray(8) { -1 }
        val ourPatch = IntArray(8) { -1 }
        fun apply(trace: IntArray, registers: IntArray): Boolean {
            var selected = -1
            var keyOff = false
            for (write in trace) when (write ushr 8) {
                0x9010 -> selected = write and 0xff
                0x9030 -> {
                    registers[selected] = write and 0xff
                    if (selected == 0x20 && write and 0x10 == 0) keyOff = true
                }
            }
            return keyOff
        }
        val legatoFrame = 16 * fs.speed
        val firstLoop = fs.orderStart[fs.loopOrder] * fs.speed
        val loopTicks = (fs.totalRows - fs.orderStart[fs.loopOrder]) * fs.speed
        for (frame in 0..firstLoop + 2 * loopTicks + 1) {
            val a = upstream.frame()
            val b = ours.frame()
            val upstreamKeyOff = apply(a, upstreamRegisters)
            val ourKeyOff = apply(b, ourRegisters)
            if (frame < legatoFrame) {
                assertContentEquals(withoutUnchangedPatchWrites(a, upstreamPatch),
                    withoutUnchangedPatchWrites(b, ourPatch), "same playback before the correction at frame $frame")
            }
            if (frame == legatoFrame) {
                assertTrue(upstreamKeyOff, "FamiStudio's export retriggers FM 1 after the other custom patch")
                assertTrue(!ourKeyOff, "restoring a patch must not retrigger a no-attack note")
                assertEquals(0, upstreamRegisters[0x30] and 15, "upstream restarts the volume envelope at 15")
                assertEquals(9, ourRegisters[0x30] and 15, "ours keeps the held volume of 6")
                assertContentEquals(song.nes.instruments[0].fm.regs, ourRegisters.take(8))
                assertContentEquals(ourRegisters.take(8), upstreamRegisters.take(8), "both restore the patch mid-song")
                assertEquals(upstreamRegisters[0x10], ourRegisters[0x10], "both play the requested pitch")
                assertEquals(upstreamRegisters[0x20], ourRegisters[0x20], "same final pitch/key-on, different attack")
            }
            if (frame == legatoFrame + 1) {
                assertEquals(3, upstreamRegisters[0x30] and 15, "upstream advances its restarted envelope to 12")
                assertEquals(9, ourRegisters[0x30] and 15, "the legato envelope remains held")
            }
            if (frame >= firstLoop && (frame - firstLoop) % loopTicks == 1) {
                assertContentEquals(song.nes.instruments[0].fm.regs, ourRegisters.take(8), "lead restored on loop $frame")
                assertContentEquals(song.nes.instruments[if (frame < firstLoop + loopTicks) 0 else 1].fm.regs,
                    upstreamRegisters.take(8), "upstream misses the patch on repeated loops")
                assertEquals(9, ourRegisters[0x30] and 15, "loop restoration also preserves the held envelope")
            }
        }
    }

    private fun trace(t: IntArray) = NesTracer.text(t)

    /** Only $00–$07 are idempotent patch data; retain every other write, including key-off/key-on sequences. */
    private fun withoutUnchangedPatchWrites(trace: IntArray, patch: IntArray): IntArray {
        val out = ArrayList<Int>(trace.size)
        var i = 0
        while (i < trace.size) {
            val register = trace[i] and 0xff
            if (trace[i] ushr 8 == 0x9010 && register < 8 &&
                i + 1 < trace.size && trace[i + 1] ushr 8 == 0x9030
            ) {
                val value = trace[i + 1] and 0xff
                if (patch[register] != value) {
                    out += trace[i]
                    out += trace[i + 1]
                    patch[register] = value
                }
                i += 2
            } else {
                out += trace[i++]
            }
        }
        return out.toIntArray()
    }

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
