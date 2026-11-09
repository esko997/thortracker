package org.thortracker.system.cbm

import org.thortracker.backend.CbmImage
import org.thortracker.backend.CbmLowering
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * VIC-20 and C16/Plus/4 song checks. [errors] mirror what `cb_load_image` (`cbm/cbm_image.c`) rejects, as
 * user-facing messages: the header's ranges (machine, multispeed, speed, master volume, DAC timer; no samples or timer
 * on the VIC: [CbmSound.problem]), samples (frames 0-8, 1..65535 of them, at most 64), the order list, sound records
 * ([CbmInstrument.problem], [instrumentProblem]: envelope shapes and values, the wave, a sample sound's sample and loop
 * start), cells (notes inside the lane's range for the song's chip and machine, defined sounds placed where the chip
 * plays them, effect codes and parameters per lane) and the data region (capacity, and patterns and envelopes inside
 * the 64 KB their u16 offsets reach). The rest of the loader's reasons (magic, layout, reserved bytes, runs) are what
 * the lowering always writes correctly. [warnings] are the specification's "Should"s.
 */
object CbmValidator {
    private const val MAX_REPORTED = 40

    fun structuralErrors(system: CbmSystem, song: Song): List<String> {
        val out = Report()
        val sound = song.sound as? CbmSound
        if (sound == null || sound.chip != system.chip) {
            out += "The song is for ${song.system.name}, not the ${system.name}"
            return out.list()
        }
        val names = CbmRules.laneNames(sound.chip)
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != names.size) {
                out += "$label has ${pattern.tracks.size} lanes; ${system.name} patterns have ${names.size}"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${names[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell -> CbmRules.cellRangeProblem(cell)?.let { out += "$label, ${names[ch]} row $row: $it" } }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        sound.problem()?.let { out += "Song settings: $it" }
        sound.instruments.forEachIndexed { i, ins -> ins?.problem(sound.chip)?.let { out += "Sound ${hex2(i + 1)} \"${ins.name}\" $it" } }
        song.tuning?.problems(system)?.forEach { out += it }
        if (song.newPatternScale.root !in 0..11) out += "The scale for new patterns has root ${song.newPatternScale.root}; roots are 0-11 (C-B)"
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) {
            out += "Parked orders ${song.parkedOrders} must be ascending, distinct orders 0-${song.orders.size - 1}"
        }
        return out.list()
    }

