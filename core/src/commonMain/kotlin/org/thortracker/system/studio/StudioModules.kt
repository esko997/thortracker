package org.thortracker.system.studio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.roundToInt

/** IDs in a kind's catalogue are persisted. Never reuse one for a different meaning. */
@Serializable enum class StudioKind(val wire: Int) {
    MONO(0), SAMPLER(1), FM(2), PERCUSSION(3), DELAY(4), DRIVE(5), CRUSHER(6), REVERB(7), CHORUS(8),
    WAVETABLE(9), PLUCK(10), MODULAR(11);
    val instrument get() = when (this) {
        MONO, SAMPLER, FM, PERCUSSION, WAVETABLE, PLUCK, MODULAR -> true
        DELAY, DRIVE, CRUSHER, REVERB, CHORUS -> false
    }
}
enum class StudioScale { LINEAR, LOG }
data class StudioParameter(val id: Int, val name: String, val unit: String, val min: Int, val max: Int,
    val default: Int, val scale: StudioScale = StudioScale.LINEAR, val discrete: Boolean = false,
    val trigger: Boolean = false, val labels: List<String> = emptyList()) {
    fun fromHex(hex: Int): Int {
        require(hex in 0..255)
        return if (scale == StudioScale.LOG) (min * (max.toDouble() / min).pow(hex / 255.0)).roundToInt()
        else min + ((max - min) * hex + 127) / 255
    }
    fun display(value: Int): String = labels.getOrNull(value - min) ?: when (unit) {
        "note" -> org.thortracker.model.Songs.noteName(value, 0)
        "wave" -> if (value % 100 == 0) "${'A' + value / 100}" else "${'A' + value / 100} + ${value % 100}%"
        "" -> "$value"
        else -> "$value $unit"
    }
}
private fun p(id:Int, name:String, unit:String, min:Int, max:Int, default:Int, log:Boolean=false, trigger:Boolean=false) =
    StudioParameter(id,name,unit,min,max,default,if(log) StudioScale.LOG else StudioScale.LINEAR,trigger=trigger)
private fun choice(id:Int,name:String,default:Int,vararg names:String,trigger:Boolean=false) =
    StudioParameter(id,name,"",0,names.lastIndex,default,discrete=true,trigger=trigger,labels=names.toList())
private val catalog = mapOf(
    StudioKind.MODULAR to List(8) { i -> p(i,"Macro ${i+1}","",0,127,0) },
    StudioKind.MONO to listOf(choice(0,"Waveform",0,"Saw","Square"),p(1,"Cutoff","%",0,100,60),p(2,"Resonance","%",0,100,15),p(3,"Envelope","%",0,100,45),p(4,"Decay","%",0,100,40),p(5,"Accent","%",0,100,50),p(6,"Glide","%",0,100,15),p(7,"Tune","st",-24,24,0),p(8,"Level","%",0,100,75)),
    StudioKind.SAMPLER to listOf(p(0,"Root","note",0,115,48,trigger=true),p(1,"Fine","cent",-1200,1200,0,trigger=true),choice(2,"Direction",0,"Forward","Reverse",trigger=true),choice(3,"Loop",0,"Off","Forward loop",trigger=true),p(4,"Attack","ms",0,2000,2),p(5,"Release","ms",0,4000,80),p(6,"Cutoff","Hz",30,20000,20000,true),p(7,"Level","%",0,100,80)),
    StudioKind.FM to listOf(p(0,"Carrier","% ratio",25,1600,100),p(1,"Modulator","% ratio",25,1600,200),p(2,"Brightness","centirad",0,1200,200),p(3,"Feedback","%",0,100,0),p(4,"Attack","ms",0,2000,5),p(5,"Decay","ms",10,8000,450,true),p(6,"Sustain","%",0,100,50),p(7,"Release","ms",0,4000,120),p(8,"Level","%",0,100,75)),
    StudioKind.PERCUSSION to listOf(choice(0,"Model",0,"Kick","Snare","Hat",trigger=true),p(1,"Body","%",0,100,65),p(2,"Snap","%",0,100,45),p(3,"Tone","%",0,100,50),p(4,"Decay","ms",10,4000,350,true),p(5,"Level","%",0,100,80)),
    StudioKind.WAVETABLE to listOf(p(0,"Morph","wave",0,300,0),p(1,"Scan rate","cHz",0,800,0),p(2,"Scan depth","%",0,100,0),p(3,"Attack","ms",0,2000,5),p(4,"Release","ms",0,4000,120),p(5,"Level","%",0,100,70)),
    StudioKind.PLUCK to listOf(p(0,"Material","%",0,100,15),p(1,"Strike","%",0,100,45,trigger=true),p(2,"Damping","%",0,100,45),p(3,"Ring","ms",40,8000,750,true),p(4,"Release","ms",0,4000,100),p(5,"Level","%",0,100,75)),
    StudioKind.DELAY to listOf(choice(0,"Clock",1,"Milliseconds","1/8","Dotted 1/8","Triplet 1/8","1/4","Dotted 1/4","Triplet 1/4","1/16","Dotted 1/16","Triplet 1/16"),p(1,"Time","ms",1,2000,250,true),p(2,"Feedback","%",0,95,35),p(3,"Tone","Hz",30,20000,5000,true),p(4,"Wet","%",0,100,35)),
    StudioKind.DRIVE to listOf(p(0,"Drive","dB",0,36,12),p(1,"Tone","Hz",30,20000,5000,true),p(2,"Trim","dB",-24,6,-6)),
    StudioKind.CRUSHER to listOf(StudioParameter(0,"Depth","bits",2,16,8,discrete=true),p(1,"Rate","Hz",100,48000,8000,true),p(2,"Wet","%",0,100,100),p(3,"Trim","dB",-24,6,0)),
    StudioKind.REVERB to listOf(p(0,"Size","%",0,100,50),p(1,"Decay","ms",200,8000,2000,true),p(2,"Damping","Hz",30,20000,4500,true),p(3,"Pre-delay","ms",0,120,18),p(4,"Wet","%",0,100,30)),
    StudioKind.CHORUS to listOf(p(0,"Rate","cHz",5,800,65,true),p(1,"Depth","µs",0,10000,3000),p(2,"Wet","%",0,100,35))
)
val StudioKind.parameters get() = catalog.getValue(this)
private fun StudioKind.checkValues(values:Map<Int,Int>) { for ((id,v) in values) {
    val p = parameters.firstOrNull { it.id == id }; require(p != null && v in p.min..p.max) { "Invalid $name parameter $id: $v" }
} }
private fun StudioKind.read(values:Map<Int,Int>,id:Int) = values[id] ?: parameters.first { it.id == id }.default

