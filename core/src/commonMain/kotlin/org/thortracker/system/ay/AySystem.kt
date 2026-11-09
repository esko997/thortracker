package org.thortracker.system.ay

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.AyLowering
import org.thortracker.backend.AyPlayerImage
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
import org.thortracker.system.nes.NesEnvelope

/** The ZX Spectrum 128's AY-3-8912 at 1773450 Hz, one tick per 50.02 Hz frame (native `"ay-zx128"`). */
object AyZx128System : AySystem(AyVariant.ZX128)

/** The MSX's AY-3-8910 at 1789772.5 Hz, one tick per 59.92 Hz NTSC frame (native `"ay-msx"`). */
object AyMsxSystem : AySystem(AyVariant.MSX)

/** The Atari ST's YM2149F at 2005311.75 Hz, one tick per 50.05 Hz PAL frame (native `"ay-atari-st"`). */
object AyAtariStSystem : AySystem(AyVariant.ATARI_ST)

/**
 * The Amstrad CPC's AY-3-8912 at 1 MHz, one tick per 50.08 Hz frame (native `"ay-cpc"`), heard through the CPC's stock
 * stereo socket: Channel A left, B both sides, C right.
 */
object AyCpcSystem : AySystem(AyVariant.CPC)

/**
 * The AY-3-8910 / YM2149 family: one system per stock computer ([variant]: its chip, clock, frame rate and output
 * wiring), played by Thor Tracker's own player on its own chip core (native `"ay-zx128"`, `"ay-msx"`, `"ay-atari-st"`,
 * `"ay-cpc"`; exported
 * as VGM): Channel A-C, up to four effect columns each, one bank of [SLOTS] sounds (five software envelopes, the
 * hardware envelope and a drum sweep). The noise generator and the hardware envelope are one per chip: the most recent
 * key-on asking for one owns it ([sharedState]). See docs/BACKENDS.md and docs/AUDIO_ENGINE.md ("AY-3-8910 / YM2149").
 */
abstract class AySystem internal constructor(val variant: AyVariant) : SoundSystem {
    override val id: String = variant.engineId
    override val name: String = "${variant.label} (${variant.chip})"
    override val family = FAMILY

    override val channels = AyRules.CHANNEL_NAMES.map { ChannelSpec(it, ChannelKind.PULSE, 0, tonal = true) }
    override val banks = listOf(BankSpec("Sounds", SLOTS))

    /** From the variant's lowest reachable note (tone periods stop at 4095) to B-7. */
    override val noteRange: IntRange = AyRules.lowestNote(variant) until AyPlayerImage.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = AyRules.EFFECTS
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports = listOf(ExportFormat("vgm") { AyVgm.export(it) })
    override val fine: FinePitch = AyFine
    override val help: SystemHelp = AyHelp(variant)

    /** OFF is a note-column event (key off). */
    override val offEffect: String? = null

