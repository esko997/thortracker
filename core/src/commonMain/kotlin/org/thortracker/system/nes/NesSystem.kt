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
import org.thortracker.backend.NesPcmLowering
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
import org.thortracker.system.SongSetting
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
 * the Konami VRC7's six FM channels, [NesN163System] the Namco 163's 1-8 wavetable channels, [NesS5bSystem] the
 * Sunsoft 5B's three squares, [NesFdsSystem] the FDS's wavetable channel, [NesMmc5System] the Nintendo MMC5's two pulses,
 * [NesEpsmSystem] the EPSM's SSG, FM and rhythm channels.
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
 * The Famicom with the Famicom Disk System's sound (the 2C33 in the RAM adapter; FamiStudio's FDS expansion): the 2A03's
 * five channels, then FDS, one channel playing 64-step waves of 6-bit samples ([FdsSound]) at a 6-bit volume, with a
 * frequency modulation unit (a 32-entry table stepped at the mod speed, scaled by the mod depth, optionally following
 * the pitch: auto-mod) and a master volume. The FamiStudio engine assembled with `FAMISTUDIO_EXP_FDS` and
 * `FAMISTUDIO_USE_FDS_AUTOMOD` plays it (native `"nes-famistudio-fds"`); the FDS channel takes FDS instruments
 * ([NesChip.FDS]).
 */
object NesFdsSystem : NesFamiStudioSystem(NesExpansion.FDS) {
    override val id = "nes-famistudio-fds"
    override val name = "NES + FDS (FamiStudio)"

    override fun demo(): Song = NesFdsDemoSong.build()
}

/**
 * The NES with the Namco 163 cartridge chip (FamiStudio's N163 expansion) with [n163Channels] (1..8) wavetable channels
 * enabled: the 2A03's five channels, then N163 1..n, each playing 4-bit waves from the chip's 128 bytes of RAM
 * ([N163Wave]) at a 4-bit linear volume. The chip updates one channel every 15 CPU cycles in turn, so the count is fixed
 * per song (FamiStudio's `NumN163Channels`): more channels lower each channel's update rate (the note table and its top
 * change), leave less RAM for waves and, at 7-8 channels, put the switching whine in the audible range. One system per
 * count, grouped as "NES + N163" under the NES family; the FamiStudio engine assembled with `FAMISTUDIO_EXP_N163` and
 * `FAMISTUDIO_EXP_N163_CHN_CNT` = n plays it (native `"nes-famistudio-n163-<n>"`).
 */
class NesN163System private constructor(expansion: NesExpansion) : NesFamiStudioSystem(expansion) {
    val n163Channels: Int = expansion.n163Channels
    override val id = "nes-famistudio-n163-$n163Channels"
    override val name = "NES + N163, $n163Channels channel${if (n163Channels == 1) "" else "s"} (FamiStudio)"
    override val familyGroup = "NES + N163"

    override fun demo(): Song = NesN163DemoSong.build(n163Channels)

