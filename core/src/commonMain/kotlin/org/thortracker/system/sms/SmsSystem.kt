package org.thortracker.system.sms

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.SmsImageLayout
import org.thortracker.backend.SmsLowering
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
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView

/**
 * The Sega Master System and Game Gear with the Banjo sound driver (joffb/banjo 7aaf837, MIT), one system per
 * [SmsLayout]: the SN76489 alone, on a Game Gear with stereo, or with the FM Sound Unit's YM2413 (nine melodic
 * channels, or six and its five rhythm drums). Songs are Banjo song data played by the real driver on a Z80 (native
 * `"sms-banjo"`) and export as Banjo sources for WLA-DX and SDAS (devkitSMS, GBDK) and as VGM. Up to four effect
 * columns, one bank of [SLOTS] instruments shown as one bank per kind the layout plays (PSG sounds, FM voices, drum
 * kits). See docs/BACKENDS.md ("Master System") and docs/AUDIO_ENGINE.md ("Master System").
 */
class SmsSystem private constructor(val layout: SmsLayout) : SoundSystem {
    override val id = layout.id
    override val name = layout.displayName
    override val family: String = "Sega 8-bit"

    override val channels: List<ChannelSpec> = layout.channelNames.mapIndexed { ch, n ->
        when (layout.kind(ch)) {
            SmsChannelKind.SQUARE -> ChannelSpec(n, ChannelKind.PULSE, layout.bank(ch), tonal = true)
            SmsChannelKind.NOISE -> ChannelSpec(n, ChannelKind.NOISE, layout.bank(ch), tonal = false)
            SmsChannelKind.FM -> ChannelSpec(n, ChannelKind.VOICE, layout.bank(ch), tonal = true)
            SmsChannelKind.DRUM -> ChannelSpec(n, ChannelKind.NOISE, layout.bank(ch), tonal = false)
        }
    }

    /** One bank per instrument kind over the one numbering of [SLOTS] slots (the PSG-only layouts have one, "Sounds"). */
    override val banks: List<BankSpec> =
        if (!layout.fm) listOf(BankSpec("Sounds", SLOTS)) else layout.kinds.map { BankSpec(it.bankName, SLOTS) }

    /** A-2..B-8 on the PSG layouts (the SN76489's lowest note in Banjo is A-2); C-0..B-8 with the FM unit. */
    override val noteRange: IntRange = if (layout.fm) 0..SmsRules.HIGHEST else SmsRules.PSG_LOWEST..SmsRules.HIGHEST

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> = SmsRules.effects(layout)
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports = listOf(
        ExportFormat("asm") { SmsExport.export(it, SmsExport.Format.WLA_DX) },
        ExportFormat("s") { SmsExport.export(it, SmsExport.Format.SDAS) },
        ExportFormat("vgm") { SmsExport.export(it, SmsExport.Format.VGM) },
    )
    override val fine: FinePitch = NoFine
    override val help: SystemHelp = Help(layout)

    /** OFF is a note-column event (note off or envelope release). */
    override val offEffect: String? = null

