package org.thortracker.system.pce

import org.thortracker.backend.PceLowering
import org.thortracker.backend.PcePlayerImage
import org.thortracker.backend.PcePlayerImage.CHANNELS
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * PC Engine song checks. [errors] mirror what `pc_load_image` (`pce/pce_image.c`, [PcePlayerImage.LOADER_PROBLEMS])
 * rejects, as user-facing messages: the header's ranges (multispeed, speed, master balance, LFO, DDA timer:
 * [PceSound.problem]), waves (32 values 0-31, at most 64) and samples (frames 0-31, 1..65535 of them, at most 64), the
 * order list, sound records ([PceInstrument.problem], [instrumentProblem]: envelope shapes and values, a tone's wave
 * and wave envelope inside the song's waves, a sample sound's sample inside the song's samples and its loop start
 * inside the sample), cells (notes A-0..B-7, defined sounds, a noise sound only on PSG 5-6, effect codes and
 * parameters, `Wxx` inside the song's waves) and the data region's capacity, samples included; the rest of the
 * loader's reasons (magic, layout, reserved bytes, offsets and runs) are what the lowering always writes correctly.
 * [warnings] are the specification's "Should"s (docs/AUDIO_ENGINE.md, "PC Engine").
 */
object PceValidator {
    private const val MAX_REPORTED = 40

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        if (song.sound !is PceSound) {
            out += "The song is for ${song.system.name}, not the PC Engine"
            return out.list()
        }
        val channels = PceRules.CHANNEL_NAMES.size
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != channels) {
                out += "$label has ${pattern.tracks.size} channels; PC Engine patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${PceRules.CHANNEL_NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    PceRules.cellRangeProblem(cell)?.let { out += "$label, ${PceRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        val sound = song.pce
        sound.problem()?.let { out += "Song settings: $it" }
        sound.instruments.forEachIndexed { i, ins -> ins?.problem()?.let { out += "Sound ${hex2(i + 1)} \"${ins.name}\": $it" } }
        song.tuning?.problems(PceSystem)?.forEach { out += it }
        if (song.newPatternScale.root !in 0..11) out += "The scale for new patterns has root ${song.newPatternScale.root}; roots are 0-11 (C-B)"
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) {
            out += "Parked orders ${song.parkedOrders} must be ascending, distinct orders 0-${song.orders.size - 1}"
        }
        return out.list()
    }

    /** Problems that block playing or exporting [document]'s arrangement (its unparked orders). */
    fun errors(document: Song): List<String> {
        val structural = structuralErrors(document)
        if (structural.isNotEmpty()) return structural
        if (document.parkedOrders.size == document.orders.size) {
            return listOf("Every order is parked; unpark at least one to play the song or export it")
        }
        val song = PceSystem.arranged(document)
        val out = Report()
        if (song.orders.size > PceSystem.maxOrders) out += "The song has ${song.orders.size} orders; the PC Engine player plays at most ${PceSystem.maxOrders}"
        val sound = song.pce
        for (slot in 1..PceSystem.SLOTS) {
            val ins = sound.instrument(slot) ?: continue
            instrumentProblem(sound, slot)?.let { out += "Sound ${hex2(slot)} \"${ins.name}\": $it" }
        }
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${PceRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) PceLowering.sizeProblem(song)?.let { out += it }
        return out.list()
    }

    /**
     * Why sound [slot] (1-based) of [sound] cannot load, as a reason without the slot's name (the instrument view shows
     * it beside the sound), or null for a playable or empty slot: its own ranges ([PceInstrument.problem]), then what it
     * refers to — a tone's wave and wave envelope steps inside [PceSound.waves], a sample sound's sample inside
     * [PceSound.samples] and its loop start inside that sample.
     */
    fun instrumentProblem(sound: PceSound, slot: Int): String? {
        val ins = sound.instrument(slot) ?: return null
        ins.problem()?.let { return it }
        val waves = sound.waves.size
        return when (ins.kind) {
            PceKind.TONE -> when {
                waves == 0 -> "plays a wave, but the song has no waves; add one in the Wave view"
                ins.wave >= waves -> "plays wave ${hex2(ins.wave)}; the song's waves are 00-${hex2(waves - 1)}"
                else -> ins.waves.values.firstOrNull { it >= waves }?.let {
                    "its wave envelope steps to wave ${hex2(it)}; the song's waves are 00-${hex2(waves - 1)}"
                }
            }
            PceKind.NOISE -> null
            PceKind.SAMPLE -> {
                val sample = sound.samples.getOrNull(ins.sample)
                when {
                    sound.samples.isEmpty() -> "plays a sample, but the song has no samples; import one or add the starter kit"
                    sample == null -> "plays sample ${hex2(ins.sample)}; the song's samples are 00-${hex2(sound.samples.size - 1)}"
                    ins.loop && ins.loopStart >= sample.data.size ->
                        "loops from frame ${ins.loopStart}, past the end of \"${sample.name}\" (${sample.data.size} frames)"
                    else -> null
                }
            }
        }
    }

    /**
     * Why [cell] on [channel] cannot load, given the song (its order count as the player sees it, sounds and waves), or
     * null.
     */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        PceRules.cellRangeProblem(cell)?.let { return it }
        val sound = song.pce
        if (cell.instrument != 0) {
            val ins = sound.instrument(cell.instrument)
                ?: return "sound ${hex2(cell.instrument)} is empty; choose a preset for it or another sound"
            if (ins.kind == PceKind.NOISE && channel !in PceRules.NOISE_CHANNELS) {
                return "sound ${hex2(cell.instrument)} \"${ins.name}\" is noise; the HuC6280 has noise on PSG 5 and PSG 6 " +
                    "only, so noise sounds belong there"
            }
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            PceRules.paramProblem(code, param, orders, sound.waves.size)?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = PceSystem.arranged(document)
        val out = Report()
        firstNotesWithoutInstrument(song).forEach { out += it }
        straightPlay(song, out)
        return out.list()
    }

    /**
     * Each order is also entered by the loop, a seek or a live edit, so its first note on a channel should name the
     * sound (otherwise it plays whatever that channel last had, possibly nothing on a fresh start).
     */
    private fun firstNotesWithoutInstrument(song: Song): List<String> {
        val out = ArrayList<String>()
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (ch in 0 until CHANNELS) {
                val track = pattern.tracks[ch]
                val first = (0 until pattern.length).firstOrNull { track[it].note >= 0 || track[it].instrument != 0 } ?: continue
                val cell = track[first]
                if (cell.note >= 0 && cell.instrument == 0) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${PceRules.CHANNEL_NAMES[ch]} row $first: ${PceSystem.noteName(cell.note)} " +
                        "has no sound; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /**
     * Walks the arrangement once in order (jumps, delays and cuts ignored) and reports note delays the speed never
     * reaches; then, for the whole song, an LFO that is switched on while PSG 2 (its source) never plays a note, and
     * `Txx` DDA rate changes in a song where no sample sound plays.
     */
    private fun straightPlay(song: Song, out: Report) {
        val sound = song.pce
        var speed = song.ticksPerRow
        var lfoWhere: String? = if (PceRules.lfoBends(sound.lfoControl)) "The song's LFO control (Song view)" else null
        var timerWhere: String? = null
        var psg2Plays = false
        var samplePlays = false
        val instrument = IntArray(CHANNELS)
        song.orders.forEach { p ->
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            for (row in 0 until pattern.length) {
                for (ch in 0 until CHANNELS) {
                    val c = pattern.tracks[ch][row]
                    for (col in 0..c.more.size) if (c.effectCode(col) == PceRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
                }
                for (ch in 0 until CHANNELS) {
                    val c = pattern.tracks[ch][row]
                    val where = "$label, ${PceRules.CHANNEL_NAMES[ch]} row $row"
                    if (c.instrument != 0) instrument[ch] = c.instrument
                    if (c.note >= 0) {
                        if (ch == PceRules.LFO_SOURCE) psg2Plays = true
                        if (sound.instrument(instrument[ch])?.kind == PceKind.SAMPLE) samplePlays = true
                    }
                    for (col in 0..c.more.size) {
                        val code = c.effectCode(col)
                        val param = c.effectParam(col)
                        if (code == 0 && param == 0) continue
                        when (code) {
                            PceRules.FX_DELAY -> if (param >= speed) out += "$where: G${hex2(param)} waits $param ticks but rows are $speed ticks; the row never plays"
                            PceRules.FX_LFO_CTRL -> if (PceRules.lfoBends(param) && lfoWhere == null) lfoWhere = "$where (M${hex2(param)})"
                            PceRules.FX_TIMER -> if (timerWhere == null) timerWhere = "$where (T${hex2(param)})"
                        }
                    }
                }
            }
        }
        if (!psg2Plays) lfoWhere?.let {
            out += "$it turns the LFO on, but PSG 2 never plays a note: the LFO is PSG 2's wave at its note's rate, so " +
                "give PSG 2 a held note (C00 keeps it quiet)"
        }
        if (!samplePlays) timerWhere?.let {
            out += "$it sets the DDA rate, which only sample sounds use, but no sample sound plays in this song"
        }
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val sound = song.pce
        val filled = sound.instruments.filterNotNull()
        val noise = filled.count { it.kind == PceKind.NOISE }
        val samples = filled.count { it.kind == PceKind.SAMPLE }
        add("Sounds" to "${used.size} used, ${filled.size} of ${PceSystem.SLOTS} filled ($noise noise, PSG 5-6 only; $samples sample)")
        add("Waves" to "${sound.waves.size} of ${PceSound.MAX_WAVES}")
        val frames = sound.samples.sumOf { it.data.size }
        add("Samples" to "${sound.samples.size} of ${PceSound.MAX_SAMPLES}, $frames frames " +
            "(${NumberText.fixed(PceSamples.seconds(frames, sound.timerReload), 1)} s at ${PceRules.ddaText(sound.timerReload)})")
        add("Song data" to "${kb(PceLowering.dataSize(song))} of ${kb(PcePlayerImage.DATA_CAPACITY)} (patterns and envelopes " +
            "${kb(PceLowering.shortDataSize(song))} of 64.0 KB)")
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
