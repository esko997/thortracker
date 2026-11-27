package org.thortracker.system.studio

import kotlinx.serialization.Serializable

enum class ModularPortType {
    AUDIO, MOD, PITCH, GATE, EVENT;
    fun accepts(source: ModularPortType) = this == source || this == MOD && (source == GATE || source == AUDIO)
}

data class ModularPort(val id: Int, val name: String, val type: ModularPortType)

private fun ports(vararg ports: Pair<String, ModularPortType>) = ports.mapIndexed { i, (name, type) -> ModularPort(i, name, type) }
private fun knob(id: Int, name: String, unit: String, min: Int, max: Int, default: Int) = StudioParameter(id,name,unit,min,max,default)
private fun selector(id: Int, name: String, vararg labels: String) = StudioParameter(id,name,"",0,labels.lastIndex,0,discrete=true,labels=labels.toList())
private val audio = ModularPortType.AUDIO
private val mod = ModularPortType.MOD
private val pitch = ModularPortType.PITCH
private val gate = ModularPortType.GATE
private val event = ModularPortType.EVENT

/** Indices within a kind are part of the document and native graph formats. Normalized controls use thousandths. */
@Serializable enum class ModularKind(val wire: Int, val label: String, val inputs: List<ModularPort>,
    val outputs: List<ModularPort>, val parameters: List<StudioParameter> = emptyList(), private val normalized: Int = 0) {
    NOTE(0,"Note",ports(),ports("Pitch" to pitch,"Gate" to gate,"Trigger" to event,"Velocity" to mod,"Accent" to mod)),
    OSC(1,"Oscillator",ports("Pitch" to pitch,"FM" to mod,"Width" to mod),ports("Audio" to audio),
        listOf(selector(0,"Wave","Sine","Saw","Pulse"),knob(1,"Tune","st",-48,48,0),knob(2,"Width","‰",50,950,500)),4),
    NOISE(2,"Noise",ports(),ports("Audio" to audio)),
    FILTER(3,"Filter",ports("Audio" to audio,"Cutoff" to mod),ports("Audio" to audio),
        listOf(knob(0,"Cutoff","Hz",20,20000,2000),knob(1,"Resonance","‰",0,1000,0)),2),
    VCA(4,"VCA",ports("Audio" to audio,"Level" to mod),ports("Audio" to audio),listOf(knob(0,"Gain","‰",0,2000,1000)),1),
    FUNCTION(5,"Function",ports("Trigger" to event,"Gate" to gate,"Rate" to mod),ports("Level" to mod,"End" to event),
        listOf(knob(0,"Rise","ms",1,8000,10),knob(1,"Fall","ms",1,8000,200),selector(2,"Loop","Off","On"))),
    RANDOM(6,"Sample & hold",ports("Trigger" to event,"Sample" to mod),ports("Stepped" to mod,"Smooth" to mod),listOf(knob(0,"Slew","ms",1,8000,100))),
    MIX(7,"Mixer",ports("A" to audio,"B" to audio),ports("Audio" to audio),
        listOf(knob(0,"A","‰",-4000,4000,1000),knob(1,"B","‰",-4000,4000,1000),knob(2,"Bias","‰",-2000,2000,0)),7),
    OUT(8,"Output",ports("Left" to audio,"Right" to audio),ports(),listOf(knob(0,"Gain","‰",0,2000,750)),1),
    DELAY(9,"Unit delay",ports("Audio" to audio),ports("Audio" to audio)),
    FOLD(10,"Wavefolder",ports("Audio" to audio,"Drive" to mod),ports("Audio" to audio),listOf(knob(0,"Drive","‰",1000,16000,2000)),1),
    RESONATOR(11,"Resonator",ports("Audio" to audio,"Pitch" to pitch,"FM" to mod),ports("Audio" to audio),
        listOf(knob(0,"Tune","st",-48,48,0),knob(1,"Decay","ms",10,4000,250)));

    internal fun physical(parameter: Int, value: Int) = if (normalized and (1 shl parameter) != 0) value / 1000f else value.toFloat()
}
