package org.thortracker.system.nes

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.thortracker.backend.FamiStudioEnvelopes
import org.thortracker.backend.FamiStudioLowering
import org.thortracker.backend.FamiStudioText
import org.thortracker.backend.FsSong
import org.thortracker.backend.NesImageLayout
import org.thortracker.backend.NsfExporter
import org.thortracker.backend.player
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.input.Keymap
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.BankSpec
import org.thortracker.system.ChannelSpec
import org.thortracker.system.EffectSpec
import org.thortracker.system.ExportFormat
import org.thortracker.system.FinePitch
import org.thortracker.system.NoteSample
import org.thortracker.system.PlaybackEngine
import org.thortracker.system.SharedState
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView
import kotlin.math.log2

/**
 * The NES 2A03 played by the FamiStudio sound engine 4.5.3 (FamiTracker tempo) on an emulated 6502 + 2A03: Pulse 1,
 * Pulse 2, Triangle, Noise and DPCM, one bank of [SLOTS] envelope instruments shared by all five channels (on DPCM an
 * instrument is its note → sample mappings) and the song's DPCM samples.
 * Lowering ([FamiStudioLowering]) and both exports (`.nsf`, FamiStudio text) read the same [FsSong].
 * See docs/BACKENDS.md and docs/AUDIO_ENGINE.md. [NesVrc6System] adds the Konami VRC6's three channels, [NesVrc7System]
 * the Konami VRC7's six FM channels, [NesS5bSystem] the Sunsoft 5B's three squares.
 */
object NesSystem : NesFamiStudioSystem(NesExpansion.NONE) {
    override val id = "nes-famistudio"
    override val name = "NES (FamiStudio)"

    /** FamiStudio's instrument limit without the extended range. */
    const val SLOTS = 64

    override fun demo(): Song = NesDemoSong.build()
}

/**
 * The NES with the Konami VRC6 cartridge chip (FamiStudio's VRC6 expansion): the 2A03's five channels, then VRC6
 * Pulse 1 and 2 (eight duties, 4-bit volume, 12-bit periods) and the VRC6 Saw (a 6-bit accumulator rate: the volume
 * shifted by the instrument's saw master volume). The FamiStudio engine assembled with `FAMISTUDIO_EXP_VRC6` plays
 * it (native `"nes-famistudio-vrc6"`); VRC6 channels take VRC6 instruments ([NesChip.VRC6]) from the same bank.
 */
object NesVrc6System : NesFamiStudioSystem(NesExpansion.VRC6) {
    override val id = "nes-famistudio-vrc6"
    override val name = "NES + VRC6 (FamiStudio)"

    override fun demo(): Song = NesVrc6DemoSong.build()
}

/**
 * The NES with the Konami VRC7 cartridge chip (FamiStudio's VRC7 expansion; a YM2413 derivative): the 2A03's five
 * channels, then VRC7 FM 1-6, six 2-operator FM channels playing one of the chip's 15 ROM patches or its one custom
 * patch ([Vrc7Patch]) with a 4-bit volume. The FamiStudio engine assembled with `FAMISTUDIO_EXP_VRC7` plays it on
 * emu2413 in VRC7 mode (native `"nes-famistudio-vrc7"`); FM channels take VRC7 instruments ([NesChip.VRC7]).
 */
object NesVrc7System : NesFamiStudioSystem(NesExpansion.VRC7) {
    override val id = "nes-famistudio-vrc7"
    override val name = "NES + VRC7 (FamiStudio)"

    override fun demo(): Song = NesVrc7DemoSong.build()
}

/**
 * The NES with the Sunsoft 5B cartridge chip (FamiStudio's S5B expansion; a YM2149F): the 2A03's five channels, then
 * 5B Square 1-3, three square tones with a 4-bit logarithmic volume (12-bit periods: every note), sharing one noise
 * generator (a mixer envelope per sound picks tone, noise, both or neither) and one envelope generator (a sound's
 * hardware envelope shape, its period following the note or manual, `Ixx`/`Jyy`). The FamiStudio engine assembled with
 * `FAMISTUDIO_EXP_S5B` plays it (native `"nes-famistudio-s5b"`); the squares take 5B instruments ([NesChip.S5B]).
 * Who owns the shared generators at a position is the Pattern view's shared state ([NesS5b.state]).
 */
