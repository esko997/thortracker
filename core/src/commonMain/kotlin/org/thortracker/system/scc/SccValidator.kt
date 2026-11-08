package org.thortracker.system.scc

import org.thortracker.backend.SccLowering
import org.thortracker.backend.SccPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope

/**
 * SCC and SCC+ song checks. [errors] mirror what `sc_load_image` (`scc/scc_image.c`, [SccPlayerImage.LOADER_PROBLEMS])
 * rejects, as user-facing messages: the header's ranges (speed, multispeed, orders, loop), instrument records and their
 * envelopes ([instrumentProblem]), waves, cells (notes A-0..B-7, defined instruments, effect codes and the parameters of
 * `B C F W`) and the data region's capacity; the rest of the loader's reasons (magic, layout, reserved bytes, offsets,
 * runs) are what the lowering always writes correctly. [warnings] are the specification's "Should"s and the chip's
 * traps (docs/AUDIO_ENGINE.md, "SCC and SCC+"): on the SCC, SCC 4 and SCC 5 sounding together with different waves
 * (they share one wave RAM); test-register bits that do nothing, are unverified or freeze the waves; `Wxx` where no note
 * sounds; delays the speed never reaches; notes above G#6, where the 12-bit period is coarse.
 */
object SccValidator {
    private const val MAX_REPORTED = 40

    /** Most shared-wave overlaps reported one by one; the rest are counted. */
    private const val MAX_SHARED_REPORTS = 8

