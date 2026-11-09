package org.thortracker.system.pokey

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.PokeyLowering
import org.thortracker.backend.PokeyPlayerImage
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
import kotlin.math.ceil

/**
 * The Atari 8-bit POKEY (C012294) played by Thor Tracker's own player on its own cycle-exact POKEY model (native
 * `"atari-pokey"`; exported as VGM): four channels, each sound one of five kinds (8-bit, 16-bit, high-pass, 16-bit
 * high-pass, samples) that takes the POKEY channels it needs, cutting what held them; FamiStudio-style envelopes
 * (volume, arpeggio, pitch, distortion); up to four effect columns per channel; global base clock, noise generator and
 * two-tone effects. See docs/BACKENDS.md ("POKEY backend") and docs/AUDIO_ENGINE.md ("POKEY").
 */
object PokeySystem : SoundSystem {
    override val id = PokeyPlayerImage.ENGINE_ID
    override val name = "Atari 8-bit (POKEY)"

    const val SLOTS = PokeyPlayerImage.MAX_INSTRUMENTS
    const val MAX_SAMPLES = PokeyPlayerImage.MAX_SAMPLES
    const val MAX_MULTISPEED = PokeyPlayerImage.MAX_MULTISPEED

    override val channels = PokeyRules.CHANNEL_NAMES.map { ChannelSpec(it, ChannelKind.VOICE, 0, tonal = true) }
    override val banks = listOf(BankSpec("Instruments", SLOTS))
    override val noteRange = PokeyRules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = PokeyRules.EFFECTS
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports = listOf(ExportFormat("vgm") { PokeyVgm.export(it) })
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help

    /** OFF is a note-column event (release). */
    override val offEffect: String? = null

    /** One player tick per NTSC video frame at multispeed 1 (59.92 Hz); [ticksPerSecond] follows the song's machine. */
    override val tickHz = PokeyMachine.NTSC.frameHz
    override val maxOrders = PokeyPlayerImage.MAX_ORDERS

