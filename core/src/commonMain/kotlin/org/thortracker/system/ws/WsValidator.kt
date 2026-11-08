package org.thortracker.system.ws

import org.thortracker.backend.WsLowering
import org.thortracker.backend.WsPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * WonderSwan song checks. [errors] mirror what `ws_load_image` (`ws/ws_image.c`, [WsPlayerImage.LOADER_PROBLEMS])
 * rejects, as user-facing messages: the header's ranges (speed, voice timer 1-255, speaker shift 0-3: [WsSound.problem]),
 * waves (32 values 0-15, at most 64) and samples (bytes 0-255, 1..65535 frames, at most 64), the order list, sound
 * records ([WsInstrument.problem], [instrumentProblem]: envelope shapes and values, a tone's wave and wave sequence
 * inside the song's waves, a sample sound's sample inside the song's samples and its loop start inside it, Sound DMA
 * only on the WonderSwan Color), cells (notes G-1..B-7, defined sounds that fit the lane — tones on CH1-CH4, noise on
 * CH4, mono samples on CH2, any sample on Hyper Voice —, effect codes, parameters and lanes: `N` on CH4, `U V` on CH3,
 * `W` on CH1-CH4, `H` on CH2 and Hyper Voice of the Color) and the data region's capacity (samples and their DMA copies
 * included) and u16 reach; the rest of the loader's reasons (magic, layout, reserved bytes, offsets and runs) are what
 * the lowering always writes correctly. [warnings] are legal but probably unintended details.
 */
object WsValidator {
    private const val MAX_REPORTED = 40

