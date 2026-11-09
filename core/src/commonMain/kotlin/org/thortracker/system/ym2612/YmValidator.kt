package org.thortracker.system.ym2612

import org.thortracker.backend.YmLowering
import org.thortracker.backend.YmPcmLowering
import org.thortracker.backend.YmPlayerImage
import org.thortracker.backend.YmPlayerImage.CHANNELS
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * Mega Drive song checks. [errors] mirror what `ym_load_image` (`ym/ym_image.c`, [YmPlayerImage.LOADER_PROBLEMS])
 * rejects, plus what would play nothing (a PCM key no kit maps) or not fit (the PCM bank), as user-facing messages;
 * [warnings] are the specification's "Should"s, the driver's frame budget and the DAC's sharing of FM 6
 * (docs/AUDIO_ENGINE.md, "YM2612").
 */
object YmValidator {
    private const val MAX_REPORTED = 40

    /**
     * YM register writes the driver executes in one vertical interrupt (SGDK's original XGM driver measured ~70-100
     * beside its PCM mixing; the XGM2 driver's figure is kept until it is measured); a row estimated above it makes the
     * driver run late.
     */
    const val FRAME_WRITE_BUDGET = 90

    /** Estimated YM writes of an FM key-on that loads a different instrument: 7 registers × 4 operators + $B0/$B4. */
    private const val INSTRUMENT_LOAD_WRITES = 30

    /** An FM key-on of the instrument already loaded: the frequency pair, key off and on. */
    private const val RETRIGGER_WRITES = 4

    /** A retrigger whose locks differ from the last note's: a few operator registers change (or are restored). */
    private const val LOCK_CHANGE_WRITES = 8