    /** One tick per frame interrupt. */
    override val tickHz: Double = variant.tickHz
    override val maxOrders = AyPlayerImage.MAX_ORDERS

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = AySound.blank(variant),
    )

    override val soundSerializer: KSerializer<AySound> = AySound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun demo(): Song = AyDemoSong.build(variant)

    override fun effectsFor(channel: Int): List<EffectSpec> = AyRules.effectsFor(channel)

    override fun effectColumns(channel: Int): Int = AyRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = AyRules.describe(song, channel, effect, param)

    /** Arpeggio notes, and what the note settings do to the row's note and the shared noise/envelope ([AyEffectText]). */
    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? =
        AyEffectText.detail(song, pattern, channel, row, column)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = AyValidator.cellProblem(song, channel, cell)

    /** Plus: the song's sound must be this variant's (a project's backend names its computer). */
    override fun structuralErrors(song: Song): List<String> {
        val sound = song.sound as? AySound ?: return AyValidator.structuralErrors(song)
        if (sound.variant != variant) return listOf("The song is for ${sound.variant.system.name}, not $name")
        return AyValidator.structuralErrors(song)
    }

    override fun errors(song: Song): List<String> = structuralErrors(song).ifEmpty { AyValidator.errors(song) }
    override fun warnings(song: Song): List<String> = if (structuralErrors(song).isNotEmpty()) emptyList() else AyValidator.warnings(song)

    /**
     * Parked orders removed; the loop order and every `Bxx` (in any effect column; `Bxx` = order xx) renumbered to
     * the played orders. A target that is parked lands on the next played order after it (the loop order past the last).
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

    /** `Bxx` jumps to order xx (absolute, B00 = the first order); the first `B` column of the cell. */
    override fun jumpTarget(cell: Cell): Int? {
        for (c in 0..cell.more.size) if (cell.effectCode(c) == AyRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == AyRules.FX_JUMP) out = out.withEffect(c, AyRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine(this)

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank !in banks.indices || slot !in 1..SLOTS) null else instrument(song, slot)?.name?.ifEmpty { "Sound ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank !in banks.indices || slot !in 1..SLOTS) null else instrument(song, slot)?.let { summary(variant, it) } ?: "Empty: choose a preset"

    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null

    /** Instrument [slot] (1..[SLOTS]), or null when empty. */
    fun instrument(song: Song, slot: Int): AyInstrument? = song.ay.instrument(slot)

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) AyInstrumentView else null

    /** Who owns the shared envelope and noise period at the position, as straight play reaches it ([AySharedState]). */
    override fun sharedState(song: Song, order: Int, row: Int): SharedState? = AySharedState.state(song, order, row)

    companion object {
        /** New and Load demo list the four computers under this entry. */
        const val FAMILY = "AY-3-8910 / YM2149"

        const val SLOTS = AyPlayerImage.MAX_INSTRUMENTS

        /** What [instrumentName] shows for an empty slot. */
        const val EMPTY_NAME = "(empty)"

        /**
         * A one-line reading of [ins]: its gates, volume shape, noise period, hardware envelope (shape, auto octave or
         * manual period, sync), arpeggio/pitch envelopes and sweep.
         */
        fun summary(variant: AyVariant, ins: AyInstrument): String {
            val parts = ArrayList<String>()
            parts += constant(ins.mixer)?.let { AyRules.mixerText(it).replaceFirstChar { c -> c.uppercase() } }
                ?: if (ins.mixer.values.isEmpty()) "Tone" else "Gates change"
            parts += when {
                ins.volume.values.isEmpty() -> "volume 15"
                ins.volume.values.size == 1 -> "volume ${ins.volume.values[0]}"
                ins.volume.loop >= 0 -> "volume ${ins.volume.values.first()}→${ins.volume.values[ins.volume.loop]} held"
                else -> "volume ${ins.volume.values.first()}→${ins.volume.values.last()}"
            }
            if (ins.noise.values.isNotEmpty()) {
                parts += constant(ins.noise)?.let { "noise $it" } ?: "noise ${ins.noise.values.first()}→${ins.noise.values.last()}"
            }
            val env = ins.envelope
            if (env.on) {
                val period = if (env.auto) "auto ${AyRules.autoText(env.octave)}" else "manual ${NumberText.hex(env.period, 4)}"
                parts += "envelope ${NumberText.hex(env.shape)} ${AyRules.SHAPE_GLYPHS[env.shape]} $period" +
                    (if (env.auto && env.sync) " synced" else "")
            }
            if (ins.arpeggio.values.isNotEmpty()) parts += "arpeggio " + ins.arpeggio.values.distinct().joinToString(" ") { AyRules.signed(it) }
            if (ins.pitch.values.isNotEmpty()) parts += if (ins.pitchRelative) "pitch slide" else "pitch envelope"
            if (ins.sweep.active) parts += "sweep ${AyRules.signed(ins.sweep.start)}→${AyRules.signed(ins.sweep.end)}"
            return parts.joinToString(", ")
        }

        /** The one value [env] keeps, or null when it is empty or changes. */
        private fun constant(env: NesEnvelope): Int? = env.values.distinct().singleOrNull()
    }

    private class Engine(private val system: AySystem) : PlaybackEngine {
        override val id = system.variant.engineId
        override val driver = "Thor Tracker's player on Thor Tracker's ${system.variant.chip} core " +
            "(${NumberText.fixed(system.variant.clockHz / 1e6, 4)} MHz, DAC levels from ayumi 07c08b4)"
        override val imageSize = AyPlayerImage.IMAGE_SIZE

        /** Four minutes of song at 50 Hz. */
        override val seekTicks = AyPlayerImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = AyLowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val ins = if (instrument in 1..SLOTS) song.ay.instrument(instrument) else null
            return when {
                instrument !in 1..SLOTS -> "Choose a sound (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Sound ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in system.channels.indices || note !in AyRules.NOTES -> "Cannot audition note ${system.noteName(note)} on channel $channel"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> ins.problem()?.let { "Sound ${NumberText.hex(instrument, 2)}: $it" }
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = fine
    }
}

/**
 * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in any free effect column, as on the
 * Mega Drive: the player's pitch unit is 1/64 semitone (1.56 cents), plus the 12-bit tone periods' rounding (coarse
 * in the top octaves).
 */
private object AyFine : FinePitch {
    override val name = "Pxx"
    override val max = 63
    override fun range(note: Int): IntRange = 0..max
    override fun cents(note: Int, fine: Int): Double = fine * 100.0 / 64

    /** The `P` column's upward fine value; 0 without one; null for a downward `P` (not a tuning). */
    override fun of(cell: Cell): Int? {
        val col = column(cell) ?: return 0
        val p = cell.effectParam(col)
        return if (p >= AyRules.FINE_CENTRE) p - AyRules.FINE_CENTRE else null
    }

    override fun write(cell: Cell, note: Int, fine: Int): Cell? {
        val current = column(cell)
        val base = cell.copy(note = note)
        if (fine == 0) {
            return if (current != null && cell.effectParam(current) >= AyRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
        }
        if (current != null && cell.effectParam(current) < AyRules.FINE_CENTRE) return null
        val col = current ?: (0 until AyRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
        return base.withEffect(col, AyRules.FX_FINE, AyRules.FINE_CENTRE + fine)
    }

    private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == AyRules.FX_FINE }
}

private class AyHelp(private val variant: AyVariant) : SystemHelp {
    override val off = "Key off: the volume envelope's release plays (a sound without one stops at once)"
    override val offEntered = "OFF: key off (the release plays)"
    override val offInspector = "OFF: key off (release phase)"
    override val effectsIntro = listOf(
        "Played by Thor Tracker's own player on its own ${variant.chip} core at the ${variant.label}'s clock " +
            "(${NumberText.fixed(variant.clockHz / 1e6, 4)} MHz): one tick per frame interrupt " +
            "(${NumberText.fixed(variant.tickHz, 2)} Hz)" +
            (if (variant.stereo) ", heard through the CPC's stereo socket: Channel A left, B on both sides, C right"
            else ", the machine's one mono output (no panning)") +
            (if (variant.ym) "; the YM2149 has 32 volume steps and a 32-step envelope." else "; 16 volume steps and a 16-step envelope."),
        "Up to four effect columns per channel, run in column order. The note settings (E, H, I, J, V, W) shape the note " +
            "started on their row; on a row without a note they change the sounding note at once. Volume, slides and " +
            "vibrato act from their row on.",
        "One noise generator and one hardware envelope serve all three channels. Notes only ask for them: the most " +
            "recent note asking owns each (of notes starting on the same tick, C wins over B, B over A), and every channel " +
            "using the envelope plays the owner's. When the owner's note ends, the latest note still asking takes it over. " +
            "Lane tags show ENV/NZ on the owner and env/nz on channels borrowing.",
        "A sound's hardware envelope period follows the note (auto, J octaves up: the buzzer bass) or is a fixed value " +
            "(manual); sync locks the tone to the envelope so the two never beat.",
        "No chords: the AY has three voices; arpeggio envelopes or 0xy play one-channel chords. Drums are sounds with " +
            "noise, a pitch sweep or a short tone: the note picks the drum's pitch.",
    )
    override val tonalChannels = "Channels A-C"
    override val tonalChannelsShort = "A/B/C"
    override val untonalNote = "Every channel is tonal; noise periods come from the sounds and Wxx, not from notes."
    override val sketchChannels = "Channel A, B or C"

    override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = AyInstrumentView.helpSections(keys)

    override fun limits(song: Song): List<Pair<String, String>> = AyValidator.limits(song)
}
