package org.thortracker.system.midi

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.input.Keymap
import org.thortracker.model.*
import org.thortracker.system.*

@Serializable
data class ControlBinding(val name: String = "Modulation", val controller: Int = 1)

@Serializable
data class MidiTrack(
    val id: Int, val channel: Int, val port: String = "", val velocity: Int = 100,
    val gate: Int = 90, val bendRange: Int = 2, val bank: Int? = null, val program: Int? = null,
    val bindings: List<ControlBinding> = listOf(ControlBinding(), ControlBinding("Brightness", 74)),
) {
    init {
        require(id > 0 && channel in 0..15 && velocity in 1..127 && gate in 1..100 && bendRange in 1..24)
        require(bank == null || bank in 0..16383)
        require(program == null || program in 0..127)
        require(bindings.size == 2 && bindings.all { it.name.isNotBlank() && it.controller in 0..119 })
    }
}

@Serializable
data class MidiSound(val tracks: List<MidiTrack> = listOf(MidiTrack(1, 0)), val clock: Boolean = false,
    val nextId: Int = 2) : SystemSound {
    init {
        require(tracks.size in 1..64 && tracks.distinctBy { it.id }.size == tracks.size)
        require(tracks.all { it.id < nextId })
    }
    @Transient override val system: MidiSystem = MidiSystem(this)
}

fun Song.addMidiTrack(): Song {
    val state = sound as MidiSound
    if (state.tracks.size == 64 || state.nextId == Int.MAX_VALUE) return this
    val port = state.tracks.last().port
    val channel = (0..15).firstOrNull { ch -> state.tracks.none { it.port == port && it.channel == ch } } ?: 0
    return copy(sound = state.copy(tracks = state.tracks + MidiTrack(state.nextId, channel, port), nextId = state.nextId + 1),
        patterns = patterns.map { it.copy(tracks = it.tracks + listOf(List(Songs.ROWS) { Cell.BLANK })) })
}

fun Song.removeMidiTrack(index: Int): Song {
    val state = sound as MidiSound
    if (state.tracks.size == 1) return this
    return copy(sound = state.copy(tracks = state.tracks.filterIndexed { i, _ -> i != index }),
        patterns = patterns.map { p -> p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != index }) })
}

