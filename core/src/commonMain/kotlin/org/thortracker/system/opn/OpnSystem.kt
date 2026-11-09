package org.thortracker.system.opn

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.OpnLowering
import org.thortracker.backend.OpnPlayerImage
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
import org.thortracker.system.fm.RhythmRomMenu
import org.thortracker.system.fm.Ym2608RhythmRom
import org.thortracker.system.ym2612.YmCurated

/**
 * The PC-88 / PC-98's YM2203 (OPN): three FM channels and the SSG (three squares sharing one noise and one envelope
 * generator), played by Thor Tracker's own player on ymfm (native `"ym2203-opn"`, exported as VGM). See
 * [OpnFamilySystem].
 */
object OpnSystem : OpnFamilySystem(OpnChip.YM2203) {
    override val id = OpnPlayerImage.ENGINE_YM2203
    override val name = "PC-88/98 OPN (YM2203)"

    override fun demo(): Song = OpnDemoSong.opn()
}

/**
 * The PC-88 / PC-98's YM2608 (OPNA): six stereo FM channels with the LFO, the SSG, the rhythm unit's six drums from
 * the YM2608 rhythm ROM the user imports ([Ym2608RhythmRom]) and the ADPCM-B channel playing the song's own samples
 * from 256 KiB of memory (native `"ym2608-opna"`, exported as VGM). See [OpnFamilySystem].
 */
object OpnaSystem : OpnFamilySystem(OpnChip.YM2608) {
    override val id = OpnPlayerImage.ENGINE_YM2608
    override val name = "PC-88/98 OPNA (YM2608)"

    override fun demo(): Song = OpnDemoSong.opna()

    /** The rhythm drums play the YM2608 rhythm ROM the user imports (the main menu offers the import). */
    override val rhythmRom: Boolean get() = true
}

/** The system of an OPN-family chip. */
fun OpnChip.system(): OpnFamilySystem = if (this == OpnChip.YM2608) OpnaSystem else OpnSystem

/**
 * What the YM2203 and YM2608 systems share: Thor Tracker's own OPN player (`opn/opn_player.c`) on ymfm's chip at its
 * PC-88 / PC-98 clock, ticked by timer B ([OpnSound.timerB]), up to four effect columns on every channel
 * ([OpnRules]), absolute notes in FamiTracker-style names. One list of [OpnSound.SLOTS] sounds holds FM voices, SSG
 * sounds and (YM2608) rhythm and ADPCM-B sounds; the banks are views of it by part, and a channel plays sounds of its
 * own part only. Songs export as VGM ([OpnVgm]); the YM2608's rhythm drums need the user's rhythm ROM to play, never
 * to export. See docs/BACKENDS.md ("OPN and OPNA") and docs/AUDIO_ENGINE.md ("OPN and OPNA").
 */
abstract class OpnFamilySystem(val chip: OpnChip) : SoundSystem {
    override val family = "PC-88/98"

    override val channels: List<ChannelSpec> = chip.channelNames.mapIndexed { ch, name ->
        when (val part = chip.part(ch)) {
            OpnPart.FM -> ChannelSpec(name, ChannelKind.VOICE, part.ordinal, tonal = true)
            OpnPart.SSG -> ChannelSpec(name, ChannelKind.PULSE, part.ordinal, tonal = true)
            OpnPart.RHYTHM -> ChannelSpec(name, ChannelKind.SAMPLE, part.ordinal, tonal = false)
            OpnPart.ADPCM -> ChannelSpec(name, ChannelKind.SAMPLE, part.ordinal, tonal = true)
        }
    }

    /**
     * One bank per part (bank index = [OpnPart.ordinal]), all naming the same [OpnSound.SLOTS] slots: they exist so the
     * editor keeps a separate current sound per part ([defaultInstrument]).
     */
    override val banks: List<BankSpec> = chip.parts.map { BankSpec(BANK_NAMES.getValue(it), OpnSound.SLOTS) }

    override val noteRange: IntRange = OpnRules.NOTES

    /** FamiTracker-style names in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override val effects: List<EffectSpec> get() = OpnRules.EFFECTS.filter { chip.opna || it.code !in OpnRules.OPNA_ONLY }
    override val views: Set<ViewKind> = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports: List<ExportFormat> = listOf(ExportFormat("vgm") { OpnVgm.export(it) })
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help()

    /** OFF is a note-column event (key off). */
    override val offEffect: String? = null

    /** The default timer B (198): 59.77 ticks per second. */
    override val tickHz: Double = OpnRules.tickHz(OpnPlayerImage.TIMER_B_DEFAULT)

