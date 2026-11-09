package org.thortracker.system.opn

import org.thortracker.backend.OpnLowering
import org.thortracker.backend.OpnPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.fm.Ym2608RhythmRom
import org.thortracker.system.fm.RhythmRomMenu
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.ym2612.YmPsgInstrument

/**
 * YM2203 / YM2608 song checks. [errors] mirror what `opn_load_image` (`opn/opn_image.c`) rejects, as user-facing
 * messages, plus what the lowering cannot store; [warnings] are legal but probably unintended details
 * (docs/AUDIO_ENGINE.md, "OPN and OPNA").
 */
object OpnValidator {
    private const val MAX_REPORTED = 40

    /** Shape and range problems; a song with any is neither saved nor loaded. */
    fun structuralErrors(song: Song, chip: OpnChip): List<String> {
        val out = Report()
        val sound = song.sound as? OpnSound
        if (sound == null) {
            out += "The song is for ${song.system.name}, not the ${chip.label}"
            return out.list()
        }
        if (sound.chip != chip) {
            out += "The song's sound data is for the ${sound.chip.label}; ${chip.system().name} songs need ${chip.label} data"
            return out.list()
        }
        val names = chip.channelNames
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != chip.channelCount) {
                out += "$label has ${pattern.tracks.size} channels; ${chip.label} patterns have ${chip.channelCount}"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${names[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    OpnRules.cellRangeProblem(chip, cell)?.let { out += "$label, ${names[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        if (sound.instruments.size != OpnSound.SLOTS) {
            out += "The song has ${sound.instruments.size} sound slots; ${chip.label} songs have exactly ${OpnSound.SLOTS}"
        }
        sound.instruments.forEachIndexed { i, ins -> ins?.let { rangeProblem(it) }?.let { out += "Sound ${hex2(i + 1)}: $it" } }
        if (sound.timerB !in OpnPlayerImage.TIMER_B_MIN..OpnPlayerImage.TIMER_B_MAX) {
            out += "Timer B ${sound.timerB} is outside ${OpnPlayerImage.TIMER_B_MIN}-${OpnPlayerImage.TIMER_B_MAX} (the tick rate)"
        }
        if (sound.timerA !in 0..OpnPlayerImage.TIMER_A_MAX) out += "Timer A ${sound.timerA} is outside 0-${OpnPlayerImage.TIMER_A_MAX}"
        if (sound.lfo != 0 && sound.lfo !in 0x08..0x0F) out += "LFO ${hex2(sound.lfo)} must be 00 (off) or 08-0F (rate 0-7)"
        if (sound.rhythmLevel !in 0..63) out += "Rhythm level ${sound.rhythmLevel} is outside 0-63"
        song.tuning?.problems(chip.system())?.forEach { out += it }
        if (song.newPatternScale.root !in 0..11) out += "The scale for new patterns has root ${song.newPatternScale.root}; roots are 0-11 (C-B)"
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) {
            out += "Parked orders ${song.parkedOrders} must be ascending, distinct orders 0-${song.orders.size - 1}"
        }
        return out.list()
    }

    /** Structural errors plus everything that blocks playing or exporting [document]'s arrangement (its unparked orders). */
    fun errors(document: Song, chip: OpnChip): List<String> {
        val structural = structuralErrors(document, chip)
        if (structural.isNotEmpty()) return structural
        if (document.parkedOrders.size == document.orders.size) {
            return listOf("Every order is parked; unpark at least one to play the song or export it")
        }
        val song = chip.system().arranged(document)
        val out = Report()
        if (song.orders.size > OpnPlayerImage.MAX_ORDERS) {
            out += "The song has ${song.orders.size} orders; the OPN player plays at most ${OpnPlayerImage.MAX_ORDERS}"
        }
        soundProblems(song.opn).forEach { out += it }
        val names = chip.channelNames
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${names[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) {
            val size = OpnLowering.dataSize(song)
            if (size > OpnPlayerImage.DATA_CAPACITY) {
                out += "The song's pattern and envelope data need ${kb(size)}; the ${chip.label} image holds " +
                    "${kb(OpnPlayerImage.DATA_CAPACITY)}. Reuse patterns or shorten the song"
            }
        }
        return out.list()
    }

    /**
     * What the chip and the image cannot hold in [sound]: the YM2203's missing YM2608 features (samples, LFO, rhythm and
     * ADPCM sounds, LFO depths), sounds the player cannot play (silent pans, missing samples), sample rates, the sample
     * table and the 256 KiB of ADPCM-B memory. Each message names its sound or sample.
     */
    fun soundProblems(sound: OpnSound): List<String> {
        val chip = sound.chip
        val out = ArrayList<String>()
        if (!chip.opna) {
            if (sound.samples.isNotEmpty()) out += "The YM2203 has no ADPCM-B channel: delete the song's ${sound.samples.size} samples"
            if (sound.lfo != 0) out += "The YM2203 has no LFO: turn the song's LFO off"
        }
        sound.instruments.forEachIndexed { i, ins ->
            if (ins == null) return@forEachIndexed
            val label = "Sound ${hex2(i + 1)} \"${ins.name}\""
            instrumentProblem(chip, ins)?.let { out += "$label: $it" }
            if (ins is OpnAdpcmSound && chip.opna && ins.sample in 0..63 && ins.sample >= sound.samples.size) {
                out += "$label plays sample ${ins.sample}, which the song does not have" +
                    (if (sound.samples.isEmpty()) "; import or generate a sample first" else "; choose one of its ${sound.samples.size}")
            }
        }
        if (chip.opna) {
            if (sound.samples.size > OpnSound.MAX_SAMPLES) {
                out += "The song has ${sound.samples.size} samples; the YM2608 player holds at most ${OpnSound.MAX_SAMPLES}. Delete some"
            }
            sound.samples.forEachIndexed { k, s -> sampleProblem(s)?.let { out += "Sample $k \"${s.name}\": $it" } }
            if (sound.adpcmUsed > OpnPlayerImage.ADPCM_MEMORY) {
                out += "The samples need ${kb(sound.adpcmUsed)}; the YM2608's ADPCM-B memory holds ${kb(OpnPlayerImage.ADPCM_MEMORY)}. " +
                    "Delete or shorten samples, or import them at a lower rate"
            }
        }
        return out
    }

    /** Why [ins] cannot play on [chip] (out-of-range values, features the chip lacks, a silent pan), or null. */
    fun instrumentProblem(chip: OpnChip, ins: OpnInstrument): String? {
        rangeProblem(ins)?.let { return it }
        return when (ins) {
            is OpnFmSound -> if (!chip.opna && (ins.ams != 0 || ins.fms != 0 || ins.operators.any { it.am })) {
                "it uses the LFO (AMS, FMS or operator AM), which the YM2203 does not have; set them to 0"
            } else null
            is OpnSsgSound -> null
            is OpnRhythmSound -> when {
                !chip.opna -> "it is a rhythm sound; the YM2203 has no rhythm unit"
                !ins.left && !ins.right -> "both outputs are off, so it is silent; choose left, right or both"
                else -> null
            }
            is OpnAdpcmSound -> when {
                !chip.opna -> "it is an ADPCM sound; the YM2203 has no ADPCM-B channel"
                !ins.left && !ins.right -> "both outputs are off, so it is silent; choose left, right or both"
                else -> null
            }
        }
    }

    /** Values outside what the image can store, whatever the chip; or null. */
    private fun rangeProblem(ins: OpnInstrument): String? = when (ins) {
        is OpnFmSound -> fmProblem(ins)
        is OpnSsgSound -> ssgProblem(ins)
        is OpnRhythmSound -> if (ins.level !in 0..31) "rhythm level ${ins.level} is outside 0-31" else null
        is OpnAdpcmSound -> when {
            ins.sample !in 0 until OpnSound.MAX_SAMPLES -> "sample ${ins.sample} is outside 0-${OpnSound.MAX_SAMPLES - 1}"
            ins.root !in OpnRules.NOTES -> "root note ${ins.root} is outside C-0..B-7 (0-95)"
            ins.level !in 0..255 -> "level ${ins.level} is outside 0-255"
            else -> ins.sweep.problem()
        }
    }

    private fun fmProblem(ins: OpnFmSound): String? {
        if (ins.operators.size != 4) return "has ${ins.operators.size} operators; FM voices have 4"
        if (ins.algorithm !in 0..7) return "algorithm ${ins.algorithm} is outside 0-7"
        if (ins.feedback !in 0..7) return "feedback ${ins.feedback} is outside 0-7"
        if (ins.ams !in 0..3) return "AMS ${ins.ams} is outside 0-3"
        if (ins.fms !in 0..7) return "FMS ${ins.fms} is outside 0-7"
        if (ins.ch3.size != 4) return "has ${ins.ch3.size} FM 3 operator offsets; voices have 4"
        ins.ch3.forEachIndexed { k, v ->
            if (v !in -OpnPlayerImage.CH3_LIMIT..OpnPlayerImage.CH3_LIMIT) {
                return "operator ${k + 1}'s FM 3 pitch offset $v is outside ±${OpnPlayerImage.CH3_LIMIT} (±4 octaves in 1/64 semitones)"
            }
        }
        ins.sweep.problem()?.let { return it }
        ins.operators.forEachIndexed { i, op -> op.problem()?.let { return "operator ${i + 1}: $it" } }
        return null
    }

    private fun ssgProblem(ins: OpnSsgSound): String? {
        YmPsgInstrument.envelopeProblem("volume", ins.volume, NesEnvelope.VOLUME, releasable = true)?.let { return it }
        YmPsgInstrument.envelopeProblem("arpeggio", ins.arpeggio, NesEnvelope.ARPEGGIO, releasable = false)?.let { return it }
        YmPsgInstrument.envelopeProblem("pitch", ins.pitch, NesEnvelope.PITCH, releasable = false)?.let { return it }
        YmPsgInstrument.envelopeProblem("mixer", ins.mixer, NesEnvelope.MIXER, releasable = false)?.let { return it }
        YmPsgInstrument.envelopeProblem("noise", ins.noise, NesEnvelope.NOISE, releasable = false)?.let { return it }
        val e = ins.envelope
        if (e.octave !in S5bEnvelope.OCTAVES) return "the hardware envelope's octave ${e.octave} is outside -8..8"
        if (e.period !in S5bEnvelope.PERIODS) return "the hardware envelope's period ${e.period} is outside 0-65535"
        return ins.sweep.problem()
    }

    /** Why [sample] cannot be stored or played (its rate, empty or oversized data), or null. */
    fun sampleProblem(sample: OpnaSample): String? = when {
        sample.rate !in OpnPlayerImage.RATE_MIN..OpnPlayerImage.RATE_MAX ->
            "it is recorded at ${sample.rate} Hz; the YM2608 plays samples recorded at ${OpnPlayerImage.RATE_MIN}-${OpnPlayerImage.RATE_MAX} Hz"
        sample.data.size == 0 -> "it has no data"
        sample.stored > OpnPlayerImage.ADPCM_MEMORY -> "it needs ${kb(sample.stored)}; the ADPCM-B memory holds ${kb(OpnPlayerImage.ADPCM_MEMORY)}"
        else -> null
    }

    /** Why [instrument] (slot) cannot play on [channel] of [chip]: a sound of another part, or null. */
    fun kindProblem(chip: OpnChip, channel: Int, slot: Int, instrument: OpnInstrument): String? {
        val part = chip.part(channel)
        if (instrument.part == part) return null
        return "sound ${hex2(slot)} \"${instrument.name}\" is ${article(instrument.part)} ${OpnRules.partText(instrument.part)} sound; " +
            "${chip.channelNames[channel]} plays ${OpnRules.partText(part)} sounds"
    }

    private fun article(part: OpnPart) = if (part == OpnPart.RHYTHM) "a" else "an"

    /** Why [cell] on [channel] cannot load, given the song (its order count as the player sees it and its sounds), or null. */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        val sound = song.opn
        val chip = sound.chip
        OpnRules.cellRangeProblem(chip, cell)?.let { return it }
        if (channel !in 0 until chip.channelCount) return "the ${chip.label} has no channel $channel"
        if (cell.instrument != 0) {
            val ins = sound.instrument(cell.instrument)
                ?: return "sound ${hex2(cell.instrument)} is empty; choose a preset for it or another sound"
            kindProblem(chip, channel, cell.instrument, ins)?.let { return it }
        }
        if (cell.note >= 0 && cell.note < OpnRules.lowest(chip, channel)) {
            return "${chip.system().noteName(cell.note)} is below the SSG's range: its lowest note is " +
                "${chip.system().noteName(OpnRules.SSG_LOWEST)} (lower notes need a tone period above 4095)"
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            OpnRules.paramProblem(chip, channel, code, param, orders)?.let { return it }
        }
        return null
    }

    /** True when [document]'s played orders hold a note on a rhythm channel: playing it needs the rhythm ROM. */
    fun playsRhythm(document: Song): Boolean {
        val sound = document.sound as? OpnSound ?: return false
        if (!sound.chip.opna) return false
        val song = sound.chip.system().arranged(document)
        val first = sound.chip.rhythmFirst
        return song.orders.toSet().any { p ->
            val pattern = song.patterns[p]
            (first until first + OpnPlayerImage.RHYTHM).any { ch -> (0 until pattern.length).any { pattern.tracks[ch][it].note >= 0 } }
        }
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song, chip: OpnChip): List<String> {
        if (structuralErrors(document, chip).isNotEmpty()) return emptyList()
        val song = chip.system().arranged(document)
        val out = Report()
        if (playsRhythm(song) && !Ym2608RhythmRom.installed) out += Ym2608RhythmRom.MISSING
        firstNotesWithoutInstrument(song).forEach { out += it }
        straightPlay(song, out)
        return out.list()
    }

    /**
     * Each order is also entered by the loop, a seek or a live edit, so its first note on a channel should name the
     * sound (otherwise it plays whatever that channel last had, possibly nothing on a fresh start).
     */
    private fun firstNotesWithoutInstrument(song: Song): List<String> {
        val chip = song.opn.chip
        val out = ArrayList<String>()
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (ch in 0 until chip.channelCount) {
                val track = pattern.tracks[ch]
                val first = (0 until pattern.length).firstOrNull { track[it].note >= 0 || track[it].instrument != 0 } ?: continue
                val cell = track[first]
                if (cell.note >= 0 && cell.instrument == 0) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${chip.channelNames[ch]} row $first: ${chip.system().noteName(cell.note)} " +
                        "has no sound; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /**
     * Walks the arrangement once in order (jumps, delays and cuts ignored) and reports note locks on rows without a
     * note, note delays the speed never reaches, timer A changes outside the CSM mode and FM 3 operator offsets that
     * the normal mode ignores.
     */
    private fun straightPlay(song: Song, out: Report) {
        val sound = song.opn
        val chip = sound.chip
        var speed = song.ticksPerRow
        var warnedTimer = false
        var warnedCh3 = false
        val instrument = IntArray(chip.channelCount)
        song.orders.forEach { p ->
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            for (row in 0 until pattern.length) {
                for (ch in 0 until chip.channelCount) {
                    val c = pattern.tracks[ch][row]
                    for (col in 0..c.more.size) if (c.effectCode(col) == OpnRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
                }
                for (ch in 0 until chip.channelCount) {
                    val c = pattern.tracks[ch][row]
                    val where = "$label, ${chip.channelNames[ch]} row $row"
                    if (c.instrument != 0) instrument[ch] = c.instrument
                    var lock: Char? = null
                    for (col in 0..c.more.size) {
                        val code = c.effectCode(col)
                        val param = c.effectParam(col)
                        if (code == 0 && param == 0) continue
                        if (code in OpnRules.NOTE_EFFECTS && lock == null) lock = OpnRules.effectText(code, param)[0]
                        if (code == OpnRules.FX_DELAY && param >= speed) {
                            out += "$where: G${hex2(param)} waits $param ticks but rows are $speed ticks; the row never plays"
                        }
                        if (code == OpnRules.FX_TIMER_A && sound.fm3Mode != OpnFm3Mode.CSM && !warnedTimer) {
                            warnedTimer = true
                            out += "$where: Txx sets timer A, which only keys FM 3 in the CSM mode; the song's FM 3 mode is ${sound.fm3Mode.label}"
                        }
                    }
                    if (c.note < 0) {
                        if (lock != null) out += "$where: ${lock}xx applies when a note starts; this row has no note, so it is ignored"
                        continue
                    }
                    val ins = sound.instrument(instrument[ch])
                    if (ch == OpnPlayerImage.CH_FM3 && sound.fm3Mode == OpnFm3Mode.NORMAL && !warnedCh3 &&
                        ins is OpnFmSound && ins.ch3.any { it != 0 }
                    ) {
                        warnedCh3 = true
                        out += "$where: sound ${hex2(instrument[ch])} \"${ins.name}\" has FM 3 operator pitches, which sound only " +
                            "in the special or CSM mode; the song's FM 3 mode is normal"
                    }
                }
            }
        }
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val sound = song.opn
        val chip = sound.chip
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val counts = chip.parts.joinToString { part -> "${sound.instruments.count { it?.part == part }} ${OpnRules.partText(part)}" }
        add("Sounds" to "${used.size} used, ${sound.instruments.count { it != null }} of ${OpnSound.SLOTS} filled ($counts)")
        add("Pattern data" to "${kb(OpnLowering.dataSize(song))} of ${kb(OpnPlayerImage.DATA_CAPACITY)}")
        add("Tick rate" to "${NumberText.fixed(OpnRules.tickHz(sound.timerB), 2)} Hz (timer B ${sound.timerB})")
        if (chip.opna) {
            add("ADPCM-B memory" to "${kb(sound.adpcmUsed)} of ${kb(OpnPlayerImage.ADPCM_MEMORY)}, ${sound.samples.size} of ${OpnSound.MAX_SAMPLES} samples")
            add("Rhythm ROM" to if (Ym2608RhythmRom.installed) "installed (YM2608 drums)" else
                "not imported: rhythm notes need it (${RhythmRomMenu.LABEL}); FM, SSG and ADPCM play")
        }
    }

    private fun kb(bytes: Int) = NumberText.fixed(bytes / 1024.0, 1) + " KiB"

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
