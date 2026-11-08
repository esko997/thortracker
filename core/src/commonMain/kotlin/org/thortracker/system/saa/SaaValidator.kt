package org.thortracker.system.saa

import org.thortracker.backend.SaaLowering
import org.thortracker.backend.SaaPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope

/**
 * SAM Coupé and CMS song checks. [errors] mirror what `sa_load_image` (`saa/saa_image.c`,
 * [SaaPlayerImage.LOADER_PROBLEMS]) rejects, as user-facing messages naming pattern, channel and row: the header's
 * ranges (speed, multispeed, orders, loop), instrument records and their envelopes ([instrumentProblem]: noise clock,
 * pan, hardware envelope, envelope lengths, loop and release points and values, plus an empty envelope that still
 * carries a loop or release point), cells (notes in the variant's range, defined instruments, effect codes and the
 * parameters of `B C E F M N Y`) and the data region's capacity; the rest of the loader's reasons (magic, layout,
 * reserved bytes, offsets, runs, the channel count) are what the lowering always writes correctly. [warnings] are the
 * specification's "Should" and the chip's shared resources, found by a row-level walk of straight play ([SaaWalk]):
 * two lanes of one triple sounding noise with different clocks at once; a hardware envelope on a lane without one;
 * an internal-clock envelope whose clock lane never set a pitch; `Y02`/`Y03` still in force at the loop point;
 * `8xy`/`Exx`/`Mxx`/`Nxx` where nothing sounds; `Gxx` beyond the speed; an order's first note without an instrument.
 */
object SaaValidator {
    private const val MAX_REPORTED = 40

