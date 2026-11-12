package org.thortracker.system.gba

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.GbaExportConstraints
import org.thortracker.backend.GbaLowering
import org.thortracker.backend.GbaPlayerImage
import org.thortracker.backend.GbaXmExporter
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.input.Keymap
import org.thortracker.model.Cell
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

/** Eight software sample voices, standard XM 1.04 export, and the same Maxmod lowering for preview. */
object GbaSystem : SoundSystem {
    override val id = "gba-maxmod"
    override val name = "Game Boy Advance (Maxmod)"
    override val channels = List(GbaRules.CHANNELS) { ChannelSpec("Sample ${it + 1}", ChannelKind.SAMPLE, 0, tonal = true) }
    override val banks = listOf(BankSpec("Samples", GbaSound.SLOTS))
    override val noteRange = GbaRules.NOTES
    override val nameOctaveOffset = 0
    override val effects: List<EffectSpec> get() = GbaRules.EFFECTS
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports = listOf(ExportFormat("xm", ".xm (Maxmod, eight channels)") { GbaXmExporter.export(it) })
    override val fine: FinePitch = SemitonePitch
    override val help: SystemHelp = Help
    override val offEffect: String? = null
    override val tickHz = 50.0
    override fun ticksPerSecond(song: Song): Double = song.gba.bpm / 2.5
    override val maxOrders = GbaRules.MAX_ORDERS
    override val backendVersion = 1
    override val soundSerializer: KSerializer<GbaSound> = GbaSound.serializer()

    override fun blank(): Song = Song(patterns = listOf(Pattern.blank(8, Songs.ROWS, "Pattern 00")), orders = listOf(0), sound = GbaSamples.starterBank())
    override fun demo(): Song = GbaDemoSong.create()
    override fun upgradeProject(song: JsonObject, version: Int): JsonObject {
        require(version >= 9) { "GBA songs require project version 9 or later" }
        return song
    }
    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject {
        require(backendVersion == 1) { "Unsupported GBA backend version $backendVersion" }
        return song
    }
    override fun effectsFor(channel: Int): List<EffectSpec> = if (channel in channels.indices) effects else emptyList()
    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String {
        GbaExportConstraints.effectError(effect, param)?.let { return it }
        if (effect == 0 && param == 0) return "No effect"
        return when (effect) {
            11 -> "Jump to order $param"
            12 -> "Set volume $param / 64"
            13 -> "Break to row ${(param shr 4) * 10 + (param and 15)} of the next order"
            15 -> if (param < 32) "Speed: $param ticks per row" else "Tempo: $param BPM"
            else -> effects.firstOrNull { it.code == effect }?.let { "${it.name}: ${it.description}" } ?: "Unsupported XM effect"
        }
    }
    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? = GbaRules.cellProblem(song, channel, cell)
    override fun structuralErrors(song: Song): List<String> = GbaRules.structuralErrors(song)
    override fun errors(song: Song): List<String> = GbaRules.errors(song)
    override fun warnings(song: Song): List<String> = GbaRules.warnings(song)