    /** Problems that block playing or exporting [document]'s arrangement (its unparked orders). */
    fun errors(system: CbmSystem, document: Song): List<String> {
        val structural = structuralErrors(system, document)
        if (structural.isNotEmpty()) return structural
        if (document.parkedOrders.size == document.orders.size) {
            return listOf("Every order is parked; unpark at least one to play the song or export it")
        }
        val song = system.arranged(document)
        val out = Report()
        if (song.orders.size > system.maxOrders) out += "The song has ${song.orders.size} orders; the player plays at most ${system.maxOrders}"
        val sound = song.cbm
        val names = CbmRules.laneNames(sound.chip)
        for (slot in 1..CbmSystem.SLOTS) {
            val ins = sound.instrument(slot) ?: continue
            instrumentProblem(sound, slot)?.let { out += "Sound ${hex2(slot)} \"${ins.name}\" $it" }
        }
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) cellProblem(song, ch, track[row])?.let { out += "$label, ${names[ch]} row $row: $it" }
            }
        }
        if (out.isEmpty()) CbmLowering.sizeProblem(song)?.let { out += it }
        return out.list()
    }

    /**
     * Why sound [slot] of [sound] cannot load, as a reason after the sound's name, or null for a playable or empty
     * slot: its own ranges ([CbmInstrument.problem]), then a sample sound's sample inside the song's samples and its loop
     * start inside that sample.
     */
    fun instrumentProblem(sound: CbmSound, slot: Int): String? {
        val ins = sound.instrument(slot) ?: return null
        ins.problem(sound.chip)?.let { return it }
        if (ins.kind != CbmKind.SAMPLE) return null
        val sample = sound.samples.getOrNull(ins.sample)
        return when {
            sound.samples.isEmpty() -> "plays a sample, but the song has no samples; import one or add the starter kit"
            sample == null -> "plays sample ${hex2(ins.sample)}; the song's samples are 00-${hex2(sound.samples.size - 1)}"
            ins.loop && ins.loopStart >= sample.data.size ->
                "loops from frame ${ins.loopStart}, past the end of \"${sample.name}\" (${sample.data.size} frames)"
            else -> null
        }
    }

    /** Why [cell] on lane [channel] cannot load in [song] (its chip, machine, sounds and order count), or null. */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        CbmRules.cellRangeProblem(cell)?.let { return it }
        val sound = song.cbm
        val chip = sound.chip
        val system = chip.system
        if (cell.note >= 0) {
            val range = CbmRules.laneRange(chip, sound.video, channel)
            if (cell.note !in range) {
                val lane = CbmRules.laneNames(chip).getOrElse(channel) { "lane $channel" }
                val where = if (chip == CbmChip.VIC && !CbmRules.isVicNoise(chip, channel)) " on a ${sound.video.name} VIC-20" else ""
                return "${system.noteName(cell.note)} is outside $lane's ${system.noteName(range.first)}..${system.noteName(range.last)}$where" +
                    (if (chip == CbmChip.VIC && !CbmRules.isVicNoise(chip, channel)) "; move it an octave or to another voice" else "")
            }
        }
        if (cell.instrument != 0) {
            val ins = sound.instrument(cell.instrument)
                ?: return "sound ${hex2(cell.instrument)} is empty; choose a preset for it or another sound"
            CbmRules.placementProblem(chip, channel, ins)?.let { return "sound ${hex2(cell.instrument)} \"${ins.name}\" $it" }
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            CbmRules.paramProblem(chip, channel, code, param, maxOf(1, orders))?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(system: CbmSystem, document: Song): List<String> {
        if (structuralErrors(system, document).isNotEmpty()) return emptyList()
        val song = system.arranged(document)
        val out = Report()
        firstNotesWithoutInstrument(song, out)
        straightPlay(song, out)
        return out.list()
    }

    /** Each order can be entered by the loop or a seek, so its first note on a lane should name the sound. */
    private fun firstNotesWithoutInstrument(song: Song, out: Report) {
        val chip = song.cbm.chip
        val names = CbmRules.laneNames(chip)
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (ch in names.indices) {
                val track = pattern.tracks[ch]
                val first = (0 until pattern.length).firstOrNull { track[it].note >= 0 || track[it].instrument != 0 } ?: continue
                val cell = track[first]
                if (cell.note >= 0 && cell.instrument == 0) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${names[ch]} row $first: ${chip.system.noteName(cell.note)} has no sound; " +
                        "an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
    }

    /**
     * Walks the arrangement once in order (jumps ignored; OFF, `Sxx` and new notes end notes) and reports: note delays
     * the speed never reaches; pitch effects the VIC Noise lane ignores; TED squares above C-9 (more than 50 cents out
     * of tune); a lane's volume envelope taking the master volume from another lane whose volume-envelope note still
     * sounds (one envelope shapes every voice); a DAC note that silences sounding voices (D/A mode takes both); and
     * `Txx` in a song where no sample plays.
     */
    private fun straightPlay(song: Song, out: Report) {
        val sound = song.cbm
        val chip = sound.chip
        val names = CbmRules.laneNames(chip)
        val lanes = chip.channels
        var speed = song.ticksPerRow
        var timerWhere: String? = null
        var samplePlays = false
        var owner = -1
        val slot = IntArray(lanes)
        val sounding = BooleanArray(lanes)
        val envelopeNote = BooleanArray(lanes)
        var ownerWarnings = 0
        var dacWarnings = 0
        var tuneWarnings = 0
        song.orders.forEach { p ->
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            for (row in 0 until pattern.length) {
                for (ch in 0 until lanes) {
                    val c = pattern.tracks[ch][row]
                    for (col in 0..c.more.size) if (c.effectCode(col) == CbmRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
                }
                for (ch in 0 until lanes) {
                    val c = pattern.tracks[ch][row]
                    val where = "$label, ${names[ch]} row $row"
                    if (c.instrument != 0) slot[ch] = c.instrument
                    var wave = -1
                    for (col in 0..c.more.size) {
                        val code = c.effectCode(col)
                        val param = c.effectParam(col)
                        if (code == 0 && param == 0) continue
                        when {
                            code == CbmRules.FX_DELAY -> if (param >= speed) out += "$where: G${hex2(param)} waits $param ticks but rows are $speed ticks; the row never plays"
                            code == CbmRules.FX_TIMER -> if (timerWhere == null) timerWhere = "$where (T${hex2(param)})"
                            code == CbmRules.FX_WAVE -> wave = param
                            CbmRules.isVicNoise(chip, ch) && code in CbmRules.PITCH_EFFECTS ->
                                out += "$where: ${CbmRules.effectText(code, param)} does nothing on the Noise lane (it plays rates, not pitches)"
                        }
                    }
                    val ins = sound.instrument(slot[ch])
                    when {
                        c.note == Cell.CUT -> {
                            if (ins == null || ins.kind != CbmKind.TONE || !ins.releases) {
                                sounding[ch] = false
                                envelopeNote[ch] = false
                                if (owner == ch) owner = -1
                            }
                        }
                        c.note >= 0 && ins != null && CbmRules.placementProblem(chip, ch, ins) == null -> {
                            if (CbmRules.isDac(chip, ch)) {
                                samplePlays = true
                                val voices = (0 until lanes).filter { it != ch && sounding[it] }
                                if (voices.isNotEmpty() && dacWarnings++ < 3) {
                                    out += "$where: the DAC note takes both voices (D/A mode), so ${voices.joinToString(" and ") { names[it] }} " +
                                        "${if (voices.size == 1) "is" else "are"} silent while it sounds"
                                }
                            }
                            if (chip == CbmChip.TED && ch == CbmImage.TED_NOISE_CHANNEL && c.note > CbmImage.ROOT_MAX &&
                                playsSquare(ins, wave) && tuneWarnings++ < 3) {
                                out += "$where: ${chip.system.noteName(c.note)} as a square is more than 50 cents out of tune " +
                                    "(squares are in tune up to ${chip.system.noteName(CbmImage.ROOT_MAX)}; above, play noise)"
                            }
                            val takes = ins.kind == CbmKind.TONE && !ins.volume.isEmpty
                            if (takes && owner >= 0 && owner != ch && envelopeNote[owner] && sounding[owner] && ownerWarnings++ < 3) {
                                out += "$where: sound ${hex2(slot[ch])}'s volume envelope takes the master volume from " +
                                    "${names[owner]}'s still-sounding note; one envelope shapes every voice"
                            }
                            sounding[ch] = true
                            envelopeNote[ch] = takes
                            if (takes) owner = ch else if (owner == ch) owner = -1
                        }
                    }
                }
            }
        }
        if (!samplePlays) timerWhere?.let { out += "$it sets the DAC rate, which only sample sounds use, but no sample plays in this song" }
    }

    /** True when [ins] keyed with `Wxx` [wave] (-1 none) plays a square at least for a tick. */
    private fun playsSquare(ins: CbmInstrument, wave: Int): Boolean = when {
        !ins.waves.isEmpty -> ins.waves.values.any { it == CbmImage.WAVE_SQUARE }
        wave >= 0 -> wave == CbmImage.WAVE_SQUARE
        else -> ins.wave == CbmImage.WAVE_SQUARE
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val sound = song.cbm
        val filled = sound.instruments.filterNotNull()
        add("Sounds" to "${used.size} used, ${filled.size} of ${CbmSystem.SLOTS} filled" +
            if (sound.chip == CbmChip.TED) " (${filled.count { it.kind == CbmKind.SAMPLE }} sample)" else "")
        if (sound.chip == CbmChip.TED) {
            val frames = sound.samples.sumOf { it.data.size }
            add("Samples" to "${sound.samples.size} of ${CbmSound.MAX_SAMPLES}, $frames frames " +
                "(${NumberText.fixed(frames / CbmRules.dacHz(sound.video, sound.dacTimer), 1)} s at ${CbmRules.dacText(sound.video, sound.dacTimer)})")
        }
        add("Song data" to "${kb(CbmLowering.dataSize(song))} of ${kb(CbmImage.DATA_CAPACITY)} (patterns and envelopes " +
            "${kb(CbmLowering.shortDataSize(song))} of 64.0 KB)")
        val names = CbmRules.laneNames(sound.chip)
        add("Lane notes" to names.indices.joinToString(" · ") { ch ->
            val r = CbmRules.laneRange(sound.chip, sound.video, ch)
            "${names[ch]} ${sound.chip.system.noteName(r.first)}-${sound.chip.system.noteName(r.last)}"
        })
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
