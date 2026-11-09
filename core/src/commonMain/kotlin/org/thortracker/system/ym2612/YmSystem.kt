package org.thortracker.system.ym2612

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.thortracker.backend.YmLowering
import org.thortracker.backend.YmPcmLowering
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
import org.thortracker.system.NoteSample
import org.thortracker.system.PlaybackEngine
import org.thortracker.system.SongSetting
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView

/**
 * The Sega Mega Drive / Genesis: a YM2612 (OPN2) and the SN76489 PSG, played by Thor Tracker's own player streamed
 * through SGDK's XGM2 Z80 driver (native `"ym2612-fm"`; exported as VGM, XGM2 `.xgm` for SGDK's `rescomp` and the
 * compiled XGM2 `.xgc` `XGM2_play` takes): thirteen channels (FM 1-6, PSG 1-3, Noise, PCM 1-3), up to four effect
 * columns each, one bank of [SLOTS] instruments holding FM voices (operator registers, curated controls [YmCurated],
 * drum sweep, FM 3 operator pitches), PSG sounds (software envelopes) or PCM kits (keys mapped to samples compiled
 * for the driver, [YmPcm]). See docs/BACKENDS.md ("YM2612") and docs/AUDIO_ENGINE.md ("YM2612").
 */
object YmSystem : SoundSystem {
    override val id = YmPlayerImage.ENGINE_ID
    override val name = "Mega Drive (YM2612 + PSG)"

    const val SLOTS = YmPlayerImage.MAX_INSTRUMENTS

    /** Vertical interrupts per second: NTSC 53693175 / (3420 × 262) and PAL 53203424 / (3420 × 313). */
    val NTSC_HZ = YmPlayerImage.MCLK_NTSC.toDouble() / (YmPlayerImage.MCLK_PER_LINE * YmPlayerImage.LINES_NTSC)
    val PAL_HZ = YmPlayerImage.MCLK_PAL.toDouble() / (YmPlayerImage.MCLK_PER_LINE * YmPlayerImage.LINES_PAL)

    /** [banks]: FM voices (FM 1-6), PSG sounds (PSG 1-3, Noise) and PCM kits (PCM 1-3) — three views of one numbering. */
    const val FM_BANK = 0
    const val PSG_BANK = 1
    const val PCM_BANK = 2

    override val channels = YmRules.CHANNEL_NAMES.mapIndexed { ch, name ->
        when {
            YmRules.isFm(ch) -> ChannelSpec(name, ChannelKind.VOICE, FM_BANK, tonal = true)
            ch == YmRules.NOISE -> ChannelSpec(name, ChannelKind.NOISE, PSG_BANK, tonal = false)
            YmRules.isPcm(ch) -> ChannelSpec(name, ChannelKind.SAMPLE, PCM_BANK, tonal = false)
            else -> ChannelSpec(name, ChannelKind.PULSE, PSG_BANK, tonal = true)
        }
    }