    companion object {
        /** The eight variants, 1..8 N163 channels. */
        val ALL: List<NesN163System> = (1..NesRules.N163_MAX_CHANNELS).map { NesN163System(NesExpansion.n163(it)) }

        /** The variant with [channels] N163 channels. */
        fun of(channels: Int): NesN163System = ALL[channels - 1]
    }
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
 * The NES with the Nintendo MMC5 cartridge chip (FamiStudio's MMC5 expansion): the 2A03's five channels, then MMC5
 * Pulse 1 and 2, two more 2A03-style pulses (four duties, 4-bit volume, 11-bit periods, no sweep unit), and the MMC5 PCM
 * lane on the chip's 8-bit DAC. The pulses play 2A03 sounds ([NesChip.APU]), so the bank has one chip; on the PCM lane a
 * 2A03 sound plays the PCM samples it maps ([NesInstrument.pcm], the PCM page). The FamiStudio engine assembled with
 * `FAMISTUDIO_EXP_MMC5` plays the seven FamiStudio channels (native `"nes-famistudio-mmc5"`); FamiStudio 4.5.3's engine
 * does not drive the DAC, so the PCM lane is Thor Tracker's own software player writing it on the emulated CPU's
 * timebase ([Pcm]): heard in playback, auditions and WAV export, refused by the `.nsf` and FamiStudio text exports.
 */
object NesMmc5System : NesFamiStudioSystem(NesExpansion.MMC5) {
    override val id = "nes-famistudio-mmc5"
    override val name = "NES + MMC5 (FamiStudio)"

    override fun demo(): Song = NesMmc5DemoSong.build()

    /** 3: the MMC5 PCM lane (an eighth track), PCM samples and instruments' PCM mappings. */
    override val backendVersion = 3

    /** NES + MMC5 data version 2 had seven lanes: every pattern gains an empty PCM lane as long as its others. */
    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject {
        val older = super.upgradeBackend(song, backendVersion)
        if (backendVersion >= 3) return older
        val patterns = older["patterns"] as? JsonArray ?: return older
        return JsonObject(older + ("patterns" to JsonArray(patterns.map { p ->
            val tracks = (p as? JsonObject)?.get("tracks") as? JsonArray
            if (tracks == null || tracks.size != NesRules.MMC5_PCM) p
            else {
                val rows = (tracks.firstOrNull() as? JsonArray)?.size ?: 0
                val blank = JsonArray(listOf(JsonPrimitive(Cell.EMPTY), JsonPrimitive(0), JsonPrimitive(0), JsonPrimitive(0)))
                JsonObject(p + ("tracks" to JsonArray(tracks + listOf(JsonArray(List(rows) { blank })))))
            }
        })))
    }
}

/**
 * The NES with the EPSM (Perkka's Expansion Port Sound Module: a Yamaha YMF288, OPN3-L, at 8 MHz; FamiStudio's EPSM
 * expansion): the 2A03's five channels, then SSG 1-3 (YM2149-compatible squares sharing one noise and one envelope
 * generator, played as the 5B's: mixer and noise envelopes, a hardware envelope with auto or manual period, `Ixx`/`Jyy`),
 * FM 1-6 (4-operator OPN voices, [EpsmPatch], sharing the chip's one LFO: the song's [NesSound.epsmLfo]) and the rhythm
 * unit's six fixed drums (Kick, Snare, Cymbal, Hi-hat, Tom, Rim: a note triggers the drum at the sound's volume and pan;
 * the YM2608 rhythm ROM the user imports plays them, [EpsmRhythmRom]). The FamiStudio engine assembled with
 * `FAMISTUDIO_EXP_EPSM` (and Thor Tracker's EPSM engine patch) plays it on ymfm's YMF288 (native
 * `"nes-famistudio-epsm"`); every EPSM channel takes EPSM sounds ([NesChip.EPSM]).
 */
object NesEpsmSystem : NesFamiStudioSystem(NesExpansion.EPSM) {
    override val id = "nes-famistudio-epsm"
    override val name = "NES + EPSM (FamiStudio)"

    override fun demo(): Song = NesEpsmDemoSong.build()
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
    override val effects: List<EffectSpec> get() = NesRules.effects(expansion)
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
        NesExpansion.FDS -> FdsHelp
        NesExpansion.S5B -> S5bHelp
        NesExpansion.MMC5 -> Mmc5Help
        NesExpansion.EPSM -> EpsmHelp
        else -> N163Help(expansion.n163Channels)
    }

    /** The EPSM's one LFO is a song setting (Song view). */
    override val songSettings: List<SongSetting> get() = if (expansion == NesExpansion.EPSM) NesEpsmSongSettings.ALL else emptyList()

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
        NesRules.cellRangeProblem(expansion, cell) ?: NesRules.cellProblem(song, channel, cell)

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

    /** An N163 sound's first wave (0..15 as 0..1) or an FDS sound's (0..63), for thumbnails; other chips have no waveform. */
    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = instrument(song, bank, slot)?.let { ins ->
        when (ins.chip) {
            NesChip.N163 -> ins.wave.waves.firstOrNull()?.map { it / 15f }
            NesChip.FDS -> ins.fds.waves.firstOrNull()?.map { it / 63f }
            else -> null
        }
    }

    /** Instrument [slot] (1..[NesSystem.SLOTS]) of the one bank, or null. */
    fun instrument(song: Song, bank: Int, slot: Int): NesInstrument? = if (bank != 0) null else song.nes.instruments.getOrNull(slot - 1)

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) NesInstrumentView else null

    /** On DPCM and the MMC5 PCM lane, the keys instrument [instrument] maps to samples; other channels play every note. */
    override fun mappedNotes(song: Song, channel: Int, instrument: Int): List<Int>? {
        val pcm = NesRules.isMmc5Pcm(expansion, channel)
        if (channel != NesRules.DPCM && !pcm) return null
        val ins = instrument(song, 0, instrument) ?: return emptyList()
        // Views ask every frame; instruments are immutable, so the last one's sorted keys are reused.
        if (ins !== mappedFor || pcm != mappedPcm) {
            mappedFor = ins
            mappedPcm = pcm
            mappedKeys = (if (pcm) ins.pcm.keys else ins.dpcm.keys).sorted()
        }
        return mappedKeys
    }

    private var mappedFor: NesInstrument? = null
    private var mappedPcm = false
    private var mappedKeys: List<Int> = emptyList()

    /**
     * A one-shot DPCM sample ends by itself ([dpcmSoundingRows]), as does a one-shot PCM sample ([NesPcmRows]); every
     * other note sounds until the next note or OFF.
     */
    override fun soundingRows(song: Song, pattern: Int, channel: Int, row: Int): Int? = when {
        channel == NesRules.DPCM -> dpcmSoundingRows(song, pattern, row)
        NesRules.isMmc5Pcm(expansion, channel) -> NesPcmRows.soundingRows(song, pattern, row)
        else -> null
    }