object NesS5bSystem : NesFamiStudioSystem(NesExpansion.S5B) {
    override val id = "nes-famistudio-s5b"
    override val name = "NES + Sunsoft 5B (FamiStudio)"

    override fun demo(): Song = NesS5bDemoSong.build()
}

/**
 * What every NES variant shares: the FamiStudio sound engine on the 2A03 plus at most one [expansion] chip (one
 * system per expansion: FamiStudio builds its engine for one, and a system's channels are fixed). Everything is
 * driven by the song's own [NesSound.expansion], so shared NES code may call [NesSystem] for any NES song.
 */
abstract class NesFamiStudioSystem internal constructor(val expansion: NesExpansion) : SoundSystem {
    override val family = "NES"

    override val channels = (0 until expansion.channels).map { ch ->
        ChannelSpec(NesRules.channelName(expansion, ch), NesRules.kind(expansion, ch), 0, tonal = NesRules.pitched(expansion, ch))
    }
    override val banks = listOf(BankSpec("Instruments", NesSystem.SLOTS))
    override val noteRange = (1 + NesRules.NOTE_OFFSET)..(NesRules.NOTE_COUNT + NesRules.NOTE_OFFSET)

    /** FamiTracker/FamiStudio names are scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0
    override val effects: List<EffectSpec> get() = NesRules.EFFECTS
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports = listOf(
        ExportFormat("nsf") { NsfExporter.export(it) },
        ExportFormat("txt") { FamiStudioText.export(it) },
    )
    override val fine: FinePitch = Fine
    override val help: SystemHelp = when (expansion) {
        NesExpansion.NONE -> Help
        NesExpansion.VRC6 -> Vrc6Help
        NesExpansion.VRC7 -> Vrc7Help
        NesExpansion.S5B -> S5bHelp
    }

    /** OFF is a note event of its own (release or stop); it needs no effect slot. */
    override val offEffect: String? = null