    /**
     * The three banks name the same 64 slots (a slot holds an FM voice, a PSG sound or a PCM kit; [YmSound.instruments]);
     * they exist so the editor keeps a separate current sound per kind ([defaultInstrument]).
     */
    override val banks = listOf(BankSpec("FM voices", SLOTS), BankSpec("PSG sounds", SLOTS), BankSpec("PCM kits", SLOTS))
    override val noteRange = YmRules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = YmRules.EFFECTS
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports = listOf(
        ExportFormat("vgm") { YmExport.export(it, YmExport.Format.VGM) },
        ExportFormat("xgm", ".xgm (XGM2)") { YmExport.export(it, YmExport.Format.XGM) },
        ExportFormat("xgc", ".xgc (XGM2 compiled)") { YmExport.export(it, YmExport.Format.XGC) },
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

    /** 2: PCM 1-3 (tracks 10-12), PCM kits and samples, FM 3's mode and the voices' FM 3 operator offsets. */
    override val backendVersion = 2

    /**
     * Mega Drive data version 1 had ten tracks (FM 1-6, PSG 1-3, Noise): every pattern — played or not — gains three
     * empty PCM tracks as long as its others, keeping lengths, effects, orders, parked orders and tuning. The sound's new
     * fields (FM 3 mode, PCM samples, voice offsets) take their defaults: normal, none, zero.
     */
    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject {
        if (backendVersion >= 2) return song
        val patterns = song["patterns"] as? JsonArray ?: return song
        return JsonObject(song + ("patterns" to JsonArray(patterns.map { p ->
            val tracks = (p as? JsonObject)?.get("tracks") as? JsonArray
            if (tracks == null || tracks.size != YmRules.TONAL_CHANNELS) p
            else {
                val rows = (tracks.firstOrNull() as? JsonArray)?.size ?: 0
                val blank = JsonArray(listOf(JsonPrimitive(Cell.EMPTY), JsonPrimitive(0), JsonPrimitive(0), JsonPrimitive(0)))
                JsonObject(p + ("tracks" to JsonArray(tracks + List(YmRules.PCM_CHANNELS) { JsonArray(List(rows) { blank }) })))
            }
        })))
    }

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

    /** Why [instrument] cannot play on [channel] (FM voices on FM 1-6, PSG sounds on PSG 1-3 and Noise, PCM kits on PCM 1-3), or null. */
    fun kindProblem(channel: Int, slot: Int, instrument: YmInstrument): String? {
        val wants = when {
            YmRules.isFm(channel) -> "FM 1-6 play FM instruments"
            YmRules.isPcm(channel) -> "${YmRules.CHANNEL_NAMES[channel]} plays PCM kits"
            else -> "${YmRules.CHANNEL_NAMES[channel]} plays PSG instruments"
        }
        val fits = when (instrument) {
            is YmFmInstrument -> YmRules.isFm(channel)
            is YmPsgInstrument -> YmRules.isPsg(channel)
            is YmPcmInstrument -> YmRules.isPcm(channel)
        }
        return if (fits) null else "instrument ${NumberText.hex(slot, 2)} \"${instrument.name}\" is ${kindName(instrument)}; $wants"
    }

    /** "an FM voice", "a PSG sound", "a PCM kit". */
    fun kindName(instrument: YmInstrument): String = when (instrument) {
        is YmFmInstrument -> "an FM voice"
        is YmPsgInstrument -> "a PSG sound"
        is YmPcmInstrument -> "a PCM kit"
    }

    /** Why [note] on PCM kit [slot] plays nothing (no mapping, or a missing sample), or null. */
    fun pcmKeyProblem(song: Song, slot: Int, kit: YmPcmInstrument, note: Int): String? {
        val m = kit.pcm[note] ?: return "${noteName(note)} plays no PCM sample in kit ${NumberText.hex(slot, 2)} \"${kit.name}\"; " +
            "map it on the kit's PCM page or play a mapped key (${mappedKeys(kit)})"
        return if (m.sample !in song.ym.pcmSamples.indices) "${noteName(note)} of kit ${NumberText.hex(slot, 2)} maps missing PCM sample ${m.sample + 1}" else null
    }

    private fun mappedKeys(kit: YmPcmInstrument): String {
        val keys = kit.pcm.keys.sorted()
        if (keys.isEmpty()) return "it maps none yet"
        return keys.take(6).joinToString(" ") { noteName(it) } + if (keys.size > 6) " …" else ""
    }

    /** On PCM 1-3, the keys kit [instrument] maps (none for an empty slot or another kind); other channels play every note. */
    override fun mappedNotes(song: Song, channel: Int, instrument: Int): List<Int>? {
        if (!YmRules.isPcm(channel)) return null
        val kit = song.ym.instrument(instrument) as? YmPcmInstrument ?: return emptyList()
        // Views ask every frame; kits are immutable, so the last one's sorted keys are reused.
        val cached = mapped
        if (cached != null && cached.kit === kit) return cached.keys
        return kit.pcm.keys.sorted().also { mapped = MappedKeys(kit, it) }
    }

    private class MappedKeys(val kit: YmPcmInstrument, val keys: List<Int>)

    private var mapped: MappedKeys? = null

    /** On PCM 1-3, the source sample [note] plays with kit [instrument]: its name, pitch, rate and one-shot length. */
    override fun noteSample(song: Song, channel: Int, note: Int, instrument: Int): NoteSample? {
        if (!YmRules.isPcm(channel)) return null
        val m = song.ymPcmMapping(instrument, note) ?: return null
        val sample = song.ym.pcmSamples.getOrNull(m.sample) ?: return null
        return NoteSample(sample.name, ymPcmDetail(sample, m))
    }

    /** A PCM note is a one-shot sample that ends by itself ([YmPcmRows]); every other note sounds until the next note or OFF. */
    override fun soundingRows(song: Song, pattern: Int, channel: Int, row: Int): Int? =
        if (YmRules.isPcm(channel)) YmPcmRows.soundingRows(song, pattern, channel, row) else null

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    /** The first slot holding the bank's kind (an FM voice, a PSG sound or a PCM kit), else 1. */
    override fun defaultInstrument(song: Song, bank: Int): Int {
        val found = song.ym.instruments.indexOfFirst {
            when (bank) {
                PSG_BANK -> it is YmPsgInstrument
                PCM_BANK -> it is YmPcmInstrument
                else -> it is YmFmInstrument
            }
        }
        return if (found >= 0) found + 1 else 1
    }

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) YmInstrumentView else null