    /**
     * On DPCM and the MMC5 PCM lane the sample the note plays; on an EPSM rhythm channel its fixed drum (every note
     * triggers the same ROM sample: the note's pitch is not heard).
     */
    override fun noteSample(song: Song, channel: Int, note: Int, instrument: Int): NoteSample? {
        if (NesRules.isEpsmRhythm(expansion, channel)) return NoteSample(NesRules.channelName(expansion, channel), RHYTHM_DETAIL)
        if (NesRules.isMmc5Pcm(expansion, channel)) {
            val m = song.pcmMapping(instrument, note) ?: return null
            val sample = song.nes.pcmSamples.getOrNull(m.sample) ?: return null
            return NoteSample(sample.name, pcmDetail(sample, m))
        }
        if (channel != NesRules.DPCM) return null
        val m = song.dpcmMapping(instrument, note) ?: return null
        val sample = song.nes.samples.getOrNull(m.sample) ?: return null
        return NoteSample(sample.name, dpcmDetail(m))
    }

    /** The 5B's or the EPSM SSG's shared envelope and noise generators: which square drives them (5B and EPSM songs only). */
    override fun sharedState(song: Song, order: Int, row: Int): SharedState? = NesS5b.state(song, order, row)

    /**
     * "vol 15 9 5 2 0 · arp 0 4 7 loop · duty 50%" — the envelopes in use (VRC6/VRC7/N163/FDS/5B/EPSM sounds: "VRC6 · …",
     * saw volume, patch, waves, mixer; FDS: "FDS · sine · mod 3F @ 040 · 66%"; EPSM: "EPSM · alg 4 · …", pan).
     */
    fun summary(ins: NesInstrument): String {
        fun env(name: String, e: NesEnvelope, neutral: Int, text: (Int) -> String = Int::toString): String? {
            if (e.values.isEmpty() || e.values.all { it == neutral }) return null
            val shown = e.values.take(6).joinToString(" ", transform = text) + if (e.values.size > 6) " …" else ""
            val marks = listOfNotNull("loop".takeIf { e.loop >= 0 }, "rel".takeIf { e.release >= 0 })
            return "$name $shown" + if (marks.isEmpty()) "" else " (${marks.joinToString()})"
        }
        val vrc6 = ins.chip == NesChip.VRC6
        val vrc7 = ins.chip == NesChip.VRC7
        val n163 = ins.chip == NesChip.N163
        val fds = ins.chip == NesChip.FDS
        val s5b = ins.chip == NesChip.S5B
        val epsm = ins.chip == NesChip.EPSM
        val ssg = s5b || epsm
        val parts = listOfNotNull(
            ins.chip.label.takeIf { vrc6 || vrc7 || n163 || fds || s5b || epsm },
            "alg ${ins.epsm.algorithm}".takeIf { epsm },
            Vrc7Presets.NAMES[ins.fm.preset.coerceIn(Vrc7Presets.NAMES.indices)].lowercase().takeIf { vrc7 },
            n163Summary(ins.wave).takeIf { n163 },
            fdsSummary(ins.fds).takeIf { fds },
            env("vol", ins.volume, 15),
            env("arp", ins.arpeggio, 0),
            env(if (ins.pitchRelative) "pitch+" else "pitch", ins.pitch, 0),
            env("duty", ins.duty, 0) { NesRules.dutyText(ins.chip, it) },
            env("mixer", ins.mixer, FamiStudioEnvelopes.MIXER_TONE, NesS5b::mixerText).takeIf { ssg },
            env("noise", ins.noise, 0).takeIf { ssg },
            NesS5b.summary(ins.s5b).takeIf { ssg && ins.s5b.shape != S5bShape.OFF },
            when {
                !epsm || (ins.epsm.left && ins.epsm.right) -> null
                ins.epsm.left -> "left"
                ins.epsm.right -> "right"
                else -> "muted"
            },
            "saw ${ins.sawVolume.label.lowercase()}".takeIf { vrc6 && ins.sawVolume != Vrc6SawVolume.HALF },
            ins.dpcm.size.takeIf { it > 0 }?.let { "DPCM $it note${if (it == 1) "" else "s"}" },
        )
        return when {
            parts.isEmpty() -> "Full volume, no envelopes"
            parts.size == 1 && (vrc6 || s5b) -> "${ins.chip.label} · full volume, no envelopes"
            else -> parts.joinToString(" · ")
        }
    }

    /** "sine 16 @ 0" or "3 waves of 32 @ 64 (loop, rel)": an N163 sound's waves, their size and RAM position. */
    private fun n163Summary(w: N163Wave): String {
        val preset = N163Presets.presetOf(w.waves, w.size)?.label?.lowercase()
        val what = if (w.waves.size == 1) (preset ?: "wave") + " ${w.size}" else "${w.waves.size} ${preset ?: "waves"} of ${w.size}"
        val marks = listOfNotNull("loop".takeIf { w.loop >= 0 }, "rel".takeIf { w.release >= 0 })
        return "$what @ ${w.position}" + if (marks.isEmpty()) "" else " (${marks.joinToString()})"
    }

