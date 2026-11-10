package org.thortracker.system.saa

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.SaaLowering
import org.thortracker.backend.SaaPlayerImage
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
import org.thortracker.system.SongSetting
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView

/** The SAM Coupé: one Philips SAA1099 at 8 MHz, six lanes, 50 player ticks per second. */
object SamCoupeSystem : Saa1099System(SaaVariant.SAM) {
    override fun demo(): Song = SaaDemoSong.coupeLights()
}

/** Creative Music System / Game Blaster: two Philips SAA1099s at 7159090 Hz, twelve lanes, 60 player ticks per second. */
object CmsSystem : Saa1099System(SaaVariant.CMS) {
    override fun demo(): Song = SaaDemoSong.blasterSkyline()
}

/**
 * Philips SAA1099 machines played by Thor Tracker's own player on a clock-exact port of SAASound's chip model (native
 * `"sam-saa1099"` / `"cms-saa1099"`; music-only, exported as VGM and WAV): per chip six square-wave tone generators
 * with 8-bit offsets and 3-bit octaves, two noise generators each shared by three lanes, twelve 4-bit amplitude
 * controllers (left and right per lane) and two hardware envelopes on lanes 3 and 6; up to four effect columns per
 * lane, one bank of [SLOTS] instruments made of envelopes (volume, arpeggio, pitch, mixer) and chip settings (noise
 * clock, pan, hardware envelope). [variant] is the machine. See docs/AUDIO_ENGINE.md ("SAA1099").
 */
abstract class Saa1099System internal constructor(val variant: SaaVariant) : SoundSystem {
    override val id: String = when (variant) {
        SaaVariant.SAM -> SaaPlayerImage.ENGINE_ID_SAM
        SaaVariant.CMS -> SaaPlayerImage.ENGINE_ID_CMS
    }

    override val name: String = when (variant) {
        SaaVariant.SAM -> "SAM Coupé (SAA1099)"
        SaaVariant.CMS -> "Creative Music System (2× SAA1099)"
    }

    override val family: String = "Philips SAA1099"

    override val channels = variant.laneNames.map { ChannelSpec(it, ChannelKind.PULSE, 0, tonal = true) }
    override val banks = listOf(BankSpec("Instruments", SLOTS))
    override val noteRange = variant.noteRange

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = SaaRules.EFFECTS
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports = listOf(ExportFormat("vgm") { SaaVgm.export(it) })
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help(variant)

    /** OFF is a note-column event (key off). */
    override val offEffect: String? = null

    /** One player tick per frame at multispeed 1: 50 Hz on the SAM Coupé, 60 Hz on the CMS. */
    override val tickHz = variant.rate.toDouble()
    override val maxOrders = SaaPlayerImage.MAX_ORDERS