    private val NAMES = SccRules.CHANNEL_NAMES

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        val sound = song.sound
        if (sound !is SccSound) {
            out += "The song is for ${song.system.name}, not the SCC"
            return out.list()
        }
        val channels = NAMES.size
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != channels) {
                out += "$label has ${pattern.tracks.size} channels; SCC patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    SccRules.cellRangeProblem(cell)?.let { out += "$label, ${NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        if (sound.instruments.size != SccSound.SLOTS) {
            out += "The song has ${sound.instruments.size} instrument slots; SCC songs have exactly ${SccSound.SLOTS}"
        }
        sound.instruments.forEachIndexed { i, ins -> ins?.let(::instrumentProblem)?.let { out += "Instrument ${hex2(i + 1)}: $it" } }
        if (sound.waves.size != SccSound.WAVES) out += "The song has ${sound.waves.size} waves; SCC songs have exactly ${SccSound.WAVES}"
        sound.waves.forEachIndexed { k, w -> w.problem()?.let { out += "Wave ${hex2(k + 1)} $it" } }
        if (sound.multispeed !in 1..SccPlayerImage.MAX_MULTISPEED) {
            out += "Multispeed ${sound.multispeed} is outside 1-${SccPlayerImage.MAX_MULTISPEED}"
        }
        song.tuning?.problems(sound.system)?.forEach { out += it }
        if (song.newPatternScale.root !in 0..11) out += "The scale for new patterns has root ${song.newPatternScale.root}; roots are 0-11 (C-B)"
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) {
            out += "Parked orders ${song.parkedOrders} must be ascending, distinct orders 0-${song.orders.size - 1}"
        }
        return out.list()
    }

    /**
     * Why [ins] cannot be stored (the loader's envelope checks), or null: envelopes hold at most 256 values; loop points
     * lie inside the envelope; only the volume envelope may have a release point, at step 1..length − 1, after its loop;
     * volumes 0..15, arpeggio and pitch −64..63; the wave envelope names at least one wave, each 1..64.
     */
    fun instrumentProblem(ins: SccInstrument): String? =
        envelopeProblem("volume", ins.volume, NesEnvelope.VOLUME, release = true)
            ?: envelopeProblem("arpeggio", ins.arpeggio, NesEnvelope.ARPEGGIO, release = false)
            ?: envelopeProblem("pitch", ins.pitch, NesEnvelope.PITCH, release = false)
            ?: if (ins.wave.values.isEmpty()) "it plays no wave; its wave envelope needs at least one wave number (01-${hex2(SccSound.WAVES)})"
            else envelopeProblem("wave", ins.wave, 1..SccSound.WAVES, release = false)

    private fun envelopeProblem(name: String, env: NesEnvelope, range: IntRange, release: Boolean): String? {
        val n = env.values.size
        return when {
            n > SccPlayerImage.MAX_ENVELOPE -> "the $name envelope has $n steps; envelopes hold at most ${SccPlayerImage.MAX_ENVELOPE}"
            n == 0 && (env.loop != -1 || env.release != -1) -> "the $name envelope has a loop or release point but no values"
            n == 0 -> null
            env.loop != -1 && env.loop !in 0 until n -> "the $name envelope loops to step ${env.loop}; its steps are 0-${n - 1}"
            env.release != -1 && !release -> "the $name envelope has a release point; only volume envelopes have one"
            env.release != -1 && env.release !in 1 until n ->
                if (n == 1) "the $name envelope has one step, too short for a release point (steps 1 and up)"
                else "the $name envelope's release point ${env.release} is outside steps 1-${n - 1}"
            env.loop != -1 && env.release != -1 && env.loop >= env.release ->
                "the $name envelope loops at step ${env.loop}, not before its release point ${env.release}; the loop must come first"
            else -> env.values.firstOrNull { it !in range }?.let {
                if (name == "wave") "the wave envelope names wave $it; waves are 01-${hex2(SccSound.WAVES)}"
                else "the $name envelope has value $it outside ${range.first}..${range.last}"
            }
        }
    }

    /** Problems that block playing or exporting [document]'s arrangement (its unparked orders). */
    fun errors(document: Song): List<String> {
        val structural = structuralErrors(document)
        if (structural.isNotEmpty()) return structural
        if (document.parkedOrders.size == document.orders.size) {
            return listOf("Every order is parked; unpark at least one to play the song or export it")
        }
        val system = document.scc.variant.system
        val song = system.arranged(document)
        val out = Report()
        if (song.orders.size > system.maxOrders) out += "The song has ${song.orders.size} orders; the SCC player plays at most ${system.maxOrders}"
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) {
            val size = SccLowering.dataSize(song)
            if (size > SccPlayerImage.DATA_CAPACITY) {
                out += "The song's pattern and envelope data needs ${kb(size)}; the SCC image holds ${kb(SccPlayerImage.DATA_CAPACITY)}. " +
                    "Reuse patterns or shorten the song"
            }
        }
        return out.list()
    }

    /** Why [cell] on [channel] cannot load, given the song (its order count as the player sees it and instruments), or null. */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        SccRules.cellRangeProblem(cell)?.let { return it }
        if (cell.instrument != 0 && song.scc.instrument(cell.instrument) == null) {
            return "instrument ${hex2(cell.instrument)} is empty; choose a preset for it or another instrument"
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            SccRules.paramProblem(code, param, orders)?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = document.scc.variant.system.arranged(document)
        val out = Report()
        firstNotesWithoutInstrument(song).forEach { out += it }
        straightPlay(song, out)
        testRegister(song, out)
        highNotes(song)?.let { out += it }
        return out.list()
    }

    /**
     * Each order is also entered by the loop, a seek or a live edit, so its first note on a channel should name the
     * instrument (otherwise it plays whatever that channel last had, possibly nothing on a fresh start).
     */
    private fun firstNotesWithoutInstrument(song: Song): List<String> {
        val out = ArrayList<String>()
        val system = song.scc.variant.system
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (ch in 0 until SccSound.CHANNELS) {
                val track = pattern.tracks[ch]
                val first = (0 until pattern.length).firstOrNull { track[it].note >= 0 || track[it].instrument != 0 } ?: continue
                val cell = track[first]
                if (cell.note >= 0 && cell.instrument == 0) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${NAMES[ch]} row $first: ${system.noteName(cell.note)} " +
                        "has no instrument; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /**
     * Walks the arrangement once in order ([SccWalk]: jumps ignored, delays at their tick) and reports note delays the
     * speed never reaches, `Wxx` on rows where no note sounds and, on the SCC, every stretch where SCC 4 and SCC 5 both
     * hold notes whose waves differ (in content): the shared wave RAM then plays only the latest claim on both.
     */
    private fun straightPlay(song: Song, out: Report) {
        val sound = song.scc
        val walk = SccWalk(sound, song.ticksPerRow)
        val seen = HashSet<String>()
        walk.warn = { if (seen.add(it)) out += it }
        var previous: Pair<List<Int>, List<Int>>? = null
        var sharedReports = 0
        var sharedMore = 0
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            for (row in 0 until pattern.length) {
                walk.where = { ch -> "$label, ${NAMES[ch]} row $row" }
                walk.row(pattern.tracks, o, row)
                if (!walk.shared) continue
                val (a, b) = SccSound.SHARED_CHANNELS.first to SccSound.SHARED_CHANNELS.last
                val key = if (walk.keyed[a] && walk.keyed[b]) walk.waveSet(a) to walk.waveSet(b) else null
                val clash = key != null && key.first.map { sound.wave(it)?.samples }.toSet() != key.second.map { sound.wave(it)?.samples }.toSet()
                if (!clash) { previous = null; continue }
                if (key == previous) continue
                previous = key
                if (sharedReports == MAX_SHARED_REPORTS) { sharedMore++; continue }
                sharedReports++
                out += "Order ${hex2(o)} row $row: ${NAMES[a]} (${setText(song, key!!.first)}) and ${NAMES[b]} (${setText(song, key.second)}) " +
                    "sound together, but the SCC (K051649) gives them one shared wave RAM, so both play the latest claim: " +
                    "${walk.heldText(song)}. Give both the same wave, end one note with OFF before the other starts (a note " +
                    "holds the RAM until OFF, even after its volume has faded), or switch the song to SCC+ (K052539), which " +
                    "has a wave RAM per channel"
            }
        }
        if (sharedMore > 0) out += "...and $sharedMore more places where SCC 4 and SCC 5 sound together with different waves"
    }

    private fun setText(song: Song, waves: List<Int>): String =
        if (waves.size == 1) SccRules.waveLabel(song, waves[0])
        else "waves " + waves.joinToString(", ") { hex2(it) } + " of a wave envelope"

    /** One warning per distinct test register value that does something unverified, nothing, or freezes the waves. */
    private fun testRegister(song: Song, out: Report) {
        val variant = song.scc.variant
        val seen = HashSet<Int>()
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            for (row in 0 until pattern.length) for (ch in 0 until SccSound.CHANNELS) {
                val c = pattern.tracks[ch][row]
                for (col in 0..c.more.size) {
                    if (c.effectCode(col) != SccRules.FX_TEST) continue
                    val v = c.effectParam(col)
                    if (!seen.add(v)) continue
                    val where = "$label, ${NAMES[ch]} row $row: ${SccRules.effectText(SccRules.FX_TEST, v)}"
                    if (v and (SccRules.TEST_FREQ_4BIT or SccRules.TEST_FREQ_8BIT) != 0) {
                        out += "$where sets an unverified frequency mode (bit 0: only the period's top 4 bits count, bit 1: only " +
                            "its low 8 bits; one chip was measured, and only openMSX models it): MAME's core plays it as " +
                            "measured, but VGM players ignore it, so the exported VGM plays these notes at their normal pitch"
                    }
                    if (v and SccRules.TEST_UNUSED != 0) out += "$where: test register bits 2-4 have no known function"
                    if (variant == SccVariant.PLUS && v and SccRules.TEST_FREEZE_SHARED != 0) {
                        out += "$where: bit 7 only locks the SCC's shared wave RAM, which the SCC+ does not have; the SCC+ ignores it"
                    }
                    val freezes = v and SccRules.TEST_FREEZE_ALL != 0 || (variant == SccVariant.SCC && v and SccRules.TEST_FREEZE_SHARED != 0)
                    if (freezes) {
                        val what = if (v and SccRules.TEST_FREEZE_ALL != 0) "every wave RAM" else "the wave RAM SCC 4 and SCC 5 share"
                        out += "$where freezes $what: wave changes after it (another instrument's wave, Wxx, wave envelopes, " +
                            "edits to a wave) are not written and stay lost until a Txx clears the bit (T00)"
                    }
                }
            }
        }
    }

    /** The notes above G#6 the song plays, with how far the 12-bit period puts them from 12-TET, or null. */
    private fun highNotes(song: Song): String? {
        val notes = HashSet<Int>()
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (track in pattern.tracks) for (row in 0 until pattern.length) {
                val n = track[row].note
                if (n > SccRules.ACCURATE_TOP) notes += n
            }
        }
        if (notes.isEmpty()) return null
        val system = song.scc.variant.system
        return "Notes above G#6 drift from 12-TET on the SCC's 12-bit period (the chip's resolution, not the player's): " +
            notes.sorted().joinToString(", ") { n ->
                "${system.noteName(n)} ${NumberText.fixed(SccRules.cents(n), 1, plus = true)}¢ " +
                    "(period ${SccPlayerImage.period(n * SccPlayerImage.PITCH_UNITS_PER_SEMITONE)})"
            }
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val sound = song.scc
        val used = HashSet<Int>()
        val waves = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) {
            if (cell.instrument > 0) used += cell.instrument
            for (col in 0..cell.more.size) if (cell.effectCode(col) == SccRules.FX_WAVE) waves += cell.effectParam(col)
        }
        for (slot in used) sound.instrument(slot)?.let { waves += it.wave.values }
        add("Instruments" to "${used.size} used, ${sound.instruments.count { it != null }} of ${SccSound.SLOTS} filled")
        add("Waves" to "${waves.size} of ${SccSound.WAVES} played" + if (sound.variant.sharedWave) " (SCC 4 and SCC 5 share one wave RAM)" else "")
        add("Pattern data" to "${kb(SccLowering.dataSize(song))} of ${kb(SccPlayerImage.DATA_CAPACITY)}")
    }

    private fun kb(bytes: Int) = NumberText.fixed(bytes / 1024.0, 1) + " KB"

    private fun hex2(v: Int) = NumberText.hex(v, 2)

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    private class Report {
        private val items = ArrayList<String>()
        private var dropped = 0
        operator fun plusAssign(message: String) {
            if (items.size < MAX_REPORTED) items += message else dropped++
        }
        fun isEmpty() = items.isEmpty()
        fun list(): List<String> = if (dropped == 0) items else items + "...and $dropped more"
    }
}