    override fun ticksPerSecond(song: Song): Double = (song.sound as? OpnSound)?.let { OpnRules.tickHz(it.timerB) } ?: tickHz

    override val maxOrders = OpnPlayerImage.MAX_ORDERS

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = OpnPresets.blank(chip),
    )

    override val soundSerializer: KSerializer<OpnSound> = OpnSound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun effectsFor(channel: Int): List<EffectSpec> = OpnRules.effectsFor(chip, channel)

    override fun effectColumns(channel: Int): Int = OpnRules.EFFECT_COLUMNS

    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String = OpnRules.describe(chip, channel, effect, param)

    /** Arpeggios read with the notes they step through; locks on rows without a note say they are ignored ([OpnEffectText]). */
    override fun effectDetail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? =
        OpnEffectText.detail(song, pattern, channel, row, column)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = OpnValidator.cellProblem(song, channel, cell)

    /** Includes the check that the song's chip data ([OpnSound.chip]) is this system's chip. */
    override fun structuralErrors(song: Song) = OpnValidator.structuralErrors(song, chip)
    override fun errors(song: Song) = OpnValidator.errors(song, chip)
    override fun warnings(song: Song) = OpnValidator.warnings(song, chip)

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
        for (c in 0..cell.more.size) if (cell.effectCode(c) == OpnRules.FX_JUMP) return cell.effectParam(c)
        return null
    }

    /** Every `B` column of [cell] aimed at [order]. */
    override fun withJumpTarget(cell: Cell, order: Int): Cell {
        var out = cell
        for (c in 0..cell.more.size) if (cell.effectCode(c) == OpnRules.FX_JUMP) out = out.withEffect(c, OpnRules.FX_JUMP, order)
        return out
    }

    override val engine: PlaybackEngine = Engine()

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank !in banks.indices || slot !in 1..OpnSound.SLOTS) null
        else song.opn.instrument(slot)?.name?.ifEmpty { "Sound ${NumberText.hex(slot, 2)}" } ?: EMPTY_NAME

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? {
        if (bank !in banks.indices || slot !in 1..OpnSound.SLOTS) return null
        val sound = song.opn
        return when (val ins = sound.instrument(slot)) {
            null -> "Empty: choose a preset"
            is OpnFmSound -> YmCurated.summary(ins.asYm()) +
                (if (ins.ch3.any { it != 0 }) " · FM 3 operator pitches" else "") +
                (if (!chip.opna && (ins.ams != 0 || ins.fms != 0 || ins.operators.any { it.am })) " · uses the LFO (YM2608 only)" else "")
            is OpnSsgSound -> listOfNotNull(
                "SSG",
                if (ins.volume.values.isEmpty()) "vol 15" else "vol ${ins.volume.values.size} step${if (ins.volume.values.size == 1) "" else "s"}" +
                    (if (ins.volume.loop >= 0) " loop" else ""),
                if (ins.arpeggio.values.isNotEmpty()) "arp" else null,
                if (ins.pitch.values.isNotEmpty()) "pitch" else null,
                if (ins.mixer.values.isNotEmpty()) "mixer" else null,
                if (ins.noise.values.isNotEmpty()) "noise" else null,
                if (ins.envelope.shape != org.thortracker.system.nes.S5bShape.OFF) "env ${ins.envelope.shape.label.lowercase()}" else null,
                if (ins.sweep.active) "sweep ${OpnRules.signed(ins.sweep.start)}→${OpnRules.signed(ins.sweep.end)}" else null,
            ).joinToString(" · ")
            is OpnRhythmSound -> "Rhythm · level ${ins.level} of 31 · ${panText(ins.left, ins.right)}"
            is OpnAdpcmSound -> {
                val sample = sound.samples.getOrNull(ins.sample)
                listOfNotNull(
                    "ADPCM",
                    sample?.let { "\"${it.name}\" ${it.rate} Hz" } ?: "sample ${ins.sample} missing",
                    "root ${noteName(ins.root)}",
                    "level ${ins.level}",
                    panText(ins.left, ins.right),
                    if (ins.loop) "loop" else null,
                ).joinToString(" · ")
            }
        }
    }

    private fun panText(left: Boolean, right: Boolean) = when {
        left && right -> "centre"
        left -> "left"
        right -> "right"
        else -> "silent"
    }

    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    /** The first slot holding the bank's part (bank = [OpnPart.ordinal]), else 1. */
    override fun defaultInstrument(song: Song, bank: Int): Int {
        val found = song.opn.instruments.indexOfFirst { it != null && it.part.ordinal == bank }
        return if (found >= 0) found + 1 else 1
    }

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) OpnInstrumentView else null

    /**
     * What a note plays on a rhythm channel (its drum, whatever the pitch) or on the ADPCM channel (the sound's sample,
     * at its rate on the root and transposed from it); null on FM and SSG channels.
     */
    override fun noteSample(song: Song, channel: Int, note: Int, instrument: Int): NoteSample? {
        if (channel !in 0 until chip.channelCount) return null
        return when (chip.part(channel)) {
            OpnPart.RHYTHM -> NoteSample(OpnChip.RHYTHM_NAMES[channel - chip.rhythmFirst], RHYTHM_DETAIL)
            OpnPart.ADPCM -> {
                val ins = song.opn.instrument(instrument) as? OpnAdpcmSound ?: return null
                val sample = song.opn.samples.getOrNull(ins.sample) ?: return null
                val hz = (ins.sampleRateAt(sample, note) + 0.5).toInt()
                NoteSample(sample.name, "${sample.rate} Hz on ${noteName(ins.root)}; ${noteName(note)} plays it at $hz Hz")
            }
            else -> null
        }
    }

    override val songSettings: List<SongSetting> get() = OpnSongSettings.forChip(chip)

    /** The rate a note plays [sample] at with this sound: the chip's delta-N step, in Hz. */
    private fun OpnAdpcmSound.sampleRateAt(sample: OpnaSample, note: Int): Double =
        OpnRules.deltaN(sample.rate, root, note) * OpnPlayerImage.NATIVE_RATE / 65536.0

    private inner class Engine : PlaybackEngine {
        override val id: String get() = this@OpnFamilySystem.id
        override val driver = "Thor Tracker's OPN player on ymfm (${if (chip.opna) "YM2608" else "YM2203"}), ymfm commit 81aec25"
        override val imageSize = chip.imageSize

        /** At 60 ticks per second, ~3.3 minutes of song. */
        override val seekTicks = OpnPlayerImage.SEEK_TICKS

        /**
         * [OpnLowering]; on the YM2608 a song whose played orders hold a rhythm note is refused while no rhythm ROM is
         * installed ([Ym2608RhythmRom.MISSING]): the drums cannot sound without it, and nothing substitutes them.
         */
        override fun lower(song: Song, out: ByteArray) {
            require(!chip.opna || Ym2608RhythmRom.installed || !OpnValidator.playsRhythm(song)) { Ym2608RhythmRom.MISSING }
            OpnLowering.lower(song, out)
        }

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            val sound = song.opn
            val ins = if (instrument in 1..OpnSound.SLOTS) sound.instrument(instrument) else null
            return when {
                instrument !in 1..OpnSound.SLOTS -> "Choose a sound (01-${NumberText.hex(OpnSound.SLOTS, 2)}) to audition"
                ins == null -> "Sound ${NumberText.hex(instrument, 2)} is empty; choose a preset for it"
                channel !in channels.indices || note !in noteRange -> "Cannot audition note ${noteName(note)} on channel $channel"
                fine !in -128..127 -> "Fine pitch $fine is outside -128..127"
                else -> OpnValidator.kindProblem(chip, channel, instrument, ins)?.replaceFirstChar { it.uppercase() }
                    ?: OpnValidator.instrumentProblem(chip, ins)?.let { "Sound ${NumberText.hex(instrument, 2)}: $it" }
                    ?: (if (note < OpnRules.lowest(chip, channel)) "${noteName(note)} is below the SSG's lowest note, ${noteName(OpnRules.SSG_LOWEST)}" else null)
                    ?: (if (ins is OpnAdpcmSound && ins.sample >= sound.samples.size) "Sound ${NumberText.hex(instrument, 2)} plays sample ${ins.sample}, which the song does not have" else null)
                    ?: (if (chip.part(channel) == OpnPart.RHYTHM && !Ym2608RhythmRom.installed) Ym2608RhythmRom.MISSING else null)
            }
        }

        /** The player takes absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        /** The system's fine pitch is already the player's unit, 1/64 semitone. */
        override fun fine(channel: Int, note: Int, fine: Int): Int = fine
    }

    /**
     * Tunings ride on `Pxx` (xx − 80 sixty-fourths of a semitone, until the next note) in any free effect column on FM,
     * SSG and ADPCM channels: the player's pitch unit is 1/64 semitone (1.56 cents), so every degree is realised within
     * 0.8 cents of the request, plus the chips' own rounding (FM F-numbers, the SSG's 12-bit periods, delta-N).
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
            return if (p >= OpnRules.FINE_CENTRE) p - OpnRules.FINE_CENTRE else null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = column(cell)
            val base = cell.copy(note = note)
            if (fine == 0) {
                return if (current != null && cell.effectParam(current) >= OpnRules.FINE_CENTRE) base.withEffect(current, 0, 0) else base
            }
            if (current != null && cell.effectParam(current) < OpnRules.FINE_CENTRE) return null
            val col = current ?: (0 until OpnRules.EFFECT_COLUMNS).firstOrNull { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 } ?: return null
            return base.withEffect(col, OpnRules.FX_FINE, OpnRules.FINE_CENTRE + fine)
        }

        private fun column(cell: Cell): Int? = (0..cell.more.size).firstOrNull { cell.effectCode(it) == OpnRules.FX_FINE }
    }

    private inner class Help : SystemHelp {
        override val off = if (chip.opna) {
            "Key off: FM notes fade with their release, SSG sounds play their volume envelope's release, rhythm drums are " +
                "damped and ADPCM samples stop"
        } else "Key off: FM notes fade with their release, SSG sounds play their volume envelope's release"
        override val offEntered = "OFF: key off (the release plays)"
        override val offInspector = "OFF: key off (release phase)"
        override val effectsIntro: List<String> = buildList {
            add("Played by Thor Tracker's own player on ymfm's ${chip.label} at the PC-88/98 clock " +
                "(${NumberText.fixed(chip.clock / 1_000_000.0, 4)} MHz; one FM sample per 55466.67 Hz on both chips). One tick per " +
                "timer B period: 16 × (256 − TB) samples, ${NumberText.fixed(OpnRules.tickHz(OpnPlayerImage.TIMER_B_DEFAULT), 2)} Hz " +
                "at the default TB ${OpnPlayerImage.TIMER_B_DEFAULT} (the Song view's tick rate: TB ${OpnPlayerImage.TIMER_B_MIN}-${OpnPlayerImage.TIMER_B_MAX}).")
            add("Up to four effect columns per channel, run in column order. Locks (E, I, M on FM; H, J on SSG) shape the note " +
                "started on their row only; volume, slides, vibrato and the global effects act from their row on.")
            add("Each part plays its own sounds: FM channels FM voices, SSG channels SSG sounds" +
                (if (chip.opna) ", the rhythm channels rhythm sounds and ADPCM the sample sounds." else ".") +
                " The three SSG channels share one noise generator (Nxx, noise envelopes) and one hardware envelope generator " +
                "(the highest SSG channel sounding a note with a shape sets its period).")
            add("FM 3 has three modes (Song view): normal; special, where each operator plays the note plus its own pitch " +
                "offset (the voice's FM 3 operator pitches: chords and detuned stacks from one channel); and CSM, where timer A " +
                "(Txx, the Song view's CSM rate) keys FM 3's operators over and over while its note holds.")
            if (chip.opna) {
                add("The six rhythm channels each play their own drum from the YM2608 rhythm ROM: any note triggers it at the " +
                    "sound's level and pan. The ROM is Yamaha's and not included: import your copy once with " +
                    "\"${RhythmRomMenu.LABEL}\" (main menu). FM, SSG and ADPCM play without it, and VGM export never needs it.")
                add("ADPCM-B plays the song's own samples (imported .wav files or generated starters) from 256 KiB of memory " +
                    "(at most ${OpnSound.MAX_SAMPLES} samples, each rounded up to 32 bytes; at 16 kHz about 32 seconds in all). " +
                    "Notes transpose from the sound's root note.")
            }
            add("No chords: the chips have no spare voices; spread a chord over channels (or use FM 3's special mode). Drums " +
                "on FM and SSG are sounds with a pitch sweep: the note picks the drum's pitch.")
        }
        override val tonalChannels = if (chip.opna) "FM, SSG and ADPCM" else "FM and SSG"
        override val tonalChannelsShort = if (chip.opna) "FM/SSG/ADPCM" else "FM/SSG"
        override val untonalNote = if (chip.opna) "Rhythm drums keep every note (any note plays the drum)." else "Every channel is tonal."
        override val sketchChannels = if (chip.opna) "FM, SSG or ADPCM; the rhythm drums have no tonal chord reference" else "FM or SSG"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = OpnInstrumentView.helpSections(keys, chip)

        override fun limits(song: Song): List<Pair<String, String>> = OpnValidator.limits(song)
    }

    companion object {
        /** What [instrumentName] shows for an empty slot. */
        const val EMPTY_NAME = "(empty)"

        private val BANK_NAMES = mapOf(
            OpnPart.FM to "FM voices",
            OpnPart.SSG to "SSG sounds",
            OpnPart.RHYTHM to "Rhythm sounds",
            OpnPart.ADPCM to "ADPCM sounds",
        )

        private const val RHYTHM_DETAIL = "rhythm ROM drum: every note plays it"
    }
}