    /** Most noise clashes reported one by one; the rest are counted. */
    private const val MAX_NOISE_REPORTS = 8

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        val sound = song.sound
        if (sound !is SaaSound) {
            out += "The song is for ${song.system.name}, not the SAA1099"
            return out.list()
        }
        val variant = sound.variant
        val names = variant.laneNames
        val channels = variant.channels
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != channels) {
                out += "$label has ${pattern.tracks.size} channels; ${variant.label} patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${names[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    SaaRules.cellRangeProblem(cell, variant)?.let { out += "$label, ${names[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        if (sound.instruments.size != SaaSound.SLOTS) {
            out += "The song has ${sound.instruments.size} instrument slots; SAA1099 songs have exactly ${SaaSound.SLOTS}"
        }
        sound.instruments.forEachIndexed { i, ins -> ins?.let(::instrumentProblem)?.let { out += "Instrument ${hex2(i + 1)}: $it" } }
        if (sound.multispeed !in 1..SaaPlayerImage.MAX_MULTISPEED) {
            out += "Multispeed ${sound.multispeed} is outside 1-${SaaPlayerImage.MAX_MULTISPEED}"
        }
        song.tuning?.problems(sound.system)?.forEach { out += it }
        if (song.newPatternScale.root !in 0..11) out += "The scale for new patterns has root ${song.newPatternScale.root}; roots are 0-11 (C-B)"
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) {
            out += "Parked orders ${song.parkedOrders} must be ascending, distinct orders 0-${song.orders.size - 1}"
        }
        return out.list()
    }

    /**
     * Why [ins] cannot be stored (the loader's record and envelope checks), or null: envelopes hold at most 256 values;
     * an empty envelope has no loop or release point; loop points lie inside the envelope; only the volume envelope may
     * have a release point, at step 1..length − 1, after its loop; volumes 0..15, arpeggio and pitch −64..63, mixer
     * 0..3; noise clock 0..3; pan levels 0..15; a hardware envelope's shape 0..7 and rate 1..255.
     */
    fun instrumentProblem(ins: SaaInstrument): String? =
        envelopeProblem("volume", ins.volume, NesEnvelope.VOLUME, release = true)
            ?: envelopeProblem("arpeggio", ins.arpeggio, NesEnvelope.ARPEGGIO, release = false)
            ?: envelopeProblem("pitch", ins.pitch, NesEnvelope.PITCH, release = false)
            ?: envelopeProblem("mixer", ins.mixer, SaaInstrument.MIXER, release = false)
            ?: when {
                ins.noise !in SaaInstrument.NOISE -> "its noise clock ${ins.noise} is outside 0-3"
                ins.panLeft !in SaaInstrument.PAN -> "its left pan level ${ins.panLeft} is outside 0-15"
                ins.panRight !in SaaInstrument.PAN -> "its right pan level ${ins.panRight} is outside 0-15"
                else -> ins.envelope?.problem()
            }

    private fun envelopeProblem(name: String, env: NesEnvelope, range: IntRange, release: Boolean): String? {
        val n = env.values.size
        return when {
            n > SaaPlayerImage.MAX_ENVELOPE -> "the $name envelope has $n steps; envelopes hold at most ${SaaPlayerImage.MAX_ENVELOPE}"
            n == 0 && (env.loop != -1 || env.release != -1) -> "the $name envelope has a loop or release point but no values"
            n == 0 -> null
            env.loop != -1 && env.loop !in 0 until n -> "the $name envelope loops to step ${env.loop}; its steps are 0-${n - 1}"
            env.release != -1 && !release -> "the $name envelope has a release point; only volume envelopes have one"
            env.release != -1 && env.release !in 1 until n ->
                if (n == 1) "the $name envelope has one step, too short for a release point (steps 1 and up)"
                else "the $name envelope's release point ${env.release} is outside steps 1-${n - 1}"
            env.loop != -1 && env.release != -1 && env.loop >= env.release ->
                "the $name envelope loops at step ${env.loop}, not before its release point ${env.release}; the loop must come first"
            else -> env.values.firstOrNull { it !in range }?.let { "the $name envelope has value $it outside ${range.first}..${range.last}" }
        }
    }

    /** Problems that block playing or exporting [document]'s arrangement (its unparked orders). */
    fun errors(document: Song): List<String> {
        val structural = structuralErrors(document)
        if (structural.isNotEmpty()) return structural
        if (document.parkedOrders.size == document.orders.size) {
            return listOf("Every order is parked; unpark at least one to play the song or export it")
        }
        val variant = document.saa.variant
        val system = variant.system
        val song = system.arranged(document)
        val out = Report()
        if (song.orders.size > system.maxOrders) out += "The song has ${song.orders.size} orders; the SAA1099 player plays at most ${system.maxOrders}"
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${variant.laneNames[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) {
            val size = SaaLowering.dataSize(song)
            if (size > SaaPlayerImage.DATA_CAPACITY) {
                out += "The song's pattern and envelope data needs ${kb(size)}; the SAA1099 image holds ${kb(SaaPlayerImage.DATA_CAPACITY)}. " +
                    "Reuse patterns or shorten the song"
            }
        }
        return out.list()
    }

    /** Why [cell] on [channel] cannot load, given the song (its order count as the player sees it and instruments), or null. */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        SaaRules.cellRangeProblem(cell, song.saa.variant)?.let { return it }
        if (cell.instrument != 0 && song.saa.instrument(cell.instrument) == null) {
            return "instrument ${hex2(cell.instrument)} is empty; choose a preset for it or another instrument"
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            SaaRules.paramProblem(code, param, orders)?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = document.saa.variant.system.arranged(document)
        val out = Report()
        firstNotesWithoutInstrument(song).forEach { out += it }
        straightPlay(song, out)
        return out.list()
    }

    /**
     * Each order is also entered by the loop, a seek or a live edit, so its first note on a channel should name the
     * instrument (otherwise it plays whatever that channel last had, possibly nothing on a fresh start).
     */
    private fun firstNotesWithoutInstrument(song: Song): List<String> {
        val out = ArrayList<String>()
        val variant = song.saa.variant
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (ch in 0 until variant.channels) {
                val track = pattern.tracks[ch]
                val first = (0 until pattern.length).firstOrNull { track[it].note >= 0 || track[it].instrument != 0 } ?: continue
                val cell = track[first]
                if (cell.note >= 0 && cell.instrument == 0) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${variant.laneNames[ch]} row $first: ${variant.system.noteName(cell.note)} " +
                        "has no instrument; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /**
     * Walks the arrangement once in order ([SaaWalk]: jumps ignored, delays at their tick) and reports what the walk
     * warns about as it goes (delays the speed never reaches, overrides where nothing sounds, hardware envelopes on
     * lanes without one, internal clocks whose lane never set a pitch), every stretch where two lanes of one triple
     * sound noise with different clocks at once, and a `Y02`/`Y03` still holding a chip when the song loops.
     */
    private fun straightPlay(song: Song, out: Report) {
        val sound = song.saa
        val variant = sound.variant
        val names = variant.laneNames
        val walk = SaaWalk(sound, song.ticksPerRow)
        val seen = HashSet<String>()
        walk.warn = { if (seen.add(it)) out += it }
        val previous = arrayOfNulls<List<Pair<Int, Int>>>(2 * variant.chips)
        var reports = 0
        var more = 0
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            for (row in 0 until pattern.length) {
                walk.where = { ch -> "$label, ${names[ch]} row $row" }
                walk.row(pattern.tracks, o, row)
                for (chip in 0 until variant.chips) for (g in 0..1) {
                    val gi = 2 * chip + g
                    val lanes = SaaSound.generatorLanes(chip, g).filter { walk.soundingNoise[it] >= 0 }.map { it to walk.soundingNoise[it] }
                    val clash = lanes.map { it.second }.distinct().size > 1
                    if (!clash) { previous[gi] = null; continue }
                    if (lanes == previous[gi]) continue
                    previous[gi] = lanes
                    if (reports == MAX_NOISE_REPORTS) { more++; continue }
                    reports++
                    val range = SaaSound.generatorLanes(chip, g)
                    val claim = walk.owner[gi]
                    val latest = if (claim == null) "its last setting" else
                        "${SaaRules.noiseText(claim.clock, variant, claim.channel)} from ${names[claim.channel]}'s note at order ${hex2(claim.order)} row ${claim.row}"
                    out += "Order ${hex2(o)} row $row: " + lanes.joinToString(" and ") { (ch, clock) -> "${names[ch]} (noise ${SaaRules.noiseText(clock, variant, ch)})" } +
                        " sound noise together, but ${names[range.first]}-${names[range.last]} share one noise generator, so all of " +
                        "them play the latest claim: $latest. Give them the same noise clock (the instrument's or Nxx), or end one " +
                        "note before the other starts"
                }
            }
        }
        if (more > 0) out += "...and $more more places where lanes sharing a noise generator sound noise with different clocks"
        val loopRow = song.patterns[song.orders[song.loopOrder]]
        for (chip in 0 until variant.chips) {
            val held = walk.sync[chip] ?: continue
            if (held.value != SaaRules.SYNC_HOLD && held.value != SaaRules.SYNC_OFF) continue
            val restored = walk.syncAt(loopRow.tracks, 0, chip)
            if (restored == SaaRules.SYNC_NORMAL || restored == SaaRules.SYNC_RESTART) continue
            val what = if (held.value == SaaRules.SYNC_HOLD) "holds every generator (silent)" else "turns the sound off"
            val chipText = if (variant.chips == 1) "the chip" else "chip ${'A' + chip}"
            out += "${SaaRules.effectText(SaaRules.FX_SYNC, held.value)} on ${names[held.channel]} at order ${hex2(held.order)} " +
                "row ${held.row} still $what when the song loops to order ${hex2(song.loopOrder)}: $chipText stays silent there " +
                "until a Y00${if (held.value == SaaRules.SYNC_HOLD) " or Y01" else ""}; put one on the loop order's first row or before the end"
        }
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val sound = song.saa
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) {
            if (cell.instrument > 0) used += cell.instrument
        }
        add("Instruments" to "${used.size} used, ${sound.instruments.count { it != null }} of ${SaaSound.SLOTS} filled")
        val envelopes = used.count { sound.instrument(it)?.envelope != null }
        add("Hardware envelopes" to "$envelopes instrument${if (envelopes == 1) "" else "s"} with one (lanes 3 and 6 of each chip play them)")
        add("Pattern data" to "${kb(SaaLowering.dataSize(song))} of ${kb(SaaPlayerImage.DATA_CAPACITY)}")
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
 * Straight play of an SAA1099 song row by row, as far as the shared resources and the warnings need it: the player's
 * row rules (docs/AUDIO_ENGINE.md, "SAA1099: Player") with envelope positions counted in ticks. Within a row, channels
 * run at tick 0 and delayed rows (`Gxx`) at their tick (never when the speed does not reach it); a row's effects run in
 * column order before its note, a note clears the note-scoped overrides (`8xy`, `Exx`, `Mxx`, `Nxx`) first. A note keys
 * on unless its slot is empty or portamento (`3xx`) glides to it while the channel is keyed; OFF and `Sxx` release a
 * note whose volume envelope has a release point, else (or when released) key it off.
 *
 * After each row: every keyed lane's mixer value (the `Mxx` override, else its mixer envelope at the row's first tick,
 * or at its key-on tick for a note started in the row) decides its **noise claim** (the player's rule: a key-on with
 * noise, noise switched on, or another clock; later ticks and higher channels win), and [soundingNoise] says which
 * clock each lane audibly plays noise with (noise in the mixer, volume above 0, a channel volume and pan above 0, its
 * chip not held or off by `Y02`/`Y03`). Volume slides (`Axy`) and envelope steps within a row are not followed. Jumps
 * and breaks are not followed (the walk is sequential, as the validator's).
 */
internal class SaaWalk(private val sound: SaaSound, startSpeed: Int) {
    /** A noise claim: by [channel] at ([order], [row]) with its note's [slot], setting the generator to [clock]. */
    data class Claim(val channel: Int, val order: Int, val row: Int, val slot: Int, val clock: Int)

    /** The latest `Yxx` of a chip: [value] by [channel] at ([order], [row]). */
    data class Sync(val value: Int, val channel: Int, val order: Int, val row: Int)

    val variant = sound.variant
    private val channels = variant.channels
    private val names = variant.laneNames

    var speed = startSpeed
        private set

    /** Ticks from the song start to the current row's first tick. */
    private var now = 0L

    /** Each channel's instrument (the last instrument byte), its sounding note's slot and note (-1 before any). */
    val instrument = IntArray(channels)
    val noteSlot = IntArray(channels)
    val note = IntArray(channels) { -1 }

    /** Keyed (a note held, released or not), and active (keyed a note since the start: its tone generator follows its pitch). */
    val keyed = BooleanArray(channels)
    val active = BooleanArray(channels)

    /** The channel volume (`Cxx`, start 15). */
    val volume = IntArray(channels) { 15 }

    /** Note-scoped overrides of the sounding note (-1: none): `8xy`, `Mxx`, `Nxx`, `Exx`. */
    val pan = IntArray(channels) { -1 }
    val mixer = IntArray(channels) { -1 }
    val noise = IntArray(channels) { -1 }
    val envelope = IntArray(channels) { -1 }

    /** The noise clock each lane audibly plays noise with after the current row, or -1 (see the class comment). */
    val soundingNoise = IntArray(channels) { -1 }

    /** Each noise generator's clock (index 2 · chip + generator) and the claim that set it (null: never claimed). */
    val generator = IntArray(2 * variant.chips)
    val owner = arrayOfNulls<Claim>(2 * variant.chips)

    /** Each chip's latest `Yxx`, or null before any (normal). */
    val sync = arrayOfNulls<Sync>(variant.chips)

    /** Where a message about [channel] on the current row points; set by the caller before [row]. */
    var where: (Int) -> String = { names.getOrElse(it) { "SAA1099" } }

    /** Receives warnings; null: none wanted. */
    var warn: ((String) -> Unit)? = null

    private val released = BooleanArray(channels)
    private val portamento = IntArray(channels)
    private val delays = IntArray(channels)
    private val keyTick = LongArray(channels)
    private val releaseTick = LongArray(channels) { -1 }
    private val keyedOnRow = BooleanArray(channels)
    private val lastMixer = IntArray(channels)
    private val lastClock = IntArray(channels)
    private val wrongLane = HashSet<Long>()
    private val unclocked = HashSet<Int>()

    private var order = -1
    private var row = -1

    /** Runs row [r] of order [order] given its pattern's [tracks]. */
    fun row(tracks: List<List<Cell>>, order: Int, r: Int) {
        this.order = order
        row = r
        for (ch in 0 until channels) {
            val c = tracks[ch][r]
            for (col in 0..c.more.size) if (c.effectCode(col) == SaaRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
        }
        keyedOnRow.fill(false)
        var delayed = false
        for (ch in 0 until channels) {
            val c = tracks[ch][r]
            var d = 0
            for (col in 0..c.more.size) if (c.effectCode(col) == SaaRules.FX_DELAY && c.effectParam(col) > 0) d = c.effectParam(col)
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
        if (delayed) for (tick in 1 until speed) for (ch in 0 until channels) if (delays[ch] == tick) run(tracks[ch][r], ch, tick)
        claims()
        now += speed
    }

    /** The `Yxx` value the last `Y` of row [r] of [tracks] sets on [chip] (channels in order), or -1 without one. */
    fun syncAt(tracks: List<List<Cell>>, r: Int, chip: Int): Int {
        var v = -1
        for (ch in chip * SaaSound.CHIP_CHANNELS until (chip + 1) * SaaSound.CHIP_CHANNELS) {
            val c = tracks[ch][r]
            for (col in 0..c.more.size) if (c.effectCode(col) == SaaRules.FX_SYNC) v = c.effectParam(col)
        }
        return v
    }

    /** The hardware envelope control value of [channel]'s sounding note (the `Exx` override, else its instrument's); 0 when none or not keyed. */
    fun control(channel: Int): Int {
        if (!keyed[channel]) return 0
        if (envelope[channel] >= 0) return envelope[channel]
        return sound.instrument(noteSlot[channel])?.envelope?.control ?: 0
    }

    /** The external clock rate of [channel]'s sounding note (its instrument's, 1 when none). */
    fun rate(channel: Int): Int = sound.instrument(noteSlot[channel])?.envelope?.rate ?: 1

    /** The noise clock of [channel]'s sounding note: the `Nxx` override, else its instrument's. */
    fun clock(channel: Int): Int = if (noise[channel] >= 0) noise[channel] else sound.instrument(noteSlot[channel])?.noise ?: 0

    /** True while `Y02` or `Y03` holds [chip] silent. */
    fun silenced(chip: Int): Boolean = sync[chip]?.value.let { it == SaaRules.SYNC_HOLD || it == SaaRules.SYNC_OFF }

    private fun run(c: Cell, ch: Int, tick: Int) {
        val hasNote = c.note >= 0
        if (hasNote) {
            pan[ch] = -1; mixer[ch] = -1; noise[ch] = -1; envelope[ch] = -1
        }
        if (c.instrument != 0) instrument[ch] = c.instrument
        var cut = -1
        var envelopeEffect = false
        for (col in 0..c.more.size) {
            val code = c.effectCode(col)
            val param = c.effectParam(col)
            if (code == 0 && param == 0) continue
            when (code) {
                SaaRules.FX_PORTAMENTO -> portamento[ch] = param
                SaaRules.FX_SLIDE_UP, SaaRules.FX_SLIDE_DOWN -> portamento[ch] = 0
                SaaRules.FX_VOLUME -> volume[ch] = param
                SaaRules.FX_PAN -> pan[ch] = param
                SaaRules.FX_MIXER -> mixer[ch] = param
                SaaRules.FX_NOISE -> noise[ch] = param
                SaaRules.FX_ENVELOPE -> { envelope[ch] = param; envelopeEffect = true }
                SaaRules.FX_SYNC -> sync[SaaSound.chipOf(ch)] = Sync(param, ch, order, row)
                SaaRules.FX_CUT -> cut = param
            }
            if (code == SaaRules.FX_ENVELOPE && !SaaSound.hasEnvelope(ch)) {
                if (param != 0 && wrongLane.add(ch.toLong() shl 8 or 0xFF)) {
                    warn?.invoke("${where(ch)}: ${SaaRules.effectText(code, param)} does nothing here: only lanes 3 and 6 of a chip " +
                        "have a hardware envelope (${envelopeLanes()})")
                }
            } else if (!hasNote && !keyed[ch] && code in NOTE_OVERRIDES) {
                warn?.invoke("${where(ch)}: ${SaaRules.effectText(code, param)} changes the sounding note, but no note sounds " +
                    "here (the channel is keyed off) and the next note clears it, so it never plays; put it on a note's row")
            }
        }
        val t = now + tick
        var keyedOn = false
        when {
            c.note == Cell.CUT -> keyOff(ch, t)
            hasNote && portamento[ch] > 0 && keyed[ch] -> note[ch] = c.note
            hasNote -> keyedOn = keyOn(ch, c.note, t)
        }
        if (cut in tick until speed) keyOff(ch, now + cut)
        if (keyedOn || (envelopeEffect && keyed[ch])) checkEnvelope(ch, keyedOn && envelope[ch] < 0)
    }

    /** Envelope warnings for [ch]'s sounding note: on a lane without an envelope, or clocked by a lane that never played. */
    private fun checkEnvelope(ch: Int, fromInstrument: Boolean) {
        val v = control(ch)
        if (v == 0) return
        if (!SaaSound.hasEnvelope(ch)) {
            if (fromInstrument && wrongLane.add(ch.toLong() shl 8 or noteSlot[ch].toLong())) {
                val ins = sound.instrument(noteSlot[ch])
                warn?.invoke("${where(ch)}: instrument ${NumberText.hex(noteSlot[ch], 2)} \"${ins?.name.orEmpty()}\" has a hardware " +
                    "envelope, but only lanes 3 and 6 of a chip have one (${envelopeLanes()}); here the note plays without it")
            }
            return
        }
        val clockLane = SaaSound.envelopeClockLane(ch)
        if (v and SaaPlayerImage.ENV_EXTERNAL == 0 && !active[clockLane] && unclocked.add(ch)) {
            warn?.invoke("${where(ch)}: the hardware envelope (${SaaRules.envelopeText(v, variant, ch)}) steps with " +
                "${names[clockLane]}'s tone generator, but ${names[clockLane]} has not played a note yet, so it runs at the " +
                "chip's lowest tone (about 31 Hz) and the envelope crawls; play a note on ${names[clockLane]} first (its pitch " +
                "sets the envelope's speed, even at volume 0) or use an external clock")
        }
    }

    private fun envelopeLanes(): String =
        if (variant.chips == 1) "${names[2]} and ${names[5]}" else "${names[2]}, ${names[5]}, ${names[8]} and ${names[11]}"

    private fun keyOn(ch: Int, n: Int, t: Long): Boolean {
        val slot = instrument[ch]
        if (sound.instrument(slot) == null) return false
        noteSlot[ch] = slot
        note[ch] = n
        keyed[ch] = true
        released[ch] = false
        active[ch] = true
        keyTick[ch] = t
        releaseTick[ch] = -1
        keyedOnRow[ch] = true
        return true
    }

    private fun keyOff(ch: Int, t: Long) {
        if (!keyed[ch]) return
        val volume = sound.instrument(noteSlot[ch])?.volume
        if (volume != null && volume.values.isNotEmpty() && volume.release >= 0 && !released[ch]) {
            released[ch] = true
            releaseTick[ch] = t
        } else keyed[ch] = false
    }

    /** Noise claims of this row (in time, then channel order) and each lane's audible noise clock. */
    private fun claims() {
        val pending = ArrayList<LongArray>()
        for (ch in 0 until channels) {
            if (!keyed[ch] || !active[ch]) {
                lastMixer[ch] = 0; lastClock[ch] = 0; soundingNoise[ch] = -1
                continue
            }
            val at = if (keyedOnRow[ch]) keyTick[ch] else now
            val m = mixerAt(ch, at)
            val clock = clock(ch)
            if (m and SaaInstrument.MIXER_NOISE != 0 && (keyedOnRow[ch] || lastMixer[ch] and SaaInstrument.MIXER_NOISE == 0 || lastClock[ch] != clock)) {
                pending += longArrayOf(at, ch.toLong(), clock.toLong())
            }
            lastMixer[ch] = m
            lastClock[ch] = clock
            val ins = sound.instrument(noteSlot[ch])
            val panByte = if (pan[ch] >= 0) pan[ch] else ins?.pan ?: 0
            val audible = m and SaaInstrument.MIXER_NOISE != 0 && volumeAt(ch, at) > 0 && volume[ch] > 0 && panByte != 0 &&
                !silenced(SaaSound.chipOf(ch))
            soundingNoise[ch] = if (audible) clock else -1
        }
        pending.sortedWith(compareBy<LongArray>({ it[0] }, { it[1] })).forEach { (_, chL, clockL) ->
            val ch = chL.toInt()
            val gi = 2 * SaaSound.chipOf(ch) + SaaSound.generatorOf(ch)
            generator[gi] = clockL.toInt()
            owner[gi] = Claim(ch, order, row, noteSlot[ch], clockL.toInt())
        }
    }

    private fun mixerAt(ch: Int, t: Long): Int {
        if (mixer[ch] >= 0) return mixer[ch]
        val env = sound.instrument(noteSlot[ch])?.mixer ?: return SaaInstrument.MIXER_TONE
        val i = index(env, ch, t)
        return if (i < 0) SaaInstrument.MIXER_TONE else env.values[i]
    }

    private fun volumeAt(ch: Int, t: Long): Int {
        val env = sound.instrument(noteSlot[ch])?.volume ?: return 15
        val i = index(env, ch, t)
        return if (i < 0) 15 else env.values[i]
    }

    /**
     * The index the player has reached in [env] of [ch]'s note at tick [t] (-1 for an empty envelope): steps to the
     * end (the release point while not released), then the loop point while not released, else hold; a release jumps
     * the volume envelope to its release point.
     */
    private fun index(env: NesEnvelope, ch: Int, t: Long): Int {
        val n = env.values.size
        if (n == 0) return -1
        val hasRelease = env.release >= 0
        val end = if (hasRelease) env.release else n
        val r = releaseTick[ch]
        if (r < 0 || r > t) return advance(0, t - keyTick[ch], end, env.loop)
        val atRelease = advance(0, r - keyTick[ch], end, env.loop)
        return advance(if (hasRelease) env.release else atRelease, t - r, n, -1)
    }

    private fun advance(from: Int, ticks: Long, end: Int, loop: Int): Int {
        val pos = from + maxOf(0L, ticks)
        if (pos <= end - 1) return pos.toInt()
        if (loop < 0 || loop >= end) return maxOf(from, end - 1)
        return (loop + (pos - loop) % (end - loop)).toInt()
    }

    private companion object {
        val NOTE_OVERRIDES = setOf(SaaRules.FX_PAN, SaaRules.FX_MIXER, SaaRules.FX_NOISE, SaaRules.FX_ENVELOPE)
    }
}