/**
 * Straight play of an SCC song row by row, as far as the shared wave RAM, the test register and the warnings need it:
 * the player's row rules (docs/AUDIO_ENGINE.md, "SCC and SCC+: Player") at row resolution. Within a row, channels 0..4
 * run at tick 0 and delayed rows (`Gxx`) at their tick (never when the speed does not reach it); a row's effects run in
 * column order before its note. A note keys on unless its slot is empty or portamento (`3xx`) glides to it while the
 * channel is keyed; OFF and `Sxx` release a note whose volume envelope has a release point, else (or when released)
 * key it off. On the SCC a key-on on SCC 4 or SCC 5, or a change of the wave such a channel plays (`Wxx`, a note that
 * ends a lock), is a wave claim: the claiming channel's wave fills the shared RAM unless the test register freezes it.
 * A note's wave is its lock, else the first value of its instrument's wave envelope: the envelope's later steps
 * re-claim the RAM tick by tick, which this row model does not follow (it says so where it matters). Jumps and breaks
 * are not followed (the walk is sequential, as the validator's).
 */
internal class SccWalk(private val sound: SccSound, startSpeed: Int) {
    /** The latest claim of the SCC's shared wave RAM: by [channel] at ([order], [row]), its note's [slot] and [wave]. */
    data class Claim(val channel: Int, val order: Int, val row: Int, val slot: Int, val wave: Int, val byNote: Boolean)