    override fun arranged(song: Song): Song {
        if (song.parkedOrders.isEmpty()) return song
        val played = Songs.playedOrders(song)
        if (played.isEmpty()) return song
        val next = IntArray(song.orders.size) { -1 }
        var k = played.size
        for (o in song.orders.indices.reversed()) {
            if (k > 0 && played[k - 1] == o) k--
            next[o] = if (k < played.size) k else -1
        }
        val loop = next[song.loopOrder].let { if (it >= 0) it else 0 }
        fun target(order: Int): Int = if (order in song.orders.indices) next[order].let { if (it >= 0) it else loop } else order
        val patterns = song.patterns.map { p ->
            val tracks = p.tracks.map { track -> track.map { c -> jumpTarget(c)?.let { withJumpTarget(c, target(it)) } ?: c } }
            if (tracks == p.tracks) p else p.copy(tracks = tracks)
        }
        return song.copy(orders = played.map { song.orders[it] }, patterns = patterns, loopOrder = loop, parkedOrders = emptyList())
    }
    override fun patternLoop(song: Song, order: Int): Song {
        val index = song.orders[order.coerceIn(song.orders.indices)]
        val pattern = song.patterns[index]
        val tracks = pattern.tracks.map { track -> track.map { c -> if (jumpTarget(c) != null) withJumpTarget(c, 0) else c } }
        val patterns = song.patterns.toMutableList().also { it[index] = pattern.copy(tracks = tracks) }
        return song.copy(orders = listOf(index), patterns = patterns, loopOrder = 0, parkedOrders = emptyList())
    }
    override fun jumpTarget(cell: Cell): Int? = if (cell.effect == 11) cell.param else null
    override fun withJumpTarget(cell: Cell, order: Int): Cell = cell.copy(param = order)

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? =
        if (bank == 0) song.gba.instrument(slot)?.name?.ifEmpty { "Sound $slot" } else null
    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? {
        if (bank != 0) return null
        val instrument = song.gba.instrument(slot) ?: return null
        val mapped = instrument.keymap.count { it >= 0 }
        if (mapped == 0) return "Empty: import or generate a sample, then map notes"
        val samples = instrument.keymap.filter { it >= 0 }.distinct().size
        return "$samples sample${if (samples == 1) "" else "s"} · $mapped / 96 keys · ${if (instrument.volumeEnvelope.enabled) "volume envelope" else "sample volume"}"
    }
    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? {
        if (bank != 0) return null
        val index = song.gba.instrument(slot)?.keymap?.firstOrNull { it >= 0 } ?: return null
        val pcm = song.gba.samples.getOrNull(index)?.pcm ?: return null
        if (pcm.isEmpty()) return null
        return List(64) { i -> (pcm[i * pcm.lastIndex / 63] + 128) / 255f }
    }
    override fun selectSound(selection: Selection, song: Song): Selection = selection
    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) GbaSoundView else null
    override fun noteSample(song: Song, channel: Int, note: Int, instrument: Int): NoteSample? {
        if (channel !in channels.indices || note !in noteRange) return null
        val index = song.gba.instrument(instrument)?.keymap?.getOrNull(note) ?: return null
        val sample = song.gba.samples.getOrNull(index) ?: return null
        return NoteSample(sample.name, "${sample.sampleRate} Hz at ${noteName(sample.rootNote)} · ${sample.pcm.size} frames · ${if (sample.loopEnd > sample.loopStart) "forward loop" else "one-shot"}")
    }
    override val songSettings = listOf(SongSetting(
        "BPM", "XM tempo", "Change BPM", { "${it.gba.bpm} BPM · XM tick = 2.5 / BPM seconds" },
        { song, fine, coarse -> song.copy(sound = song.gba.copy(bpm = (song.gba.bpm + fine + coarse * 10).coerceIn(32, 255))) },
        { song -> song.copy(sound = song.gba.copy(bpm = 125)) },
    ))

    override val engine: PlaybackEngine = object : PlaybackEngine {
        override val id = "gba-maxmod"
        override val driver = "Maxmod GBA · eight software sample voices · 15768 Hz mixing"
        override val imageSize: Int get() = GbaPlayerImage.SIZE
        override val seekTicks = 12000
        override fun lower(song: Song, out: ByteArray) = GbaLowering.lower(song, out)
        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? {
            if (fine != 0) return "GBA XM audition supports exact semitone notes only; remove microtuning"
            if (channel !in channels.indices || note !in noteRange) return "Choose a GBA sample lane and note C0–B7"
            val sound = song.sound as? GbaSound ?: return "Song does not contain GBA sample data"
            val ins = sound.instrument(instrument) ?: return "Choose an instrument in slots 1–64"
            GbaRules.instrumentProblem(ins, sound.samples.size)?.let { return it }
            val index = ins.keymap[note]
            val sample = sound.samples.getOrNull(index) ?: return "${noteName(note)} is unmapped; import or generate a sample and map this key in Sound"
            GbaRules.sampleProblem(sample)?.let { return it }
            return GbaExportConstraints.errors(sound).firstOrNull()
        }
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note
        override fun fine(channel: Int, note: Int, fine: Int): Int = 0
    }

    private object SemitonePitch : FinePitch {
        override val name = "semitones only (no microtuning)"
        override val max = 0
        override fun range(note: Int) = 0..0
        override fun cents(note: Int, fine: Int) = 0.0
        override fun of(cell: Cell): Int = 0
        override fun write(cell: Cell, note: Int, fine: Int): Cell? = if (fine == 0 && note in GbaRules.NOTES) cell.copy(note = note) else null
    }

    private object Help : SystemHelp {
        override val off = "XM key-off: release the volume envelope's sustain and apply fadeout; without a volume envelope, silence the note."
        override val offEntered = "OFF: release sample voice"
        override val offInspector = "OFF: XM key-off/release"
        override val effectsIntro = listOf(
            "Eight tonal software sample lanes, one XM effect column each. Initial speed is ticks per row (1–31); BPM is in Song (32–255).",
            "Standard XM 1.04 export uses the same sample conversion as Maxmod preview at 15768 Hz. GBA game sound effects and CPU usage share the mixer budget.",
            "64 instruments, 128 global samples, 16 samples per XM instrument including any silent key, and 512 KiB embedded signed eight-bit PCM (a backend limit, not GBA RAM).",
            "Keys without a sample are silent; map them in Sound. Source rate, root and tuning determine transposition. Converter quantization and legacy restrictions appear in Check.",
            "Only the listed XM effects and extended subcommands are supported. Per-note microtuning is rejected rather than silently rounded.",
        )
        override val tonalChannels = "All eight sample lanes"
        override val tonalChannelsShort = "Samples 1–8"
        override val untonalNote = "Every lane is tonal; drum keymaps still select recordings."
        override val sketchChannels = "Any of the eight sample lanes"
        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = listOf(
            "GBA Sound" to listOf("Up / Down" to "Select instrument", "Left / Right" to "Select parameter", "Edit + Left / Right" to "Change parameter", "Audition (hold)" to "Play selected sample; release for key-off"),
        )
        override fun limits(song: Song): List<Pair<String, String>> = listOf(
            "Voices" to "8 software sample lanes", "Instruments" to "${song.gba.instruments.count { i -> i.keymap.any { it >= 0 } }} / 64 mapped",
            "Samples" to "${song.gba.samples.size} / 128", "Embedded PCM" to "${song.gba.samples.sumOf { it.pcm.size.toLong() }} / 524288 bytes",
            "Timing" to "${song.gba.bpm} BPM · speed ${song.ticksPerRow}",
        )
    }
}