class MidiSystem(state: MidiSound) : SoundSystem {
    override val id = ID
    override val name = "USB MIDI (Beta)"
    override val channels = state.tracks.map { ChannelSpec("Track ${it.id}", ChannelKind.VOICE, 0, true) }
    override val banks = listOf(BankSpec("Track defaults", 1))
    override val noteRange = 0..115
    override val nameOctaveOffset = 0
    override val effects = listOf("Arpeggio" to "xy semitones", "Slide up" to "xx cents per row",
        "Slide down" to "xx cents per row", "Expression rise" to "xx steps per row",
        "Expression fall" to "xx steps per row", "Retrigger" to "1–16 strikes",
        "Delay" to "xx/256 of row", "Echo" to "1–8 fading repeats within row",
        "Velocity" to "1–127", "Control A" to "0–127", "Control B" to "0–127").mapIndexed { i, (label, unit) ->
        EffectSpec(i, label, unit, unit, true)
    }
    override val views = ViewKind.entries.toSet() - ViewKind.INSTRUMENT - ViewKind.WAVE
    override val exports = emptyList<ExportFormat>()
    override val offEffect: String? = null
    override val tickHz = 48.0
    override val maxOrders = 256
    override val soundSerializer = MidiSound.serializer()
    override val backendVersion = 1
    override val driverDescription = "Android USB MIDI 1.0"
    override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int) =
        if (note in noteRange) null else "MIDI notes must be C0–G9"
    override fun blank() = Song(patterns = listOf(Pattern.blank(1)), orders = listOf(0), sound = MidiSound())
    override fun demo() = blank()
    override fun upgradeProject(song: JsonObject, version: Int) = song
    override fun upgradeBackend(song: JsonObject, backendVersion: Int) = song
    override fun effectsFor(channel: Int) = effects
    override fun effectColumns(channel: Int) = 4
    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int) =
        effects.getOrNull(effect)?.let { spec ->
            val name = (song.sound as MidiSound).tracks[channel].bindings.getOrNull(effect - 9)?.name ?: spec.name
            "$name $param (${spec.param})"
        } ?: "Unknown MIDI effect"
    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        if (cell.note !in Cell.CUT..115 || cell.instrument !in 0..1) return "Invalid MIDI note or instrument"
        for (column in 0..cell.more.size) {
            val code = cell.effectCode(column)
            val limit = when (code) { 5 -> 1..16; 7 -> 1..8; 8 -> 1..127; 9, 10 -> 0..127; else -> 0..255 }
            if (code !in effects.indices || cell.effectParam(column) !in limit) return "Invalid MIDI effect in column ${column + 1}"
        }
        return null
    }
    override fun structuralErrors(song: Song): List<String> {
        val sound = song.sound as? MidiSound ?: return listOf("Missing MIDI track settings")
        if (song.orders.size !in 1..maxOrders || song.orders.any { it !in song.patterns.indices } ||
            song.loopOrder !in song.orders.indices || song.ticksPerRow !in 1..255 ||
            song.parkedOrders != song.parkedOrders.distinct().sorted() || song.parkedOrders.any { it !in song.orders.indices })
            return listOf("Invalid MIDI arrangement or speed")
        if (song.newPatternScale.root !in 0..11) return listOf("Invalid default scale root")
        song.tuning?.problems(song.system)?.takeIf { it.isNotEmpty() }?.let { return it }
        for (p in song.patterns) {
            if (p.length !in 1..Songs.ROWS || p.tracks.size != sound.tracks.size || p.scale.root !in 0..11)
                return listOf("Invalid MIDI pattern dimensions or scale")
            for ((ch, track) in p.tracks.withIndex()) {
                if (track.size != Songs.ROWS) return listOf("Invalid MIDI track length")
                // Preserve editable intermediate effect values. Playback validates their musical limits.
                if (track.any { c -> c.note !in Cell.CUT..115 || c.instrument !in 0..1 ||
                    (0..c.more.size).any { c.effectCode(it) !in effects.indices || c.effectParam(it) !in 0..255 } })
                    return listOf("Invalid MIDI cell on track ${ch + 1}")
            }
        }
        return emptyList()
    }
    override fun errors(song: Song): List<String> {
        structuralErrors(song).takeIf { it.isNotEmpty() }?.let { return it }
        if (Songs.playedOrders(song).isEmpty()) return listOf("Unpark an order to play")
        for (p in song.patterns) for ((ch, track) in p.tracks.withIndex()) for (c in track.take(p.length))
            cellProblem(song, ch, c)?.let { return listOf(it) }
        return emptyList()
    }
    override fun warnings(song: Song) = (song.sound as MidiSound).tracks.groupBy { it.port to it.channel }
        .values.filter { it.size > 1 }.map { "Tracks ${it.joinToString { t -> t.id.toString() }} share patch, bend and controllers" }
    override fun arranged(song: Song): Song {
        val played = Songs.playedOrders(song)
        return song.copy(orders = played.map { song.orders[it] }, loopOrder = played.indexOfFirst { it >= song.loopOrder }.coerceAtLeast(0), parkedOrders = emptyList())
    }
    override fun patternLoop(song: Song, order: Int) = song.copy(orders = listOf(song.orders[order]), loopOrder = 0, parkedOrders = emptyList())
    override fun jumpTarget(cell: Cell): Int? = null
    override fun withJumpTarget(cell: Cell, order: Int) = cell
    override fun instrumentName(song: Song, bank: Int, slot: Int) = "Track defaults"
    override fun instrumentSummary(song: Song, bank: Int, slot: Int) = "Set velocity and patch in MIDI tracks"
    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null
    override fun selectSound(selection: Selection, song: Song) = selection
    override fun view(kind: ViewKind): SystemView? = null
    override val fine = object : FinePitch {
        override val name = "Semitones"
        override val max = 0
        override fun range(note: Int) = 0..0
        override fun cents(note: Int, fine: Int) = 0.0
        override fun of(cell: Cell) = 0
        override fun write(cell: Cell, note: Int, fine: Int) = cell.takeIf { fine == 0 }
    }
    override val help = object : SystemHelp {
        override val off = "Release track notes"
        override val offEntered = off
        override val offInspector = off
        override val effectsIntro = listOf("Four columns run left to right. Later settings win.")
        override val tonalChannels = "All MIDI tracks"
        override val tonalChannelsShort = "All tracks"
        override val untonalNote = ""
        override val sketchChannels = tonalChannels
        override fun viewSections(keys: Keymap) = emptyList<Pair<String, List<Pair<String, String>>>>()
        override fun limits(song: Song) = listOf("Tracks" to "${channels.size}/64")
    }
    companion object { const val ID = "android-usb-midi" }
}
