package org.thortracker.system.tia

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.TiaLowering
import org.thortracker.backend.TiaPlayerImage
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.input.Keymap
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.BankSpec
import org.thortracker.system.ChannelKind
import org.thortracker.system.ChannelSpec
import org.thortracker.system.EffectSpec
import org.thortracker.system.ExportFormat
import org.thortracker.system.FinePitch
import org.thortracker.system.PlaybackEngine
import org.thortracker.system.SharedState
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView

/**
 * The Atari 2600 NTSC console's sound: the TIA's two audio channels at the NTSC colour clock (3579545.45 Hz), the
 * player running once per 262-line frame (59.92 ticks per second). See [TiaSystem].
 */
object TiaNtscSystem : TiaSystem(TiaRegion.NTSC, TiaPlayerImage.ENGINE_ID_NTSC, "Atari 2600 NTSC (TIA)")

/**
 * The Atari 2600 PAL console's sound: the TIA at the PAL colour clock (3546894 Hz, every pitch about 16 cents lower
 * than on NTSC at the same AUDF), the player running once per 312-line frame (49.86 ticks per second). See [TiaSystem].
 */
object TiaPalSystem : TiaSystem(TiaRegion.PAL, TiaPlayerImage.ENGINE_ID_PAL, "Atari 2600 PAL (TIA)")

/**
 * The Atari 2600's TIA (Television Interface Adaptor) played by Thor Tracker's own player on Stella 7.0's gate-level
 * audio channel model (natives `"atari2600-tia-ntsc"`, `"atari2600-tia-pal"`; music-only, exported as WAV): two
 * channels, each a 4-bit waveform select (AUDC), a 5-bit frequency divider (AUDF) and a 4-bit volume (AUDV); up to two
 * effect columns per channel and one bank of [SLOTS] sounds (waveform, tonal or raw pitch, volume, arpeggio, pitch and
 * waveform envelopes). One family, "Atari 2600", with a variant per TV standard ([region]): the region sets the chip's
 * clock (every pitch) and the frame rate (the tick). Pitch is honest: a note plays the nearest divider's frequency
 * ([TiaRules.audf]). See docs/AUDIO_ENGINE.md ("Atari 2600") and docs/BACKENDS.md.
 */
sealed class TiaSystem(val region: TiaRegion, override val id: String, override val name: String) : SoundSystem {
    override val family = "Atari 2600"

    override val channels = TiaRules.CHANNEL_NAMES.map { ChannelSpec(it, ChannelKind.VOICE, 0, tonal = true) }
    override val banks = listOf(BankSpec("Sounds", SLOTS))
    override val noteRange = TiaRules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = TiaRules.EFFECTS

    /** The shared views plus the Instrument view; the TIA has no wavetables. */
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE

    /** WAV only (the hosts' generic export): VGM has no TIA, and a cartridge would need a 6507 driver and display kernel. */
    override val exports: List<ExportFormat> = emptyList()
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help

    /** OFF is a note-column event (release or stop). */
    override val offEffect: String? = null

