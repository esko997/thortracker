package org.thortracker.system.pokey

import org.thortracker.backend.PokeyLowering
import org.thortracker.backend.PokeyPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import kotlin.math.abs

/**
 * POKEY song checks. [structuralErrors] are shape and range problems; [errors] add what `pk_load_image`
 * (`pokey/pokey_image.c`) rejects and what the player would leave silent (a sound on a channel its kind cannot use);
 * [warnings] are the specification's "Should"s: cutting a sounding voice, distortions the 15 kHz clock cannot play,
 * notes the counter cannot reach, effects that do nothing where they stand (docs/AUDIO_ENGINE.md, "POKEY").
 *
 * Only the fields an instrument's kind uses are checked: the editor keeps the others for a lossless kind switch, and
 * the lowering writes them as 0 / 0xFFFF.
 */
object PokeyValidator {
    private const val MAX_REPORTED = 40

    /** Off-pitch notes beyond this many cents are reported. */
    private const val PITCH_TOLERANCE_CENTS = 50.0

    private val NAMES = PokeyRules.CHANNEL_NAMES
    private const val LANES = PokeyPlayerImage.CHANNELS

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        if (song.sound !is PokeySound) {
            out += "The song is for ${song.system.name}, not the POKEY"
            return out.list()
        }
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != LANES) {
                out += "$label has ${pattern.tracks.size} channels; POKEY patterns have $LANES"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell -> PokeyRules.cellRangeProblem(cell)?.let { out += "$label, ${NAMES[ch]} row $row: $it" } }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        val sound = song.pokey
        if (sound.instruments.size != PokeySystem.SLOTS) {
            out += "The song has ${sound.instruments.size} instrument slots; POKEY songs have exactly ${PokeySystem.SLOTS}"
        }
        sound.instruments.forEachIndexed { i, ins -> ins?.let { instrumentProblem(it) }?.let { out += "Instrument ${hex2(i + 1)}: $it" } }
        if (sound.samples.size > PokeySystem.MAX_SAMPLES) out += "The song has ${sound.samples.size} samples; at most ${PokeySystem.MAX_SAMPLES}"
        sound.samples.forEachIndexed { k, s -> sampleProblem(s)?.let { out += "Sample ${hex2(k)}: $it" } }
        if (sound.multispeed !in 1..PokeySystem.MAX_MULTISPEED) out += "Multispeed ${sound.multispeed} is outside 1-${PokeySystem.MAX_MULTISPEED}"
        song.tuning?.problems(PokeySystem)?.forEach { out += it }
        if (song.newPatternScale.root !in 0..11) out += "The scale for new patterns has root ${song.newPatternScale.root}; roots are 0-11 (C-B)"
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) {
            out += "Parked orders ${song.parkedOrders} must be ascending, distinct orders 0-${song.orders.size - 1}"
        }
        return out.list()
    }

    /** Range problems of the fields [ins]'s kind uses, or null. */
    fun instrumentProblem(ins: PokeyInstrument): String? {
        val kind = ins.kind
        val sample = kind == PokeyKind.SAMPLE
        if (!sample) {
            envelopeProblem("volume", ins.volume, NesEnvelope.VOLUME)?.let { return it }
            envelopeProblem("distortion", ins.distortion, PokeyDistortion.RANGE)?.let { return it }
        }
        envelopeProblem("arpeggio", ins.arpeggio, NesEnvelope.ARPEGGIO)?.let { return it }
        envelopeProblem("pitch", ins.pitch, NesEnvelope.PITCH)?.let { return it }
        if (kind.filtered) {
            if (ins.difference && ins.filterOffset !in PokeyInstrument.DIFFERENCE_OFFSET) {
                return "a difference sound's offset is 1-127 (it is ${ins.filterOffset})"
            }
            if (ins.filterOffset !in PokeyInstrument.FILTER_OFFSET) return "high-pass offset ${ins.filterOffset} is outside -128..127"
        }
        if (kind.joined || kind.filtered) {
            if (ins.partnerDistortion !in PokeyDistortion.RANGE) return "partner distortion ${ins.partnerDistortion} is outside 0-8"
            if (ins.partnerVolume !in PokeyInstrument.VOLUME) return "partner volume ${ins.partnerVolume} is outside 0-15"
        }
        if (sample) {
            if (ins.sample !in 0 until PokeySystem.MAX_SAMPLES) return "sample number ${ins.sample} is outside 0-${PokeySystem.MAX_SAMPLES - 1}"
            if (ins.root !in PokeyRules.NOTES) return "root note ${ins.root} is outside C-0..B-8"
            if (ins.rate !in PokeyInstrument.RATES) return "rate ${ins.rate} is outside ${PokeyInstrument.RATES.first}-${PokeyInstrument.RATES.last} levels per second"
            if (ins.loop !in -1 until PokeySample.MAX_LENGTH) return "loop start ${ins.loop} is outside the sample"
        }
        return null
    }

    /** An envelope the image cannot store: values out of [range], too long, loop or release out of place. */
    fun envelopeProblem(name: String, e: NesEnvelope, range: IntRange): String? {
        val n = e.values.size
        if (n > PokeyPlayerImage.MAX_ENVELOPE) return "the $name envelope has $n steps; at most ${PokeyPlayerImage.MAX_ENVELOPE}"
        e.values.firstOrNull { it !in range }?.let { return "$name envelope value $it is outside ${range.first}..${range.last}" }
        if (n == 0) {
            return if (e.loop != -1 || e.release != -1) "the empty $name envelope has a loop or release" else null
        }
        if (e.loop != -1 && e.loop !in 0 until n) return "the $name envelope loops to step ${e.loop}, past its $n steps"
        if (e.release != -1) {
            if (e.release !in 1 until n) return "the $name envelope's release step ${e.release} is outside 1-${n - 1}"
            if (e.loop >= 0 && e.release <= e.loop) return "the $name envelope's release step ${e.release} must come after its loop (${e.loop})"
        }
        return null
    }

    fun sampleProblem(s: PokeySample): String? = when {
        s.levels.isEmpty() -> "\"${s.name}\" is empty"
        s.levels.size > PokeySample.MAX_LENGTH -> "\"${s.name}\" has ${s.levels.size} levels; at most ${PokeySample.MAX_LENGTH}"
        s.levels.any { it !in 0..15 } -> "\"${s.name}\" has levels outside 0-15"
        s.name.length > PokeySample.MAX_NAME -> "the name \"${s.name}\" is longer than ${PokeySample.MAX_NAME} characters"
        else -> null
    }

    /** Problems that block playing or exporting [document]'s arrangement (its unparked orders). */
    fun errors(document: Song): List<String> {
        val structural = structuralErrors(document)
        if (structural.isNotEmpty()) return structural
        if (document.parkedOrders.size == document.orders.size) {
            return listOf("Every order is parked; unpark at least one to play the song or export it")
        }
        val song = PokeySystem.arranged(document)
        val sound = song.pokey
        val out = Report()
        if (song.orders.size > PokeySystem.maxOrders) out += "The song has ${song.orders.size} orders; the POKEY player plays at most ${PokeySystem.maxOrders}"
        sound.instruments.forEachIndexed { i, ins ->
            if (ins == null || ins.kind != PokeyKind.SAMPLE) return@forEachIndexed
            val s = sound.samples.getOrNull(ins.sample)
            when {
                s == null -> out += "Instrument ${hex2(i + 1)} \"${ins.name}\" plays sample ${hex2(ins.sample)}; the song has ${sound.samples.size} " +
                    "sample${if (sound.samples.size == 1) "" else "s"}. Choose one of them or import a sample"
                ins.loop >= s.levels.size -> out += "Instrument ${hex2(i + 1)} \"${ins.name}\" loops from level ${ins.loop}, past the end of " +
                    "\"${s.name}\" (${s.levels.size} levels)"
            }
        }
        val used = PokeySamples.used(sound.samples)
        if (used > PokeySample.MEMORY) {
            out += "The samples need $used levels of memory; the POKEY image holds ${PokeySample.MEMORY}. Shorten or delete samples, " +
                "or import them at a lower rate"
        }
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) cellProblem(song, ch, track[row])?.let { out += "$label, ${NAMES[ch]} row $row: $it" }
            }
        }
        // Notes without a sound number play the lane's last sound: check it where straight play reaches them.
        val walk = PokeyStraightPlay(song)
        walk.onUnplayable = { lane, slot, cell ->
            if (cell.instrument == 0) {
                val ins = sound.instrument(slot)
                val why = if (ins == null) "instrument ${hex2(slot)} is empty" else laneProblem(ins, slot, lane)
                if (why != null) out += "${where(song, walk, lane)}: ${PokeySystem.noteName(cell.note)} plays the lane's last sound, but $why"
            }
        }
        playThrough(song, walk)
        if (out.isEmpty()) {
            val size = PokeyLowering.dataSize(song)
            if (size > PokeyPlayerImage.DATA_CAPACITY) {
                out += "The song's patterns and envelopes need ${kb(size)}; the POKEY image holds ${kb(PokeyPlayerImage.DATA_CAPACITY)}. " +
                    "Reuse patterns or shorten the song"
            }
        }
        return out.list()
    }

    /** Why sound [slot] ([ins]) cannot play on [lane] (the player keys nothing on there), or null. */
    fun laneProblem(ins: PokeyInstrument, slot: Int, lane: Int): String? {
        val kind = ins.kind
        val name = "sound ${hex2(slot)} \"${ins.name}\""
        if (lane !in kind.lanes) {
            val why = if (kind == PokeyKind.SAMPLE && lane == 2) " (timer 3 has no interrupt)" else ""
            return "$name is a ${kind.label} sound, which plays on ${lanes(kind.lanes)} only$why"
        }
        if (ins.fast && lane !in kind.fastLanes) {
            return "$name counts the 1.79 MHz clock, which a ${kind.label} sound has on ${lanes(kind.fastLanes)} only; " +
                "turn Fast off or move it"
        }
        return null
    }

    private fun lanes(set: Set<Int>): String = set.sorted().joinToString(", ") { NAMES[it] }

    /** Why [cell] on [channel] cannot load or play, given the song (its played order count and instruments), or null. */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        PokeyRules.cellRangeProblem(cell)?.let { return it }
        val sound = song.pokey
        if (cell.instrument != 0) {
            val ins = sound.instrument(cell.instrument)
                ?: return "instrument ${hex2(cell.instrument)} is empty; choose a preset for it or another instrument"
            if (cell.note >= 0) laneProblem(ins, cell.instrument, channel)?.let { return it }
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            PokeyRules.paramProblem(code, param, orders)?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = PokeySystem.arranged(document)
        val out = Report()
        firstNotesWithoutInstrument(song, out)
        straightPlay(song, out)
        return out.list()
    }

    /**
     * Each order is also entered by the loop, a seek or a live edit, so its first note on a channel should name the
     * instrument (otherwise it plays whatever that channel last had, possibly nothing on a fresh start).
     */
    private fun firstNotesWithoutInstrument(song: Song, out: Report) {
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (ch in 0 until LANES) {
                val track = pattern.tracks[ch]
                val first = (0 until pattern.length).firstOrNull { track[it].note >= 0 || track[it].instrument != 0 } ?: continue
                val cell = track[first]
                if (cell.note >= 0 && cell.instrument == 0) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${NAMES[ch]} row $first: ${PokeySystem.noteName(cell.note)} has no instrument; " +
                        "an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
    }

    /**
     * Straight play once through the arrangement: notes that cut another lane's unreleased voice, distortions `C` and
     * `4` on the 15 kHz clock, notes their counter plays far off pitch, `Hxx`/`Vxx` where they change nothing and note
     * delays the speed never reaches.
     */
    private fun straightPlay(song: Song, out: Report) {
        val sound = song.pokey
        val machine = sound.machine
        val walk = PokeyStraightPlay(song)
        val reported = HashSet<String>()
        walk.onCut = { lane, victim, channel ->
            if (walk.unreleased(victim)) {
                val v = sound.instrument(walk.voiceSlot[victim])
                out += "${where(song, walk, lane)}: this note takes ${NAMES[channel]} and cuts ${NAMES[victim]}'s " +
                    "\"${v?.name ?: ""}\" (from order ${hex2(walk.voiceOrder[victim])} row ${hex2(walk.voiceRow[victim])}), " +
                    "which has not been released. End it with OFF first, or move one of the notes"
            }
        }
        walk.onKeyOn = { lane, slot, note ->
            val ins = sound.instrument(slot)
            if (ins != null) {
                val pitch = 64 * note + walk.fine[lane]
                val distortion = if (ins.kind == PokeyKind.SAMPLE) PokeyDistortion.VOLUME_ONLY
                    else walk.distortionOverride[lane].takeIf { it >= 0 } ?: ins.distortion.values.firstOrNull() ?: PokeyDistortion.PURE
                val counter = PokeyPitch.counterOf(ins, lane)
                val baseClocked = counter != null && !counter.fast
                if (walk.base15 && baseClocked && (distortion == 2 || distortion == 6) && !(ins.kind.filtered && ins.difference)) {
                    if (reported.add("15k/$slot/$lane/$distortion")) {
                        out += "${where(song, walk, lane)}: \"${ins.name}\" plays distortion ${PokeyDistortion.NAMES[distortion]} on the 15 kHz clock, " +
                            "where every period is a multiple of 3, so its pattern never plays in full (wrong pitch, thin sound). " +
                            "Use K00 or the 1.79 MHz clock"
                    }
                } else if (ins.kind == PokeyKind.SAMPLE || (ins.kind.filtered && ins.difference) || PokeyDistortion.pitched(distortion)) {
                    val cents = PokeyPitch.cents(machine, walk.base15, ins, lane, pitch, distortion)
                    if (!cents.isNaN() && abs(cents) > PITCH_TOLERANCE_CENTS && reported.add("pitch/$slot/$lane/$pitch/$distortion/${walk.base15}")) {
                        val n = PokeyPitch.chosen(machine, walk.base15, ins, lane, pitch, distortion)
                        val hz = PokeyPitch.hzText(PokeyPitch.hz(machine, walk.base15, ins, lane, pitch, distortion))
                        val what = if (ins.kind == PokeyKind.SAMPLE) "its sample at ${hz.removeSuffix(" Hz")} levels per second" else hz
                        val why = if (PokeyPitch.atLimit(ins, lane, n)) "out of the counter's range" else "the counter's steps are coarse here"
                        out += "${where(song, walk, lane)}: ${PokeySystem.noteName(note)} with \"${ins.name}\" plays $what " +
                            "(${PokeyPitch.centsText(cents)}): $why. Try ${hint(ins)}"
                    }
                }
            }
        }
        playThrough(song, walk) { lane, cell, order, row ->
            val here = "${Songs.patternLabel(song, song.orders[order]).cap()}, ${NAMES[lane]} row $row"
            val slot = if (cell.note >= 0 && cell.instrument != 0) cell.instrument else walk.instrument[lane]
            val ins = if (cell.note >= 0) sound.instrument(slot) else sound.instrument(walk.voiceSlot[lane].takeIf { it != 0 } ?: slot)
            for (col in 0..cell.more.size) {
                val code = cell.effectCode(col)
                val param = cell.effectParam(col)
                if (code == 0 && param == 0) continue
                when (code) {
                    PokeyRules.FX_FILTER -> when {
                        ins == null -> {}
                        !ins.kind.filtered -> out += "$here: H${hex2(param)} changes high-pass sounds only; \"${ins.name}\" is ${ins.kind.label}"
                        ins.difference && param !in PokeyInstrument.DIFFERENCE_OFFSET ->
                            out += "$here: H${hex2(param)} on the difference sound \"${ins.name}\" is clamped to 01-7F"
                    }
                    PokeyRules.FX_DISTORTION -> if (ins != null && ins.kind == PokeyKind.SAMPLE) {
                        out += "$here: V${hex2(param)} does nothing for the sample sound \"${ins.name}\" (samples play volume only)"
                    }
                    PokeyRules.FX_DELAY -> if (param >= walk.speed) {
                        out += "$here: G${hex2(param)} waits $param ticks but rows are ${walk.speed} ticks; the row never plays"
                    }
                }
            }
        }
    }

    private fun hint(ins: PokeyInstrument): String = when (ins.kind) {
        PokeyKind.TONE, PokeyKind.FILTERED -> "a 16-bit sound, another octave, or Fast (1.79 MHz) for high notes"
        PokeyKind.JOINED, PokeyKind.JOINED_FILTERED -> "another octave or Fast"
        PokeyKind.SAMPLE -> "a rate or root nearer the note (one level per scan line at most: 15.7 kHz)"
    }

    /** Feeds the arranged [song] through [walk] once in order; [each] sees every row's cells after the row ran. */
    private fun playThrough(song: Song, walk: PokeyStraightPlay, each: ((lane: Int, cell: Cell, order: Int, row: Int) -> Unit)? = null) {
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            for (row in 0 until pattern.length) {
                walk.row(pattern, o, row)
                if (each != null) for (lane in 0 until LANES) each(lane, pattern.tracks[lane][row], o, row)
            }
        }
    }

    /** The row [walk] is running on [lane], for messages. */
    private fun where(song: Song, walk: PokeyStraightPlay, lane: Int): String {
        val order = walk.currentOrder
        return "${Songs.patternLabel(song, song.orders[order]).cap()} (order ${hex2(order)}), ${NAMES[lane]} row ${walk.currentRow}"
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val sound = song.pokey
        add("Instruments" to "${used.size} used, ${sound.instruments.count { it != null }} of ${PokeySystem.SLOTS} filled")
        add("Samples" to "${sound.samples.size} of ${PokeySystem.MAX_SAMPLES}, ${PokeySamples.used(song)} of ${PokeySample.MEMORY} levels")
        add("Pattern and envelope data" to "${kb(PokeyLowering.dataSize(song))} of ${kb(PokeyPlayerImage.DATA_CAPACITY)}")
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