    /** One tick per NTSC frame: 3579545 / (228 × 262) = 59.92 Hz (Master System and Game Gear alike). */
    override val tickHz = SmsImageLayout.TICK_HZ
    override val maxOrders = SmsImageLayout.MAX_ORDERS

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = SmsSound.blank(layout),
    )

    override val soundSerializer: KSerializer<SmsSound> = SmsSoundSerializer(layout)

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun demo(): Song = SmsDemoSong.build(layout)

    override fun effectsFor(channel: Int): List<EffectSpec> = SmsRules.effectsFor(layout, channel)

    override fun effectColumns(channel: Int): Int = SmsRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = SmsRules.describe(layout, channel, effect, param)

    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? =
        SmsRules.detail(song, pattern, channel, row, column)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = SmsValidator.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song) = SmsValidator.structuralErrors(this, song)
    override fun errors(song: Song) = SmsValidator.errors(this, song)
    override fun warnings(song: Song) = SmsValidator.warnings(this, song)

    /**
     * Parked orders removed; the loop order and every `Bxx` renumbered to the played orders. A target that is parked
     * lands on the next played order after it (the loop order past the last).
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

    /** `Bxx` jumps to order xx (B00 = the first order); the first `B` column of the cell. */
    override fun jumpTarget(cell: Cell): Int? {
        for (c in 0..cell.more.size) if (cell.effectCode(c) == SmsRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == SmsRules.FX_JUMP) out = out.withEffect(c, SmsRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine(this)

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank !in banks.indices || slot !in 1..SLOTS) null
        else song.sms.instrument(slot)?.name?.ifEmpty { "Instrument ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? =
        if (bank !in banks.indices || slot !in 1..SLOTS) null else song.sms.instrument(slot)?.let(SmsPresets::summary) ?: "Empty: choose a preset"

    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    /** The first slot holding the bank's kind, else 1. */
    override fun defaultInstrument(song: Song, bank: Int): Int {
        val kind = layout.kinds.getOrNull(bank) ?: return 1
        val found = song.sms.instruments.indexOfFirst { it?.kind == kind }
        return if (found >= 0) found + 1 else 1
    }

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) SmsInstrumentView else null

    /** Why [instrument] in [slot] cannot play on [channel] (each channel plays its own kind), or null. */
    fun kindProblem(channel: Int, slot: Int, instrument: SmsInstrument): String? {
        val wanted = layout.instrumentKind(channel)
        if (instrument.kind == wanted) return null
        val what = when (instrument.kind) {
            SmsInstrumentKind.PSG -> "a PSG sound"
            SmsInstrumentKind.FM -> "an FM voice"
            SmsInstrumentKind.KIT -> "a drum kit"
        }
        val plays = when (wanted) {
            SmsInstrumentKind.PSG -> "PSG sounds"
            SmsInstrumentKind.FM -> "FM voices"
            SmsInstrumentKind.KIT -> "drum kits"
        }
        return "instrument ${NumberText.hex(slot, 2)} \"${instrument.name}\" is $what; ${layout.channelNames[channel]} plays $plays"
    }

    private class Engine(private val system: SmsSystem) : PlaybackEngine {
        override val id = SmsImageLayout.ENGINE_ID
        override val driver = "Banjo (7aaf837) on z80.h (chips 9e88298), Thor Tracker's SN76489; FM: emu2413 1.6.4 (a1d5120)"
        override val imageSize = SmsImageLayout.IMAGE_SIZE
        override val seekTicks = SmsImageLayout.SEEK_TICKS

        override fun lower(song: Song, out: ByteArray) = SmsLowering.lower(song, out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val ins = if (instrument in 1..SLOTS) song.sms.instrument(instrument) else null
            return when {
                instrument !in 1..SLOTS -> "Choose an instrument (01-${NumberText.hex(SLOTS, 2)}) to audition"
                ins == null -> "Instrument ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in system.channels.indices || note !in system.noteRange -> "Cannot audition note ${system.noteName(note)} on channel $channel"
                fine != 0 -> "Banjo has no fine pitch"
                system.layout.kind(channel).let { it == SmsChannelKind.FM || it == SmsChannelKind.DRUM } && note > SmsInstruments.FM_TOP ->
                    "The YM2413 plays up to B-7"
                else -> system.kindProblem(channel, instrument, ins)?.replaceFirstChar { it.uppercase() }
            }
        }

        /** Banjo's note byte: SN channels count from A-2 = 9 (absolute − 24), the YM2413 from C-0. */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = SmsRules.driverNote(system.layout, channel, note)

        override fun fine(channel: Int, note: Int, fine: Int): Int = 0
    }

    /** Banjo plays the twelve notes of its tables only (no fine-pitch command): a tuning is realised at its nearest 12-TET notes. */
    private object NoFine : FinePitch {
        override val name = "no fine pitch (Banjo)"
        override val max = 0
        override fun range(note: Int): IntRange = 0..0
        override fun cents(note: Int, fine: Int): Double = 0.0
        override fun of(cell: Cell): Int = 0
        override fun write(cell: Cell, note: Int, fine: Int): Cell? = if (fine == 0) cell.copy(note = note) else null
    }

    private class Help(private val layout: SmsLayout) : SystemHelp {
        override val off = "Note off: a sound whose volume envelope has a release part plays it, others stop (FM: key off, the voice's release)"
        override val offEntered = "OFF: note off (or the volume envelope's release)"
        override val offInspector = "OFF: note off / release"
        override val effectsIntro = listOfNotNull(
            "Played by the Banjo sound driver on a Z80, as in a game: one tick per frame (59.92 Hz). Up to four effect columns per channel.",
            "Arpeggio, slides, portamento and vibrato share one slot per channel: the last on a row wins and stops the others.",
            "Speed (F, O) changes take effect from the next row; G and S must stay within their row.",
            if (layout.fm) "FM channels play FM voices: the YM2413's 15 built-in instruments or its one custom voice, shared by all channels." else null,
            if (layout.drums) "Rhythm channels play the YM2413's drums: notes set their pitch (snare with hi-hat, tom with cymbal share one), or a fixed kit." else null,
            "No fine pitch: tunings sound at their nearest notes.",
        )
        override val tonalChannels = if (layout.fm) "Square 1-3 and the FM channels" else "Square 1-3"
        override val tonalChannelsShort = if (layout.fm) "Square/FM" else "Square"
        override val untonalNote = if (layout.drums) "Noise and the drums keep every pitch." else "Noise keeps every pitch."
        override val sketchChannels = if (layout.fm) "Square 1-3 or an FM channel; Noise and the drums have no tonal chord reference"
            else "Square 1-3; Noise has no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = SmsInstrumentView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = SmsValidator.limits(song)
    }

    companion object {
        /** Instrument slots (Banjo numbers up to 255 instruments). */
        const val SLOTS = 64

        /** What [instrumentName] shows for an empty slot. */
        const val EMPTY_NAME = "(empty)"

        val PSG = SmsSystem(SmsLayout.PSG)
        val GG = SmsSystem(SmsLayout.GG)
        val FM = SmsSystem(SmsLayout.FM)
        val FM_DRUMS = SmsSystem(SmsLayout.FM_DRUMS)

        val ALL: List<SmsSystem> = listOf(PSG, GG, FM, FM_DRUMS)

        fun of(layout: SmsLayout): SmsSystem = ALL[layout.ordinal]
    }
}