    /**
     * "sine · mod 3F @ 040 · 66%", "3 waves (loop, rel) · mod 20 @ ×2/1 · delay 12": an FDS sound's waves, its modulation
     * (depth @ speed, or the auto-mod ratio; none at depth 0) with its delay, and a master volume below 100%.
     */
    private fun fdsSummary(f: FdsSound): String {
        val preset = FdsPresets.presetOf(f.waves)?.label?.lowercase()
        val what = if (f.waves.size == 1) preset ?: "wave" else "${f.waves.size} ${preset ?: "waves"}"
        val marks = listOfNotNull("loop".takeIf { f.loop >= 0 }, "rel".takeIf { f.release >= 0 })
        val waves = what + if (marks.isEmpty()) "" else " (${marks.joinToString()})"
        val mod = if (f.modDepth == 0) null else {
            val speed = if (f.autoMod) f.autoModFraction.let { (n, d) -> "×$n/$d" } else NumberText.hex(f.modSpeed, 3)
            "mod ${NesRules.hex2(f.modDepth)} @ $speed" + if (f.modDelay > 0) " · delay ${f.modDelay}" else ""
        }
        return listOfNotNull(waves, mod, f.masterVolume.label.takeIf { f.masterVolume != FdsMasterVolume.FULL }).joinToString(" · ")
    }