    override val songSettings: List<SongSetting> get() = YmSongSettings.ALL

    private object Engine : PlaybackEngine {
        override val id = YmPlayerImage.ENGINE_ID
        override val driver = "Thor Tracker's player through SGDK's XGM2 driver (commit 9a78a44) on z80.h (chips 9e88298), ymfm (commit 81aec25) and Thor Tracker's SN76489"
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
                    ?: (ins as? YmPcmInstrument)?.let { kit ->
                        pcmKeyProblem(song, instrument, kit, note) ?: YmPcmLowering.problem(song.ym)
                    }?.replaceFirstChar { it.uppercase() }
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
        override val off = "Key off: FM notes fade with their release, PSG sounds play their volume envelope's release, PCM samples stop"
        override val offEntered = "OFF: key off (the release plays; PCM stops)"
        override val offInspector = "OFF: key off (release phase; PCM stops)"
        override val effectsIntro = listOf(
            "Played by Thor Tracker's own player through SGDK's XGM2 driver on a Z80, YM2612 and PSG: one tick per vertical " +
                "interrupt (60 Hz NTSC, 50 Hz PAL).",
            "Up to four effect columns per channel, run in column order. Locks (E, I, M, N) shape the note started on their " +
                "row only; volume, pan, slides, vibrato and the LFO act from their row on.",
            "FM 1-6 play FM instruments, PSG 1-3 and Noise play PSG instruments, PCM 1-3 play PCM kits. The Noise channel's " +
                "pitched mode takes PSG 3's period, so PSG 3 is silent while it sounds.",
            "PCM 1-3 are XGM2's three one-shot sample channels mixed into the YM2612's DAC, which replaces FM 6 while it is on: " +
                "FM 6 is silent then and its pan pans all PCM. A sample cannot change once it plays: PCM lanes take notes, OFF, " +
                "Gxx, Sxx and the song's Bxx, Dxx, Fxx and Lxx only.",
            "FM 3 special mode (Song view) plays each operator of FM 3's voice at the note plus its own offset (the voice's " +
                "Ch3 page). No chords: the YM2612 has no spare voices; spread a chord over channels. Drums are instruments " +
                "with a pitch sweep, or PCM kits.",
        )
        override val tonalChannels = "FM 1-6 and PSG 1-3"
        override val tonalChannelsShort = "FM/PSG"
        override val untonalNote = "Noise and PCM 1-3 keep every pitch (Noise only uses it in pitched mode; a PCM key picks its mapped sound)."
        override val sketchChannels = "FM 1-6 or PSG 1-3; Noise and PCM have no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = YmInstrumentView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = YmValidator.limits(song)
    }
}

/** The Song view's Mega Drive rows: the region, the chip's LFO at the song start and FM 3's mode. */
object YmSongSettings {
    private fun with(song: Song, f: (YmSound) -> YmSound): Song = f(song.ym).let { if (it == song.ym) song else song.copy(sound = it) }

    /** LFO choices in stepping order: off, then rates 0-7 (register values 0, 8..15). */
    private val LFO_STEPS = listOf(0) + (0x08..0x0F)

    val ALL: List<SongSetting> = listOf(
        SongSetting("Region", "NTSC or PAL", "Set region",
            value = { s ->
                if (s.ym.pal) "PAL   50 Hz ticks (Europe); XGM2 exports carry the PAL flag"
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
        SongSetting("FM 3 mode", "Normal or special (operator pitches)", "Set FM 3 mode",
            value = { s ->
                when (s.ym.fm3Mode) {
                    YmFm3Mode.NORMAL -> "Normal   FM 3 plays like the other FM channels"
                    YmFm3Mode.SPECIAL -> "Special   FM 3's four operators each sound the note plus their voice's Ch3 offset"
                }
            },
            adjust = { s, f, c ->
                if ((f + c) % 2 == 0) s
                else with(s) { it.copy(fm3Mode = if (it.fm3Mode == YmFm3Mode.NORMAL) YmFm3Mode.SPECIAL else YmFm3Mode.NORMAL) }
            },
            reset = { s -> with(s) { it.copy(fm3Mode = YmFm3Mode.NORMAL) } },
        ),
    )
}
