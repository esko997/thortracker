package org.thortracker.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden comparison with GoatTracker 2.77 itself.
 *
 * The spike's test song (`tools/audio/sid/gt_test.sng`, written by `gt_test_song.py`): GoatTracker's song file read by
 * [GtSong.read] and packed by [SidLowering] must be the image `tools/audio/prepare_sid_player.py` proved to be
 * GoatTracker's packer's data (`app/src/test/cpp/sid_gt_song.h`), and our `.sid` export of it must write the SID
 * registers py65 recorded running gt2reloc's own `.sid` in all 1500 frames.
 *
 * Thor Tracker songs ([SidGoldenSongs]): `src/jvmTest/resources/sid-golden` (in `core/`) holds our `.sng` export of each
 * (`<name>.sng`) and GoatTracker's packing of that file (`<name>.sid`, `gt2reloc <name>.sng <name>.sid -I0 -J1` with the
 * song's multispeed and hard-restart options; `tools/audio/sid_golden.py` makes them, which also proves GoatTracker
 * loads our files). Our `.sng` export must equal the fixture, and our `.sid` export and GoatTracker's must write the same
 * SID registers in every frame through the song, its loop and a second pass (gt2reloc drops instruments and table rows
 * no pattern reaches, so the data bytes differ while what plays may not). Run with `THOR_UPDATE_SID_GOLDEN=1` to rewrite
 * the `.sng` fixtures, then `tools/audio/sid_golden.py`.
 */
class SidGoldenTest {
    private val dir = File("src/jvmTest/resources/sid-golden")
    private val update = System.getenv("THOR_UPDATE_SID_GOLDEN") == "1"

    private fun bytes(name: String): ByteArray? = javaClass.getResource("/sid-golden/$name")?.readBytes()

    /** The C arrays of the native host test's fixture, by name. */
    private val fixture: Map<String, IntArray> by lazy {
        val text = File("../app/src/test/cpp/sid_gt_song.h").takeIf { it.exists() }?.readText()
            ?: fail("../app/src/test/cpp/sid_gt_song.h not found (tests run in core/)")
        Regex("""static const uint\d+_t (\w+)\[[^\]]*\] = \{([^}]*)\};""").findAll(text).associate { m ->
            m.groupValues[1] to m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { if (it.startsWith("0x")) it.substring(2).toLong(16).toInt() else it.toLong().toInt() }.toIntArray()
        }
    }

    private fun spike(): GtSong {
        val image = fixture.getValue("sid_gt_image")
        val sng = File("../tools/audio/sid/gt_test.sng").readBytes()
        return GtSong.read(sng, model = image[SidPlayerImage.HEADER_MODEL], multiplier = image[SidPlayerImage.HEADER_MULTIPLIER])
    }

    @Test
    fun spikeSongLowersToGoatTrackersPacking() {
        val expected = ByteArray(SidPlayerImage.IMAGE_SIZE)
        fixture.getValue("sid_gt_image").forEachIndexed { i, b -> expected[i] = b.toByte() }
        val image = ByteArray(SidPlayerImage.IMAGE_SIZE)
        SidLowering.lower(spike(), image)
        val first = expected.indices.firstOrNull { expected[it] != image[it] }
        assertEquals(null, first, "image differs from gt_song.lower's (= GoatTracker's packer) first at byte $first")
    }

    @Test
    fun spikeSongSidExportWritesWhatGoatTrackersSidWrites() {
        val index = fixture.getValue("sid_gt_ref_index")
        val ref = fixture.getValue("sid_gt_ref_writes")
        val ours = SidTracer.psid(SidExport.psid(spike()))
        val theirs = SidTracer.psid(ByteArray(fixture.getValue("sid_gt_psid").size) { fixture.getValue("sid_gt_psid")[it].toByte() })
        for (f in 0 until index.size - 1) {
            val expected = IntArray(index[f + 1] - index[f]) { (ref[2 * (index[f] + it)] shl 8) or ref[2 * (index[f] + it) + 1] }
            assertContentEquals(expected, ours.frame(), "frame $f: our .sid writes differ from py65 running gt2reloc's .sid")
            assertContentEquals(expected, theirs.frame(), "frame $f: the tracer differs from py65 on gt2reloc's .sid")
        }
    }