    /**
     * FamiStudio 4.5.3 on a 6502 + 2A03 (+ the expansion chip) — native `"nes-famistudio"` or the expansion build's
     * engine (app/src/main/cpp/nes): the song lowered as for `.nsf` export ([FamiStudioLowering]) in the engine's image
     * ([NesImageLayout]), plus every DPCM mapping the song does not play that still fits, so the editor can audition it.
     */
    private inner class Engine : PlaybackEngine {
        override val id = expansion.player.engineId
        override val driver = "FamiStudio sound engine 4.5.3 (FamiTracker tempo${when {
            expansion == NesExpansion.MMC5 -> ", FAMISTUDIO_EXP_MMC5"
            else -> when (expansion.chip) {
                NesChip.APU -> ""
                NesChip.VRC6 -> ", FAMISTUDIO_EXP_VRC6"
                NesChip.VRC7 -> ", FAMISTUDIO_EXP_VRC7"
                NesChip.N163 -> ", FAMISTUDIO_EXP_N163, FAMISTUDIO_EXP_N163_CHN_CNT = ${expansion.n163Channels}"
                NesChip.FDS -> ", FAMISTUDIO_EXP_FDS, FAMISTUDIO_USE_FDS_AUTOMOD, Thor Tracker's manual mod speed patch"
                NesChip.S5B -> ", FAMISTUDIO_EXP_S5B"
                NesChip.EPSM -> ", FAMISTUDIO_EXP_EPSM, Thor Tracker's EPSM patch (global LFO register, legato-safe SSG loads)"
            }
        }}) on m6502.h (chips 9e88298) and Thor Tracker's 2A03 APU${when {
            expansion == NesExpansion.MMC5 -> " + MMC5 pulses and DAC (NESdev \"MMC5 audio\"), Thor Tracker's software PCM player on the DAC"
            else -> when (expansion.chip) {
                NesChip.APU -> ""
                NesChip.VRC6 -> " + VRC6"
                NesChip.VRC7 -> ", emu2413 1.6.4 (commit a1d5120) in VRC7 mode"
                NesChip.N163 -> " + Namco 163 (${expansion.n163Channels} channel${if (expansion.n163Channels == 1) "" else "s"})"
                NesChip.FDS -> " + Thor Tracker's FDS 2C33 (NESdev \"FDS audio\", FamiStudio's measured DAC)"
                NesChip.S5B -> " and Sunsoft 5B (YM2149F, after NESdev and emu2149)"
                NesChip.EPSM -> " and EPSM (YMF288 / OPN3-L on ymfm, the user's YM2608 rhythm ROM)"
            }
        }}, blip_buf 1.1.0"
        override val imageSize = NesPcmLowering.imageSize(expansion)

        /** At 60.1 frames per second, ~3.3 minutes of song. */
        override val seekTicks = 12000

        /**
         * @throws IllegalArgumentException as [FamiStudioLowering.lower], and on the EPSM when the played orders hold a
         * rhythm note but no rhythm ROM is installed ([EpsmRhythmRom.MISSING]): the drums cannot sound without it, and
         * playing or rendering the song silently without them would misrepresent it (FM and SSG songs play without it).
         */
        override fun lower(song: Song, out: ByteArray) {
            // Each variant's engine runs its own player build: another variant's song would be laid out for that player.
            require(song.nes.expansion == expansion) { "The song is for ${song.nes.expansion.system.name}, not ${this@NesFamiStudioSystem.name}" }
            require(expansion != NesExpansion.EPSM || EpsmRhythmRom.installed || !NesRules.playsRhythm(song)) { EpsmRhythmRom.MISSING }
            val lowered = FamiStudioLowering.lower(song, withUnplayed = true)
            if (!expansion.hasPcmLane) return NesImageLayout.build(lowered, out)
            // MMC5: the seven-channel FamiStudio image as it is, then the PCM lane's extension (NesPcmLowering).
            require(out.size == imageSize) { "NES + MMC5 image buffer is ${out.size} bytes; the engine needs $imageSize" }
            val base = ByteArray(expansion.player.imageSize)
            NesImageLayout.build(lowered, base)
            base.copyInto(out)
            NesPcmLowering.build(song, out, base.size)
        }

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? = when {
            instrument !in 1..NesSystem.SLOTS -> "Choose an instrument (01-${NumberText.hex(NesSystem.SLOTS, 2)}) to audition"
            channel !in channels.indices || note !in noteRange -> "Cannot audition note ${noteName(note)} on channel $channel"
            note > NesRules.highestNote(expansion, channel) ->
                "${channels[channel].name} cannot play above ${noteName(NesRules.highestNote(expansion, channel))}"
            NesRules.pitched(expansion, channel) && note < NesRules.lowestNote(expansion, channel) ->
                "${channels[channel].name} cannot play below A-1"
            note > NesRules.highestNote(expansion, channel) ->
                "${channels[channel].name} cannot play above ${noteName(NesRules.highestNote(expansion, channel))}" + when {
                    NesRules.isN163(expansion, channel) -> " with ${expansion.n163Channels} N163 channels"
                    NesRules.isFds(expansion, channel) -> " (the chip's 12-bit frequency)"
                    else -> ""
                }
            fine !in 0..Fine.max -> "Cannot audition fine pitch $fine (0-${Fine.max})"
            NesRules.isMmc5Pcm(expansion, channel) -> NesRules.pcmMappingProblem(song, instrument, note)?.replaceFirstChar { it.uppercase() }
            channel == NesRules.DPCM -> NesRules.dpcmMappingProblem(song, instrument, note)?.replaceFirstChar { it.uppercase() }
                ?: if (dpcmEntry(song, note, instrument) == null) {
                    "Sample memory or the sample list is full: ${noteName(note)} of instrument ${NesRules.hex2(instrument)} cannot be auditioned"
                } else null
            song.nes.instruments[instrument - 1].chip != NesRules.chip(expansion, channel) ->
                "Sound ${NesRules.hex2(instrument)} is a ${song.nes.instruments[instrument - 1].chip.label} sound; " +
                    "${channels[channel].name} plays ${NesRules.chip(expansion, channel).label} sounds"
            NesRules.isEpsmRhythm(expansion, channel) && !EpsmRhythmRom.installed -> EpsmRhythmRom.MISSING
            else -> null
        }

        /**
         * Chip-specific engine note number (true pitch: [NesRules.engineNote]); on DPCM the sample list entry (1..63); on the
         * MMC5 PCM lane the PCM mapping table entry + 1 ([NesPcmLowering.mappings]).
         */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = when {
            channel == NesRules.DPCM -> dpcmEntry(song, note, instrument) ?: 0
            NesRules.isMmc5Pcm(expansion, channel) -> NesPcmLowering.mappings(song)[instrument to note]?.plus(1) ?: 0
            else -> NesRules.engineNote(expansion, channel, note)
        }

        private fun dpcmEntry(song: Song, note: Int, instrument: Int): Int? =
            FamiStudioLowering.lower(FsSong.of(song), withUnplayed = true).dpcm.index[instrument to note]

        /**
         * The engine's fine pitch adds to the period on the 2A03, VRC6, 5B and EPSM SSG, so raising the pitch is negative
         * (on the 5B and the EPSM SSG converted to their own periods, [NesRules.s5bFinePitch], [NesRules.epsmSsgFinePitch]);
         * on the VRC7's and the EPSM's FM channels it adds (× 8) to the frequency number, on the N163's (× 2^shift) and the
         * FDS's to its frequency, all converted as a cell's Pxx is ([NesRules.vrc7FinePitch], [NesRules.epsmFmFinePitch],
         * [NesRules.n163FinePitch], [NesRules.fdsFinePitch]).
         */
        override fun fine(channel: Int, note: Int, fine: Int): Int = when {
            NesRules.isVrc7(expansion, channel) -> NesRules.vrc7FinePitch(NesRules.engineNote(expansion, channel, note), fine)
            NesRules.isN163(expansion, channel) ->
                NesRules.n163FinePitch(NesRules.engineNote(expansion, channel, note), fine, expansion.n163Channels)
            NesRules.isFds(expansion, channel) -> NesRules.fdsFinePitch(NesRules.engineNote(expansion, channel, note), fine)
            NesRules.isS5b(expansion, channel) -> -NesRules.s5bFinePitch(NesRules.engineNote(expansion, channel, note), fine)
            NesRules.isEpsmFm(expansion, channel) -> NesRules.epsmFmFinePitch(NesRules.engineNote(expansion, channel, note), fine)
            NesRules.isEpsmSsg(expansion, channel) -> -NesRules.epsmSsgFinePitch(NesRules.engineNote(expansion, channel, note), fine)
            else -> -fine
        }
    }

    /**
     * Tuned pitches are a note-table note plus `Pxx` (xx = 80 + fine): the engine subtracts fine from the timer period
     * for the note's duration. Pulse and triangle pitch is proportional to 1 / (period + 1). The VRC6 saw's periods are
     * 8/7 of the pulse table's, so the same fine moves it 7/8 as far (tunings use the pulse table's arithmetic). The VRC7's
     * FM channels convert a Pxx to their nearest frequency step for the same pitch ([NesRules.vrc7FinePitch]): one step
     * is 0.3 cents at B-7, 40 at C-1, so tuned FM notes are as exact as the chip allows there. The 5B squares convert a
     * Pxx to their own (about half as long) periods for the same pitch ([NesRules.s5bFinePitch]); the EPSM's FM and SSG
     * channels likewise ([NesRules.epsmFmFinePitch], [NesRules.epsmSsgFinePitch]).
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

        private fun period(note: Int) = FamiStudioLowering.NOTE_TABLE[(note - NesRules.NOTE_OFFSET).coerceIn(1, NesRules.NOTE_COUNT)]
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
            "fade when the song's Release sustain is on) plus the volume envelope's release; on the 2A03 as there. For a cut inside a row use Sxx"
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
            "5B Square 1-3 play 5B sounds (Chip: 5B on the Instrument view): square tones at true pitch, C-1..B-7 (FamiStudio " +
                "plays the 5B from the pulses' note table, where it sounds an octave low, so they take FamiStudio's note an octave " +
                "up), with a mixer envelope (tone, noise, both, neither), a noise envelope and an optional hardware envelope. No Vxx.",
            "The three squares share one noise generator and one envelope generator: the highest square sounding a noise or " +
                "envelope sound sets them for all (the lane headers tag who does: ENV, NOISE; the check warns when two sounds fight).",
            "Ixx / Jyy (5B squares): the shared envelope period's high / low byte, together on one row (on two squares) for xxyy, " +
                "alone xx00 / 00yy. It drives sounds with a manual period until a note reloads its sound (a new sound, or a note " +
                "with attack after it).",
        )
        override val tonalChannels = "Pulse, Triangle and 5B Square"
        override val tonalChannelsShort = "Pulse/Tri/5B"
        override val sketchChannels = "Pulse 1, Pulse 2, Triangle or 5B Square 1-3; Noise and DPCM have no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> =
            NesInstrumentView.helpSections(keys, NesExpansion.S5B)
    }

    private object EpsmHelp : NesHelp() {
        override val off = "Releases the note: on the EPSM FM channels a key off (the patch's release rates) plus the volume " +
            "envelope's release; elsewhere a sound whose volume envelope has a release point plays its tail, any other note " +
            "stops at once; a drum plays its ROM sample to its end. For a cut inside a row use Sxx"
        override val offEntered = "OFF: release (FM: key off; sounds with a volume release point fade out; others stop)"
        override val offInspector = "OFF: release the note (FM: key off and its release; tail or silence elsewhere)"
        override val effectsIntro = super.effectsIntro + listOf(
            "SSG 1-3, FM 1-6 and the six drums play EPSM sounds (Chip: EPSM on the Instrument view; the EPSM is a Yamaha YMF288, " +
                "OPN3-L); the 2A03 channels play 2A03 sounds. EPSM sounds have no duty: no Vxx.",
            "SSG and FM notes sound at true pitch, C-1..B-7 (FamiStudio's EPSM tables sound its notes an octave below the 2A03 " +
                "table's, so the export carries them an octave up). Pxx detunes by the pitch the same Pxx gives a pulse, in the " +
                "chip's own steps.",
            "SSG: as the 5B's squares, a mixer envelope (tone, noise, both, neither), a noise envelope and an optional hardware " +
                "envelope; the three squares share one noise generator and one envelope generator: the highest square sounding " +
                "such a sound sets them (the lane headers tag who does; the check warns when two sounds fight). Ixx / Jyy set the " +
                "shared envelope period's high / low byte, together on one row for xxyy; it drives manual-period sounds until a " +
                "note reloads its sound.",
            "FM: 4-operator patches; the chip's one LFO is the song's (Song view: LFO), the patches set its depths (AMS, PMS).",
            "Drums (Kick, Snare, Cymbal, Hi-hat, Tom, Rim) have a fixed pitch: any note triggers the drum at the sound's first " +
                "volume value (times Cxx) and its patch's L/R pan; only Axy, Cxx, Fxx, Gxx and Sxx apply. They need the YM2608 " +
                "rhythm ROM, which you import once (\"EPSM rhythm ROM…\"); FM, SSG and the .nsf and text exports work without it.",
        )
        override val tonalChannels = "Pulse, Triangle, SSG and FM"
        override val tonalChannelsShort = "Pulse/Tri/SSG/FM"
        override val untonalNote = "Noise keeps every pitch (16 noise rates, repeating every 16 semitones); DPCM notes pick samples; " +
            "EPSM drums have a fixed pitch."
        override val sketchChannels = "Pulse 1, Pulse 2, Triangle, SSG 1-3 or FM 1-6; Noise, DPCM and the drums have no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> =
            NesInstrumentView.helpSections(keys, NesExpansion.EPSM)

        override fun limits(song: Song): List<Pair<String, String>> = super.limits(song) + listOf(
            "Rhythm ROM" to if (EpsmRhythmRom.installed) "installed (YM2608 drums)" else "not imported: the drums need it; FM and SSG play",
        )
    }

    private object Mmc5Help : NesHelp() {
        override val effectsIntro = super.effectsIntro + listOf(
            "MMC5 Pulse 1/2 are two more pulses that play 2A03 sounds from the same bank, with every 2A03 pulse effect: " +
                "Vxx takes duties 00-03, and there is no sweep (FamiStudio has none either).",
            "The MMC5 pulses have no sweep unit to hide a period change, so their waveform restarts (a soft click) " +
                "whenever a note, slide or vibrato moves the period across a high-byte boundary.",
            "MMC5 PCM is Thor Tracker's own software sample player on the MMC5's 8-bit DAC (FamiStudio's engine does not " +
                "drive it): a note plays the PCM sample its sound maps to that key (the sound's PCM page: sample, pitch in " +
                "semitones, loop). Cxx sets the lane's volume, Gxx delays, Sxx cuts, OFF stops, Fxx sets the song speed; no " +
                "other effect applies there. It plays in the app and in WAV exports; .nsf and FamiStudio text cannot hold it " +
                "and refuse a song whose PCM lane plays.",
        )
        override val tonalChannels = "Pulse, Triangle and MMC5 Pulse"
        override val tonalChannelsShort = "Pulse/Tri/MMC5"
        override val sketchChannels = "Pulse 1, Pulse 2, Triangle or MMC5 Pulse 1/2; Noise, DPCM and MMC5 PCM have no tonal chord reference"

        override fun limits(song: Song): List<Pair<String, String>> = super.limits(song) + listOf(
            "PCM samples" to "${song.nes.pcmSamples.size} of ${Pcm.MAX_SAMPLES} taking ${Pcm.kb(Pcm.stored(song.nes))} of the " +
                "${Pcm.kb(Pcm.MEMORY)} PCM memory (Thor Tracker's software player; 1-48 kHz samples, pitch " +
                "${Pcm.PITCH.first}..+${Pcm.PITCH.last} semitones)",
        )

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> =
            NesInstrumentView.helpSections(keys, NesExpansion.MMC5)
    }

    private class N163Help(private val n163Channels: Int) : NesHelp() {
        private val expansion = NesExpansion.n163(n163Channels)
        private val names = if (n163Channels == 1) "N163 1" else "N163 1-$n163Channels"

        override val off = "Releases the note: an instrument whose volume envelope or (N163) wave sequence has a release point " +
            "plays its release tail, any other note (or one already released) stops at once. For a cut inside a row use Sxx"
        override val offEntered = "OFF: release (sounds with a volume or wave release point play their tail; others stop at once)"
        override val offInspector = "OFF: release the note (volume and wave release tails, else silent)"
        override val effectsIntro = super.effectsIntro + listOf(
            "$names play N163 sounds (Chip: N163 on the Instrument view): 4-bit waves of 4-${NesRules.n163WaveRam(n163Channels)} " +
                "samples in the chip's RAM, a sequence of waves (Wave page), shaped by the volume, arpeggio and pitch envelopes.",
            "With $n163Channels channel${if (n163Channels == 1) "" else "s"} the chip keeps ${NesRules.n163WaveRam(n163Channels)} of its " +
                "256 samples for waves (the registers take the rest) and updates each channel once every ${15 * n163Channels} CPU " +
                "cycles: notes reach ${NesSystem.noteName(NesRules.highestNote(expansion, NesRules.N163_FIRST))}" +
                (if (n163Channels >= 7) ", and the chip's switching whine is audible (as on hardware)." else "."),
            "Notes are in tune for 16-sample waves: 32 samples sound an octave lower, 8 an octave higher, other sizes off-key. " +
                "Sounds playing at once on different channels need separate RAM (Wave position), or identical waves at the same place (the check warns).",
            "On the N163 channels Vxx has no effect (no duty), Pxx detunes by the pitch the same Pxx gives a pulse, to the chip's " +
                "nearest frequency step, and X00 restarts the wave (phase reset).",
        )
        override val tonalChannels = "Pulse, Triangle and N163"
        override val tonalChannelsShort = "Pulse/Tri/N163"
        override val sketchChannels = "Pulse 1, Pulse 2, Triangle or $names; Noise and DPCM have no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> =
            NesInstrumentView.helpSections(keys, expansion)

        override fun limits(song: Song): List<Pair<String, String>> = super.limits(song) + listOf(
            "N163 RAM" to "${NesValidator.n163RamUsed(song)} of ${NesRules.n163WaveRam(n163Channels)} wave samples used by N163 sounds " +
                "(${NesRules.N163_RAM_SAMPLES - NesRules.n163WaveRam(n163Channels)} hold the $n163Channels channels' registers)",
        )
    }

    private object FdsHelp : NesHelp() {
        private val top = NesSystem.noteName(NesRules.highestNote(NesExpansion.FDS, NesRules.FDS))

        override val off = "Releases the note: an instrument whose volume envelope or (FDS) wave sequence has a release point " +
            "plays its release tail, any other note (or one already released) stops at once. For a cut inside a row use Sxx"
        override val offEntered = "OFF: release (sounds with a volume or wave release point play their tail; others stop at once)"
        override val offInspector = "OFF: release the note (volume and wave release tails, else silent)"
        override val effectsIntro = super.effectsIntro + listOf(
            "FDS plays FDS sounds (Chip: FDS on the Instrument view): 64-step waves of 6-bit samples, a sequence of up to 16 waves " +
                "(Waves page), a master volume, and a modulation unit that bends the pitch by a 32-step table at the mod speed, " +
                "scaled by the mod depth, after the mod delay; auto-mod sets the speed from the note's pitch (FM).",
            "Notes are true pitch for a wave holding one cycle: 2 cycles per wave sound an octave up, 4 two octaves up. The FDS " +
                "plays C-1..$top (above that FamiStudio's FDS frequency passes the chip's 12 bits).",
            "On FDS Hxx sets the mod depth (00-3F), Ixx the mod speed's high digit (00-0F) and Jxx its low byte, until the next " +
                "note with an attack and without them; I/J have no effect while an auto-mod sound plays. X00 restarts the wave " +
                "(phase reset), Pxx detunes by the pitch the same Pxx gives a pulse, to the nearest FDS frequency step; Vxx has no effect.",
        )
        override val tonalChannels = "Pulse, Triangle and FDS"
        override val tonalChannelsShort = "Pulse/Tri/FDS"
        override val sketchChannels = "Pulse 1, Pulse 2, Triangle or FDS; Noise and DPCM have no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> =
            NesInstrumentView.helpSections(keys, NesExpansion.FDS)

        override fun limits(song: Song): List<Pair<String, String>> = super.limits(song) + listOf(
            "FDS range" to "C-1..$top for one-cycle waves (FamiStudio's FDS frequency is 12-bit); 1-16 waves of 64 samples per sound",
        )
    }

    companion object {
        /** What [noteSample] says about an EPSM drum note. */
        const val RHYTHM_DETAIL = "YM2608 rhythm ROM · fixed pitch"
    }
}