    private val channels = SccSound.CHANNELS

    /** True on the SCC: SCC 4 and SCC 5 share a wave RAM. */
    val shared = sound.variant.sharedWave

    var speed = startSpeed
        private set

    /** The test register's value. */
    var test = 0
        private set

    /** Each channel's instrument (the last instrument byte), its sounding note's slot, and its wave lock (0 none). */
    val instrument = IntArray(channels)
    val noteSlot = IntArray(channels)
    val lock = IntArray(channels)

    /** Keyed (a note held, released or not), and active (keyed a note since the start: the player updates it). */
    val keyed = BooleanArray(channels)
    val active = BooleanArray(channels)

    /** The wave each active channel plays at its note's start (its lock, else its wave envelope's first value). */
    val current = IntArray(channels)

    private val released = BooleanArray(channels)
    private val portamento = IntArray(channels)
    private val delays = IntArray(channels)

    /** The shared RAM's latest claim (SCC), or null before any. */
    var claim: Claim? = null
        private set

    /** The wave number the shared RAM holds (SCC); 0 = never written (silent). */
    var held = 0
        private set

    /** Where a message about [channel] on the current row points; set by the caller before [row]. */
    var where: (Int) -> String = { NAMES_FALLBACK }

    /** Receives warnings (delays never reached, `Wxx` where nothing sounds); null: none wanted. */
    var warn: ((String) -> Unit)? = null

    private var order = -1
    private var row = -1

    /** Shared wave RAM writes are suppressed by the test register (bit 6, or bit 7 on the SCC). */
    val frozen: Boolean get() = test and SccRules.TEST_FREEZE_ALL != 0 || (shared && test and SccRules.TEST_FREEZE_SHARED != 0)

    /** The waves the note on [channel] may play: its lock, else every value of its wave envelope. */
    fun waveSet(channel: Int): List<Int> =
        if (lock[channel] > 0) listOf(lock[channel]) else sound.instrument(noteSlot[channel])?.wave?.values?.distinct().orEmpty()

