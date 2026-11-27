package org.thortracker.system.studio

import kotlin.math.abs
import kotlin.math.roundToInt
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.*

/** The active touch stays with the face that accepted it, even if an edit changes the patch type. */
internal class StudioInstrumentPainter(v:PaneView):Painter(v) {
    private val mono by lazy { StudioSoundPainter(v) }
    private val module by lazy { StudioModulePainter(v) }
    private var drawn:Painter?=null; private var active:Painter?=null
    override fun draw(c:Surface,area:Rect) {
        val face=if(StudioSoundView.patch(song,st) is MonoPatch) mono else module
        face.draw(c,area); drawn=face
    }
    override fun down(x:Float,y:Float):Boolean {
        val face=drawn ?: return false
        if(face.down(x,y)) { active=face; return true };return false
    }
    override fun move(x:Float,y:Float) { active?.move(x,y) }
    override fun up(x:Float,y:Float) { val a=active; active=null; a?.up(x,y) }
    override fun cancel() { val a=active; active=null; a?.cancel() }
}

internal class StudioModulePainter(v:PaneView):FmInstrumentPainter(v) {
    private val mode=Array(2){Rect()}; private val pages=Array(3){Rect()}; private val name=Rect(); private val listen=Rect()
    private val scope=Rect(); private val prev=Rect(); private val next=Rect(); private val field=Rect(); private val guide=Rect()
    private val previousSlot=Rect(); private val nextSlot=Rect()
    private val drag=PanelDrag<Pointer>(); private val cancelPointer:()->Unit={ cancel() }; private var heldPad=false
    private class Pointer(val st:ViewState,val revision:Int,val slot:Int,val lane:Int,val page:Int,val key:Int,
        val x:Float,val initial:Int,val unitsPerPixel:Float,val preview:Boolean,val tapEntry:Boolean,var version:Long)
    private var version=-1L; private var state:ViewState?=null; private var revision=0; private var slot=0; private var lane=0; private var selected=0; private var page=0; private var slice=0
    private var windowStart=0; private var windowEnd=1
    private var waveAsset:StudioPcm?=null; private var waveFirst=-1; private var waveLast=-1; private var waveCount=0
    private val lows=FloatArray(256); private val highs=FloatArray(256)
    private fun button(c:Surface,r:Rect,label:String,accent:Int,focus:Boolean=false,on:Boolean=false) {
        PanelWidgets.segment(ink,c,r,on,accent)
        val size=minOf(ink.unit,r.height()*.55f);ink.label(c,ink.fit(label,r.width()-ink.dp(4f),size,on),r.centerX(),r.top,r.height(),ink.palette.TEXT,on,size,Align.CENTER)
        if(focus) ink.outline(c,r,ink.palette.FOCUS,ink.dp(1.65f),ink.dp(2f))
    }
    override fun draw(c:Surface,area:Rect) {
        state=null
        val patch=StudioSoundView.patch(song,st); if(patch is MonoPatch) return
        val s=song.studio; val panel=StudioSoundView.panel(st); val advanced=panel.advanced; val p=ink.palette
        val kind=patch.kind; val accent=StudioGraphics.color(kind,p); val u=ink.unit; val pad=u*.44f
        val top=header(c,area,"Sound",kind.faceName)
        val bank=drawBank(c,area,top,StudioSound.SLOTS,p.channel(st.listenChannel),false){ id->s.patch(id)?.name?.ifEmpty { "(unnamed)" } }
        val left=bank.right+pad;val right=area.right-pad;val width=right-left;val bottom=area.bottom-pad;val h=bottom-top-pad
        if(width<1 || h<1) return
        val bar=minOf(u*2.8f,h*.14f);var y=top+pad
        val split=left+width*.50f
        mode[0].set(left,y,split-pad*.25f,y+bar); mode[1].set(split+pad*.25f,y,right,y+bar)
        for(i in 0..1) button(c,mode[i],if(i==0) "Sound" else "Advanced",accent,st.instField==StudioSoundView.MODE && advanced==(i==1),advanced==(i==1))
        y+=bar+pad*.45f
        val listenW=minOf(width*.32f,maxOf(ink.dp(48f),u*5.5f))
        val arrowW=minOf(bar,width*.11f)
        name.set(left,y,right-listenW-2*arrowW-pad,y+bar)
        previousSlot.set(name.right,y,name.right+arrowW,y+bar)
        nextSlot.set(previousSlot.right,y,previousSlot.right+arrowW,y+bar)
        listen.set(right-listenW,y,right,y+bar)
        val shownName=if(st.instField==StudioSoundView.NAME) patch.name.padEnd(st.textPos+1).let { it.substring(0,st.textPos)+"["+it[st.textPos]+"]"+it.substring(st.textPos+1) } else patch.name.ifEmpty { "(unnamed)" }
        button(c,name,shownName,accent,st.instField==StudioSoundView.NAME);button(c,listen,"Listen",accent,on=heldPad || ctl.auditionHeld)
        button(c,previousSlot,"‹",accent);button(c,nextSlot,"›",accent)
        y+=bar+pad*.48f
        for(r in pages) r.set(0f,0f,0f,0f)
        if(patch is SamplerPatch && !advanced) {
            val w=width/3f
            for(i in pages.indices) { pages[i].set(left+i*w,y,left+(i+1)*w-pad*.26f,y+bar);button(c,pages[i],StudioModuleControls.pageNames[i],accent,st.instField==2 && st.samplerPage==i,st.samplerPage==i) }; y+=bar+pad*.52f
        }
        val guideH=minOf(u*1.8f,h*.085f);guide.set(left,bottom-guideH,right,bottom)
        val paramH=minOf(u*5.6f,h*.23f);val fy=guide.top-pad*.5f-paramH
        scope.set(left,y,right,fy-pad*.6f)
        if(scope.height()>0f) {
            when {
                advanced -> drawExact(c,patch,accent)
                patch is SamplerPatch -> sampleScope(c,patch,accent)
                patch is FmPatch -> fmScope(c,patch,accent)
                patch is PercussionPatch -> drumScope(c,patch,accent)
            }
        }
        val arrow=minOf(paramH,width*.14f);prev.set(left,fy,left+arrow,fy+paramH);next.set(right-arrow,fy,right,fy+paramH)
        field.set(prev.right+pad*.5f,fy,next.left-pad*.5f,fy+paramH)
        button(c,prev,"‹",accent);button(c,next,"›",accent)
        val key=StudioModuleControls.field(patch,st)
        val title=if(key==null) (if(st.instField==StudioSoundView.NAME) "Name" else "Face") else StudioModuleControls.title(patch,key,advanced)
        val readout=if(key!=null) StudioModuleControls.display(patch,st,s,key) else if(st.instField==StudioSoundView.NAME) patch.name else if(advanced) "Advanced" else "Sound"
        ink.round(c,field,ink.dp(3f),p.BAR);ink.outline(c,field,if(key!=null) p.FOCUS else p.RULE,ink.dp(1.5f),ink.dp(3f))
        val textH=field.height()*.42f;val size=minOf(u,textH*.8f)
        ink.label(c,ink.fit(title,field.width()-pad*2,size,true),field.centerX(),fy,textH,p.TEXT,true,size,Align.CENTER)
        val spec=key?.takeIf { it<100 }?.let { k -> patch.kind.parameters.first { it.id==k } }
        val detail=if(spec!=null && advanced) "$readout  /  ${fxHex(spec.nearestByte(patch.value(spec.id)))}" else readout
        ink.mono(c,ink.fit(detail,field.width()-pad*2,size,true),field.centerX(),fy+textH,textH,accent,true,size,Align.CENTER)
        if(key!=null) {
            val r=StudioModuleControls.range(patch,st,s,key);val value=StudioModuleControls.value(patch,st,s,key)
            if(r!=null && value!=null && r.last>r.first) {
                val l=field.left+pad; val w=field.width()-2*pad;val cy=field.bottom-pad*.55f
                c.line(l,cy,l+w,cy,p.RULE,ink.dp(2f)); c.line(l,cy,l+w*(value-r.first)/(r.last-r.first),cy,accent,ink.dp(2.5f))
            }
        }
        val help=if(key==null) "Choose a sound slot with Up / Down." else StudioModuleControls.help(patch,key)
        val gs=minOf(u*.88f,guideH*.8f);ink.label(c,ink.fit(help,width,gs),left,guide.top,guideH,p.DIM,size=gs)
        version=session.documentVersion;state=st;revision=panel.revision;slot=st.instrument;lane=st.listenChannel;page=st.samplerPage;slice=st.samplerSlice;selected=st.instField
    }
    private var exactFirst=0; private var exactCount=0
    private fun drawExact(c:Surface,patch:StudioPatch,accent:Int) {
        val keys=StudioModuleControls.fields(patch,st);val p=ink.palette
        val rows=minOf(keys.size,maxOf(1,(scope.height()/maxOf(ink.dp(34f),ink.unit*2.4f)).toInt()))
        exactFirst=((st.instField-2).coerceAtLeast(0)/rows)*rows;exactCount=minOf(rows,keys.size-exactFirst)
        val rh=scope.height()/rows
        for(i in 0 until exactCount) {
            val index=exactFirst+i;val k=keys[index];val title=StudioModuleControls.title(patch,k,true);val value=StudioModuleControls.display(patch,st,song.studio,k)
            val y=scope.top+i*rh;val selected=st.instField==index+2;val size=minOf(ink.unit,rh*.65f);val pad=ink.unit*.48f
            c.fillRect(scope.left,y,scope.right,y+rh-ink.dp(1f),if(selected) p.CURSOR_FILL else p.LANE)
            ink.label(c,ink.fit(title,scope.width()*.51f-pad,size),scope.left+pad,y,rh,p.TEXT,selected,size)
            ink.mono(c,ink.fit(value,scope.width()*.46f-pad,size),scope.right-pad,y,rh,accent,selected,size,Align.RIGHT)
        }
        exactRowH=rh
    }
    private var exactRowH=1f
    private fun caption(c:Surface,text:String,accent:Int):Float {
        val h=minOf(ink.unit*1.6f,scope.height()*.28f);val pad=ink.unit*.5f
        val size=minOf(ink.unit,h*.82f);ink.label(c,ink.fit(text,scope.width()-2*pad,size),scope.left+pad,scope.top,h,accent,true,size)
        return scope.top+h
    }
    private fun sampleScope(c:Surface,p:SamplerPatch,accent:Int) {
        val a=StudioModuleControls.pcm(song.studio,p);val col=ink.palette
        ink.rect(c,scope,Palette.mix(col.BG,accent,.075f));val selected=StudioModuleControls.selected(p,st)
        val shape=st.samplerPage==2
        if(a==null) { val size=minOf(ink.unit,scope.height()*.6f);ink.label(c,ink.fit("Import WAV on Play",scope.width(),size),scope.centerX(),scope.top,scope.height(),col.TEXT,true,size,Align.CENTER);return }
        val h=caption(c,when(st.samplerPage){1->"${p.slices.size} regions  /  ${st.samplerZoom}×";2->"Amplitude envelope";else->"${a.sampleRate} Hz   ${if(a.channels==2) "Stereo" else "Mono"}"},accent)
        val pad=ink.unit*.43f;val l=scope.left+pad;val w=scope.width()-2*pad;val b=scope.bottom-pad;val height=b-h
        if(w<=0 || height<=0) return
        if(shape) {
            val attack=w*.42f*p.value(4)/2000;val release=w*.48f*p.value(5)/4000;val peak=b-height*(.2f+.76f*p.value(7)/100f)
            c.line(l,b,l+attack,peak,accent,ink.dp(2.3f));c.line(l+attack,peak,l+w-release,peak,accent,ink.dp(2.3f));c.line(l+w-release,peak,l+w,b,accent,ink.dp(2.3f));return
        }
        val zoom=if(st.samplerPage==1) st.samplerZoom else 1
        val span=(a.frames/zoom).coerceAtLeast(1)
        val focus=if(st.samplerPage==1 && selected!=null) when(StudioModuleControls.field(p,st)){StudioModuleControls.MARK_OUT->selected.end;StudioModuleControls.MARK_IN->selected.start;else->(selected.start+selected.end)/2} else p.start
        windowStart=(focus-span/2).coerceIn(0,a.frames-span);windowEnd=windowStart+span
        fun x(frame:Int)=l+w*(frame-windowStart).toFloat()/span
        val start=if(st.samplerPage==1 && selected!=null) selected.start else p.start
        val end=if(st.samplerPage==1 && selected!=null) selected.end else StudioModuleControls.end(p,a.frames)
        val l0=x(start).coerceIn(l,l+w);val r0=x(end).coerceIn(l,l+w)
        c.fillRect(l0,h,r0,b,Palette.mix(col.BG,accent,.19f));val mid=(h+b)*.5f
        c.line(l,mid,l+w,mid,col.RULE,ink.dp(1f));val count=minOf(256,span,maxOf(1,(w/ink.dp(2f)).toInt()))
        if(a!==waveAsset || windowStart!=waveFirst || windowEnd!=waveLast || waveCount!=count) {
            for(i in 0 until count) { val from=windowStart+(span.toLong()*i/count).toInt();val to=windowStart+(span.toLong()*(i+1)/count).toInt();lows[i]=a.waveformMin(from,to);highs[i]=a.waveformMax(from,to) }
            waveAsset=a;waveFirst=windowStart;waveLast=windowEnd;waveCount=count
        }
        val amp=height*.44f
        for(i in 0 until count) { val px=l+w*(i+.5f)/count; c.line(px,mid-highs[i]*amp,px,mid-lows[i]*amp,if(px>=l0 && px<=r0) accent else col.DIM,maxOf(ink.dp(1f),w/count)) }
        if(st.samplerPage==1) {
            val size=minOf(ink.unit*.84f,height*.28f);val sorted=p.slices
            for(s in sorted){ val px=x(s.start);if(px<l || px>=l+w) continue
                c.line(px,h,px,b,if(s.id==st.samplerSlice) col.FOCUS else col.DIM,ink.dp(if(s.id==st.samplerSlice) 2f else 1f))
                if(x(s.end)-px>=size*1.65f) ink.mono(c,fxHex(s.id),px+1,h,minOf(size*1.4f,height),col.TEXT,true,size)
            }
            if(selected!=null && x(selected.end) in l..l+w) c.line(x(selected.end),h,x(selected.end),b,col.FOCUS,ink.dp(2f))
        } else {c.line(l0,h,l0,b,col.FOCUS,ink.dp(1.4f));c.line(r0,h,r0,b,col.FOCUS,ink.dp(1.4f))}
    }
    private fun fmScope(c:Surface,p:FmPatch,accent:Int) {
        val col=ink.palette;ink.round(c,scope,ink.dp(4f),Palette.mix(col.BG,accent,.085f));val y=caption(c,"Harmonic ratio   →   Brightness",accent)
        val rad=minOf(scope.width()*.13f,(scope.bottom-y)*.40f);if(rad<1f) return
        val cy=(scope.bottom+y)/2;val x1=scope.left+scope.width()*.25f;val x2=scope.left+scope.width()*.75f
        c.line(x1+rad,cy,x2-rad,cy,accent,ink.dp(1.5f+p.value(2)*3f/1200))
        for(i in 0..1) {val cx=if(i==0)x1 else x2; c.fillCircle(cx,cy,rad,col.BG);c.strokeCircle(cx,cy,rad,if(i==0) accent else col.TEXT,ink.dp(2f));var last=cy
            for(j in 0..48) {val t=j/48f;val phase=if(i==0)t*p.value(1)/100f else t*p.value(0)/100f+StudioGraphics.sinCycle(t*p.value(1)/100f)*p.value(2)/628f
                val at=cy-rad*.42f*StudioGraphics.sinCycle(phase);if(j!=0)c.line(cx+rad*((j-1)/48f*1.65f-.825f),last,cx+rad*(t*1.65f-.825f),at,accent,ink.dp(1.5f));last=at }
        }
    }
    private fun drumScope(c:Surface,p:PercussionPatch,accent:Int) {
        val col=ink.palette;ink.round(c,scope,ink.dp(5f),Palette.mix(col.BAR,accent,.09f));val model=p.value(0)
        val y=caption(c,"${p.kind.parameters[0].display(model)}  /  strike pad",accent);val pad=ink.unit*.6f
        val w=scope.width()-2*pad;val h=scope.bottom-y-pad; if(w<1 || h<1)return
        val rad=minOf(w*.19f,h*.44f);val cx=scope.left+rad+pad;val cy=y+h*.5f
        c.fillCircle(cx,cy,rad,col.BG);c.strokeCircle(cx,cy,rad,accent,ink.dp(2f));c.strokeCircle(cx,cy,rad*(.24f+.54f*p.value(1)/100f),if(heldPad || ctl.auditionHeld) col.FOCUS else accent,ink.dp(2f))
        val lx=cx+rad+pad;val len=scope.right-pad-lx; val tail=.10f+.90f*p.value(4)/4000f
        var py=cy
        for(i in 1..80) {val t=i/80f;val env=(1-t/tail).coerceAtLeast(0f);val body=StudioGraphics.sinCycle(t*(4f+model*10f+p.value(3)*.05f));val snap=(((i*73+i*i*13)%53)/26f-1f)*(1f-t).let { it*it*it }*p.value(2)/100f
            val ny=cy-rad*.85f*env*(body*p.value(1)/100f+snap).coerceIn(-1f,1f); c.line(lx+len*(i-1)/80f,py,lx+len*t,ny,accent,ink.dp(1.7f));py=ny }
    }
    override fun down(x:Float,y:Float):Boolean {
        val panel=StudioSoundView.panel(st)
        if(st!==state || st.kind!=ViewKind.INSTRUMENT || version!=session.documentVersion || slot!=st.instrument || lane!=st.listenChannel || page!=st.samplerPage || slice!=st.samplerSlice || revision!=panel.revision || selected!=st.instField) return false
        val p=StudioSoundView.patch(song,st);if(p is MonoPatch) return false
        val preview=ctl.editHeld && !panel.editBlocked;StudioSoundView.blockHeldEdit(ctl)
        for(i in 0..1) if(mode[i].contains(x,y)){StudioSoundView.selectMode(ctl,i==1);return true}
        if(slotRect.contains(x,y)) {StudioSoundView.selectSlot(ctl,slotScroll+((y-slotTop)/slotH).toInt()+1);return true}
        if(previousSlot.contains(x,y) || nextSlot.contains(x,y)) {
            StudioSoundView.selectSlot(ctl,st.instrument+if(nextSlot.contains(x,y)) 1 else -1)
            return true
        }
        if(listen.contains(x,y)) { listenDown();return true }
        if(name.contains(x,y)) {StudioSoundView.selectField(ctl,StudioSoundView.NAME);StudioSoundView.requestEntry(ctl);return true}
        for(i in pages.indices) if(pages[i].width()>0 && pages[i].contains(x,y)){StudioModuleControls.selectPage(ctl,i);return true}
        if(prev.contains(x,y) || next.contains(x,y)) { val n=StudioModuleControls.fields(p,st).size+2;val f=(st.instField.coerceAtLeast(0)+if(next.contains(x,y))1 else -1).mod(n);StudioSoundView.selectField(ctl,if(f==0) StudioSoundView.MODE else f);return true }
        if(panel.advanced && scope.contains(x,y)) {
            val i=((y-scope.top)/exactRowH).toInt();if(i !in 0 until exactCount) return false
            StudioSoundView.selectField(ctl,exactFirst+i+2);StudioSoundView.requestEntry(ctl);return true
        }
        if(!panel.advanced && scope.contains(x,y)) {
            fun dragArtwork(key:Int, units:Float?=null):Boolean {
                val index=StudioModuleControls.fields(p,st).indexOf(key)
                if(index<0) return false
                StudioSoundView.selectField(ctl,index+2)
                return begin(p,key,x,scope.width()*.5f,preview,tapEntry=false,units=units)
            }
            when(p) {
                is FmPatch -> return dragArtwork(if(x<scope.centerX()) 1 else 2)
                is PercussionPatch -> {
                    if(x<scope.left+scope.width()*.42f) { listenDown(); return true }
                    return dragArtwork(4)
                }
                is SamplerPatch -> if(st.samplerPage==2) return dragArtwork(if(x<scope.centerX()) 4 else 5)
                else -> Unit
            }
            if(p is SamplerPatch && p.assetId!=null && st.samplerPage<=1) {
                val region=if(st.samplerPage==1) StudioModuleControls.selected(p,st) else null
                val a=StudioModuleControls.pcm(song.studio,p)
                if(a!=null && (st.samplerPage==0 || region!=null)) {
                    val left=scope.left+ink.unit*.43f; val width=scope.width()-ink.unit*.86f
                    val span=(windowEnd-windowStart).coerceAtLeast(1)
                    val x0=left+width*((region?.start ?: p.start)-windowStart).toFloat()/span
                    val x1=left+width*((region?.end ?: StudioModuleControls.end(p,a.frames))-windowStart).toFloat()/span
                    val nearStart=abs(x-x0)<=abs(x-x1)
                    if(minOf(abs(x-x0),abs(x-x1))<=ink.dp(12f) && width>0) {
                        val key=if(region!=null) { if(nearStart) StudioModuleControls.MARK_IN else StudioModuleControls.MARK_OUT }
                            else if(nearStart) StudioModuleControls.TRIM_IN else StudioModuleControls.TRIM_OUT
                        return dragArtwork(key,span/width)
                    }
                    if(st.samplerPage==0) { listenDown(); return true }
                }
            }
        }
        if(!panel.advanced && p is SamplerPatch && st.samplerPage==1 && scope.contains(x,y)) {
            val frame=windowStart+((x-scope.left-ink.unit*.43f)/(scope.width()-ink.unit*.86f)).coerceIn(0f,1f)*(windowEnd-windowStart)
            val s=p.slices.firstOrNull { frame>=it.start && frame<it.end }
            if(s!=null){ panel.invalidate();st.samplerSlice=s.id;listenDown() } else if(p.slices.isEmpty()) StudioModuleControls.activate(ctl,StudioModuleControls.CHOP)
            return true
        }
        if(!field.contains(x,y)) return false
        if(st.instField==StudioSoundView.MODE){ StudioSoundView.selectMode(ctl,!panel.advanced,true);return true }
        if(st.instField==StudioSoundView.NAME){ StudioSoundView.requestEntry(ctl);return true }
        val key=StudioModuleControls.field(p,st) ?: return false
        if(panel.advanced || !begin(p,key,x,field.width()*.72f,preview,tapEntry=true)) StudioModuleControls.activate(ctl,key)
        return true
    }
    private fun begin(p:StudioPatch,key:Int,x:Float,width:Float,preview:Boolean,tapEntry:Boolean,units:Float?=null):Boolean {
        val r=StudioModuleControls.range(p,st,song.studio,key) ?: return false
        val n=StudioModuleControls.value(p,st,song.studio,key) ?: return false
        val panel=StudioSoundView.panel(st)
        drag.begin(session,"Set ${StudioModuleControls.title(p,key,false)}",Pointer(st,panel.revision,st.instrument,
            st.listenChannel,st.samplerPage,key,x,n,units ?: ((r.last-r.first)/maxOf(width,1f)),preview,tapEntry,session.documentVersion))
        panel.cancelTouch=cancelPointer
        return true
    }
    private fun listenDown() { if(!ctl.auditionHeld) {StudioSoundView.audition(ctl);heldPad=true;StudioSoundView.panel(st).cancelTouch=cancelPointer} }
    override fun move(x:Float,y:Float) {
        val d=drag.live(session) ?: return
        val panel=StudioSoundView.panel(st);val p=StudioSoundView.patch(song,st)
        if(st!==d.st || st.kind!=ViewKind.INSTRUMENT || st.instrument!=d.slot || st.listenChannel!=d.lane || st.samplerPage!=d.page || panel.revision!=d.revision || d.version!=session.documentVersion || p is MonoPatch || StudioModuleControls.field(p,st)!=d.key) {cancel();return}
        val n=d.initial+((x-d.x)*d.unitsPerPixel).roundToInt()
        StudioModuleControls.set(ctl,d.key,n,d.preview);d.version=session.documentVersion
    }
    override fun up(x:Float,y:Float) {
        val d=drag.live(session)
        val tap=d!=null && d.tapEntry && abs(x-d.x)<ink.dp(4f) && session.documentVersion==d.version &&
            d.st===st && d.slot==st.instrument && d.revision==StudioSoundView.panel(st).revision &&
            d.initial==StudioModuleControls.value(StudioSoundView.patch(song,st),st,song.studio,d.key)
        cancel();if(tap && d!=null) StudioModuleControls.activate(ctl,d.key)
    }
    override fun cancel() {
        val panel=StudioSoundView.panel(drag.target?.st ?: st);if(panel.cancelTouch===cancelPointer)panel.cancelTouch=null
        drag.finish(session);if(heldPad) { heldPad=false;if(!ctl.auditionHeld) session.releaseAudition() }
    }
}
