package org.thortracker.system.nes

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.thortracker.backend.FamiStudioLowering
import org.thortracker.backend.FamiStudioText
import org.thortracker.backend.NesDpcm
import org.thortracker.backend.FsSong
import org.thortracker.backend.NesImageLayout
import org.thortracker.backend.NsfExporter
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
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView
import kotlin.math.log2

/**
 * The NES 2A03 played by the FamiStudio sound engine 4.5.3 (FamiTracker tempo) on an emulated 6502 + 2A03: Pulse 1,
 * Pulse 2, Triangle, Noise and DPCM, one bank of [SLOTS] envelope instruments shared by all five channels (on DPCM an
 * instrument is its note → sample mappings) and the song's DPCM samples.
 * Lowering ([FamiStudioLowering]) and both exports (`.nsf`, FamiStudio text) read the same [FsSong].
 * See docs/BACKENDS.md and docs/AUDIO_ENGINE.md.
 */
object NesSystem : SoundSystem {
    override val id = "nes-famistudio"
    override val name = "NES (FamiStudio)"

    /** FamiStudio's instrument limit without the extended range. */
    const val SLOTS = 64

    override val channels = NesRules.CHANNEL_NAMES.indices.map { ch ->
        ChannelSpec(NesRules.CHANNEL_NAMES[ch], NesRules.CHANNEL_KINDS[ch], 0, tonal = NesRules.pitched(ch))
    }
    override val banks = listOf(BankSpec("Instruments", SLOTS))
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
    override val help: SystemHelp = Help

    /** OFF is a note event of its own (release or stop); it needs no effect slot. */
    override val offEffect: String? = null

    /** The engine runs once per NTSC frame: 1789773 / 29780.5 Hz. */
    override val tickHz = 60.0988
    override val maxOrders = NesRules.MAX_ORDERS

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = NesSound.blank(),
    )

    override val soundSerializer: KSerializer<NesSound> = NesSound.serializer()

    /** Version 8 NES songs already counted in scientific pitch (FamiTracker names: A-4 = 440 Hz). */
    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 8) song
        else throw IllegalArgumentException("$name songs need project version 8 or later; version $version files are Game Boy songs")

    /** 2: the DPCM channel, samples and DPCM mappings. */
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

    override fun demo(): Song = NesDemoSong.build()

    override fun effectsFor(channel: Int): List<EffectSpec> = NesRules.effectsFor(channel)
    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int) = NesRules.describeEffect(channel, effect, param)
    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? =
        NesRules.cellRangeProblem(cell) ?: NesRules.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song) = NesValidator.structuralErrors(song)
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

    override val engine: PlaybackEngine = Engine

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? = instrument(song, bank, slot)?.name

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? = instrument(song, bank, slot)?.let(::summary)

    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null

    /** Instrument [slot] (1..[SLOTS]) of the one bank, or null. */
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

    override fun noteSample(song: Song, channel: Int, note: Int, instrument: Int): NoteSample? {
        if (channel != NesRules.DPCM) return null
        val m = song.dpcmMapping(instrument, note) ?: return null
        val sample = song.nes.samples.getOrNull(m.sample) ?: return null
        return NoteSample(sample.name, dpcmDetail(m))
    }

    /** "vol 15 9 5 2 0 · arp 0 4 7 loop · duty 50%" — the envelopes in use. */
    fun summary(ins: NesInstrument): String {
        fun env(name: String, e: NesEnvelope, neutral: Int, text: (Int) -> String = Int::toString): String? {
            if (e.values.isEmpty() || e.values.all { it == neutral }) return null
            val shown = e.values.take(6).joinToString(" ", transform = text) + if (e.values.size > 6) " …" else ""
            val marks = listOfNotNull("loop".takeIf { e.loop >= 0 }, "rel".takeIf { e.release >= 0 })
            return "$name $shown" + if (marks.isEmpty()) "" else " (${marks.joinToString()})"
        }
        val duties = listOf("12.5%", "25%", "50%", "75%")
        val parts = listOfNotNull(
            env("vol", ins.volume, 15),
            env("arp", ins.arpeggio, 0),
            env(if (ins.pitchRelative) "pitch+" else "pitch", ins.pitch, 0),
            env("duty", ins.duty, 0) { duties.getOrElse(it) { "?" } },
            ins.dpcm.size.takeIf { it > 0 }?.let { "DPCM $it note${if (it == 1) "" else "s"}" },
        )
        return if (parts.isEmpty()) "Full volume, no envelopes" else parts.joinToString(" · ")
    }

    /**
     * FamiStudio 4.5.3 on a 6502 + 2A03 (native `"nes-famistudio"`, app/src/main/cpp/nes): the song lowered as for
     * `.nsf` export ([FamiStudioLowering]) in the engine's image ([NesImageLayout]), plus every DPCM mapping the song does
     * not play that still fits, so the editor can audition it.
     */
    private object Engine : PlaybackEngine {
        override val id = "nes-famistudio"
        override val imageSize = NesImageLayout.SIZE

        /** At 60.1 frames per second, ~3.3 minutes of song. */
        override val seekTicks = 12000

        override fun lower(song: Song, out: ByteArray) = NesImageLayout.build(FamiStudioLowering.lower(song, withUnplayed = true), out)

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? = when {
            instrument !in 1..SLOTS -> "Choose an instrument (01-${NumberText.hex(SLOTS, 2)}) to audition"
            channel !in channels.indices || note !in noteRange -> "Cannot audition note ${noteName(note)} on channel $channel"
            NesRules.pitched(channel) && note < NesRules.LOWEST_TONAL ->
                "${channels[channel].name} cannot play below A-1"
            fine !in 0..Fine.max -> "Cannot audition fine pitch $fine (0-${Fine.max})"
            channel == NesRules.DPCM -> NesRules.dpcmMappingProblem(song, instrument, note)?.replaceFirstChar { it.uppercase() }
                ?: if (dpcmEntry(song, note, instrument) == null) {
                    "Sample memory or the sample list is full: ${noteName(note)} of instrument ${NesRules.hex2(instrument)} cannot be auditioned"
                } else null
            else -> null
        }

        /** FamiStudio's note numbers: 1 = C-1; on DPCM the sample list entry (1..63). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int =
            if (channel == NesRules.DPCM) dpcmEntry(song, note, instrument) ?: 0 else NesRules.fsNote(note)

        private fun dpcmEntry(song: Song, note: Int, instrument: Int): Int? =
            NesDpcm.layout(FsSong.of(song), withUnplayed = true).index[instrument to note]

        /** The engine's fine pitch adds to the period, so raising the pitch is negative. */
        override fun fine(note: Int, fine: Int): Int = -fine
    }

    /**
     * Tuned pitches are a note-table note plus `Pxx` (xx = 80 + fine): the engine subtracts fine from the timer period
     * for the note's duration. Pulse and triangle pitch is proportional to 1 / (period + 1).
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

    private object Help : SystemHelp {
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
            add("Instruments" to "${used.size} of $SLOTS used")
            add("Samples" to "${song.nes.samples.size} taking ${NesSampleMenu.stored(song)} of ${Dpcm.MEMORY} bytes of sample memory (\$C000-\$FFF9); " +
                "the export places the ${Dpcm.memoryUsed(song)} bytes notes play")
            if (NesValidator.errors(song).isEmpty()) {
                val bytes = FamiStudioLowering.lower(FsSong.of(song)).data.size
                val room = org.thortracker.backend.NesPlayerImage.SONG_END - org.thortracker.backend.NesPlayerImage.SONG_BASE
                add("Song data" to "$bytes of $room bytes")
            }
        }
    }
}