    /** Runs row [r] of order [order] given its pattern's [tracks]. */
    fun row(tracks: List<List<Cell>>, order: Int, r: Int) {
        this.order = order
        row = r
        for (ch in 0 until channels) {
            val c = tracks[ch][r]
            for (col in 0..c.more.size) if (c.effectCode(col) == SccRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
        }
        var delayed = false
        for (ch in 0 until channels) {
            val c = tracks[ch][r]
            var d = 0
            for (col in 0..c.more.size) if (c.effectCode(col) == SccRules.FX_DELAY && c.effectParam(col) > 0) d = c.effectParam(col)
            delays[ch] = d
            when {
                d == 0 -> run(c, ch, 0)
                d >= speed -> {
                    warn?.invoke("${where(ch)}: G${NumberText.hex(d, 2)} waits $d ticks but rows are $speed ticks; the row never plays")
                    delays[ch] = -1
                }
                else -> delayed = true
            }
        }
        if (!delayed) return
        for (tick in 1 until speed) for (ch in 0 until channels) if (delays[ch] == tick) run(tracks[ch][r], ch, tick)
    }

    private fun run(c: Cell, ch: Int, tick: Int) {
        val hasNote = c.note >= 0
        if (hasNote) lock[ch] = 0
        if (c.instrument != 0) instrument[ch] = c.instrument
        var cut = -1
        var wave = 0
        for (col in 0..c.more.size) {
            val code = c.effectCode(col)
            val param = c.effectParam(col)
            if (code == 0 && param == 0) continue
            when (code) {
                SccRules.FX_PORTAMENTO -> portamento[ch] = param
                SccRules.FX_SLIDE_UP, SccRules.FX_SLIDE_DOWN -> portamento[ch] = 0
                SccRules.FX_WAVE -> { lock[ch] = param; wave = param }
                SccRules.FX_TEST -> test = param
                SccRules.FX_CUT -> cut = param
            }
        }
        if (!hasNote && wave > 0 && !keyed[ch]) {
            val sharedNote = if (shared && ch in SccSound.SHARED_CHANNELS && active[ch]) {
                "; on the SCC it still claims the wave RAM SCC 4 and SCC 5 share, so the other channel switches to it"
            } else ""
            warn?.invoke("${where(ch)}: ${SccRules.effectText(SccRules.FX_WAVE, wave)} changes the sounding note's wave, but no " +
                "note sounds here (the channel is keyed off) and the next note clears it, so this channel never plays it" +
                "$sharedNote; put it on a note's row")
        }
        var keyedOn = false
        when {
            c.note == Cell.CUT -> keyOff(ch)
            hasNote && portamento[ch] > 0 && keyed[ch] -> Unit
            hasNote -> keyedOn = keyOn(ch)
        }
        if (cut in tick until speed) keyOff(ch)
        if (active[ch]) {
            val w = if (lock[ch] > 0) lock[ch] else sound.instrument(noteSlot[ch])?.wave?.values?.firstOrNull() ?: 0
            if (keyedOn || w != current[ch]) {
                current[ch] = w
                if (shared && ch in SccSound.SHARED_CHANNELS) claim = Claim(ch, order, row, noteSlot[ch], w, keyedOn)
            }
        }
        if (shared && !frozen) claim?.let { held = current[it.channel] }
    }

    private fun keyOn(ch: Int): Boolean {
        val slot = instrument[ch]
        if (sound.instrument(slot) == null) return false
        noteSlot[ch] = slot
        keyed[ch] = true
        released[ch] = false
        active[ch] = true
        return true
    }

    private fun keyOff(ch: Int) {
        if (!keyed[ch]) return
        val volume = sound.instrument(noteSlot[ch])?.volume
        if (volume != null && volume.values.isNotEmpty() && volume.release >= 0 && !released[ch]) released[ch] = true
        else keyed[ch] = false
    }

    /** What the shared RAM holds and who claimed it, for messages: "wave 05 "Pulse 25%" from SCC 5's note at order 01 row 8". */
    fun heldText(song: Song): String {
        val c = claim ?: return "nothing yet (no note on SCC 4 or SCC 5)"
        val how = if (c.byNote) "note" else "wave change"
        val holds = if (held == 0) "silence (the RAM was never written)" else SccRules.waveLabel(song, held)
        val frozenNote = if (held != c.wave) " (frozen by T${NumberText.hex(test, 2)}; the claim was for ${SccRules.waveLabel(song, c.wave)})" else ""
        return "$holds from ${SccRules.CHANNEL_NAMES[c.channel]}'s $how at order ${NumberText.hex(c.order, 2)} row ${c.row}$frozenNote"
    }

    private companion object {
        const val NAMES_FALLBACK = "SCC"
    }
}