/**
 * The Song view's EPSM row: the chip's one LFO (`$22`), shared by all FM channels; every EPSM sound's patch carries it
 * when exported, and Thor Tracker's EPSM player writes it to `$22` with every FM sound it loads (FamiStudio 4.5.3's
 * engine wrote it to `$22` plus the channel's register offset, see docs/AUDIO_ENGINE.md, "EPSM").
 */
object NesEpsmSongSettings {
    private fun with(song: Song, f: (NesSound) -> NesSound): Song = f(song.nes).let { if (it == song.nes) song else song.copy(sound = it) }

    /** LFO choices in stepping order: off, then rates 0-7 (register values 0, 8..15). */
    private val LFO_STEPS = listOf(0) + (NesSound.EPSM_LFO_ON..NesSound.EPSM_LFO_ON + 7)

    /** The OPN LFO frequencies (Hz) of rates 0-7 at the YMF288's 8 MHz (the YM2608 manual's table). */
    val LFO_HZ = listOf("3.98", "5.56", "6.02", "6.37", "6.88", "9.63", "48.1", "72.2")

    val ALL: List<SongSetting> = listOf(
        SongSetting("LFO", "EPSM LFO rate", "Set LFO",
            value = { s ->
                val v = s.nes.epsmLfo
                if (v == 0) "Off   FM sounds' AMS/PMS have no effect"
                else "Rate ${v and 7}   ${LFO_HZ[v and 7]} Hz (FM sounds set the depth)"
            },
            adjust = { s, f, c ->
                with(s) {
                    val i = LFO_STEPS.indexOf(it.epsmLfo).coerceAtLeast(0)
                    it.copy(epsmLfo = LFO_STEPS[(i + f + 4 * c).coerceIn(LFO_STEPS.indices)])
                }
            },
            reset = { s -> with(s) { it.copy(epsmLfo = 0) } },
        ),
    )
}