    fun structuralErrors(song: Song, system: WonderSwanSystemBase): List<String> {
        val out = Report()
        val sound = song.sound as? WsSound
        if (sound == null || sound.color != system.color) {
            out += "The song is for ${song.system.name}, not the ${system.name}"
            return out.list()
        }
        val names = WsRules.laneNames(sound.color)
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
                track.forEachIndexed { row, cell ->
                    WsRules.cellRangeProblem(cell)?.let { out += "$label, ${names[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        sound.problem()?.let { out += "Song settings: $it" }
        sound.instruments.forEachIndexed { i, ins -> ins?.problem()?.let { out += "Sound ${hex2(i + 1)} \"${ins.name}\": $it" } }
        song.tuning?.problems(system)?.forEach { out += it }
        if (song.newPatternScale.root !in 0..11) out += "The scale for new patterns has root ${song.newPatternScale.root}; roots are 0-11 (C-B)"
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) {
            out += "Parked orders ${song.parkedOrders} must be ascending, distinct orders 0-${song.orders.size - 1}"
        }
        return out.list()
    }

    /** Problems that block playing or exporting [document]'s arrangement (its unparked orders). */
    fun errors(document: Song, system: WonderSwanSystemBase): List<String> {
        val structural = structuralErrors(document, system)
        if (structural.isNotEmpty()) return structural
        if (document.parkedOrders.size == document.orders.size) {
            return listOf("Every order is parked; unpark at least one to play the song or export it")
        }
        val song = system.arranged(document)
        val out = Report()
        if (song.orders.size > system.maxOrders) out += "The song has ${song.orders.size} orders; the WonderSwan player plays at most ${system.maxOrders}"
        val sound = song.ws
        for (slot in 1..WsRules.SLOTS) {
            val ins = sound.instrument(slot) ?: continue
            instrumentProblem(sound, slot)?.let { out += "Sound ${hex2(slot)} \"${ins.name}\": $it" }
        }
        val names = WsRules.laneNames(sound.color)
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${names[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) WsLowering.sizeProblem(song)?.let { out += it }
        return out.list()
    }

    /**
     * Why sound [slot] (1-based) of [sound] cannot load, as a reason without the slot's name, or null for a playable or
     * empty slot: its own ranges ([WsInstrument.problem]), then the model and what it refers to — Sound DMA only on the
     * WonderSwan Color, a tone's wave and wave sequence inside [WsSound.waves], a sample sound's sample inside
     * [WsSound.samples] and a CPU-fed loop's start inside that sample.
     */
    fun instrumentProblem(sound: WsSound, slot: Int): String? {
        val ins = sound.instrument(slot) ?: return null
        ins.problem()?.let { return it }
        val waves = sound.waves.size
        return when (ins.kind) {
            WsKind.TONE -> when {
                waves == 0 -> "plays a wave, but the song has no waves; add one in the Wave view"
                ins.wave >= waves -> "plays wave ${hex2(ins.wave)}; the song's waves are 00-${hex2(waves - 1)}"
                else -> ins.waves.values.firstOrNull { it >= waves }?.let {
                    "its wave sequence steps to wave ${hex2(it)}; the song's waves are 00-${hex2(waves - 1)}"
                }
            }
            WsKind.NOISE -> null
            WsKind.SAMPLE -> {
                val sample = sound.samples.getOrNull(ins.sample)
                when {
                    ins.isDma && !sound.color -> "is fed by Sound DMA, which only the WonderSwan Color has; make it CPU-fed"
                    sound.samples.isEmpty() -> "plays a sample, but the song has no samples; import one or add the starter kit"
                    sample == null -> "plays sample ${hex2(ins.sample)}; the song's samples are 00-${hex2(sound.samples.size - 1)}"
                    !ins.isDma && ins.loop && ins.loopStart >= sample.frames ->
                        "loops from frame ${ins.loopStart}, past the end of \"${sample.name}\" (${sample.frames} frames)"
                    else -> null
                }
            }
        }
    }

    /**
     * True when [ins] may play on [lane] of a song with [sound] (the loader's and the player's lane fit): tones on
     * CH1-CH4, noise on CH4, samples on CH2 when mono and on the Color's Hyper Voice; DMA samples on the Color only.
     */
    fun fits(sound: WsSound, ins: WsInstrument, lane: Int): Boolean = laneProblem(sound, ins, lane) == null

    /** Why [ins] cannot play on [lane] of a song with [sound], or null when it fits ([fits]). */
    fun laneProblem(sound: WsSound, ins: WsInstrument, lane: Int): String? {
        val names = WsRules.laneNames(sound.color)
        if (lane !in names.indices) return "has no lane $lane to play on"
        val where = names[lane]
        return when (ins.kind) {
            WsKind.TONE -> if (lane == WsRules.HYPER_VOICE) "is a tone; Hyper Voice plays samples only, so tones belong on CH1-CH4" else null
            WsKind.NOISE -> if (lane != WsRules.NOISE_LANE) "is noise, which the WonderSwan has on channel 4 only; noise belongs on CH4" else null
            WsKind.SAMPLE -> when {
                ins.isDma && !sound.color -> "is fed by Sound DMA, which only the WonderSwan Color has"
                lane == WsRules.HYPER_VOICE -> null
                lane != WsRules.VOICE_LANE -> "is a sample; $where cannot play samples (CH2's voice output${if (sound.color) " and Hyper Voice" else ""} can)"
                sound.samples.getOrNull(ins.sample)?.stereo == true -> "plays a stereo sample; CH2's voice is mono, so stereo samples belong on Hyper Voice"
                else -> null
            }
        }
    }

    /**
     * Why [cell] on [channel] cannot load, given the song (its model, order count as the player sees it, sounds and
     * waves), or null.
     */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        WsRules.cellRangeProblem(cell)?.let { return it }
        val sound = song.ws
        if (cell.instrument != 0) {
            val ins = sound.instrument(cell.instrument)
                ?: return "sound ${hex2(cell.instrument)} is empty; choose a preset for it or another sound"
            laneProblem(sound, ins, channel)?.let { return "sound ${hex2(cell.instrument)} \"${ins.name}\" $it" }
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            WsRules.paramProblem(code, param, channel, sound.color, orders, sound.waves.size)?.let { return it }
        }
        return null
    }

    /** True when the arranged [song] has a note on the Hyper Voice lane in a row that plays (VGM cannot carry it). */
    fun usesHyperVoice(song: Song): Boolean {
        if (!song.ws.color) return false
        val arranged = song.system.arranged(song)
        return arranged.orders.toSet().any { p ->
            val pattern = arranged.patterns[p]
            val track = pattern.tracks.getOrNull(WsRules.HYPER_VOICE) ?: return@any false
            (0 until pattern.length).any { track[it].note in WsRules.NOTES }
        }
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song, system: WonderSwanSystemBase): List<String> {
        if (structuralErrors(document, system).isNotEmpty()) return emptyList()
        val song = system.arranged(document)
        val out = Report()
        firstNotesWithoutInstrument(song).forEach { out += it }
        straightPlay(song, out)
        return out.list()
    }

    /**
     * Each order is also entered by the loop, a seek or a live edit, so its first note on a lane should name the sound
     * (otherwise it plays whatever that lane last had, possibly nothing on a fresh start).
     */
    private fun firstNotesWithoutInstrument(song: Song): List<String> {
        val out = ArrayList<String>()
        val names = WsRules.laneNames(song.ws.color)
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (ch in names.indices) {
                val track = pattern.tracks[ch]
                val first = (0 until pattern.length).firstOrNull { track[it].note >= 0 || track[it].instrument != 0 } ?: continue
                val cell = track[first]
                if (cell.note >= 0 && cell.instrument == 0) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${names[ch]} row $first: ${song.system.noteName(cell.note)} " +
                        "has no sound; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /**
     * Walks the arrangement once in order (jumps, delays and cuts ignored) and reports note delays the speed never
     * reaches and sounds with a sweep keyed off CH3 (only CH3 sweeps); then, for the whole song, `Txx` voice-timer
     * changes where no CPU-fed sample plays, and Hyper Voice notes in a song heard through the speaker.
     */
    private fun straightPlay(song: Song, out: Report) {
        val sound = song.ws
        val names = WsRules.laneNames(sound.color)
        var speed = song.ticksPerRow
        var timerWhere: String? = null
        var cpuSamplePlays = false
        var hyperVoiceWhere: String? = null
        val sweepReported = HashSet<Int>()
        val instrument = IntArray(names.size)
        song.orders.forEach { p ->
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            for (row in 0 until pattern.length) {
                for (ch in names.indices) {
                    val c = pattern.tracks[ch][row]
                    for (col in 0..c.more.size) if (c.effectCode(col) == WsRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
                }
                for (ch in names.indices) {
                    val c = pattern.tracks[ch][row]
                    val where = "$label, ${names[ch]} row $row"
                    if (c.instrument != 0) instrument[ch] = c.instrument
                    if (c.note >= 0) {
                        val ins = sound.instrument(instrument[ch])
                        if (ins?.kind == WsKind.SAMPLE && !ins.isDma) cpuSamplePlays = true
                        if (ch == WsRules.HYPER_VOICE && hyperVoiceWhere == null) hyperVoiceWhere = where
                        if (ins?.kind == WsKind.TONE && ins.sweep != 0 && ch != WsRules.SWEEP_LANE && sweepReported.add(instrument[ch])) {
                            out += "$where: sound ${hex2(instrument[ch])} \"${ins.name}\" has a sweep, but only CH3 sweeps; " +
                                "here it plays without one"
                        }
                    }
                    for (col in 0..c.more.size) {
                        val code = c.effectCode(col)
                        val param = c.effectParam(col)
                        if (code == 0 && param == 0) continue
                        when (code) {
                            WsRules.FX_DELAY -> if (param >= speed) out += "$where: G${hex2(param)} waits $param ticks but rows are $speed ticks; the row never plays"
                            WsRules.FX_TIMER -> if (timerWhere == null) timerWhere = "$where (T${hex2(param)})"
                        }
                    }
                }
            }
        }
        if (!cpuSamplePlays) timerWhere?.let {
            out += "$it sets the voice timer, which only CPU-fed sample sounds use, but none plays in this song"
        }
        if (sound.output == WsOutput.SPEAKER) hyperVoiceWhere?.let {
            out += "$it plays Hyper Voice, which the speaker does not sound: the song's output is the speaker (Song view)"
        }
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val sound = song.ws
        val filled = sound.instruments.filterNotNull()
        val noise = filled.count { it.kind == WsKind.NOISE }
        val samples = filled.count { it.kind == WsKind.SAMPLE }
        val dma = filled.count { it.isDma }
        add("Sounds" to "${used.size} used, ${filled.size} of ${WsRules.SLOTS} filled ($noise noise, CH4 only; $samples sample" +
            (if (sound.color) ", $dma by DMA" else "") + ")")
        add("Waves" to "${sound.waves.size} of ${WsSound.MAX_WAVES}")
        val bytes = sound.samples.sumOf { it.data.size }
        add("Samples" to "${sound.samples.size} of ${WsSound.MAX_SAMPLES}, ${kb(bytes)}" +
            (if (sound.color) " (+ ${kb(WsLowering.signedCopyBytes(song))} of DMA copies for Hyper Voice)" else ""))
        add("Song data" to "${kb(WsLowering.dataSize(song))} of ${kb(WsPlayerImage.DATA_CAPACITY)} (patterns and envelopes " +
            "${kb(WsLowering.shortDataSize(song))} of 64.0 KB)")
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