    /** The engine runs once per NTSC frame: 1789773 / 29780.5 Hz. */
    override val tickHz = 60.0988
    override val maxOrders = NesRules.MAX_ORDERS

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = NesSound.blank(expansion),
    )

    override val soundSerializer: KSerializer<NesSound> = NesSound.serializer()

    /** Version 8 NES songs already counted in scientific pitch (FamiTracker names: A-4 = 440 Hz). */
    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 8) song
        else throw IllegalArgumentException("$name songs need project version 8 or later; version $version files are Game Boy songs")

    /** 2: the DPCM channel, samples and DPCM mappings. Every variant shares the NES data version. */
    override val backendVersion = 2

    /** NES data version 1 had four channels: every pattern gains an empty DPCM track as long as its others. */
    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject {
        val patterns = song["patterns"] as? JsonArray ?: return song
        return JsonObject(song + ("patterns" to JsonArray(patterns.map { p ->
            val tracks = (p as? JsonObject)?.get("tracks") as? JsonArray
            if (tracks == null || tracks.size != NesRules.DPCM) p
            else {
                val rows = (tracks.firstOrNull() as? JsonArray)?.size ?: 0
                val blank = JsonArray(listOf(JsonPrimitive(Cell.EMPTY), JsonPrimitive(0), JsonPrimitive(0), JsonPrimitive(0)))
                JsonObject(p + ("tracks" to JsonArray(tracks + listOf(JsonArray(List(rows) { blank })))))
            }
        })))
    }

    override fun effectsFor(channel: Int): List<EffectSpec> = NesRules.effectsFor(expansion, channel)
    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int) = NesRules.describeEffect(expansion, channel, effect, param)
    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? =
        NesRules.cellRangeProblem(cell) ?: NesRules.cellProblem(song, channel, cell)

    /** Plus: the song's expansion chip must be this variant's (a project's backend names its variant). */
    override fun structuralErrors(song: Song): List<String> {
        val sound = song.sound as? NesSound ?: return NesValidator.structuralErrors(song)
        if (sound.expansion != expansion) return listOf("The song is for ${sound.expansion.system.name}, not $name")
        return NesValidator.structuralErrors(song)
    }
    override fun errors(song: Song) = NesValidator.errors(song)
    override fun warnings(song: Song) = NesValidator.warnings(song)

    /**
     * The song as played and exported: parked orders removed and the loop order renumbered (a parked loop target
     * lands on the next played order, or the first one when none follows). The NES has no jump effect to renumber.
     */
    override fun arranged(song: Song): Song {
        if (song.parkedOrders.isEmpty()) return song
        val played = Songs.playedOrders(song)
        if (played.isEmpty()) return song
        val loop = played.indexOfFirst { it >= song.loopOrder }.let { if (it >= 0) it else 0 }
        return song.copy(orders = played.map { song.orders[it] }, loopOrder = loop, parkedOrders = emptyList())
    }

    override fun patternLoop(song: Song, order: Int): Song =
        song.copy(orders = listOf(song.orders[order.coerceIn(0, song.orders.size - 1)]), loopOrder = 0, parkedOrders = emptyList())

    /** FamiStudio has no jump effect: the song's loop order is its only jump. */
    override fun jumpTarget(cell: Cell): Int? = null
    override fun withJumpTarget(cell: Cell, order: Int): Cell = cell

    override val engine: PlaybackEngine = Engine()

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? = instrument(song, bank, slot)?.name

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? = instrument(song, bank, slot)?.let(::summary)

    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null

    /** Instrument [slot] (1..[NesSystem.SLOTS]) of the one bank, or null. */
    fun instrument(song: Song, bank: Int, slot: Int): NesInstrument? = if (bank != 0) null else song.nes.instruments.getOrNull(slot - 1)

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) NesInstrumentView else null

    /** On DPCM, the keys instrument [instrument] maps to samples; other channels play every note. */
    override fun mappedNotes(song: Song, channel: Int, instrument: Int): List<Int>? {
        if (channel != NesRules.DPCM) return null
        val ins = instrument(song, 0, instrument) ?: return emptyList()
        // Views ask every frame; instruments are immutable, so the last one's sorted keys are reused.
        if (ins !== mappedFor) { mappedFor = ins; mappedKeys = ins.dpcm.keys.sorted() }
        return mappedKeys
    }

    private var mappedFor: NesInstrument? = null
    private var mappedKeys: List<Int> = emptyList()

    /** A one-shot DPCM sample ends by itself ([dpcmSoundingRows]); every other note sounds until the next note or OFF. */
    override fun soundingRows(song: Song, pattern: Int, channel: Int, row: Int): Int? =
        if (channel == NesRules.DPCM) dpcmSoundingRows(song, pattern, row) else null

    override fun noteSample(song: Song, channel: Int, note: Int, instrument: Int): NoteSample? {
        if (channel != NesRules.DPCM) return null
        val m = song.dpcmMapping(instrument, note) ?: return null
        val sample = song.nes.samples.getOrNull(m.sample) ?: return null
        return NoteSample(sample.name, dpcmDetail(m))
    }

    /** The 5B's shared envelope and noise generators: which square drives them (5B songs only). */
    override fun sharedState(song: Song, order: Int, row: Int): SharedState? = NesS5b.state(song, order, row)

    /** "vol 15 9 5 2 0 · arp 0 4 7 loop · duty 50%" — the envelopes in use (VRC6/VRC7/5B sounds: "VRC6 · …", saw volume, patch, mixer). */
    fun summary(ins: NesInstrument): String {
        fun env(name: String, e: NesEnvelope, neutral: Int, text: (Int) -> String = Int::toString): String? {
            if (e.values.isEmpty() || e.values.all { it == neutral }) return null
            val shown = e.values.take(6).joinToString(" ", transform = text) + if (e.values.size > 6) " …" else ""
            val marks = listOfNotNull("loop".takeIf { e.loop >= 0 }, "rel".takeIf { e.release >= 0 })
            return "$name $shown" + if (marks.isEmpty()) "" else " (${marks.joinToString()})"
        }
        val vrc6 = ins.chip == NesChip.VRC6
        val vrc7 = ins.chip == NesChip.VRC7
        val s5b = ins.chip == NesChip.S5B
        val parts = listOfNotNull(
            ins.chip.label.takeIf { vrc6 || vrc7 || s5b },
            Vrc7Presets.NAMES[ins.fm.preset.coerceIn(Vrc7Presets.NAMES.indices)].lowercase().takeIf { vrc7 },
            env("vol", ins.volume, 15),
            env("arp", ins.arpeggio, 0),
            env(if (ins.pitchRelative) "pitch+" else "pitch", ins.pitch, 0),
            env("duty", ins.duty, 0) { NesRules.dutyText(ins.chip, it) },
            env("mixer", ins.mixer, FamiStudioEnvelopes.MIXER_TONE, NesS5b::mixerText).takeIf { s5b },
            env("noise", ins.noise, 0).takeIf { s5b },
            NesS5b.summary(ins.s5b).takeIf { s5b && ins.s5b.shape != S5bShape.OFF },
            "saw ${ins.sawVolume.label.lowercase()}".takeIf { vrc6 && ins.sawVolume != Vrc6SawVolume.HALF },
            "sustain on release".takeIf { vrc7 && ins.fm.sustainRelease },
            "sustain on stop".takeIf { vrc7 && ins.fm.sustainStop },
            ins.dpcm.size.takeIf { it > 0 }?.let { "DPCM $it note${if (it == 1) "" else "s"}" },
        )
        return when {
            parts.isEmpty() -> "Full volume, no envelopes"
            parts.size == 1 && (vrc6 || s5b) -> "${ins.chip.label} · full volume, no envelopes"
            else -> parts.joinToString(" · ")
        }
    }

    /**
     * FamiStudio 4.5.3 on a 6502 + 2A03 (+ the expansion chip) — native `"nes-famistudio"` or the expansion build's
     * engine (app/src/main/cpp/nes): the song lowered as for `.nsf` export ([FamiStudioLowering]) in the engine's image
     * ([NesImageLayout]), plus every DPCM mapping the song does not play that still fits, so the editor can audition it.
     */
    private inner class Engine : PlaybackEngine {
        override val id = expansion.player.engineId
        override val driver = "FamiStudio sound engine 4.5.3 (FamiTracker tempo) on m6502.h (chips 9e88298) and Thor Tracker's 2A03 APU" +
            (if (expansion == NesExpansion.S5B) " and Sunsoft 5B (YM2149F, after NESdev and emu2149)" else "") + ", blip_buf 1.1.0"
        override val imageSize = expansion.player.imageSize

        /** At 60.1 frames per second, ~3.3 minutes of song. */
        override val seekTicks = 12000

        override fun lower(song: Song, out: ByteArray) = NesImageLayout.build(FamiStudioLowering.lower(song, withUnplayed = true), out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? = when {
            instrument !in 1..NesSystem.SLOTS -> "Choose an instrument (01-${NumberText.hex(NesSystem.SLOTS, 2)}) to audition"
            channel !in channels.indices || note !in noteRange -> "Cannot audition note ${noteName(note)} on channel $channel"
            NesRules.pitched(expansion, channel) && note < NesRules.lowestNote(expansion, channel) ->
                "${channels[channel].name} cannot play below A-1"
            fine !in 0..Fine.max -> "Cannot audition fine pitch $fine (0-${Fine.max})"
            channel == NesRules.DPCM -> NesRules.dpcmMappingProblem(song, instrument, note)?.replaceFirstChar { it.uppercase() }
                ?: if (dpcmEntry(song, note, instrument) == null) {
                    "Sample memory or the sample list is full: ${noteName(note)} of instrument ${NesRules.hex2(instrument)} cannot be auditioned"
                } else null
            song.nes.instruments[instrument - 1].chip != NesRules.chip(expansion, channel) ->
                "Sound ${NesRules.hex2(instrument)} is a ${song.nes.instruments[instrument - 1].chip.label} sound; " +
                    "${channels[channel].name} plays ${NesRules.chip(expansion, channel).label} sounds"
            else -> null
        }

        /** FamiStudio's note numbers: 1 = C-1; on DPCM the sample list entry (1..63). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int =
            if (channel == NesRules.DPCM) dpcmEntry(song, note, instrument) ?: 0 else NesRules.fsNote(note)

        private fun dpcmEntry(song: Song, note: Int, instrument: Int): Int? =
            FamiStudioLowering.lower(FsSong.of(song), withUnplayed = true).dpcm.index[instrument to note]

        /**
         * The engine's fine pitch adds to the period on the 2A03 and VRC6, so raising the pitch is negative; on the
         * VRC7's FM channels it adds (× 8) to the frequency number, converted as a cell's Pxx is ([NesRules.vrc7FinePitch]).
         */
        override fun fine(channel: Int, note: Int, fine: Int): Int =
            if (NesRules.isVrc7(expansion, channel)) NesRules.vrc7FinePitch(NesRules.fsNote(note), fine) else -fine
    }

    /**
     * Tuned pitches are a note-table note plus `Pxx` (xx = 80 + fine): the engine subtracts fine from the timer period
     * for the note's duration. Pulse and triangle pitch is proportional to 1 / (period + 1). The VRC6 saw's periods are
     * 8/7 of the pulse table's, so the same fine moves it 7/8 as far (tunings use the pulse table's arithmetic). The VRC7's
     * FM channels convert a Pxx to their nearest frequency step for the same pitch ([NesRules.vrc7FinePitch]): one step
     * is 0.3 cents at B-8, 80 at C-1, so tuned FM notes are as exact as the chip allows there. The 5B squares play the
     * pulse table's period + 1 (an octave lower), so the pulse arithmetic holds for them unchanged.
     */
    private object Fine : FinePitch {
        override val name = "Pxx"
        override val max = 0x7F

        /** Periods below 8 silence the pulses; keep tuned notes above that. */
        override fun range(note: Int): IntRange = 0..minOf(max, maxOf(0, period(note) - 8))

        override fun cents(note: Int, fine: Int): Double {
            val p = period(note) + 1.0
            return 1200.0 * log2(p / (p - fine))
        }

        override fun of(cell: Cell): Int? = when {
            !cell.hasEffect -> 0
            cell.effect == NesRules.FX_FINE_PITCH && cell.param >= NesRules.FINE_CENTRE -> cell.param - NesRules.FINE_CENTRE
            else -> null
        }

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = of(cell)
            return when {
                fine > 0 -> if (current == null) null else cell.copy(note = note, effect = NesRules.FX_FINE_PITCH, param = NesRules.FINE_CENTRE + fine)
                current != null && cell.effect == NesRules.FX_FINE_PITCH -> cell.copy(note = note, effect = 0, param = 0)
                else -> cell.copy(note = note)
            }
        }

        private fun period(note: Int) = FamiStudioLowering.NOTE_TABLE[NesRules.fsNote(note).coerceIn(1, NesRules.NOTE_COUNT)]
    }

    private open class NesHelp : SystemHelp {
        override val off = "Releases the note: an instrument whose volume envelope has a release point plays its release " +
            "tail, any other note (or one already released) stops at once. For a cut inside a row use Sxx"
        override val offEntered = "OFF: release (instruments with a volume release point fade out; others stop at once)"
        override val offInspector = "OFF: release the note (tail if its instrument has a release point, else silent)"
        override val effectsIntro = listOf(
            "Played by the FamiStudio sound engine at FamiTracker tempo: a tick is one 60 Hz frame, Fxx sets frames per row.",
            "There are no jump effects: the song's loop order and each pattern's length arrange the song.",
            "Instruments are envelopes (volume, arpeggio, pitch, duty); effects change the playing note on top of them.",
            "On DPCM a note plays the sample its instrument maps to that key (the instrument's DPCM page); only Fxx, Gxx, Sxx and Zxx apply there.",
        )
        override val tonalChannels = "Pulse and Triangle"
        override val tonalChannelsShort = "Pulse/Tri"
        override val untonalNote = "Noise keeps every pitch (16 noise rates, repeating every 16 semitones); DPCM notes pick samples."
        override val sketchChannels = "Pulse 1, Pulse 2 or Triangle; Noise and DPCM have no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = NesInstrumentView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = buildList {
            val used = HashSet<Int>()
            for (p in song.orders.toSet()) for (track in song.patterns[p].tracks) for (cell in track) if (cell.instrument > 0) used += cell.instrument
            add("Instruments" to "${used.size} of ${NesSystem.SLOTS} used")
            add("Samples" to "${song.nes.samples.size} taking ${NesSampleMenu.stored(song)} of ${Dpcm.MEMORY} bytes of sample memory (\$C000-\$FFF9); " +
                "the export places the ${Dpcm.memoryUsed(song)} bytes notes play")
            if (NesValidator.errors(song).isEmpty()) {
                val lowered = FamiStudioLowering.lower(FsSong.of(song))
                // Song data and DPCM samples share the region after the player ($8C00-$FFF9 on the plain 2A03 build).
                add("Song data" to "${lowered.data.size} + samples ${lowered.samples.size} of ${song.nes.expansion.player.regionSize} bytes")
            }
        }
    }

    private object Help : NesHelp()

    private object Vrc6Help : NesHelp() {
        override val effectsIntro = super.effectsIntro + listOf(
            "VRC6 Pulse 1/2 and the VRC6 Saw play VRC6 sounds (Chip: VRC6 on the Instrument view); the other channels play 2A03 sounds. " +
                "Vxx on the VRC6 pulses takes eight duties, 00 = 1/16 to 07 = 8/16; the saw has no duty.",
        )
        override val tonalChannels = "Pulse, Triangle, VRC6 Pulse and VRC6 Saw"
        override val tonalChannelsShort = "Pulse/Tri/VRC6"
        override val sketchChannels = "Pulse 1, Pulse 2, Triangle, VRC6 Pulse 1/2 or VRC6 Saw; Noise and DPCM have no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> =
            NesInstrumentView.helpSections(keys, NesExpansion.VRC6)
    }

    private object Vrc7Help : NesHelp() {
        override val off = "Releases the note: on the VRC7 FM channels a key off (the FM patch's release, or the slow sustain " +
            "fade with Release: sustain) plus the volume envelope's release; on the 2A03 as there. For a cut inside a row use Sxx"
        override val offEntered = "OFF: release (FM: key off; 2A03 sounds with a volume release point fade out, others stop)"
        override val offInspector = "OFF: release the note (FM: key off and its release; 2A03: tail or silence)"
        override val effectsIntro = super.effectsIntro + listOf(
            "VRC7 FM 1-6 play VRC7 sounds (Chip: VRC7 on the Instrument view): one of the chip's 15 built-in patches or the custom " +
                "patch, shaped by the volume, arpeggio and pitch envelopes. The chip holds one custom patch for all six channels: " +
                "two different custom sounds playing at once share whichever loaded last (the check warns).",
            "On the FM channels Vxx has no effect (no duty), and Pxx detunes by the pitch the same Pxx gives a pulse, to the chip's " +
                "nearest frequency step.",
        )
        override val tonalChannels = "Pulse, Triangle and VRC7 FM"
        override val tonalChannelsShort = "Pulse/Tri/FM"
        override val sketchChannels = "Pulse 1, Pulse 2, Triangle or VRC7 FM 1-6; Noise and DPCM have no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> =
            NesInstrumentView.helpSections(keys, NesExpansion.VRC7)
    }

    private object S5bHelp : NesHelp() {
        override val effectsIntro = super.effectsIntro + listOf(
            "5B Square 1-3 play 5B sounds (Chip: 5B on the Instrument view): square tones that sound an octave below a 2A03 " +
                "pulse on the same note (as the triangle does: FamiStudio plays the 5B from the pulses' note table), with a mixer " +
                "envelope (tone, noise, both, neither), a noise envelope and an optional hardware envelope. No Vxx (no duty).",
            "The three squares share one noise generator and one envelope generator: the highest square sounding a noise or " +
                "envelope sound sets them for all (the lane headers tag who does: ENV, NOISE; the check warns when two sounds fight).",
            "Ixx / Jyy (5B squares): the shared envelope period's high / low byte, together on one row (on two squares) for xxyy, " +
                "alone xx00 / 00yy. It drives sounds with a manual period until a note reloads its sound (a new sound, a note with " +
                "attack after it, or an order's first note).",
        )
        override val tonalChannels = "Pulse, Triangle and 5B Square"
        override val tonalChannelsShort = "Pulse/Tri/5B"
        override val sketchChannels = "Pulse 1, Pulse 2, Triangle or 5B Square 1-3; Noise and DPCM have no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> =
            NesInstrumentView.helpSections(keys, NesExpansion.S5B)
    }
}