    fun structuralErrors(song: Song): List<String> {
        val out = Report()
        if (song.sound !is YmSound) {
            out += "The song is for ${song.system.name}, not the Mega Drive"
            return out.list()
        }
        val channels = YmRules.CHANNEL_NAMES.size
        if (song.ticksPerRow !in 1..255) out += "Speed ${song.ticksPerRow} is out of range; ticks per row must be 1-255"
        if (song.patterns.isEmpty()) out += "The song has no patterns; add one"
        song.patterns.forEachIndexed { p, pattern ->
            val label = Songs.patternLabel(song, p).cap()
            if (pattern.scale.root !in 0..11) out += "$label has scale root ${pattern.scale.root}; roots are 0-11 (C-B)"
            if (pattern.length !in 1..Songs.ROWS) out += "$label is ${pattern.length} rows long; lengths are 1-${Songs.ROWS}"
            if (pattern.tracks.size != channels) {
                out += "$label has ${pattern.tracks.size} channels; Mega Drive patterns have $channels"
                return@forEachIndexed
            }
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) {
                    out += "$label, ${YmRules.CHANNEL_NAMES[ch]} has ${track.size} rows; patterns have ${Songs.ROWS}"
                    return@forEachIndexed
                }
                track.forEachIndexed { row, cell ->
                    YmRules.cellRangeProblem(cell)?.let { out += "$label, ${YmRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (song.orders.isEmpty()) out += "The order list is empty; add at least one order"
        song.orders.forEachIndexed { i, p -> if (p !in song.patterns.indices) out += "Order $i refers to missing pattern $p" }
        if (song.orders.isNotEmpty() && song.loopOrder !in song.orders.indices) {
            out += "Loop order ${song.loopOrder} is outside the order list (0-${song.orders.size - 1})"
        }
        val sound = song.ym
        if (sound.instruments.size != YmSystem.SLOTS) {
            out += "The song has ${sound.instruments.size} instrument slots; Mega Drive songs have exactly ${YmSystem.SLOTS}"
        }
        sound.instruments.forEachIndexed { i, ins -> ins?.problem()?.let { out += "Instrument ${hex2(i + 1)}: $it" } }
        sound.pcmProblem()?.let { out += it }
        if (sound.lfo != 0 && sound.lfo !in 0x08..0x0F) out += "LFO ${hex2(sound.lfo)} must be 00 (off) or 08-0F (rate 0-7)"
        song.tuning?.problems(YmSystem)?.forEach { out += it }
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
        val song = YmSystem.arranged(document)
        val out = Report()
        if (song.orders.size > YmSystem.maxOrders) out += "The song has ${song.orders.size} orders; the Mega Drive player plays at most ${YmSystem.maxOrders}"
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            pattern.tracks.forEachIndexed { ch, track ->
                for (row in 0 until pattern.length) {
                    cellProblem(song, ch, track[row])?.let { out += "$label, ${YmRules.CHANNEL_NAMES[ch]} row $row: $it" }
                }
            }
        }
        if (out.isEmpty()) pcmKeys(song, out)
        if (out.isEmpty()) {
            val size = YmLowering.dataSize(song)
            if (size > YmPlayerImage.DATA_CAPACITY) {
                out += "The song's pattern and envelope data need ${kb(size)}; the Mega Drive image holds " +
                    "${kb(YmPlayerImage.DATA_CAPACITY)}. Reuse patterns or shorten the song"
            }
            val pcm = YmLowering.pcmDataSize(song)
            if (pcm > YmPlayerImage.PCM_DATA_CAPACITY) {
                out += "PCM 1-3's pattern data need ${kb(pcm)}; the Mega Drive image holds ${kb(YmPlayerImage.PCM_DATA_CAPACITY)} " +
                    "for them. Reuse PCM patterns or shorten the song"
            }
            YmPcmLowering.problem(song.ym)?.let { out += it }
        }
        return out.list()
    }

    /**
     * Walks the arrangement in order (jumps ignored) and reports PCM notes without an instrument of their own whose
     * lane's kit at that point maps no sample for the key: the player ignores such a note, so it is an error to fix.
     */
    private fun pcmKeys(song: Song, out: Report) {
        val kit = IntArray(YmRules.PCM_CHANNELS)
        val seen = HashSet<Triple<Int, Int, Int>>()
        for (p in song.orders) {
            val pattern = song.patterns[p]
            for (row in 0 until pattern.length) for (k in 0 until YmRules.PCM_CHANNELS) {
                val ch = YmRules.PCM_FIRST + k
                val c = pattern.tracks[ch][row]
                if (c.instrument != 0) { kit[k] = c.instrument; continue } // cellProblem checked it
                if (c.note < 0 || kit[k] == 0) continue
                val ins = song.ym.instrument(kit[k]) as? YmPcmInstrument ?: continue
                YmSystem.pcmKeyProblem(song, kit[k], ins, c.note)?.let {
                    if (seen.add(Triple(p, row, k))) out += "${Songs.patternLabel(song, p).cap()}, ${YmRules.CHANNEL_NAMES[ch]} row $row: $it"
                }
            }
        }
    }

    /** Why [cell] on [channel] cannot load, given the song (its order count as the player sees it and instruments), or null. */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        YmRules.cellRangeProblem(cell)?.let { return it }
        if (cell.instrument != 0) {
            val ins = song.ym.instrument(cell.instrument)
                ?: return "instrument ${hex2(cell.instrument)} is empty; choose a preset for it or another instrument"
            YmSystem.kindProblem(channel, cell.instrument, ins)?.let { return it }
            if (ins is YmPcmInstrument && cell.note in YmRules.NOTES) YmSystem.pcmKeyProblem(song, cell.instrument, ins, cell.note)?.let { return it }
        }
        val orders = song.orders.size
        for (c in 0..cell.more.size) {
            val code = cell.effectCode(c)
            val param = cell.effectParam(c)
            if (code == 0 && param == 0) continue
            YmRules.paramProblem(channel, code, param, orders)?.let { return it }
        }
        return null
    }

