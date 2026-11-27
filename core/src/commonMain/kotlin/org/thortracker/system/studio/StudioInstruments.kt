package org.thortracker.system.studio

import org.thortracker.editor.*
import org.thortracker.model.Song

/** Unassigned rows can inherit a slot across orders, but unrelated sampler slots own separate slice IDs. */
internal fun Song.removedSliceProblem(slot:Int, before:StudioPatch, after:StudioPatch?):String? {
    if(before !is SamplerPatch || before.slices.isEmpty()) return null
    val kept = (after as? SamplerPatch)?.slices.orEmpty()
    if (before.slices === kept) return null
    var missing = 0
    for (slice in before.slices) missing = missing or (1 shl slice.id)
    for (slice in kept) missing = missing and (1 shl slice.id).inv()
    if (missing == 0) return null
    val mayInherit = BooleanArray(studio.tracks.size) { ch ->
        slot == 1 || patterns.any { pattern -> pattern.tracks[ch].any { it.instrument == slot } }
    }
    for ((index,pattern) in patterns.withIndex()) for ((ch,track) in pattern.tracks.withIndex()) {
        if(studio.tracks[ch].target==StudioTarget.USB_MIDI || !mayInherit[ch]) continue
        var assigned = 0
        for((row,cell) in track.withIndex()) {
            if (cell.instrument != 0) assigned = cell.instrument
            if (assigned != 0 && assigned != slot) continue
            for(col in 0..cell.more.size) {
                val id=cell.effectParam(col)
                if(cell.effectCode(col)==13 && id in 0..31 && missing and (1 shl id) != 0) return "D${fxHex(id)} is used in pattern ${fxHex(index)}, track ${ch+1}, row ${fxHex(row)}. Clear or change that command before removing the slice."
            }
        }
    }
    return null
}

internal object StudioInstruments {
    fun rename(p:StudioPatch,name:String):StudioPatch = when(p) {
        is MonoPatch -> p.copy(name=name); is SamplerPatch -> p.copy(name=name)
        is FmPatch -> p.copy(name=name); is PercussionPatch -> p.copy(name=name)
        is WavetablePatch -> p.copy(name=name); is PluckPatch -> p.copy(name=name)
        is ModularPatch -> p.copy(name=name)
    }
    fun store(session:EditorSession,slot:Int,value:StudioPatch?,label:String):Boolean {
        val song=session.song; val current=song.studio.patch(slot) ?: return false
        song.removedSliceProblem(slot,current,value)?.let { session.note(it); return false }
        return session.apply(label) { it.copy(sound=it.studio.withPatch(slot,value)) }
    }
    fun library(session:EditorSession,menu:MenuContext,slot:Int, valid:()->Boolean = { true }) {
        val doc=session.documentVersion
        val current=session.song.studio.patch(slot) ?: return
        fun apply(next:StudioPatch) {
            if(!valid() || doc!=session.documentVersion) { menu.error("The instrument changed. Open presets again."); return }
            menu.menu("Replace ${fxHex(slot)} ${current.name}?",listOf("Use ${next.name}" to {
                if(valid() && doc==session.documentVersion) store(session,slot,next,"Load ${next.name}"); Unit
            }))
        }
        menu.menu("Instrument ${fxHex(slot)} · ${current.name}",StudioKind.entries.filter { it.instrument }.map { kind -> kind.faceName to {
            val presets=studioInstrumentPresets(kind)
            val init=when(kind) {
                StudioKind.MONO -> MonoPatch(); StudioKind.SAMPLER -> SamplerPatch()
                StudioKind.FM -> FmPatch(); StudioKind.PERCUSSION -> PercussionPatch()
                StudioKind.WAVETABLE -> WavetablePatch(); StudioKind.PLUCK -> PluckPatch()
                StudioKind.MODULAR -> presets.first()
                else -> error("Not an instrument: $kind")
            }
            menu.menu("${kind.faceName} instruments",buildList {
                if (kind == StudioKind.MODULAR) {
                    add("New Modular · ${init.name}" to { apply(init) })
                    for (i in 1 until presets.size) add(presets[i].name to { apply(presets[i]) })
                    add("Simple patch · unassigned macros" to { apply(ModularPatch()) })
                } else {
                    add("New ${kind.faceName}" to { apply(init) })
                    for (p in presets) add(p.name to { apply(p) })
                }
                if(kind==StudioKind.SAMPLER && current is SamplerPatch) add("Import WAV…" to { if(doc==session.documentVersion) importStudioSample(session,menu,slot) })
            })
        } })
    }
}

