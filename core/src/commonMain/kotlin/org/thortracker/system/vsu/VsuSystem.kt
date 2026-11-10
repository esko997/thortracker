package org.thortracker.system.vsu

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.VsuLowering
import org.thortracker.backend.VsuPlayerImage
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
 * The Virtual Boy's VSU played by Thor Tracker's own player on a VSU core after Mednafen 1.32.1 with the Sacred Tech
 * Scroll's amendments (native `"vb-vsu"`; exported as VGM and WAV, no Virtual Boy executable): five wave lanes playing
 * the song's five shared 32-sample waves (Wave 5 with the hardware sweep and modulation) and a noise lane; hardware
 * envelopes, lengths and stereo levels; two effect columns per lane; one bank of [SLOTS] instruments. See
 * docs/AUDIO_ENGINE.md ("Virtual Boy VSU").
 */
object VsuSystem : SoundSystem {
    override val id = VsuPlayerImage.ENGINE_ID
    override val name = "Virtual Boy (VSU)"

    const val SLOTS = VsuPlayerImage.MAX_INSTRUMENTS

    override val channels = VsuRules.CHANNEL_NAMES.mapIndexed { i, n ->
        if (i == VsuRules.NOISE_LANE) ChannelSpec(n, ChannelKind.NOISE, 0, tonal = false) else ChannelSpec(n, ChannelKind.WAVE, 0, tonal = true)
    }
    override val banks = listOf(BankSpec("Instruments", SLOTS))
    override val noteRange = VsuRules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = VsuRules.EFFECTS
    override val views = ViewKind.entries.toSet()
    override val exports = listOf(ExportFormat("vgm") { VsuVgm.export(it) })
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help

    /** OFF is a note-column event (the channel stops). */
    override val offEffect: String? = null