    /** Legal but probably unintended details. */
    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = YmSystem.arranged(document)
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
        for (p in song.orders.toSet()) {
            val pattern = song.patterns[p]
            for (ch in 0 until CHANNELS) {
                val track = pattern.tracks[ch]
                val first = (0 until pattern.length).firstOrNull { track[it].note >= 0 || track[it].instrument != 0 } ?: continue
                val cell = track[first]
                if (cell.note >= 0 && cell.instrument == 0) {
                    out += "${Songs.patternLabel(song, p).cap()}, ${YmRules.CHANNEL_NAMES[ch]} row $first: ${YmSystem.noteName(cell.note)} " +
                        "has no instrument; an order can be entered by the loop or a seek, so give its first note one"
                }
            }
        }
        return out
    }

    /**
     * Walks the arrangement once in order (jumps, delays and cuts ignored) and reports note locks on rows without a
     * note, note delays the speed never reaches, PSG notes below the PSG's range, PSG 3 notes while pitched noise owns
     * PSG 3, FM 3 operator offsets the song's normal FM 3 mode ignores, FM 6 notes and pan while PCM 1-3 own the DAC,
     * and rows whose register writes exceed the driver's frame budget ([FRAME_WRITE_BUDGET]).
     */
    private fun straightPlay(song: Song, out: Report) {
        val sound = song.ym
        val instrument = IntArray(CHANNELS)
        val loaded = IntArray(CHANNELS)
        val loadedLocks = arrayOfNulls<List<Pair<Int, Int>>>(CHANNELS)
        var pitchedNoise = false
        var speed = song.ticksPerRow
        var warnedLow = 0
        var warnedHeavy = 0
        var warnedPsg3 = 0
        var warnedCh3 = false
        var pcmPlays = false
        var fm6Note: String? = null
        var fm6Pan: String? = null
        song.orders.forEachIndexed { o, p ->
            val pattern = song.patterns[p]
            val label = Songs.patternLabel(song, p).cap()
            for (row in 0 until pattern.length) {
                for (ch in 0 until CHANNELS) {
                    val c = pattern.tracks[ch][row]
                    for (col in 0..c.more.size) if (c.effectCode(col) == YmRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
                }
                var writes = 0
                var loads = 0
                for (ch in 0 until CHANNELS) {
                    val c = pattern.tracks[ch][row]
                    val where = "$label, ${YmRules.CHANNEL_NAMES[ch]} row $row"
                    if (c.instrument != 0) instrument[ch] = c.instrument
                    var noteFx: Char? = null
                    val locks = ArrayList<Pair<Int, Int>>()
                    var noiseLock: Int? = null
                    for (col in 0..c.more.size) {
                        val code = c.effectCode(col)
                        val param = c.effectParam(col)
                        if (code == 0 && param == 0) continue
                        if (code in YmRules.NOTE_EFFECTS) {
                            locks += code to param
                            if (noteFx == null) noteFx = YmRules.effectText(code, param)[0]
                        }
                        if (code == YmRules.FX_NOISE) noiseLock = ((param shr 4) shl 2) or (param and 3)
                        if (code == YmRules.FX_DELAY && param >= speed) {
                            out += "$where: G${hex2(param)} waits $param ticks but rows are $speed ticks; the row never plays"
                        }
                    }
                    if (ch == YmRules.FM6 && fm6Pan == null && (0..c.more.size).any { c.effectCode(it) == YmRules.FX_PAN }) fm6Pan = where
                    if (c.note < 0) {
                        if (noteFx != null) out += "$where: ${noteFx}xx applies when a note starts; this row has no note, so it is ignored"
                        if (c.note == Cell.CUT && ch == YmRules.NOISE) pitchedNoise = false
                        continue
                    }
                    val ins = sound.instrument(instrument[ch])
                    if (YmRules.isPcm(ch)) {
                        if (ins is YmPcmInstrument) pcmPlays = true
                        continue
                    }
                    if (YmRules.isFm(ch)) {
                        if (ch == YmRules.FM6 && fm6Note == null) fm6Note = where
                        if (ins is YmFmInstrument) {
                            if (ch == YmRules.FM3 && ins.hasCh3 && sound.fm3Mode == YmFm3Mode.NORMAL && !warnedCh3) {
                                warnedCh3 = true
                                out += "$where: sound ${hex2(instrument[ch])} \"${ins.name}\" has FM 3 operator pitches, which sound only " +
                                    "in FM 3's special mode; the song's FM 3 mode is normal (Song view)"
                            }
                            // Registers are shadowed: only what differs from the last note is written.
                            writes += when {
                                loaded[ch] != instrument[ch] -> INSTRUMENT_LOAD_WRITES.also { loads++ }
                                loadedLocks[ch] != locks -> LOCK_CHANGE_WRITES
                                else -> RETRIGGER_WRITES
                            }
                            loaded[ch] = instrument[ch]
                            loadedLocks[ch] = locks
                        }
                        continue
                    }
                    // PSG writes go to the PSG port without the YM2612's busy waits: not counted.
                    if (ch == YmRules.NOISE) {
                        if (ins is YmPsgInstrument) pitchedNoise = ((noiseLock ?: ins.noise) and 3) == YmPsgInstrument.PITCHED
                        continue
                    }
                    if (c.note < YmRules.PSG_LOWEST && warnedLow < 3) {
                        warnedLow++
                        out += "$where: ${YmSystem.noteName(c.note)} is below the PSG's range (A-2 and up); it sounds at its lowest pitch, 109 Hz"
                    }
                    if (ch == YmRules.NOISE - 1 && pitchedNoise && warnedPsg3 < 3) {
                        warnedPsg3++
                        out += "$where: pitched noise on the Noise channel uses PSG 3's period, so this note is silent; end the noise with OFF first"
                    }
                }
                if (writes > FRAME_WRITE_BUDGET && !(o == 0 && row == 0) && warnedHeavy < 3) {
                    warnedHeavy++
                    out += "Order ${hex2(o)} row $row: $loads FM instrument changes at once (about $writes register writes); the sound driver " +
                        "executes about $FRAME_WRITE_BUDGET per frame, so the row may sound up to ${(writes - 1) / FRAME_WRITE_BUDGET} ticks late. " +
                        "Stagger the changes with Gxx or reuse instruments"
                }
            }
        }
        if (pcmPlays && fm6Note != null) {
            out += "$fm6Note: FM 6 plays while the song uses PCM 1-3. The PCM channels sound through the YM2612's DAC, which " +
                "replaces FM 6's output: FM 6 is inaudible while the DAC is on. Move this part to another channel"
        }
        if (pcmPlays && fm6Pan != null) {
            out += "$fm6Pan: FM 6's pan is also the DAC's: 8xy here pans every PCM channel together (PCM has no pan of its own)"
        }
    }

    /** "Limits used" rows of the song check. */
    fun limits(song: Song): List<Pair<String, String>> = buildList {
        val used = HashSet<Int>()
        for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
        val sound = song.ym
        val fm = sound.instruments.count { it is YmFmInstrument }
        val psg = sound.instruments.count { it is YmPsgInstrument }
        val pcm = sound.instruments.count { it is YmPcmInstrument }
        add("Instruments" to "${used.size} used, ${fm + psg + pcm} of ${YmSystem.SLOTS} filled ($fm FM, $psg PSG, $pcm PCM)")
        add("Pattern data" to "${kb(YmLowering.dataSize(song))} of ${kb(YmPlayerImage.DATA_CAPACITY)}")
        add("PCM pattern data" to "${kb(YmLowering.pcmDataSize(song))} of ${kb(YmPlayerImage.PCM_DATA_CAPACITY)}")
        add("PCM samples" to "${sound.pcmSamples.size} of ${YmPcm.MAX_SAMPLES}, ${kb(YmPcm.stored(sound))} of ${kb(YmPcm.MEMORY)}")
        add("PCM bank" to (YmPcmLowering.problem(sound) ?: YmPcmLowering.usage(YmPcmLowering.compile(sound))))
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