/** Fields in each face are a small physical control bank. The exact page exposes the entire catalogue. */
internal object StudioModuleControls {
    const val PAGE=100; const val LIBRARY=101; const val SAMPLE=102; const val TRIM_IN=103; const val TRIM_OUT=104
    const val LOOP_IN=105; const val LOOP_OUT=106; const val CHOP=107; const val SLICE=108; const val MARK_IN=109
    const val MARK_OUT=110; const val ZOOM=111; const val WRITE=112; const val DEFAULT=113
    const val SOURCE=114; const val POINT=115; const val HEIGHT=116
    val wavePages=listOf("Play","Draw")
    private val sources=listOf("A","B","C","D")
    private val wavePlay=intArrayOf(PAGE,0,1,2,3,4,5,LIBRARY)
    private val waveDraw=intArrayOf(PAGE,SOURCE,POINT,HEIGHT,LIBRARY)
    private val waveExact=intArrayOf(0,1,2,3,4,5,SOURCE,POINT,HEIGHT,LIBRARY)
    private val pluck=intArrayOf(0,1,2,3,4,5,LIBRARY)
    val pageNames=listOf("Play","Chop","Shape")
    private val play=intArrayOf(PAGE,SAMPLE,0,1,TRIM_IN,TRIM_OUT,2,7,LIBRARY)
    private val chop=intArrayOf(PAGE,SLICE,MARK_IN,MARK_OUT,ZOOM,CHOP,WRITE,DEFAULT)
    private val shape=intArrayOf(PAGE,4,5,6,3,LOOP_IN,LOOP_OUT,7)
    private val samplerExact=intArrayOf(SAMPLE,0,1,2,3,4,5,6,7,TRIM_IN,TRIM_OUT,LOOP_IN,LOOP_OUT,LIBRARY)
    private val fm=intArrayOf(2,1,6,3,4,5,8,LIBRARY); private val fmExact=intArrayOf(0,1,2,3,4,5,6,7,8,LIBRARY)
    private val drums=intArrayOf(0,1,2,4,3,5,LIBRARY)
    fun fields(p:StudioPatch,st:ViewState):IntArray = when(p) {
        is ModularPatch -> ModularControls.fields(st)
        is SamplerPatch -> if(StudioSoundView.panel(st).advanced) samplerExact else when(st.samplerPage){1->chop;2->shape;else->play}
        is FmPatch -> if(StudioSoundView.panel(st).advanced) fmExact else fm
        is WavetablePatch -> if(StudioSoundView.panel(st).advanced) waveExact else if(st.wavetablePage==1) waveDraw else wavePlay
        is PluckPatch -> pluck
        else -> drums
    }
    fun field(p:StudioPatch,st:ViewState)=fields(p,st).getOrNull(st.instField-2)
    fun title(p:StudioPatch,k:Int,advanced:Boolean):String = when(k) {
        PAGE->"Page";SAMPLE->"Recording";TRIM_IN->"Region start";TRIM_OUT->"Region end";LOOP_IN->"Loop start";LOOP_OUT->"Loop end"
        SOURCE->"Wave";POINT->"Point";HEIGHT->"Height"
        CHOP->"Slice tools";SLICE->"Slice";MARK_IN->"Left marker";MARK_OUT->"Right marker";ZOOM->"Zoom";WRITE->"Write Dxx";DEFAULT->"Default region";LIBRARY->"Instrument / presets"
        else-> if(!advanced && p is FmPatch && k==1) "Harmonic ratio" else if(!advanced && p is FmPatch && k==6) "Body" else p.kind.parameters.first { it.id==k }.name
    }
    fun selectPage(v:ViewController,page:Int) {
        val st=v.state; StudioSoundView.panel(st).invalidate(); StudioSoundView.blockHeldEdit(v)
        if(StudioSoundView.patch(v.song,st) is WavetablePatch) st.wavetablePage=page.coerceIn(0,1)
        else st.samplerPage=page.coerceIn(0,2)
        st.instField=2
    }
    fun selected(p:SamplerPatch,st:ViewState) = p.slices.firstOrNull { it.id==st.samplerSlice }
    fun pcm(s:StudioSound,p:SamplerPatch)=p.assetId?.let { s.assets[it] }
    fun end(p:SamplerPatch,frames:Int)=if(p.end==0) frames else p.end.coerceAtMost(frames)
    private fun loopStart(p:SamplerPatch)=maxOf(p.start,p.loopStart)
    private fun loopEnd(p:SamplerPatch,frames:Int)=if(p.loopEnd==0) end(p,frames) else minOf(p.loopEnd,end(p,frames))
    fun value(p:StudioPatch,st:ViewState,s:StudioSound,k:Int):Int? {
        if(k<100) return p.value(k)
        if(p is WavetablePatch) return when(k) { SOURCE->st.waveSource;POINT->st.wavePoint+1;HEIGHT->p.waves.point(st.waveSource,st.wavePoint);else->null }
        if(p !is SamplerPatch) return null
        val frames=pcm(s,p)?.frames ?: return null
        return when(k) {TRIM_IN->p.start;TRIM_OUT->end(p,frames);LOOP_IN->loopStart(p);LOOP_OUT->loopEnd(p,frames)
            MARK_IN->selected(p,st)?.start;MARK_OUT->selected(p,st)?.end;else->null }
    }
    fun range(p:StudioPatch,st:ViewState,s:StudioSound,k:Int):IntRange? {
        if(k<100) return p.kind.parameters.first { it.id==k }.let { it.min..it.max }
        if(p is WavetablePatch) return when(k) { SOURCE->0..3;POINT->1..64;HEIGHT->-128..127;else->null }
        if(p !is SamplerPatch) return null
        val frames=pcm(s,p)?.frames ?: return null
        val last=end(p,frames); val region=selected(p,st)
        return when(k) {
            TRIM_IN->0..minOf(last-1,p.slices.minOfOrNull { it.start } ?: last-1,if(p.loopStart>p.start) p.loopStart else last-1,loopEnd(p,frames)-1)
            TRIM_OUT->maxOf(p.start+1,p.slices.maxOfOrNull { it.end } ?: 1,if(p.loopEnd==last) 1 else p.loopEnd,loopStart(p)+1)..frames
            LOOP_IN->p.start until loopEnd(p,frames);LOOP_OUT->loopStart(p)+1..last
            MARK_IN->region?.let { p.start until it.end }; MARK_OUT->region?.let { it.start+1..last };else->null
        }?.takeUnless { it.isEmpty() }
    }
    fun display(p:StudioPatch,st:ViewState,s:StudioSound,k:Int):String {
        if(k<100) {
            val n=p.value(k)
            if(p is FmPatch && !StudioSoundView.panel(st).advanced) when(k) {
                1->{ val ratio=(100*n+p.value(0)/2)/p.value(0);return "${ratio/100}.${(ratio%100).toString().padStart(2,'0')} : 1" }
                2->return "${(100*n+600)/1200} %"
            }
            return p.kind.parameters.first { it.id==k }.display(n)
        }
        if(p is WavetablePatch) when(k) {
            PAGE->return wavePages[st.wavetablePage]; SOURCE->return sources[st.waveSource]
            POINT->return "${st.wavePoint+1} / 64"; HEIGHT->return p.waves.point(st.waveSource,st.wavePoint).toString()
        }
        if(p is SamplerPatch) when(k) {
            PAGE->return pageNames[st.samplerPage];SAMPLE->return p.assetId?.let { "Recording ${fxHex(it)}" } ?: "Import WAV…"
            SLICE->return selected(p,st)?.let { fxHex(it.id) } ?: "Create slices…";ZOOM->return "${st.samplerZoom}×";DEFAULT->return p.defaultSlice?.let { fxHex(it) } ?: "Whole region"
            WRITE->return selected(p,st)?.let { "D${fxHex(it.id)}" } ?: "Choose a slice"
        }
        return value(p,st,s,k)?.let { "$it frames" } ?: when(k){ LIBRARY,CHOP->"Open…";else->"Not set" }
    }
    fun help(p:StudioPatch,k:Int):String = when(k) {
        PAGE->if(p is WavetablePatch) "Play blends the waves; Draw edits one source." else "Play sets pitch; Chop chooses a region; Shape sets its contour."
        SOURCE->"Choose a source to edit without moving Morph."
        POINT->"Select point 1–64; hold Edit + Up / Down to skip eight."
        HEIGHT->"Set this point from −128 to 127. Drag the graph to paint."
        SAMPLE->"Import a WAV or reuse a recording from this project."
        MARK_IN,MARK_OUT->"Moving a marker keeps its Dxx identity."
        TRIM_IN,TRIM_OUT->"Playback region in source frames; the end is exclusive."
        LOOP_IN,LOOP_OUT->"Forward-loop points inside the playback region."
        CHOP->"Divide the region or add and remove stable slice IDs."
        SLICE,WRITE->"Dxx selects the next attack; notes still set pitch."
        DEFAULT->"Starting region when switching to this instrument."
        ZOOM->"Magnify the waveform around the selected marker."
        LIBRARY->"Choose an instrument type or load a preset into this slot."
        in 0..5 -> when(p) {
            is WavetablePatch -> when(k) {
                0->"Blend between the four drawn sources."
                1->"Scan speed. Set to zero for a static blend."
                2->"Scan distance either side of Morph, bounded by A and D."
                3->"Time to reach full level on attack."
                4->"Fade time after note release."
                else->"Output level before track effects."
            }
            is PluckPatch -> when(k) {
                0->"Stretch the tuning of the two upper resonances."
                1->"Weight of upper modes on the next strike."
                2->"How quickly the upper resonances die away."
                3->"Time for the root resonance to decay by 60 dB."
                4->"Fade time after note release."
                else->"Output level before track effects."
            }
            else->voiceHelp(p,k)
        }
        else->voiceHelp(p,k)
    }
    private fun voiceHelp(p:StudioPatch,k:Int):String =
        when(p.kind){StudioKind.FM->when(k){1->"Multiplier of the modulation oscillator.";2->"How far the modulator bends the carrier.";3->"Feed the modulator back into itself.";else->"Edit the stored operator envelope or level."};StudioKind.PERCUSSION->when(k){0->"Kick, snare and metallic hat; notes set pitch.";1->"Weight of the ringing drum body.";2->"Amount of attack transient.";else->"Shape the pitched drum voice."};else->if(p.kind.parameters.first { it.id==k }.trigger) "Takes effect at the next note attack." else "Shapes the sample while it sounds."}
    fun set(v:ViewController,k:Int,n:Int,preview:Boolean) {
        if(StudioSoundView.patch(v.song,v.state) is ModularPatch) { ModularControls.set(v,k,n); return }
        val st=v.state; val s=v.song.studio; val p=s.patches[st.instrument-1]; val r=range(p,st,s,k) ?: return
        val amount=n.coerceIn(r); if(amount==value(p,st,s,k)) return
        val next=if(k<100) p.withValue(k,amount) else if(p is WavetablePatch) when(k) {
            SOURCE->{ st.waveSource=amount;return }
            POINT->{ st.wavePoint=amount-1;return }
            HEIGHT->p.copy(waves=p.waves.withPoint(st.waveSource,st.wavePoint,amount))
            else->return
        } else {
            p as SamplerPatch
            val frames=pcm(s,p)?.frames ?: return
            when(k){
                TRIM_IN->p.copy(start=amount,loopStart=if(p.loopStart<=p.start) amount else p.loopStart)
                TRIM_OUT->p.copy(end=amount,loopEnd=if(p.loopEnd==end(p,frames)) amount else p.loopEnd)
                LOOP_IN->p.copy(loopStart=amount);LOOP_OUT->p.copy(loopEnd=amount)
                MARK_IN,MARK_OUT->p.copy(slices=p.slices.map { if(it.id!=st.samplerSlice) it else if(k==MARK_IN) it.copy(start=amount) else it.copy(end=amount) });else->return }
        }
        // New voices follow live document updates; re-audition would reset scan phase or re-strike a ringing mode.
        if(StudioInstruments.store(v.session,st.instrument,next,"Set ${title(p,k,false)}") &&
            p !is WavetablePatch && p !is PluckPatch && (v.auditionHeld || (preview && v.editHeld))) StudioSoundView.audition(v)
    }
    fun adjust(v:ViewController,k:Int,dx:Int,dy:Int) {
        if(StudioSoundView.patch(v.song,v.state) is ModularPatch) { ModularControls.adjust(v,k,dx,dy); return }
        val st=v.state; val s=v.song.studio; val p=s.patches[st.instrument-1]; val delta=if(dx!=0) dx else dy
        when(k) {
            PAGE->if(p is WavetablePatch) selectPage(v,(st.wavetablePage+delta).mod(2)) else selectPage(v,(st.samplerPage+delta).mod(3))
            SLICE->if(p is SamplerPatch && p.slices.isNotEmpty()) {
                val i=p.slices.indexOfFirst { it.id==st.samplerSlice }; st.samplerSlice=p.slices[(i+delta).mod(p.slices.size)].id; if(v.auditionHeld) StudioSoundView.audition(v)
            }
            ZOOM->st.samplerZoom=(if(delta<0) st.samplerZoom/2 else st.samplerZoom*2).coerceIn(1,32)
            else->{ val n=value(p,st,s,k) ?: return; val r=range(p,st,s,k) ?: return
                val step=if(dy==0) 1 else if(k==POINT) 8 else maxOf(1,(r.last-r.first)/16); set(v,k,n+delta*step,true) }
        }
    }
    fun activate(v:ViewController,k:Int) {
        if(StudioSoundView.patch(v.song,v.state) is ModularPatch) { ModularControls.activate(v,k); return }
        val st=v.state; val s=v.song.studio; val p=s.patches[st.instrument-1]; val version=v.session.documentVersion
        val slot=st.instrument; val revision=StudioSoundView.panel(st).revision; val field=st.instField
        val source=st.waveSource; val point=st.wavePoint
        fun valid()=v.state===st && st.kind==ViewKind.INSTRUMENT && st.instrument==slot && v.session.documentVersion==version &&
            st.instField==field && StudioSoundView.panel(st).revision==revision &&
            (p !is WavetablePatch || (st.waveSource==source && st.wavePoint==point))
        v.session.requestMenu { m ->
            when(k) {
                LIBRARY->StudioInstruments.library(v.session,m,slot)
                PAGE->if(p is WavetablePatch) m.menu("Wavetable page",wavePages.mapIndexed { i,label->label to { if(valid()) selectPage(v,i) } },st.wavetablePage)
                    else m.menu("Sampler page",pageNames.mapIndexed { i,label -> label to { if(valid()) selectPage(v,i) } },st.samplerPage)
                SOURCE->m.menu("Edit source wave",sources.mapIndexed { i,label->label to { if(valid()) set(v,k,i,false) } },st.waveSource)
                SAMPLE->if(p is SamplerPatch) m.menu("Recording",buildList {
                    add("Import WAV…" to { if(valid()) importStudioSample(v.session,m,slot) })
                    for(id in s.assets.ids.sorted()) { val a=s.assets[id]!!
                        add("Use ${fxHex(id)} · ${a.frames} frames · ${a.channels} ch" to { if(valid() && p.assetId!=id) {
                            m.menu("Replace recording and region markers?",listOf("Use recording ${fxHex(id)}" to { if(valid()) StudioInstruments.store(v.session,slot,p.copy(assetId=id,start=0,end=a.frames,loopStart=0,loopEnd=a.frames,slices=emptyList(),defaultSlice=null),"Reuse recording"); Unit }))
                        } })
                    }
                })
                CHOP->if(p is SamplerPatch) sliceTools(v,m,p,::valid)
                SLICE->if(p is SamplerPatch) { if(p.slices.isEmpty()) sliceTools(v,m,p,::valid) else m.menu("Select slice",p.slices.map { slice->"${fxHex(slice.id)} · ${slice.start}–${slice.end}" to { if(valid()) st.samplerSlice=slice.id } },p.slices.indexOfFirst { it.id==st.samplerSlice }.coerceAtLeast(0)) }
                DEFAULT->if(p is SamplerPatch) m.menu("Default attack region",buildList {
                    add("Whole region" to { if(valid()) StudioInstruments.store(v.session,slot,p.copy(defaultSlice=null),"Set default region");Unit }); for(slice in p.slices) add("Slice ${fxHex(slice.id)}" to { if(valid()) StudioInstruments.store(v.session,slot,p.copy(defaultSlice=slice.id),"Set default slice");Unit }) })
                ZOOM->m.menu("Waveform zoom",listOf(1,2,4,8,16,32).map { z->"$z×" to { if(valid()) st.samplerZoom=z } })
                WRITE->if(p is SamplerPatch && selected(p,st)!=null) StudioAssignments.writeSlice(v.session,m,st,st.samplerSlice) else m.error("Create and select a slice first.")
                else->{ val r=range(p,st,s,k); val n=value(p,st,s,k); val spec=p.kind.parameters.firstOrNull { it.id==k }
                    if(r==null || n==null) m.error("Import a recording and select a valid region first.")
                    else studioExact(m,title(p,k,true),n,r,spec?.labels.orEmpty()) { if(valid()) set(v,k,it,false) } }
            }
        }
    }
    private fun sliceTools(v:ViewController,m:MenuContext,p:SamplerPatch,valid:()->Boolean) {
        val st=v.state; val a=pcm(v.song.studio,p)
        if(a==null) { m.error("Import a WAV on Play before creating slices."); return }
        val end=end(p,a.frames); val length=end-p.start
        fun put(slices:List<StudioSlice>,id:Int,label:String) {
            if(!valid()) return
            val next=p.copy(slices=slices,defaultSlice=p.defaultSlice?.takeIf { d->slices.any { it.id==d } })
            v.song.removedSliceProblem(st.instrument,p,next)?.let { m.error(it); return }
            StudioInstruments.store(v.session,st.instrument,next,label); st.samplerSlice=id
        }
        m.menu("Slice tools",buildList {
            if(length>=2) add("Equal divisions…" to { m.menu("Divide ${p.start}–$end frames",listOf(2,4,8,16,32).filter { it<=length }.map { count->
                "$count slices" to {
                    val ids=p.slices.sortedBy { it.start }.map { it.id }; val free=(0..31).filterNot { it in ids }; val keys=ids+free
                    fun applyDivision() { val slices=List(count){i->StudioSlice(keys[i],p.start+(length.toLong()*i/count).toInt(),p.start+(length.toLong()*(i+1)/count).toInt())};put(slices,slices.first().id,"Divide sample") }
                    if(p.slices.isEmpty()) applyDivision() else m.menu("Replace the current marker layout?",listOf("Divide into $count" to ::applyDivision))
                }
            }) })
            if(p.slices.size<32) add("Add region" to { val id=(0..31).first { n->p.slices.none { it.id==n } }; put(p.slices+StudioSlice(id,p.start,end),id,"Add slice") })
            val slice=selected(p,st)
            if(slice!=null) add("Remove slice ${fxHex(slice.id)}…" to { m.menu("Remove slice ${fxHex(slice.id)}?",listOf("Remove marker pair" to { val next=p.slices.filterNot { it.id==slice.id };put(next,next.firstOrNull()?.id ?: 0,"Remove slice") })) })
        })
    }
}