    override fun ticksPerSecond(song: Song): Double = tickHz * song.saa.multispeed

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = SaaSound.blank(variant),
    )

    override val soundSerializer: KSerializer<SaaSound> = SaaSound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun effectsFor(channel: Int): List<EffectSpec> = SaaRules.EFFECTS

    override fun effectColumns(channel: Int): Int = SaaRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = SaaRules.describe(song, channel, effect, param)

    /** Arpeggio notes, what an envelope, noise or sync effect reaches (the lanes sharing it). */
    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? =
        SaaEffectText.detail(song, pattern, channel, row, column)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = SaaValidator.cellProblem(song, channel, cell)

    /** [SaaValidator]'s checks, after making sure the song's chip data is this system's variant (a project's backend id). */
    override fun structuralErrors(song: Song): List<String> = variantProblem(song)?.let { listOf(it) } ?: SaaValidator.structuralErrors(song)
    override fun errors(song: Song): List<String> = variantProblem(song)?.let { listOf(it) } ?: SaaValidator.errors(song)

    private fun variantProblem(song: Song): String? = (song.sound as? SaaSound)?.variant?.takeIf { it != variant }?.let {
        "The song's chip data is for the ${it.label} (${it.chipLabel}), not the ${variant.label} (${variant.chipLabel})"
    }

    override fun warnings(song: Song) = SaaValidator.warnings(song)

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

    /**
     * The order at [order] alone, looping: every `Bxx` in it aims at that one order (`B00`); `Dxx` already lands on
     * "the next order", which is the loop.
     */
    override fun patternLoop(song: Song, order: Int): Song {
        val index = song.orders[order.coerceIn(0, song.orders.size - 1)]
        val pattern = song.patterns[index]
        val tracks = pattern.tracks.map { track -> track.map { c -> if (jumpTarget(c) != null) withJumpTarget(c, 0) else c } }
        val patterns = if (tracks == pattern.tracks) song.patterns else song.patterns.toMutableList().also { it[index] = pattern.copy(tracks = tracks) }
        return song.copy(orders = listOf(index), loopOrder = 0, patterns = patterns, parkedOrders = emptyList())
    }

    /** `Bxx` jumps to order xx (absolute, B00 = the first order); the first `B` column of the cell. */
    override fun jumpTarget(cell: Cell): Int? {
        for (c in 0..cell.more.size) if (cell.effectCode(c) == SaaRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == SaaRules.FX_JUMP) out = out.withEffect(c, SaaRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine(this)

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else instrument(song, slot)?.name?.ifEmpty { "Instrument ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else instrument(song, slot)?.let { SaaRules.summary(it, song.saa.variant) } ?: "Empty: choose a preset"

    /**
     * The waveform a thumbnail shows: one cycle of the hardware envelope's left level when the instrument has one,
     * else a square for a sound whose first mixer step plays tone; null for noise alone, silence or an empty slot.
     */
    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? {
        if (bank != 0 || slot !in 1..SLOTS) return null
        val ins = instrument(song, slot) ?: return null
        ins.envelope?.let { e -> return SaaHardwareEnvelope.levels(e.shape, e.threeBit).map { it / 15f } }
        val first = ins.mixer.values.firstOrNull() ?: SaaInstrument.MIXER_TONE
        return if (first and SaaInstrument.MIXER_TONE != 0) SQUARE else null
    }

    /** Instrument [slot] (1..[SLOTS]), or null when empty. */
    fun instrument(song: Song, slot: Int): SaaInstrument? = song.saa.instrument(slot)

    /** Nothing of the SAA1099's own views follows the selection beyond the instrument itself. */
    override fun selectSound(selection: Selection, song: Song): Selection = selection

    override fun view(kind: ViewKind): SystemView? = when (kind) {
        ViewKind.INSTRUMENT -> SaaInstrumentView
        else -> null
    }

    override val songSettings: List<SongSetting> get() = SaaSongSettings.ALL

    /**
     * Each noise generator's clock and the lane that set it last, each sounding hardware envelope's clock and the
     * chips held or off by `Y02`/`Y03` ([SaaSharedState]).
     */
    override fun sharedState(song: Song, order: Int, row: Int): SharedState? = SaaSharedState.state(song, order, row)

    companion object {
        const val SLOTS = SaaPlayerImage.MAX_INSTRUMENTS

        /** What [instrumentName] shows for an empty slot. */
        const val EMPTY_NAME = "(empty)"

        private val SQUARE = List(32) { if (it < 16) 1f else 0f }
    }

    private class Engine(private val system: Saa1099System) : PlaybackEngine {
        override val id = system.id
        override val driver = when (system.variant) {
            SaaVariant.SAM -> "Thor Tracker's SAA1099 player on one SAA1099 at 8 MHz (a port of SAASound 3d92322), blip_buf 1.1.0"
            SaaVariant.CMS -> "Thor Tracker's SAA1099 player on two SAA1099s at 7.15909 MHz (a port of SAASound 3d92322), blip_buf 1.1.0"
        }
        override val imageSize = SaaPlayerImage.IMAGE_SIZE

        /** 4 minutes of song at 50 ticks per second, 3:20 at 60. */
        override val seekTicks = SaaPlayerImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = SaaLowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val ins = if (instrument in 1..SLOTS) (song.sound as? SaaSound)?.instrument(instrument) else null
            val variant = system.variant
            return when {
                song.sound !is SaaSound -> "The song is for ${song.system.name}, not the SAA1099"
                instrument !in 1..SLOTS -> "Choose an instrument (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Instrument ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in system.channels.indices -> "Cannot audition on channel $channel"
                note !in system.noteRange ->
                    "${system.noteName(note)} is outside ${SaaRules.rangeText(variant)}, the notes the ${variant.label}'s SAA1099 reaches"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> SaaValidator.instrumentProblem(ins)?.let { "Instrument ${NumberText.hex(instrument, 2)} cannot play: $it" }
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = fine
    }

    /**
     * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in any free effect column: the
     * player's pitch unit is 1/64 semitone (768 divider steps per octave), so every degree is realised within 0.8 cents
     * of the request, plus the chip's own divider rounding (within 3.4 cents of 12-TET).
     */
    private object Fine : FinePitch {
        override val name = "Pxx"
        override val max = 63
        override fun range(note: Int): IntRange = 0..max
        override fun cents(note: Int, fine: Int): Double = fine * 100.0 / 64

        /** The `P` column's upward fine value; 0 without one; null for a downward `P` (not a tuning). */
        override fun of(cell: Cell): Int? {
            val col = column(cell) ?: return 0
            val p = cell.effectParam(col)
            return if (p >= SaaRules.FINE_CENTRE) p - SaaRules.FINE_CENTRE else null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = column(cell)
            val base = cell.copy(note = note)
            if (fine == 0) {
                return if (current != null && cell.effectParam(current) >= SaaRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
            }
            if (current != null && cell.effectParam(current) < SaaRules.FINE_CENTRE) return null
            val col = current ?: (0 until SaaRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
            return base.withEffect(col, SaaRules.FX_FINE, SaaRules.FINE_CENTRE + fine)
        }

        private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == SaaRules.FX_FINE }
    }

    private class Help(private val variant: SaaVariant) : SystemHelp {
        private val lanes = variant.laneNames
        private val chip = if (variant.chips == 1) "the chip" else "each chip"

        override val off = "Key off: a note whose volume envelope has a release point plays its release (a second OFF cuts it); " +
            "any other note falls silent at once"
        override val offEntered = "OFF: key off (the release plays if the instrument has one)"
        override val offInspector = "OFF: key off (release, else silence)"
        override val effectsIntro = listOf(
            when (variant) {
                SaaVariant.SAM -> "Played by Thor Tracker's own player on the SAM Coupé's Philips SAA1099 (a port of SAASound's " +
                    "chip model) at 8 MHz: 50 ticks per second times the song's multispeed."
                SaaVariant.CMS -> "Played by Thor Tracker's own player on the Creative Music System's two Philips SAA1099s (a " +
                    "port of SAASound's chip model) at 7.15909 MHz, summed into one stereo output: 60 ticks per second times " +
                    "the song's multispeed. Lanes A1-A6 are chip A, B1-B6 chip B."
            },
            "Every lane is a square-wave tone generator with its own left and right level (0-15). Instruments are envelopes " +
                "stepped every tick (volume, arpeggio, pitch, and a mixer envelope choosing off, tone, noise or tone + noise), " +
                "a noise clock, a pan and an optional hardware envelope.",
            "Shared parts of $chip: lanes 1-3 share one noise generator and lanes 4-6 another; the latest note that starts " +
                "noise (or changes its clock) sets the clock for all three. Lanes 3 and 6 have the hardware envelopes, " +
                "clocked by lane 2's or 5's tone generator or externally by the player. The Pattern lane headers show who " +
                "holds each one; the song check warns where they clash.",
            "Up to four effect columns per lane, run in column order. 0xy, 8xy, Exx, Mxx, Nxx and Pxx shape the note on " +
                "their row (on a row without a note, the sounding note); volume, slides, vibrato and Yxx act from their row on.",
            "Yxx controls the whole chip of its lane: Y01 restarts every generator in phase (a clean unison), Y02 holds " +
                "them silent, Y03 turns the sound off, Y00 returns to normal.",
            "No chords: each lane is one fixed voice, so spread chords over lanes or use arpeggios. Drums are instruments " +
                "with volume, relative pitch and mixer envelopes (tone for a kick's thump, noise for snares and hats).",
        )
        override val tonalChannels = "all ${lanes.size} SAA1099 lanes"
        override val tonalChannelsShort = "SAA"
        override val untonalNote = "Every lane is tonal; noise follows its clock, not the note (except clock 3 on lanes 1 and 4)."
        override val sketchChannels = "${lanes.first()}-${lanes.last()}"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = SaaInstrumentView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = SaaValidator.limits(song)
    }
}

/** The Song view's SAA1099 row: multispeed (both machines). */
object SaaSongSettings {
    private fun with(song: Song, f: (SaaSound) -> SaaSound): Song = f(song.saa).let { if (it == song.saa) song else song.copy(sound = it) }

    val ALL: List<SongSetting> = listOf(
        SongSetting("Multispeed", "Player ticks per frame", "Set multispeed",
            value = { s ->
                val m = s.saa.multispeed
                val rate = s.saa.variant.rate
                if (m == 1) "1×   $rate ticks per second" else "$m×   ${rate * m} ticks per second: speeds and effects count $m× faster"
            },
            adjust = { s, f, c -> with(s) { it.copy(multispeed = (it.multispeed + f + 4 * c).coerceIn(1, SaaPlayerImage.MAX_MULTISPEED)) } },
            reset = { s -> with(s) { it.copy(multispeed = 1) } },
        ),
    )
}
