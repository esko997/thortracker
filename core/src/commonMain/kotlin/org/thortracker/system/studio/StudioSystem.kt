package org.thortracker.system.studio

import org.thortracker.editor.ViewKind
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.system.*
import org.thortracker.system.midi.TrackSystem

class StudioSystem(private val state: StudioSound) : TrackSystem(state.tracks.map { it.midi }) {
    override val id = ID
    override val name = "Studio"
    override val driverDescription = "Studio mono synth · 48 ticks/s"
    override val engine: PlaybackEngine = StudioEngine
    override val soundSerializer = StudioSound.serializer()
    override val banks = listOf(BankSpec("Mono patches", StudioSound.SLOTS))
    override val channels = state.tracks.map { ChannelSpec("${if (it.target == StudioTarget.MONO) "Mono" else "USB"} ${it.midi.id}", ChannelKind.VOICE, 0, true) }
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val trackLimit = StudioSound.MAX_TRACKS
    override val usbTracks = state.tracks.filter { it.target == StudioTarget.USB_MIDI }.map { it.midi }
    private val usbEffects = super.effects
    override val effects = usbEffects.take(9) + listOf(
        EffectSpec(9, "Cutoff", "0–127", "Set low-pass cutoff", true),
        EffectSpec(10, "Resonance", "0–127", "Set filter resonance", true),
        EffectSpec(11, "Accent", "0–127", "Accent this row’s attacks", true),
        EffectSpec(12, "Tie", "0/1", "1: glide into the next row’s note without retriggering", true))
    private val semitones = super.fine
    private val trackHelp = super.help
    override val fine = object : FinePitch by semitones { override val retunesTable = true }
    override val help = object : SystemHelp by trackHelp {
        override val tonalChannels = "Studio tracks"
        override val sketchChannels = tonalChannels
    }
    override fun effectsFor(channel: Int) = if (state.tracks[channel].target == StudioTarget.MONO) effects else usbEffects
    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String {
        val spec = effectsFor(channel).getOrNull(effect) ?: return "Not supported on this destination"
        val label = if (state.tracks[channel].target == StudioTarget.USB_MIDI) trackSettings[channel].bindings.getOrNull(effect - 9)?.name else null
        return "${label ?: spec.name} $param (${spec.param})"
    }
    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        if (state.tracks[channel].target == StudioTarget.USB_MIDI) {
            if (cell.instrument > 1) return "USB track ${trackSettings[channel].id}: use Track defaults (01); choose Bank/Program in Studio tracks"
            if ((0..cell.more.size).any { cell.effectCode(it) in 11..12 }) return "USB track ${trackSettings[channel].id}: accent and tie require Mono"
        }
        return super.cellProblem(song, channel, cell)
    }
    override fun errors(song: Song): List<String> {
        if (song.tuning != null && usbTracks.isNotEmpty()) return listOf("Studio USB tracks cannot play the song’s tuning; assign Mono or remove the tuning")
        return super.errors(song)
    }
    override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? = when {
        channel !in channels.indices || note !in noteRange || fine != 0 -> "Invalid Studio track or pitch"
        state.tracks[channel].target == StudioTarget.USB_MIDI && song.tuning != null -> "USB audition cannot play this tuning"
        instrument !in 0..StudioSound.SLOTS -> "Invalid Studio patch slot"
        else -> null
    }
    override fun blank() = Song(patterns = listOf(Pattern.blank(1)), orders = listOf(0), sound = StudioSound())
    override fun instrumentName(song: Song, bank: Int, slot: Int) = song.studio.patch(slot)?.name
    override fun instrumentSummary(song: Song, bank: Int, slot: Int) = song.studio.patch(slot)?.let {
        "${if (it.waveform == 0) "Saw" else "Square"} · cutoff ${it.cutoff} · resonance ${it.resonance}"
    }
    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) StudioSoundView else null
    companion object { const val ID = "studio-mono" }
}