    override fun ticksPerSecond(song: Song): Double = song.pokey.machine.frameHz * song.pokey.multispeed

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = PokeySound.blank(),
    )

    override val soundSerializer: KSerializer<PokeySound> = PokeySound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun demo(): Song = PokeyDemoSong.build()

    override fun effectsFor(channel: Int): List<EffectSpec> = PokeyRules.EFFECTS

    override fun effectColumns(channel: Int): Int = PokeyRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = PokeyRules.describe(song, effect, param)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = PokeyValidator.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song) = PokeyValidator.structuralErrors(song)
    override fun errors(song: Song) = PokeyValidator.errors(song)
    override fun warnings(song: Song) = PokeyValidator.warnings(song)

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
     * The order at [order] alone, looping, starting with the state sequential straight play starts it with (the
     * loader's `start_speed`/`start_audctl`/`start_two_tone` derivation, [startState]): that speed as the song speed,
     * those base-clock and noise bits as the song's start bits, and a non-zero two-tone mode as a `Wxx` in the first
     * free effect column of row 0 (lanes, then columns), unless row 0 already sets two-tone itself or has no free
     * column (then the loop plays with two-tone off). Every `Bxx` in it aims at that one order (`B00`); `Dxx` already
     * lands on "the next order", which is the loop.
     */
    override fun patternLoop(song: Song, order: Int): Song {
        val at = order.coerceIn(0, song.orders.size - 1)
        val index = song.orders[at]
        val pattern = song.patterns[index]
        val start = startState(song, at)
        var tracks = pattern.tracks.map { track -> track.map { c -> if (jumpTarget(c) != null) withJumpTarget(c, 0) else c } }
        if (start.twoTone != 0 && tracks.none { t -> t[0].let { c -> (0..c.more.size).any { c.effectCode(it) == PokeyRules.FX_TWO_TONE } } }) {
            free@ for (lane in tracks.indices) for (col in 0 until PokeyRules.EFFECT_COLUMNS) {
                val c = tracks[lane][0]
                if (c.effectCode(col) == 0 && c.effectParam(col) == 0) {
                    tracks = tracks.toMutableList().also { t ->
                        t[lane] = t[lane].toMutableList().also { it[0] = c.withEffect(col, PokeyRules.FX_TWO_TONE, start.twoTone) }
                    }
                    break@free
                }
            }
        }
        val patterns = if (tracks == pattern.tracks) song.patterns else song.patterns.toMutableList().also { it[index] = pattern.copy(tracks = tracks) }
        val sound = song.pokey.copy(poly9 = start.poly9, base15 = start.base15)
        return song.copy(orders = listOf(index), loopOrder = 0, patterns = patterns, parkedOrders = emptyList(),
            ticksPerRow = start.speed, sound = if (sound == song.pokey) song.pokey else sound)
    }

    /** What sequential straight play starts an order with: speed, the shared AUDCTL bits and the two-tone mode. */
    data class StartState(val speed: Int, val poly9: Boolean, val base15: Boolean, val twoTone: Int)

    /**
     * The state sequential straight play starts order [order] of [song] with, as `pk_load_image` derives it: the song's
     * start values, then every order played before it (a parked order: none, it plays only on its own), each row in
     * order, channels then columns: the last `Fxx` (01-FF), `Kxx`/`Nxx` (00-01), `Wxx` (00-02) win; jumps ignored.
     */
    fun startState(song: Song, order: Int): StartState {
        var speed = song.ticksPerRow
        var poly9 = song.pokey.poly9
        var base15 = song.pokey.base15
        var twoTone = 0
        val before = if (order in song.parkedOrders) emptyList() else Songs.playedOrders(song).filter { it < order }
        for (o in before) {
            val p = song.patterns[song.orders[o]]
            for (r in 0 until p.length) for (track in p.tracks) {
                val c = track[r]
                for (col in 0..c.more.size) {
                    val v = c.effectParam(col)
                    when (c.effectCode(col)) {
                        PokeyRules.FX_SPEED -> if (v > 0) speed = v
                        PokeyRules.FX_CLOCK -> if (v <= 1) base15 = v == 1
                        PokeyRules.FX_POLY -> if (v <= 1) poly9 = v == 1
                        PokeyRules.FX_TWO_TONE -> if (v <= 2) twoTone = v
                    }
                }
            }
        }
        return StartState(speed, poly9, base15, twoTone)
    }

    /** `Bxx` jumps to order xx (absolute, B00 = the first order); the first `B` column of the cell. */
    override fun jumpTarget(cell: Cell): Int? {
        for (c in 0..cell.more.size) if (cell.effectCode(c) == PokeyRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == PokeyRules.FX_JUMP) out = out.withEffect(c, PokeyRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine

    /** What [instrumentName] shows for an empty slot. */
    const val EMPTY_NAME = "(empty)"

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else song.pokey.instrument(slot)?.name?.ifEmpty { "Instrument ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else song.pokey.instrument(slot)?.let { summary(song, it) } ?: "Empty: choose a preset"

    /**
     * "8-bit · dist A pure tone · A-4 on POKEY 1: 437.8 Hz (−9¢)": the kind, its clock, the distortion it starts with and what
     * A-4 sounds on the first channel it plays on, with the song's starting base clock (samples: the sample and rate).
     */
    fun summary(song: Song, ins: PokeyInstrument): String {
        val sound = song.pokey
        val lane = ins.kind.lanes.minOrNull() ?: 0
        val fast = ins.fast && lane in ins.kind.fastLanes
        val clock = when {
            fast -> "1.79 MHz"
            sound.base15 -> "15 kHz"
            else -> null
        }
        if (ins.kind == PokeyKind.SAMPLE) {
            val s = sound.samples.getOrNull(ins.sample)
            val sample = if (s == null) "no sample ${NumberText.hex(ins.sample, 2)}" else "${s.name} (${s.levels.size} levels)"
            val loop = if (ins.loop >= 0) "loops from ${ins.loop}" else "one-shot"
            return listOfNotNull(ins.kind.label, clock, sample, "${ins.rate} Hz at ${noteName(ins.root)}", loop).joinToString(" · ")
        }
        val distortion = ins.distortion.values.firstOrNull() ?: PokeyDistortion.PURE
        val difference = ins.kind.filtered && ins.difference
        val kind = if (difference) "${ins.kind.label} difference ${ins.filterOffset}"
            else if (ins.kind.filtered) "${ins.kind.label} offset ${PokeyRules.signed(ins.filterOffset)}" else ins.kind.label
        val a4 = PokeyPlayerImage.PITCH_A4
        val hz = PokeyPitch.hz(sound.machine, sound.base15, ins, lane, a4, distortion)
        val cents = PokeyPitch.cents(sound.machine, sound.base15, ins, lane, a4, distortion)
        val readout = "A-4 on ${PokeyRules.CHANNEL_NAMES[lane]}: ${PokeyPitch.hzText(hz)}" +
            if (PokeyDistortion.pitched(distortion) || difference) " (${PokeyPitch.centsText(cents)})" else ""
        return listOfNotNull(kind, clock, "dist ${PokeyDistortion.label(distortion)}".takeUnless { difference }, readout).joinToString(" · ")
    }

    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) PokeyInstrumentView else null

    override val songSettings: List<SongSetting> get() = PokeySongSettings.ALL

    override fun sharedState(song: Song, order: Int, row: Int): SharedState? = PokeyChannelHolders.state(song, order, row)

    /**
     * A one-shot sample sound's note sounds for its sample's length at the rate the counter gives the note (the song's
     * starting base clock and speed); null for every other sound, which sounds until the next note or OFF.
     */
    override fun soundingRows(song: Song, pattern: Int, channel: Int, row: Int): Int? {
        val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row) ?: return null
        if (cell.note < 0) return null
        val slot = if (cell.instrument > 0) cell.instrument else (row - 1 downTo 0).firstNotNullOfOrNull { r -> track[r].instrument.takeIf { it > 0 } } ?: return null
        val ins = song.pokey.instrument(slot) ?: return null
        if (ins.kind != PokeyKind.SAMPLE || ins.loop >= 0 || channel !in ins.kind.lanes) return null
        val sample = song.pokey.samples.getOrNull(ins.sample) ?: return null
        val rate = PokeyPitch.hz(song.pokey.machine, song.pokey.base15, ins, channel, 64 * cell.note, PokeyDistortion.VOLUME_ONLY)
        if (rate.isNaN() || rate <= 0) return null
        val ticks = sample.levels.size / rate * ticksPerSecond(song)
        return maxOf(1, ceil(ticks / song.ticksPerRow).toInt())
    }

    private object Engine : PlaybackEngine {
        override val id = PokeyPlayerImage.ENGINE_ID
        override val driver = "Thor Tracker's POKEY player on its own cycle-exact POKEY model (after the Altirra Hardware " +
            "Reference Manual 2026-01-02, Altirra 4.40 and MAME pokey.cpp), blip_buf 1.1.0"
        override val imageSize = PokeyPlayerImage.IMAGE_SIZE

        /** At 59.92 ticks per second, ~3.3 minutes of song (multispeed songs: proportionally less). */
        override val seekTicks = PokeyPlayerImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = PokeyLowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val ins = if (instrument in 1..SLOTS) song.pokey.instrument(instrument) else null
            return when {
                instrument !in 1..SLOTS -> "Choose an instrument (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Instrument ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in channels.indices || note !in noteRange -> "Cannot audition note ${noteName(note)} on channel $channel"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> PokeyValidator.laneProblem(ins, instrument, channel)?.replaceFirstChar { it.uppercase() }
                    ?: PokeyValidator.instrumentProblem(ins)?.let { "Instrument ${NumberText.hex(instrument, 2)}: $it" }
                    ?: if (ins.kind == PokeyKind.SAMPLE && ins.sample !in song.pokey.samples.indices) {
                        "\"${ins.name}\" plays sample ${NumberText.hex(ins.sample, 2)}, which the song does not have; choose a sample"
                    } else null
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = fine
    }

    /**
     * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in any free effect column. The
     * player's unit is 1/64 semitone (1.56 cents); what sounds is then the nearest counter period (docs/AUDIO_ENGINE.md,
     * "Pitch to counter"), which on 8-bit base-clock voices is coarser than any tuning.
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
            return if (p >= PokeyRules.FINE_CENTRE) p - PokeyRules.FINE_CENTRE else null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = column(cell)
            val base = cell.copy(note = note)
            if (fine == 0) {
                return if (current != null && cell.effectParam(current) >= PokeyRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
            }
            if (current != null && cell.effectParam(current) < PokeyRules.FINE_CENTRE) return null
            val col = current ?: (0 until PokeyRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
            return base.withEffect(col, PokeyRules.FX_FINE, PokeyRules.FINE_CENTRE + fine)
        }

        private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == PokeyRules.FX_FINE }
    }

    private object Help : SystemHelp {
        override val off = "Release: envelopes with a release step jump to it and play on from there; a sound whose volume " +
            "envelope has no release step goes silent at once; a sample stops"
        override val offEntered = "OFF: release (the volume envelope's release plays, or the sound stops)"
        override val offInspector = "OFF: release (release steps, else silence)"
        override val effectsIntro = listOf(
            "Played by Thor Tracker's own player on a cycle-exact POKEY: one tick per video frame (NTSC 59.92, PAL 49.86 per " +
                "second) times the song's multispeed. Up to four effect columns per channel, run in column order.",
            "Each sound has a kind. 8-bit sounds play on any channel. 16-bit sounds play on POKEY 2 or 4 and take POKEY 1 or 3 " +
                "(the low byte). High-pass sounds play on POKEY 1 or 2 and take POKEY 3 or 4 (the clock); 16-bit high-pass takes " +
                "all four from POKEY 2. Samples play on POKEY 1, 2 or 4 (timer 3 has no interrupt). Fast (1.79 MHz) exists on " +
                "POKEY 1 and 3 for 8-bit sounds, on the low byte for 16-bit, on POKEY 1 for high-pass and samples.",
            "A note takes every channel its sound needs, cutting whatever held them: the most recent note wins. The lane headers " +
                "show who holds which channel; the song check warns where a note cuts a sound that was not released.",
            "K (base clock), N (noise generator), W (two-tone), R (generator restart), T (timer restart), B, D and F act on the " +
                "whole chip at the row's first tick. Each pass of the loop starts with the base clock, noise generator and " +
                "two-tone mode the loop order starts with in straight play.",
            "H and V shape the note on their row (or the sounding voice): the high-pass offset and the distortion.",
        )
        override val tonalChannels = "all four POKEY channels"
        override val tonalChannelsShort = "POKEY"
        override val untonalNote = "Every channel is tonal; noise distortions 0 and 8 pick a noise rate from the note."
        override val sketchChannels = "POKEY 1-4"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = PokeyInstrumentView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = PokeyValidator.limits(song)
    }
}
