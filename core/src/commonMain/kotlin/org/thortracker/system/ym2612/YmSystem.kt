package org.thortracker.system.ym2612

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.YmLowering
import org.thortracker.backend.YmPlayerImage
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
import org.thortracker.system.SongSetting
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView

/**
 * The Sega Mega Drive / Genesis: a YM2612 (OPN2) and the SN76489 PSG, played by Thor Tracker's own player streamed
 * through SGDK's XGM Z80 driver (native `"ym2612-fm"`; exported as VGM, XGM for SGDK's `rescomp` and the compiled XGC
 * `XGM_startPlay` takes): ten channels (FM 1-6, PSG 1-3, Noise), up to four effect columns each, one bank of [SLOTS]
 * instruments holding FM voices (operator registers, curated controls [YmCurated], drum sweep) or PSG sounds
 * (software envelopes). See docs/BACKENDS.md ("YM2612") and docs/AUDIO_ENGINE.md ("YM2612").
 */
object YmSystem : SoundSystem {
    override val id = YmPlayerImage.ENGINE_ID
    override val name = "Mega Drive (YM2612 + PSG)"

    const val SLOTS = YmPlayerImage.MAX_INSTRUMENTS

    /** Vertical interrupts per second: NTSC 53693175 / (3420 × 262) and PAL 53203424 / (3420 × 313). */
    val NTSC_HZ = YmPlayerImage.MCLK_NTSC.toDouble() / (YmPlayerImage.MCLK_PER_LINE * YmPlayerImage.LINES_NTSC)
    val PAL_HZ = YmPlayerImage.MCLK_PAL.toDouble() / (YmPlayerImage.MCLK_PER_LINE * YmPlayerImage.LINES_PAL)

    /** [banks]: FM voices (FM 1-6) and PSG sounds (PSG 1-3, Noise) — two views of the one numbering of [SLOTS] slots. */
    const val FM_BANK = 0
    const val PSG_BANK = 1

    override val channels = YmRules.CHANNEL_NAMES.mapIndexed { ch, name ->
        when {
            YmRules.isFm(ch) -> ChannelSpec(name, ChannelKind.VOICE, FM_BANK, tonal = true)
            ch == YmRules.NOISE -> ChannelSpec(name, ChannelKind.NOISE, PSG_BANK, tonal = false)
            else -> ChannelSpec(name, ChannelKind.PULSE, PSG_BANK, tonal = true)
        }
    }

    /**
     * Both banks name the same 64 slots (a slot holds an FM voice or a PSG sound; [YmSound.instruments]); they exist so
     * the editor keeps a separate current sound for FM and PSG channels ([defaultInstrument]).
     */
    override val banks = listOf(BankSpec("FM voices", SLOTS), BankSpec("PSG sounds", SLOTS))
    override val noteRange = YmRules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = YmRules.EFFECTS
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports = listOf(
        ExportFormat("vgm") { YmExport.export(it, YmExport.Format.VGM) },
        ExportFormat("xgm") { YmExport.export(it, YmExport.Format.XGM) },
        ExportFormat("xgc") { YmExport.export(it, YmExport.Format.XGC) },
    )
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help

    /** OFF is a note-column event (key off). */
    override val offEffect: String? = null

    /** NTSC: one tick per vertical interrupt. */
    override val tickHz = NTSC_HZ
    override val maxOrders = YmPlayerImage.MAX_ORDERS