    /** One player tick per Virtual Boy display frame (the VIP's 50 Hz). */
    override val tickHz = VsuPlayerImage.TICK_HZ.toDouble()
    override val maxOrders = VsuPlayerImage.MAX_ORDERS

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = VsuSound.blank(),
    )

    override val soundSerializer: KSerializer<VsuSound> = VsuSound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun demo(): Song = VsuDemoSong.build()

    override fun effectsFor(channel: Int): List<EffectSpec> = VsuRules.effectsFor(channel)

    override fun effectColumns(channel: Int): Int = VsuRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = VsuRules.describe(channel, effect, param)

    /** Locks read with what they do to the row's note: the wave's name, the sweep's direction, the tap's sequence. */
    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
        val cell = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel)?.getOrNull(row) ?: return null
        val code = cell.effectCode(column)
        val param = cell.effectParam(column)
        val base = VsuRules.describe(channel, code, param)
        val noNote = cell.note == Cell.EMPTY
        return when (code) {
            VsuRules.FX_WAVE -> if (param !in 1..VsuPlayerImage.WAVES) null
                else "$base${VsuWaves.name(song.vsu, param - 1)?.let { " ($it)" } ?: ""}" + if (noNote) "; no note here: the sounding channel switches at once" else ""
            VsuRules.FX_LENGTH -> if (noNote) "$base; no note here, so it does nothing" else null
            VsuRules.FX_LEVEL, VsuRules.FX_SWEEP_MOD, VsuRules.FX_NOISE_TAP -> if (noNote) "$base; no note here: written to the sounding channel at once" else null
            else -> null
        }
    }

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = VsuValidator.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song) = VsuValidator.structuralErrors(song)
    override fun errors(song: Song) = VsuValidator.errors(song)
    override fun warnings(song: Song) = VsuValidator.warnings(song)

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
     * The order at [order] alone, looping, at the speed sequential straight play starts it with ([startSpeed]: the
     * loader's derivation, which the player's loop-speed rule applies on every pass). Every `Bxx` in it aims at that one
     * order (`B00`); `Dxx` already lands on "the next order", which is the loop.
     */
    override fun patternLoop(song: Song, order: Int): Song {
        val at = order.coerceIn(0, song.orders.size - 1)
        val index = song.orders[at]
        val pattern = song.patterns[index]
        val tracks = pattern.tracks.map { track -> track.map { c -> if (jumpTarget(c) != null) withJumpTarget(c, 0) else c } }
        val patterns = if (tracks == pattern.tracks) song.patterns else song.patterns.toMutableList().also { it[index] = pattern.copy(tracks = tracks) }
        return song.copy(orders = listOf(index), loopOrder = 0, patterns = patterns, parkedOrders = emptyList(), ticksPerRow = startSpeed(song, at))
    }

    /**
     * The speed sequential straight play starts order [order] of [song] with, as `vb_load_image` derives it: the song's
     * speed, then every order played before it (a parked order: none, it plays only on its own), each row in order,
     * lanes then columns: the last `Fxx` wins; jumps ignored.
     */
    fun startSpeed(song: Song, order: Int): Int {
        var speed = song.ticksPerRow
        for (o in VsuWalk.ordersBefore(song, order)) {
            val p = song.patterns[song.orders[o]]
            for (r in 0 until p.length) for (track in p.tracks) {
                val c = track[r]
                for (col in 0..c.more.size) if (c.effectCode(col) == VsuRules.FX_SPEED && c.effectParam(col) > 0) speed = c.effectParam(col)
            }
        }
        return speed
    }

    /** `Bxx` jumps to order xx (absolute, B00 = the first order); the first `B` column of the cell. */
    override fun jumpTarget(cell: Cell): Int? {
        for (c in 0..cell.more.size) if (cell.effectCode(c) == VsuRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == VsuRules.FX_JUMP) out = out.withEffect(c, VsuRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine

    /** What [instrumentName] shows for an empty slot. */
    const val EMPTY_NAME = "(empty)"

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else song.vsu.instrument(slot)?.name?.ifEmpty { "Instrument ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank != 0 || slot !in 1..SLOTS) null else song.vsu.instrument(slot)?.let { summary(song, it) } ?: "Empty: choose a preset"

    /**
     * "Wave 4 (Square) · level 15 ↓ every 123 ms · stops after 77 ms · A-4 F 1693: 440.1 Hz (+0.6¢)": the wave, the
     * envelope, the hardware length, Wave 5's sweep/modulation and the pitch sweep when set, and what A-4 really sounds.
     */
    fun summary(song: Song, ins: VsuInstrument): String {
        val parts = ArrayList<String>()
        parts += "Wave ${ins.wave + 1}" + (VsuWaves.name(song.vsu, ins.wave)?.let { " ($it)" } ?: "")
        parts += envelopeText(ins)
        if (ins.autoLength) parts += "stops after ${msText((ins.length + 1) * VsuPlayerImage.LENGTH_UNIT_MS)}"
        if (ins.noiseTap != 0) parts += "noise tap ${ins.noiseTap}"
        if (ins.freqMod.on) parts += "Wave 5 " + if (ins.freqMod.modulation) "modulation" else "sweep ${if (ins.freqMod.up) "up" else "down"}"
        if (ins.sweep.active) parts += "pitch ${VsuRules.signed(ins.sweep.start)}→${VsuRules.signed(ins.sweep.end)}"
        parts += VsuPitch.readout(0, 57)
        return parts.joinToString(" · ")
    }

    /** "level 15 ↓ every 123 ms", "level 12 held". */
    fun envelopeText(ins: VsuInstrument): String {
        if (!ins.envelope) return "level ${ins.level} held"
        val step = msText((ins.interval + 1) * VsuPlayerImage.ENVELOPE_UNIT_MS)
        return "level ${ins.level} ${if (ins.grow) "↑" else "↓"} every $step" + if (ins.repeat) ", repeating" else ""
    }

    fun msText(ms: Double): String = if (ms >= 1000.0) NumberText.fixed(ms / 1000.0, 2) + " s" else NumberText.fixed(ms, 0) + " ms"

    /** The wave the sound plays, as levels 0..1. */
    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? {
        if (bank != 0) return null
        val ins = song.vsu.instrument(slot) ?: return null
        return song.vsu.waves.getOrNull(ins.wave)?.map { it / VsuPlayerImage.SAMPLE_MAX.toFloat() }
    }

    /** The Wave view follows the wave the selected sound plays. */
    override fun selectSound(selection: Selection, song: Song): Selection =
        if (selection.bank != 0) selection else selection.copy(wave = song.vsu.instrument(selection.instrument)?.wave ?: selection.wave)

    override fun view(kind: ViewKind): SystemView? = when (kind) {
        ViewKind.INSTRUMENT -> VsuInstrumentView
        ViewKind.WAVE -> VsuWaveView
        else -> null
    }

    override fun sharedState(song: Song, order: Int, row: Int): SharedState? = VsuLaneState.state(song, order, row)

    private object Engine : PlaybackEngine {
        override val id = VsuPlayerImage.ENGINE_ID
        override val driver = "Thor Tracker VSU player on a VSU core after Mednafen 1.32.1 (Sacred Tech Scroll 2024-10-20 amendments)"
        override val imageSize = VsuPlayerImage.IMAGE_SIZE

        /** At 50 ticks per second, 4 minutes of song. */
        override val seekTicks = VsuPlayerImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = VsuLowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val ins = if (instrument in 1..SLOTS) song.vsu.instrument(instrument) else null
            return when {
                instrument !in 1..SLOTS -> "Choose an instrument (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Instrument ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in channels.indices || note !in noteRange -> "Cannot audition note ${noteName(note)} on channel $channel"
                note !in VsuRules.noteRange(channel) -> "${channels[channel].name} plays D#2-B-7; ${noteName(note)} is below (the Noise lane plays every note)"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> null
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = fine
    }

    /**
     * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in any free effect column, as on
     * the OPL3: the player's pitch unit is 1/64 semitone (1.56 cents); what sounds is then the nearest frequency register
     * value, which the editor's pitch readout shows ([VsuPitch.cents]).
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
            return if (p >= VsuRules.FINE_CENTRE) p - VsuRules.FINE_CENTRE else null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = column(cell)
            val base = cell.copy(note = note)
            if (fine == 0) {
                return if (current != null && cell.effectParam(current) >= VsuRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
            }
            if (current != null && cell.effectParam(current) < VsuRules.FINE_CENTRE) return null
            val col = current ?: (0 until VsuRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
            return base.withEffect(col, VsuRules.FX_FINE, VsuRules.FINE_CENTRE + fine)
        }

        private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == VsuRules.FX_FINE }
    }

    private object Help : SystemHelp {
        override val off = "Key off: the channel stops at once (the VSU has no release; use a decaying envelope or a length)"
        override val offEntered = "OFF: the channel stops"
        override val offInspector = "OFF: channel stop (no release)"
        override val effectsIntro = listOf(
            "Played by Thor Tracker's own player on a VSU core: one tick per Virtual Boy frame, 50 per second. Two effect " +
                "columns per lane, run in column order.",
            "Wave 1-5 play the song's five shared waves (Wave view): any lane may play any wave, and an edited wave changes " +
                "every lane playing it. Wave 5 adds the hardware sweep and modulation (its table is the Wave view's last page); " +
                "Noise plays a shift register whose tap picks the sequence and whose note picks the rate.",
            "Locks (C level, L length, M sweep/mod, N noise tap, W wave) shape the note on their row; on a row that starts " +
                "no note (none, OFF, a portamento glide) they change the sounding channel at once (L then does nothing). Stereo (8xy), slides and vibrato act from " +
                "their row on.",
            "Hardware quirk: with Wave 5's sweep set to go up, a note whose frequency register plus its shift passes 2047 " +
                "stops at once, even with the sweep off. The song check warns about such notes.",
        )
        override val tonalChannels = "Wave 1-5"
        override val tonalChannelsShort = "Wave"
        override val untonalNote = "Noise keeps every note: its notes pick shift-register rates."
        override val sketchChannels = "Wave 1-5; Noise has no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> =
            VsuInstrumentView.helpSections(keys) + VsuWaveView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = VsuValidator.limits(song)
    }
}