@Serializable sealed interface StudioPatch { val name:String; val kind:StudioKind }
@Serializable data class StudioSlice(val id:Int,val start:Int,val end:Int) {
    init { require(id in 0..31 && start >= 0 && end > start) { "Invalid Studio slice ID or bounds" } }
}
@Serializable @SerialName("sampler") data class SamplerPatch(override val name:String="Sampler",val assetId:Int?=null,
    val start:Int=0,val end:Int=0,val loopStart:Int=0,val loopEnd:Int=0,val slices:List<StudioSlice> = emptyList(),
    val values:Map<Int,Int> = emptyMap(), val defaultSlice:Int?=null) : StudioPatch {
    override val kind get() = StudioKind.SAMPLER
    init { require(name.length<=22 && (assetId==null || assetId in 1..32)); kind.checkValues(values)
        require(defaultSlice == null || slices.any { it.id==defaultSlice }) { "Default slice does not exist" }
        require(start>=0 && (end==0 || end>start) && loopStart>=0 && (loopEnd==0 || loopEnd>loopStart)); require(slices.size<=32 && slices.map{it.id}.distinct().size==slices.size) }
}
@Serializable @SerialName("fm") data class FmPatch(override val name:String="FM",val values:Map<Int,Int> = emptyMap()):StudioPatch {
    override val kind get()=StudioKind.FM
    init { require(name.length<=22); kind.checkValues(values) }
}
@Serializable @SerialName("percussion") data class PercussionPatch(override val name:String="Percussion",val values:Map<Int,Int> = emptyMap()):StudioPatch {
    override val kind get()=StudioKind.PERCUSSION
    init { require(name.length<=22); kind.checkValues(values) }
}
@Serializable @SerialName("wavetable") data class WavetablePatch(override val name:String="Wavetable",
    val values:Map<Int,Int> = emptyMap(), val waves:StudioWaves = StudioWaves.initial()):StudioPatch {
    override val kind get()=StudioKind.WAVETABLE
    init { require(name.length<=22); kind.checkValues(values) }
}
@Serializable @SerialName("pluck") data class PluckPatch(override val name:String="Pluck",
    val values:Map<Int,Int> = emptyMap()):StudioPatch {
    override val kind get()=StudioKind.PLUCK
    init { require(name.length<=22); kind.checkValues(values) }
}
fun StudioPatch.value(id:Int):Int = when(this) {
    is MonoPatch -> when(id){0->waveform;1->cutoff;2->resonance;3->envelope;4->decay;5->accent;6->glide;7->tune;8->level;else->error("Unknown Mono parameter $id")}
    is SamplerPatch -> kind.read(values,id); is FmPatch -> kind.read(values,id); is PercussionPatch -> kind.read(values,id)
    is WavetablePatch -> kind.read(values,id); is PluckPatch -> kind.read(values,id)
    is ModularPatch -> macro(id)
}
fun StudioPatch.withValue(id:Int,value:Int):StudioPatch = when(this){
    is MonoPatch -> when(id){0->copy(waveform=value);1->copy(cutoff=value);2->copy(resonance=value);3->copy(envelope=value);4->copy(decay=value);5->copy(accent=value);6->copy(glide=value);7->copy(tune=value);8->copy(level=value);else->error("Unknown Mono parameter $id")}
    is SamplerPatch->copy(values=values+(id to value)); is FmPatch->copy(values=values+(id to value)); is PercussionPatch->copy(values=values+(id to value))
    is WavetablePatch -> copy(values=values+(id to value)); is PluckPatch -> copy(values=values+(id to value))
    is ModularPatch -> copy(values=values+(id to value))
}
@Serializable data class StudioInsert(val id:Int,val kind:StudioKind,val bypass:Boolean=false,val values:Map<Int,Int> = emptyMap()) {
    init { require(id>0 && !kind.instrument); kind.checkValues(values) }
    fun value(id:Int)=kind.read(values,id)
    fun withValue(id:Int,value:Int)=copy(values=values+(id to value))
}
@Serializable data class StudioBinding(val instance:Int,val kind:StudioKind,val parameter:Int) {
    init { require(instance>=0 && (instance==0)==kind.instrument && kind.parameters.any{it.id==parameter}) { "Invalid Studio binding" } }
}
@Serializable data class StudioBus(val inserts:List<StudioInsert> = emptyList(),val gain:Int=100) {
    init { require(inserts.size<=4 && gain in 0..200) }
}