    /** One player tick per TV frame: the colour clock / (lines × 228). */
    override val tickHz = TiaRules.tickHz(region)
    override val maxOrders = TiaPlayerImage.MAX_ORDERS

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = TiaSound.blank(region),
    )

    override val soundSerializer: KSerializer<TiaSound> = TiaSound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun demo(): Song = TiaDemoSong.song(region)

    override fun effectsFor(channel: Int): List<EffectSpec> = TiaRules.EFFECTS

    override fun effectColumns(channel: Int): Int = TiaRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = TiaRules.describe(song, effect, param)

    /** Arpeggios read with the notes they step through. */
    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? =
        TiaEffectText.detail(song, pattern, channel, row, column)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = TiaValidator.cellProblem(song, channel, cell)

    /** Plus: the song's region must be this variant's (a project's backend names its variant). */
    override fun structuralErrors(song: Song): List<String> {
        val sound = song.sound as? TiaSound ?: return TiaValidator.structuralErrors(song)
        if (sound.region != region) return listOf("The song is for ${sound.region.system.name}, not $name")
        return TiaValidator.structuralErrors(song)
    }

    override fun errors(song: Song) = structuralErrors(song).ifEmpty { TiaValidator.errors(song) }
    override fun warnings(song: Song) = TiaValidator.warnings(song)

    /**
     * Parked orders removed; the loop order and every `Bxx` (in any effect column; `Bxx` = order xx) renumbered to the
     * played orders. A target that is parked lands on the next played order after it (the loop order past the last).
     */
    override fun arranged(song: Song): Song {
        if (song.parkedOrders.isEmpty()) return song
        val played = Songs.playedOrders(song)
        if (played.isEmpty()) return song
        val next = IntArray(song.orders.size + 1) { -1 }
        var k = played.size
        for (o in song.orders.indices.reversed()) {
            if (k > 0 && played[k - 1] == o) k--
            next[o] = if (k < played.size) k else -1
        }
        val loop = next[song.loopOrder].let { if (it >= 0) it else 0 }
        fun target(order: Int) = if (order in song.orders.indices) next[order].let { if (it >= 0) it else loop } else order
        val patterns = song.patterns.map { p ->
            val tracks = p.tracks.map { track -> track.map { c -> jumpTarget(c)?.let { withJumpTarget(c, target(it)) } ?: c } }
            if (tracks == p.tracks) p else p.copy(tracks = tracks)
        }
        return song.copy(orders = played.map { song.orders[it] }, loopOrder = loop, patterns = patterns, parkedOrders = emptyList())
    }

    /** The order at [order] alone, looping: every `Bxx` in it aims at that one order (`B00`). */
    override fun patternLoop(song: Song, order: Int): Song {
        val index = song.orders[order.coerceIn(0, song.orders.size - 1)]
        val pattern = song.patterns[index]
        val tracks = pattern.tracks.map { track -> track.map { c -> if (jumpTarget(c) != null) withJumpTarget(c, 0) else c } }
        val patterns = if (tracks == pattern.tracks) song.patterns else song.patterns.toMutableList().also { it[index] = pattern.copy(tracks = tracks) }
        return song.copy(orders = listOf(index), loopOrder = 0, patterns = patterns, parkedOrders = emptyList())
    }

    /** `Bxx` jumps to order xx (absolute); the first `B` column of the cell. */
    override fun jumpTarget(cell: Cell): Int? {
        for (c in 0..cell.more.size) if (cell.effectCode(c) == TiaRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == TiaRules.FX_JUMP) out = out.withEffect(c, TiaRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine(region, id)

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else song.tia.instrument(slot)?.name?.ifEmpty { "Sound ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else song.tia.instrument(slot)?.let { summary(song.tia.region, it) } ?: "Empty: choose a preset"

    /**
     * One period of the waveform a sound keys on with, as levels 0..1, for the pure tones (div 2 and div 6 squares, the
     * 18/13 div 31 and div 93 tones) and the constant level; null for the polynomial (noise-like) waveforms.
     */
    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? {
        if (bank != 0) return null
        val ins = song.tia.instrument(slot) ?: return null
        return when (ins.keyOnWaveform()) {
            0x0, 0xB -> List(SHAPE_POINTS) { 1f }
            0x4, 0x5, 0xC, 0xD -> List(SHAPE_POINTS) { if (it < SHAPE_POINTS / 2) 1f else 0f }
            0x6, 0xA, 0xE -> List(SHAPE_POINTS) { if (it * 31 < 18 * SHAPE_POINTS) 1f else 0f }
            else -> null
        }
    }

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) TiaInstrumentView else null

    /** The key-on registers straight play reaches at the cursor, per channel ([TiaSharedState]). */
    override fun sharedState(song: Song, order: Int, row: Int): SharedState = TiaSharedState.state(song, order, row)

    companion object {
        const val SLOTS = TiaPlayerImage.MAX_INSTRUMENTS

        /** What [instrumentName] shows for an empty slot. */
        const val EMPTY_NAME = "(empty)"

        private const val SHAPE_POINTS = 32

        /**
         * One line about [ins]: "AUDC C div 6 square · tonal E-3..D#8 · volume envelope", "AUDC 8 9-bit poly (noise) ·
         * raw: the note picks AUDF".
         */
        fun summary(region: TiaRegion, ins: TiaInstrument): String {
            val wf = TiaRules.WAVEFORMS[ins.waveform and 15]
            val envelopes = buildList {
                if (!ins.volume.isEmpty) add("volume")
                if (!ins.arpeggio.isEmpty) add("arpeggio")
                if (!ins.pitch.isEmpty && !ins.raw) add(if (ins.relativePitch) "relative pitch" else "pitch")
                if (!ins.waveforms.isEmpty) add("waveform")
            }
            val pitch = when {
                ins.raw -> "raw: the note picks AUDF"
                else -> TiaRules.reach(region, ins.waveform)?.let { "tonal ${noteText(it.first)}..${noteText(it.last)}" } ?: "no pitch (DC level)"
            }
            val head = "AUDC ${TiaRules.audcText(ins.waveform)} ${wf.short} · $pitch"
            return if (envelopes.isEmpty()) head else "$head · ${envelopes.joinToString(", ")} envelope${if (envelopes.size > 1) "s" else ""}"
        }

        /** [note]'s name ("A-4"). */
        fun noteText(note: Int): String = Songs.noteName(note, 0)
    }

    private class Engine(region: TiaRegion, override val id: String) : PlaybackEngine {
        override val driver = "Thor Tracker TIA player on Stella 7.0 AudioChannel (d55b1aec), ${region.label} " +
            "${NumberText.fixed(TiaRules.clockHz(region), 2)} Hz colour clock, blip_buf 1.1.0"
        override val imageSize = TiaPlayerImage.IMAGE_SIZE

        /** 7200 frames: two minutes of NTSC song, 2 min 24 s of PAL. */
        override val seekTicks = TiaPlayerImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = TiaLowering.lower(song, out)

        /**
         * A note outside the sound's waveform's span still plays (its nearest end, as in the song), so it is no problem
         * here; the instrument view shows the span.
         */
        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val sound = song.tia
            val ins = if (instrument in 1..SLOTS) sound.instrument(instrument) else null
            return when {
                instrument !in 1..SLOTS -> "Choose a sound (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Sound ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in TiaRules.CHANNEL_NAMES.indices -> "Cannot audition on channel $channel"
                note !in TiaRules.NOTES -> "${noteText(note)} is outside C-0..B-9, the notes the player plays"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> ins.problem()?.let { "Sound ${NumberText.hex(instrument, 2)} \"${ins.name}\" $it" }
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = fine
    }

    /**
     * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in a free effect column: the
     * player's pitch unit is 1/64 semitone; the TIA's dividers then pick the nearest frequency they have, so a tuning
     * is heard only where it moves a note to another divider.
     */
    private object Fine : FinePitch {
        override val name = "Pxx"
        override val max = 63
        override fun range(note: Int): IntRange = 0..max
        override fun cents(note: Int, fine: Int): Double = fine * 100.0 / TiaRules.UNITS

        /** The `P` column's upward fine value; 0 without one; null for a downward `P` (not a tuning). */
        override fun of(cell: Cell): Int? {
            val col = column(cell) ?: return 0
            val p = cell.effectParam(col)
            return if (p >= TiaRules.FINE_CENTRE) p - TiaRules.FINE_CENTRE else null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = column(cell)
            val base = cell.copy(note = note)
            if (fine == 0) {
                return if (current != null && cell.effectParam(current) >= TiaRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
            }
            if (current != null && cell.effectParam(current) < TiaRules.FINE_CENTRE) return null
            val col = current ?: (0 until TiaRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
            return base.withEffect(col, TiaRules.FX_FINE, TiaRules.FINE_CENTRE + fine)
        }

        private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == TiaRules.FX_FINE }
    }

    private object Help : SystemHelp {
        override val off = "Release: sounds whose volume envelope has a release point play on from it; other sounds stop at once (AUDV 0)"
        override val offEntered = "OFF: release (or stop without a release point)"
        override val offInspector = "OFF: release, or stop without a volume release point"
        override val effectsIntro = listOf(
            "Played by Thor Tracker's own player on Stella 7.0's model of the TIA: one tick per TV frame (59.92 per " +
                "second on NTSC, 49.86 on PAL); speeds count frames.",
            "Each channel has a waveform (AUDC 0-F: pure squares at div 2 and div 6, the 18/13 tone at div 31, div 93, " +
                "polynomial buzzes and noise, and a constant level), a 5-bit divider (AUDF: 32 frequencies per waveform) " +
                "and a 4-bit volume. A tonal sound's note picks the divider nearest its pitch and sounds at that " +
                "divider's true frequency (A-4 on div 6: 436.1 Hz NTSC, 432.1 Hz PAL); each waveform reaches its own " +
                "span, and notes outside it play its nearest end. The Instrument view's pitch guide lists every divider.",
            "A raw sound (for noise and drums) uses the note as the divider: C-0 is AUDF 31, each semitone up one step " +
                "brighter, repeating every 32 semitones; fine pitch, slides and pitch envelopes do not apply.",
            "Slides, vibrato, portamento and Pxx move the pitch in 1/64 semitones and are heard where they cross to another " +
                "divider: they step, as the chip does.",
            "Nothing restarts a waveform: a new note continues the running counters, so repeated notes need not start alike.",
            "Two effect columns per channel, run in column order. No chords: each channel is one voice.",
            "The TIA has no sample playback; AUDC 0 with a volume envelope gives clicks and levels at the frame rate.",
        )
        override val tonalChannels = "TIA 1 and TIA 2"
        override val tonalChannelsShort = "TIA"
        override val untonalNote = "Both channels are tonal; on a raw sound the note picks the divider directly."
        override val sketchChannels = "TIA 1 or TIA 2"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = TiaInstrumentView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = TiaValidator.limits(song)
    }
}

/** The editor's longer reading of an arpeggio (`0xy`): the notes it steps through from the row's (or the sounding) note. */
internal object TiaEffectText {
    fun detail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
        val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row) ?: return null
        val code = cell.effectCode(column)
        val param = cell.effectParam(column)
        if (code != TiaRules.FX_ARPEGGIO || param == 0) return null
        val x = param shr 4
        val y = param and 0x0F
        val root = if (cell.note >= 0) cell.note else soundingNote(track, row) ?: return "note, +$x, +$y: no note sounding here"
        return listOf(root, root + x, root + y).joinToString(" → ") { name(it) } + " every tick"
    }

    /** The note sounding at [row] of [track] from the rows above it (the pattern only), or null after an OFF or none. */
    private fun soundingNote(track: List<Cell>, row: Int): Int? {
        for (r in row - 1 downTo 0) {
            val n = track[r].note
            if (n >= 0) return n
            if (n == Cell.CUT) return null
        }
        return null
    }

    private fun name(note: Int): String = if (note in TiaRules.NOTES) TiaSystem.noteText(note) else "(out of range)"
}