    override fun ticksPerSecond(song: Song): Double = if ((song.sound as? YmSound)?.pal == true) PAL_HZ else NTSC_HZ

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = YmSound.blank(),
    )

    override val soundSerializer: KSerializer<YmSound> = YmSound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun demo(): Song = YmDemoSong.build()

    override fun effectsFor(channel: Int): List<EffectSpec> = YmRules.effectsFor(channel)

    override fun effectColumns(channel: Int): Int = YmRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = YmRules.describe(song, channel, effect, param)

    /** Arpeggios read with the notes they step through; locks on rows without a note say they are ignored ([YmEffectText]). */
    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? =
        YmEffectText.detail(song, pattern, channel, row, column)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = YmValidator.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song) = YmValidator.structuralErrors(song)
    override fun errors(song: Song) = YmValidator.errors(song)
    override fun warnings(song: Song) = YmValidator.warnings(song)

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
        for (c in 0..cell.more.size) if (cell.effectCode(c) == YmRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == YmRules.FX_JUMP) out = out.withEffect(c, YmRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank !in banks.indices || slot !in 1..SLOTS) null else instrument(song, slot)?.name?.ifEmpty { "Instrument ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank !in banks.indices || slot !in 1..SLOTS) null else instrument(song, slot)?.let(YmCurated::summary) ?: "Empty: choose a preset"

    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null

    /** What [instrumentName] shows for an empty slot. */
    const val EMPTY_NAME = "(empty)"

    /** Instrument [slot] (1..[SLOTS]), or null when empty. */
    fun instrument(song: Song, slot: Int): YmInstrument? = song.ym.instrument(slot)

    /** Why [instrument] cannot play on [channel] (FM voices on FM 1-6, PSG sounds on PSG 1-3 and Noise), or null. */
    fun kindProblem(channel: Int, slot: Int, instrument: YmInstrument): String? = when {
        YmRules.isFm(channel) && instrument is YmPsgInstrument ->
            "instrument ${NumberText.hex(slot, 2)} \"${instrument.name}\" is a PSG sound; FM 1-6 play FM instruments"
        !YmRules.isFm(channel) && instrument is YmFmInstrument ->
            "instrument ${NumberText.hex(slot, 2)} \"${instrument.name}\" is an FM voice; ${YmRules.CHANNEL_NAMES[channel]} plays PSG instruments"
        else -> null
    }

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    /** The first slot holding the bank's kind (an FM voice for [FM_BANK], a PSG sound for [PSG_BANK]), else 1. */
    override fun defaultInstrument(song: Song, bank: Int): Int {
        val found = song.ym.instruments.indexOfFirst { if (bank == PSG_BANK) it is YmPsgInstrument else it is YmFmInstrument }
        return if (found >= 0) found + 1 else 1
    }

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) YmInstrumentView else null

    override val songSettings: List<SongSetting> get() = YmSongSettings.ALL

    private object Engine : PlaybackEngine {
        override val id = YmPlayerImage.ENGINE_ID
        override val imageSize = YmPlayerImage.IMAGE_SIZE

        /** At 60 ticks per second, ~3.3 minutes of song. */
        override val seekTicks = YmPlayerImage.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = YmLowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val ins = if (instrument in 1..SLOTS) song.ym.instrument(instrument) else null
            return when {
                instrument !in 1..SLOTS -> "Choose an instrument (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Instrument ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in channels.indices || note !in noteRange -> "Cannot audition note ${noteName(note)} on channel $channel"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> kindProblem(channel, instrument, ins)?.replaceFirstChar { it.uppercase() }
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = fine
    }

    /**
     * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in any free effect column: the
     * player's pitch unit is 1/64 semitone (1.56 cents), so every degree is realised within 0.8 cents of the request,
     * plus the chips' own rounding (FM F-numbers ≤ 0.66 cents from C-1; the PSG's 10-bit periods are coarse up high).
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
            return if (p >= YmRules.FINE_CENTRE) p - YmRules.FINE_CENTRE else null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = column(cell)
            val base = cell.copy(note = note)
            if (fine == 0) {
                return if (current != null && cell.effectParam(current) >= YmRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
            }
            if (current != null && cell.effectParam(current) < YmRules.FINE_CENTRE) return null
            val col = current ?: (0 until YmRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
            return base.withEffect(col, YmRules.FX_FINE, YmRules.FINE_CENTRE + fine)
        }

        private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == YmRules.FX_FINE }
    }

    private object Help : SystemHelp {
        override val off = "Key off: FM notes fade with their release, PSG sounds play their volume envelope's release"
        override val offEntered = "OFF: key off (the release plays)"
        override val offInspector = "OFF: key off (release phase)"
        override val effectsIntro = listOf(
            "Played by Thor Tracker's own player through SGDK's XGM driver on a Z80, YM2612 and PSG: one tick per vertical " +
                "interrupt (60 Hz NTSC, 50 Hz PAL).",
            "Up to four effect columns per channel, run in column order. Locks (E, I, M, N) shape the note started on their " +
                "row only; volume, pan, slides, vibrato and the LFO act from their row on.",
            "FM 1-6 play FM instruments, PSG 1-3 and Noise play PSG instruments. The Noise channel's pitched mode takes " +
                "PSG 3's period, so PSG 3 is silent while it sounds.",
            "No chords: the YM2612 has no spare voices; spread a chord over channels. Drums are instruments with a pitch " +
                "sweep: the note picks the drum's pitch.",
        )
        override val tonalChannels = "FM 1-6 and PSG 1-3"
        override val tonalChannelsShort = "FM/PSG"
        override val untonalNote = "Noise keeps every pitch (it only matters in pitched mode)."
        override val sketchChannels = "FM 1-6 or PSG 1-3; Noise has no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = YmInstrumentView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = YmValidator.limits(song)
    }
}

/** The Song view's Mega Drive rows: the region and the chip's LFO at the song start. */
object YmSongSettings {
    private fun with(song: Song, f: (YmSound) -> YmSound): Song = f(song.ym).let { if (it == song.ym) song else song.copy(sound = it) }

    /** LFO choices in stepping order: off, then rates 0-7 (register values 0, 8..15). */
    private val LFO_STEPS = listOf(0) + (0x08..0x0F)

    val ALL: List<SongSetting> = listOf(
        SongSetting("Region", "NTSC or PAL", "Set region",
            value = { s ->
                if (s.ym.pal) "PAL   50 Hz ticks (Europe); a game sets XGM_setMusicTempo(50)"
                else "NTSC   60 Hz ticks (Japan, Americas)"
            },
            adjust = { s, f, c -> if ((f + c) % 2 == 0) s else with(s) { it.copy(pal = !it.pal) } },
            reset = { s -> with(s) { it.copy(pal = false) } },
        ),
        SongSetting("LFO", "Chip LFO rate", "Set LFO",
            value = { s ->
                val v = s.ym.lfo
                if (v == 0) "Off   instruments' AMS/FMS have no effect"
                else "Rate ${v - 8}   ${YmRules.LFO_HZ[(v - 8).coerceIn(0, 7)]} Hz (instruments set the depth)"
            },
            adjust = { s, f, c ->
                with(s) {
                    val i = LFO_STEPS.indexOf(it.lfo).coerceAtLeast(0)
                    it.copy(lfo = LFO_STEPS[(i + f + 4 * c).coerceIn(LFO_STEPS.indices)])
                }
            },
            reset = { s -> with(s) { it.copy(lfo = 0) } },
        ),
    )
}