    @Test
    fun goldenSongsAreValid() {
        for ((name, build) in SidGoldenSongs.ALL) {
            val song = build()
            assertEquals(emptyList(), song.system.errors(song), "$name: errors")
            assertEquals(emptyList(), song.system.warnings(song), "$name: warnings")
        }
    }

    @Test
    fun prgPlaysLikeTheSid() {
        for (gt in listOf(spike()) + SidGoldenSongs.ALL.values.map { GtSong.of(it()) }) {
            val prg = SidExport.prg(gt)
            assertEquals(SidExport.PRG_LOAD, (prg[0].toInt() and 0xff) or ((prg[1].toInt() and 0xff) shl 8))
            val c64 = SidTracer.prg(prg, SidExport.PRG_START)
            val latch = c64.cia[4] or (c64.cia[5] shl 8)
            assertEquals(SidPlayerImage.CIA_PAL_LATCH / gt.multiplier, latch, "${gt.name}: CIA 1 timer A latch")
            assertEquals(0x11, c64.cia[0x0E], "${gt.name}: timer A started continuous")
            val sid = SidTracer.psid(SidExport.psid(gt))
            assertContentEquals(sid.initWrites, c64.initWrites, "${gt.name}: init writes")
            repeat(FRAMES) { f -> assertContentEquals(sid.frame(), c64.frame(), "${gt.name}: frame $f of the .prg differs from the .sid") }
        }
    }

    @Test
    fun sngExportsMatchTheFixtures() {
        for ((name, build) in SidGoldenSongs.ALL) {
            val sng = SidExport.sng(build())
            if (update) {
                dir.mkdirs()
                File(dir, "$name.sng").writeBytes(sng)
                File(dir, "$name.args").writeText(SidGoldenSongs.packerOptions(build()) + "\n")
                continue
            }
            assertContentEquals(bytes("$name.sng") ?: fail("missing fixture $name.sng"), sng, "$name: .sng export changed; regenerate the golden fixtures")
        }
    }

    @Test
    fun sngReadsBackToTheSameSong() {
        for ((name, build) in SidGoldenSongs.ALL) {
            val gt = GtSong.of(build())
            val back = GtSong.read(gt.sng(), gt.model, gt.multiplier)
            assertEquals(gt.tempo, back.tempo, "$name: tempo")
            assertEquals(gt.orderlists, back.orderlists, "$name: orderlists")
            assertEquals(gt.patterns, back.patterns, "$name: patterns")
            for (t in 0 until 4) {
                assertContentEquals(gt.left[t], back.left[t], "$name: table $t left")
                assertContentEquals(gt.right[t], back.right[t], "$name: table $t right")
            }
            val a = ByteArray(SidPlayerImage.IMAGE_SIZE).also { SidLowering.lower(gt, it) }
            val b = ByteArray(SidPlayerImage.IMAGE_SIZE).also {
                SidLowering.lower(GtSong(back.name, back.author, back.copyright, back.orderlists, back.restart,
                    gt.instruments, back.left, back.right, back.patterns, back.tempo, gt.model, gt.multiplier, gt.hrAd, gt.hrSr), it)
            }
            assertContentEquals(a, b, "$name: the .sng packs to the same image")
        }
    }

    @Test
    fun sidTracesMatchGoatTrackersPacking() {
        if (update) return
        for ((name, build) in SidGoldenSongs.ALL) {
            val theirs = SidTracer.psid(bytes("$name.sid") ?: fail("missing fixture $name.sid; run tools/audio/sid_golden.py"))
            val song = build()
            val ours = SidTracer.psid(SidExport.psid(song))
            assertContentEquals(theirs.initWrites, ours.initWrites, "$name: init writes")
            var nonEmpty = 0
            repeat(FRAMES) { f ->
                val t = theirs.frame()
                assertContentEquals(t, ours.frame(), "$name: frame $f writes differ from GoatTracker's packing")
                if (t.isNotEmpty()) nonEmpty++
            }
            assertTrue(nonEmpty > FRAMES / 2, "$name: the song writes the SID")
            println("$name: $FRAMES frames identical to GoatTracker's packing ($nonEmpty with SID writes)")
        }
    }

    private companion object {
        /** Long enough for every golden song to play through, loop and play again (~60 s at 50 Hz). */
        const val FRAMES = 3000
    }
}
