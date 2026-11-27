package org.thortracker.system.studio

import org.thortracker.editor.ViewKind
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.system.*
import org.thortracker.system.midi.TrackSystem
import kotlinx.serialization.json.*

class StudioSystem(private val state: StudioSound) : TrackSystem(state.tracks.map { it.midi }) {
    override val id = ID
    override val name = "Studio"
    override val driverDescription = "Studio instruments and stereo effects · 48 ticks/s"
    override val backendVersion = 2
    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject {
        require(backendVersion == 1)
        val sound = song.getValue("sound").jsonObject
        val patches = sound["patches"]?.jsonArray ?: return song
        return JsonObject(song + ("sound" to JsonObject(sound + ("patches" to JsonArray(patches.map {
            JsonObject(it.jsonObject + ("type" to JsonPrimitive("mono")))
        })))))
    }
    override val engine: PlaybackEngine = StudioEngine
    override val soundSerializer = StudioSound.serializer()
    override val banks = listOf(BankSpec("Studio instruments", StudioSound.SLOTS))
    override val channels = state.tracks.map { ChannelSpec("${if (it.target == StudioTarget.MONO) "Local" else "USB"} ${it.midi.id}", ChannelKind.VOICE, 0, true) }
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val trackLimit = StudioSound.MAX_TRACKS
    override val usbTracks = state.tracks.filter { it.target == StudioTarget.USB_MIDI }.map { it.midi }
    private val usbEffects = super.effects
    override val effects = usbEffects.take(9) + listOf(
        EffectSpec(9, "Cutoff", "0–127", "Set low-pass cutoff", true),
        EffectSpec(10, "Resonance", "0–127", "Set filter resonance", true),
        EffectSpec(11, "Accent", "0–127", "Accent this row’s attacks", true),
        EffectSpec(12, "Tie", "0/1", "1: glide into the next row’s note without retriggering", true),
        EffectSpec(13, "Slice", "00–1F", "Select a slice for subsequent attacks", true)) +
        (14..35).map { EffectSpec(it,"Unbound ${'A'+(it-10)}","00–FF","Assign a parameter on the track strip",true) }
    private val laneEffects = state.tracks.map { lane -> if(lane.target==StudioTarget.USB_MIDI) usbEffects else effects.map { spec ->
        val binding=lane.bindings[spec.code]
        if(binding==null) spec else {
            val p=binding.kind.parameters.first { it.id==binding.parameter }
            EffectSpec(spec.code,"${binding.kind.name.lowercase()} ${p.name}","00–FF", "${p.display(p.min)} – ${p.display(p.max)}",true)
        }
    } }
    private val semitones = super.fine
    private val trackHelp = super.help
    override val fine = object : FinePitch by semitones { override val retunesTable = true }
    override val help = object : SystemHelp by trackHelp {
        override val tonalChannels = "Studio tracks"
        override val sketchChannels = tonalChannels
    }
    override fun effectsFor(channel: Int) = laneEffects[channel]
    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String {
        val spec = effectsFor(channel).getOrNull(effect) ?: return "Not supported on this destination"
        state.tracks[channel].bindings[effect]?.let { binding ->
            val p=binding.kind.parameters.first { it.id==binding.parameter }
            return "${spec.name}: ${p.display(p.fromHex(param))} (${param.toString(16).uppercase().padStart(2,'0')})"
        }
        val label = if (state.tracks[channel].target == StudioTarget.USB_MIDI) trackSettings[channel].bindings.getOrNull(effect - 9)?.name else null
        return "${label ?: spec.name} $param (${spec.param})"
    }
    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        if (state.tracks[channel].target == StudioTarget.USB_MIDI) {
            if (cell.instrument > 1) return "USB track ${trackSettings[channel].id}: use Track defaults (01); choose Bank/Program in Studio tracks"
            if ((0..cell.more.size).any { cell.effectCode(it) in 11..12 }) return "USB track ${trackSettings[channel].id}: accent and tie require Mono"
        }
        else for(col in 0..cell.more.size) {
            val code=cell.effectCode(col)
            if(code==13 && cell.effectParam(col)>31) return "Dxx slice must be 00–1F"
            if(code in 14..35 && code !in state.tracks[channel].bindings) return "Track ${trackSettings[channel].id}: assign ${'A'+(code-10)} on the track strip or clear the command"
        }
        return super.cellProblem(song, channel, cell)
    }
    override fun structuralErrors(song:Song):List<String> {
        super.structuralErrors(song).takeIf { it.isNotEmpty() }?.let { return it }
        for ((slot,p) in state.patches.withIndex()) if(p is SamplerPatch && p.assetId!=null) {
            val pcm=state.assets[p.assetId] ?: return listOf("Sampler ${slot+1}: missing asset ${p.assetId}")
            val end=if(p.end==0) pcm.frames else p.end
            if(end>pcm.frames || p.start>=end || p.loopStart>=end || p.loopEnd>end ||
                p.slices.any { it.start<p.start || it.end>end }) return listOf("Sampler ${slot+1}: region or slice lies outside its recording")
        }
        return emptyList()
    }
    override fun patternLoop(song:Song,order:Int):Song {
        val prefix=(0 until order).filterNot { it in song.parkedOrders }.map { song.orders[it] }
        return super.patternLoop(song,order).copy(sound=state.copy(previewPrefix=prefix))
    }
    override fun errors(song: Song): List<String> {
        if (song.tuning != null && usbTracks.isNotEmpty()) return listOf("Studio USB tracks cannot play the song’s tuning; assign Mono or remove the tuning")
        super.errors(song).takeIf { it.isNotEmpty() }?.let { return it }
        val score=arranged(song)
        val orders=state.previewPrefix+score.orders
        if(orders.size>maxOrders || orders.any { it !in score.patterns.indices }) return listOf("Invalid Studio preview order history")
        for((ch,lane) in state.tracks.withIndex()) {
            if(lane.target==StudioTarget.USB_MIDI) continue
            if(lane.sends.drop(state.buses.size).any { it!=0 }) return listOf("Track ${lane.midi.id}: create the missing return bus or clear its send")
            var slot=1
            // Validate both the first pass and loop entry (which can inherit a different instrument).
            for(pass in 0..1) for(order in (if(pass==0) 0 else state.previewPrefix.size+score.loopOrder)..orders.lastIndex) {
                val pattern=score.patterns[orders[order]]
                for(row in 0 until pattern.length) {
                    val c=pattern.tracks[ch][row]; if(c.isBlank) continue
                    if(c.instrument>0) slot=c.instrument
                    val p=state.patches[slot-1]
                    fun issue(text:String)=listOf("Track ${lane.midi.id}, order ${order+1}, row $row: $text")
                    if(c.note>=0 && p is SamplerPatch && p.assetId==null) return issue("import a sample into instrument $slot")
                    for(col in 0..c.more.size) {
                        val code=c.effectCode(col); val b=lane.bindings[code]
                        if(code==13 && (p !is SamplerPatch || p.slices.none { it.id==c.effectParam(col) }))
                            return issue("Dxx names a missing slice on instrument $slot; edit the slice or command")
                        if(code in 9..10 && p !is MonoPatch) return issue("9/A controls require Mono; bind a ${p.kind} parameter instead")
                        if(b?.instance==0 && b.kind!=p.kind) return issue("${'A'+(code-10)} requires ${b.kind}, not ${p.kind}; change the binding or instrument")
                    }
                }
            }
        }
        return emptyList()
    }
    override val auditionArgumentMax = 32
    override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? = when {
        channel !in channels.indices || note !in noteRange -> "Invalid Studio track or pitch"
        fine != 0 && (state.tracks[channel].target==StudioTarget.USB_MIDI ||
            (state.patch(instrument) as? SamplerPatch)?.slices?.none { it.id==fine-1 } != false) -> "Unknown sampler audition slice"
        state.tracks[channel].target==StudioTarget.MONO &&
            (state.patch(instrument.coerceAtLeast(1)) as? SamplerPatch)?.let { it.assetId==null } == true -> "Import a sample before auditioning"
        state.tracks[channel].target == StudioTarget.USB_MIDI && song.tuning != null -> "USB audition cannot play this tuning"
        instrument !in 0..StudioSound.SLOTS -> "Invalid Studio patch slot"
        else -> null
    }
    override fun blank() = Song(patterns = listOf(Pattern.blank(1)), orders = listOf(0), sound = StudioSound())
    override fun demo() = studioModuleDemo()
    override fun instrumentName(song: Song, bank: Int, slot: Int) = song.studio.patch(slot)?.name
    override fun instrumentSummary(song: Song, bank: Int, slot: Int) = song.studio.patch(slot)?.let {
        if(it is MonoPatch) "${if (it.waveform == 0) "Saw" else "Square"} · cutoff ${it.cutoff} · resonance ${it.resonance}"
        else "${it.kind.name.lowercase()} · ${it.name}"
    }
    override fun view(kind: ViewKind): SystemView? = when(kind) {
        ViewKind.INSTRUMENT -> StudioSoundView; ViewKind.STUDIO_STRIP -> StudioStripView; else -> null
    }
    companion object { const val ID = "studio-mono" }
}
