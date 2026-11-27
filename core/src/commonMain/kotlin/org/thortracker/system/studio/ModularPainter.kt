package org.thortracker.system.studio

import kotlin.math.abs
import kotlin.math.roundToInt
import org.thortracker.editor.GestureToken
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.*

/** Eight ribbons on the front; on the back, inspect one sender and one receiver at a time. No runtime meters. */
internal class ModularPainter(v: PaneView) : FmInstrumentPainter(v) {
    private val modes = Array(2) { Rect() }
    private val name = Rect(); private val prev = Rect(); private val next = Rect(); private val listen = Rect()
    private val ribbons = Array(8) { Rect() }; private val presets = Rect()
    private val send = Rect(); private val receive = Rect(); private val control = Rect()
    private val map = Rect(); private val cables = Rect(); private val macros = Rect(); private val seed = Rect()
    private val outputs = Array(ModularKind.entries.maxOf { it.outputs.size }) { Rect() }
    private val inputs = Array(ModularKind.entries.maxOf { it.inputs.size }) { Rect() }
    private val indexArea = Rect(); private var indexStart = 0; private var indexRows = 0
    private var drawn: ModularCapture? = null
    private var indexed: ModularGraph? = null
    private var neighbors: List<String> = emptyList()
    private val cancelPointer: () -> Unit = { cancel() }
    private var owner: ModularCapture? = null
    private var heldPad = false
    private class Fader(val macro: Int,val initial: Int,val x: Float,val scale: Float,var moved: Boolean = false)
    private val fader = PanelDrag<Fader>()
    private var jack = -1; private var fingerX = 0f; private var fingerY = 0f
    private var cableToken: GestureToken? = null
    private var downX = 0f; private var downY = 0f; private var draggedCable = false
    private val accent: Int get() = ink.palette.channel(0)
    private fun focus(key: Int) = ModularControls.fields(st).getOrNull(st.instField-2) == key
    private fun box(c: Surface,r: Rect,text: String,selected: Boolean=false,on: Boolean=false) {
        PanelWidgets.segment(ink,c,r,on,accent)
        val sz = minOf(ink.unit,r.height()*.53f)
        ink.label(c,ink.fit(text,r.width()-ink.unit*.5f,sz,on || selected),r.centerX(),r.top,r.height(),ink.palette.TEXT,on || selected,sz,Align.CENTER)
        if (selected) ink.outline(c,r,cursorColor(),ink.dp(1.6f),ink.dp(2f))
    }
    override fun draw(c: Surface, area: Rect) {
        val patch = StudioSoundView.patch(song,st) as? ModularPatch ?: return
        val top = header(c,area,"Sound","Modular")
        val bank = drawBank(c,area,top,StudioSound.SLOTS,ink.palette.channel(st.listenChannel),false) { song.studio.patch(it)?.name?.ifEmpty { "(unnamed)" } }
        val u = ink.unit; val gap = u*.38f
        val l = bank.right+gap; val r = area.right-gap; val b = area.bottom-gap
        if (r<=l || b<=top+gap) { drawn=null; return }
        val h = b-top-gap; val w = r-l; val bar = minOf(u*2.75f,h*.118f)
        val split = (l+r)*.5f; var y = top+gap
        val build = StudioSoundView.panel(st).advanced
        modes[0].set(l,y,split-gap*.25f,y+bar); modes[1].set(split+gap*.25f,y,r,y+bar)
        box(c,modes[0],"Performance",st.instField == StudioSoundView.MODE && !build,!build)
        box(c,modes[1],"Build",st.instField == StudioSoundView.MODE && build,build)
        y += bar+gap*.6f
        val listenW = minOf(w*.24f,u*6f); val arrow = minOf(w*.105f,bar)
        listen.set(r-listenW,y,r,y+bar); next.set(listen.left-gap-arrow,y,listen.left-gap,y+bar)
        prev.set(next.left-arrow,y,next.left,y+bar); name.set(l,y,prev.left-gap*.4f,y+bar)
        val shown = if (st.instField == StudioSoundView.NAME) patch.name.padEnd(st.textPos+1).let {
            it.substring(0,st.textPos)+"["+it[st.textPos]+"]"+it.substring(st.textPos+1)
        } else patch.name.ifEmpty { "(unnamed)" }
        box(c,name,shown,st.instField == StudioSoundView.NAME); box(c,prev,"‹"); box(c,next,"›")
        box(c,listen,"Listen",on=heldPad || ctl.auditionHeld)
        y += bar+gap
        if (build) bay(c,patch,l,y,r,b,bar,gap) else front(c,patch,l,y,r,b,bar,gap)
        if (drawn?.valid(ctl) != true) drawn=ModularCapture(ctl)
    }
    private fun front(c: Surface,patch: ModularPatch,l: Float,t: Float,r: Float,b: Float,bar: Float,gap: Float) {
        val u=ink.unit; val w=r-l; val p=ink.palette
        val foot=b-bar; presets.set(r-minOf(w*.36f,u*9f),foot,r,b)
        box(c,presets,"Presets",focus(ModularControls.LIBRARY))
        val selected=st.instField-2
        val hint=if(selected in 0..7 && patch.graph.macros[selected].targets.isEmpty()) "Unbound · tap to assign" else "Drag to adjust · tap for FX"
        val sz=minOf(u*.93f,bar*.50f)
        ink.label(c,ink.fit(hint,presets.left-l-gap,sz),l,foot,bar,p.DIM,size=sz)
        val columns=if (w < u*25 && foot-t > u*22) 1 else 2
        val rows=8/columns; val cw=(w-gap*(columns-1))/columns; val rh=(foot-gap-t)/rows
        for (i in ribbons.indices) {
            val col=i/rows; val row=i%rows; val x=l+col*(cw+gap); val y=t+row*rh
            val a=ribbons[i]; a.set(x,y,x+cw,y+rh-gap*.5f)
            ink.rect(c,a,if (focus(i)) p.CURSOR_FILL else p.LANE)
            val value=patch.value(i); val color=p.channel(i); val pad=minOf(u*.65f,cw*.055f)
            val size=minOf(u*1.13f,a.height()*.34f); val th=a.height()*.58f
            val vx=a.right-pad; val valueW=ink.monoWidth("127",size,true)+pad
            ink.label(c,ink.fit(patch.graph.macros[i].name,cw-2*pad-valueW,size,true),x+pad,y,th,p.TEXT,true,size)
            ink.mono(c,value.toString(),vx,y,th,color,true,size,Align.RIGHT)
            val x0=x+pad; val x1=vx; val railY=y+a.height()*.75f; val lineH=minOf(a.height()*.12f,u*.55f)
            c.fillRect(x0,railY-lineH*.5f,x1,railY+lineH*.5f,p.RULE)
            val pos=x0+(x1-x0)*value/127f
            c.fillRect(x0,railY-lineH*.5f,pos,railY+lineH*.5f,color)
            for (k in 1..3) { val tx=x0+(x1-x0)*k/4; c.line(tx,railY+lineH,tx,railY+lineH*1.5f,p.DIM,ink.dp(1f)) }
            val half=minOf(u*.21f,(x1-x0)*.02f)
            c.fillRect(pos-half,railY-lineH,pos+half,railY+lineH,if (focus(i)) cursorColor() else color)
            if (focus(i)) ink.outline(c,a,cursorColor(),ink.dp(1.7f))
        }
    }
    private fun bay(c: Surface,patch: ModularPatch,left: Float,t: Float,r: Float,b: Float,bar: Float,gap: Float) {
        val u=ink.unit; val g=patch.graph; val p=ink.palette
        val from=g.nodes.first { it.id == st.modularSource }; val to=g.nodes.first { it.id == st.modularDestination }
        var l=left
        indexRows=0
        if (r-l >= u*48 && b-t >= u*13) {
            val w=minOf((r-l)*.26f,u*15f); indexArea.set(l,t,l+w,b)
            drawIndex(c,g,from); l+=w+gap
        }
        val w=r-l; val mid=(r+l)*.5f
        send.set(l,t,mid-gap*.5f,t+bar); receive.set(mid+gap*.5f,t,r,t+bar)
        nodeHeader(c,send,"Send from",from,focus(ModularControls.SOURCE)); nodeHeader(c,receive,"Receive at",to,focus(ModularControls.DESTINATION))
        val actionH=minOf(u*3f,(b-t)*.18f); val last=b-actionH
        val third=w/3
        map.set(l,last,l+third-gap*.33f,b); cables.set(l+third+gap*.33f,last,r-third-gap*.33f,b); macros.set(r-third+gap*.33f,last,r,b)
        box(c,map,"Patch map",focus(ModularControls.OVERVIEW)); box(c,cables,"Cables · ${g.connections.size}",focus(ModularControls.CABLES)); box(c,macros,"Bindings",focus(ModularControls.MACROS))
        val tools=last-gap-actionH
        control.set(l,tools,l+w*.57f-gap*.5f,last-gap); seed.set(control.right+gap,tools,r-w*.23f-gap*.5f,last-gap); presets.set(seed.right+gap,tools,r,last-gap)
        val spec=from.kind.parameters.firstOrNull { it.id == st.modularParameter }
        val ownerName=spec?.let { s -> g.macros.firstOrNull { m -> m.targets.any { it.node == from.id && it.parameter == s.id } }?.name }
        val desc=when { spec == null -> "No controls"; ownerName != null -> "${spec.name} ← $ownerName"; else -> "${spec.name}  ${spec.display(from.value(spec.id))}" }
        box(c,control,desc,focus(ModularControls.PARAMETER)); box(c,seed,"Seed",focus(ModularControls.SEED)); box(c,presets,"Presets",focus(ModularControls.LIBRARY))
        val bayTop=send.bottom+gap; val bayBottom=tools-gap
        val oh=from.kind.outputs; val ih=to.kind.inputs
        val gutter=w*.17f; val x0=mid-gutter*.5f; val x1=mid+gutter*.5f
        val rows=maxOf(1,oh.size,ih.size); val rowH=minOf(u*4.6f,(bayBottom-bayTop)/rows)
        fun place(rects: Array<Rect>, count: Int, x: Float, end: Float) {
            val start=(bayTop+bayBottom-rowH*count)*.5f
            for (i in 0 until count) rects[i].set(x,start+i*rowH,end,start+(i+1)*rowH-gap*.2f)
        }
        place(outputs,oh.size,l,x0); place(inputs,ih.size,x1,r)
        c.fillRect(l,bayTop,x0,bayBottom,p.LANE); c.fillRect(x1,bayTop,r,bayBottom,p.LANE)
        for (cable in g.connections) if (cable.source==from.id && cable.destination==to.id) {
            val a=oh.indexOfFirst { it.id == cable.output }; val d=ih.indexOfFirst { it.id == cable.input }
            if (a>=0 && d>=0) drawCable(c,jackX(outputs[a],true),outputs[a].centerY(),jackX(inputs[d],false),inputs[d].centerY(),portColor(oh[a]))
        }
        val dragging=jack.takeIf { it in oh.indices && owner?.valid(ctl) == true }
        if (dragging != null) drawCable(c,jackX(outputs[dragging],true),outputs[dragging].centerY(),fingerX.coerceIn(l,r),fingerY.coerceIn(bayTop,bayBottom),portColor(oh[dragging]))
        for (i in oh.indices) port(c,outputs[i],oh[i],true,false)
        for (i in ih.indices) port(c,inputs[i],ih[i],false,dragging!=null && !ih[i].type.accepts(oh[dragging].type))
        fun empty(text: String, x: Float) { val sz=minOf(u,(bayBottom-bayTop)*.55f); ink.label(c,ink.fit(text,x0-l,sz),x,bayTop,bayBottom-bayTop,p.DIM,size=sz,align=Align.CENTER) }
        if (oh.isEmpty()) empty("No outputs",(l+x0)*.5f)
        if (ih.isEmpty()) empty("No inputs",(x1+r)*.5f)
    }
    private fun nodeHeader(c: Surface,r: Rect,role: String,node: ModularNode,focus: Boolean) {
        val size=minOf(ink.unit,r.height()*.43f); val pad=ink.unit*.45f; val w=r.width()-2*pad
        PanelWidgets.segment(ink,c,r,false,accent)
        ink.label(c,role,r.left+pad,r.top,r.height()*.44f,ink.palette.DIM,size=size*.8f)
        ink.label(c,ink.fit(node.caption,w,size,true),r.left+pad,r.top+r.height()*.4f,r.height()*.60f,ink.palette.TEXT,true,size)
        if (focus) ink.outline(c,r,cursorColor(),ink.dp(1.6f))
    }
    private fun drawIndex(c: Surface,g: ModularGraph,from: ModularNode) {
        if (indexed !== g) { neighbors=g.nodes.map { ModularControls.neighbors(g,it.id) }; indexed=g }
        val a=indexArea; val h=ink.unit*1.7f; val usable=a.height()-h
        indexRows=minOf(g.nodes.size,maxOf(1,(usable/(ink.unit*3f)).toInt())); val pos=g.nodes.indexOf(from)
        indexStart=(pos-indexRows/2).coerceIn(0,g.nodes.size-indexRows)
        ink.rect(c,a,ink.palette.LANE)
        ink.label(c,"Patch map",a.left+ink.unit*.4f,a.top,h,ink.palette.DIM,true,size=ink.unit*.9f)
        val rowH=usable/indexRows; val pad=ink.unit*.4f; val w=a.width()-2*pad
        for (i in 0 until indexRows) {
            val idx=indexStart+i; val y=a.top+h+i*rowH; val n=g.nodes[idx]; val on=n.id==from.id
            if (on) c.fillRect(a.left,y,a.right,y+rowH,ink.palette.CURSOR_FILL)
            ink.label(c,ink.fit(n.caption,w,ink.unit,on),a.left+pad,y,rowH*.52f,ink.palette.TEXT,on)
            ink.mono(c,ink.fit(neighbors[idx],w,ink.unit*.85f),a.left+pad,y+rowH*.49f,rowH*.5f,if(on) accent else ink.palette.DIM,size=ink.unit*.85f)
        }
    }
    private fun portColor(p: ModularPort) = ink.palette.channel(when (p.type) {
        ModularPortType.AUDIO->0; ModularPortType.MOD->2; ModularPortType.PITCH->1; ModularPortType.GATE->5; ModularPortType.EVENT->3
    })
    private fun jackX(r: Rect,output: Boolean): Float { val inset=minOf(r.width()*.13f,ink.unit*.9f); return if(output) r.right-inset else r.left+inset }
    private fun port(c: Surface,r: Rect,port: ModularPort,output: Boolean,muted: Boolean) {
        val p=ink.palette; val cx=jackX(r,output); val cy=r.centerY(); val radius=minOf(ink.unit*.46f,r.height()*.23f,r.width()*.08f)
        val color=if(muted) p.FAINT else portColor(port)
        c.fillCircle(cx,cy,radius,p.BG); c.strokeCircle(cx,cy,radius,color,ink.dp(1.6f))
        if (output) c.fillCircle(cx,cy,radius*.42f,color)
        val x=if(output) r.left+ink.unit*.4f else cx+2*radius; val width=if(output) cx-2*radius-x else r.right-ink.unit*.4f-x
        val compact=r.height()<ink.unit*1.8f
        val size=minOf(ink.unit,r.height()*(if (compact) .83f else .46f))
        val text=if(compact && !port.name.equals(port.type.caption,ignoreCase=true)) "${port.name} / ${port.type.caption}" else port.name
        ink.label(c,ink.fit(text,width,size,true),x,r.top,if(compact) r.height() else r.height()*.59f,if(muted) p.DIM else p.TEXT,true,size)
        if(!compact) ink.label(c,ink.fit(port.type.caption,width,size*.78f),x,r.top+r.height()*.53f,r.height()*.46f,color,size=size*.78f)
    }
    private fun drawCable(c: Surface,x: Float,y: Float,x1: Float,y1: Float,color: Int) {
        val bend=(x1-x)*.26f; val stroke=ink.dp(2f)
        c.line(x,y,x+bend,y,color,stroke); c.line(x+bend,y,x1-bend,y1,color,stroke); c.line(x1-bend,y1,x1,y1,color,stroke)
    }
    override fun down(x: Float,y: Float): Boolean {
        if (drawn?.valid(ctl) != true) return false
        val patch=StudioSoundView.patch(song,st) as? ModularPatch ?: return false
        val panel=StudioSoundView.panel(st)
        StudioSoundView.blockHeldEdit(ctl)
        for (i in modes.indices) if(modes[i].contains(x,y)) { StudioSoundView.selectMode(ctl,i==1); return true }
        if (slotRect.contains(x,y)) { StudioSoundView.selectSlot(ctl,slotScroll+((y-slotTop)/slotH).toInt()+1); return true }
        if (prev.contains(x,y) || next.contains(x,y)) { StudioSoundView.selectSlot(ctl,st.instrument+if(prev.contains(x,y)) -1 else 1); return true }
        if (name.contains(x,y)) { StudioSoundView.selectField(ctl,StudioSoundView.NAME); StudioSoundView.requestEntry(ctl); return true }
        if (listen.contains(x,y)) {
            if (!ctl.auditionHeld) { StudioSoundView.audition(ctl); heldPad=true; capture() }; return true
        }
        fun open(key: Int): Boolean { ModularControls.focus(ctl,key); ModularControls.activate(ctl,key); return true }
        if (presets.contains(x,y)) return open(ModularControls.LIBRARY)
        if (!panel.advanced) {
            val i=ribbons.indexOfFirst { it.contains(x,y) }; if (i<0) return false
            ModularControls.focus(ctl,i); capture()
            fader.begin(session,"Set ${patch.graph.macros[i].name}",Fader(i,patch.value(i),x,127f/maxOf(1f,ribbons[i].width()*.85f)))
            return true
        }
        if (send.contains(x,y)) return open(ModularControls.SOURCE)
        if (receive.contains(x,y)) return open(ModularControls.DESTINATION)
        if (control.contains(x,y)) return open(ModularControls.PARAMETER)
        if (map.contains(x,y)) return open(ModularControls.OVERVIEW)
        if (cables.contains(x,y)) return open(ModularControls.CABLES)
        if (macros.contains(x,y)) return open(ModularControls.MACROS)
        if (seed.contains(x,y)) return open(ModularControls.SEED)
        if (indexRows>0 && indexArea.contains(x,y)) {
            val h=ink.unit*1.7f; if(y<indexArea.top+h) return open(ModularControls.OVERVIEW)
            val i=((y-indexArea.top-h)*indexRows/(indexArea.height()-h)).toInt().coerceIn(0,indexRows-1)
            ModularControls.focus(ctl,ModularControls.SOURCE); ModularControls.selectNode(ctl,true,patch.graph.nodes[indexStart+i]); return true
        }
        val from=patch.graph.nodes.first { it.id == st.modularSource }; val to=patch.graph.nodes.first { it.id == st.modularDestination }
        for (i in from.kind.outputs.indices) if(outputs[i].contains(x,y)) {
            ModularControls.focus(ctl,ModularControls.CABLES); capture()
            cableToken=session.beginGesture("Connect cable"); jack=i; downX=x; downY=y; fingerX=x; fingerY=y; draggedCable=false; return true
        }
        for (i in to.kind.inputs.indices) if(inputs[i].contains(x,y)) { ModularControls.focus(ctl,ModularControls.CABLES); ModularControls.input(ctl,to,to.kind.inputs[i]); return true }
        return false
    }
    private fun capture() { owner=ModularCapture(ctl); StudioSoundView.panel(st).cancelTouch=cancelPointer }
    override fun move(x: Float,y: Float) {
        val target=owner ?: return
        if (!target.valid(ctl) || cableToken?.let { !session.isGestureOpen(it) } == true) { cancel(); return }
        if (jack>=0) { fingerX=x; fingerY=y; if(abs(x-downX)+abs(y-downY)>=ink.dp(4f)) draggedCable=true; return }
        val d=fader.live(session) ?: return
        if (!d.moved && abs(x-d.x)<ink.dp(4f)) return
        d.moved=true; ModularControls.set(ctl,d.macro,d.initial+((x-d.x)*d.scale).roundToInt()); target.version=session.documentVersion
    }
    override fun up(x: Float,y: Float) {
        if (owner?.valid(ctl) != true) { cancel(); return }
        move(x,y)
        val d=fader.live(session); val socket=jack; val tapped = !draggedCable
        cancel()
        if (d!=null && !d.moved) ModularControls.activate(ctl,d.macro)
        if (socket<0) return
        val patch=StudioSoundView.patch(song,st) as? ModularPatch ?: return
        val from=patch.graph.nodes.first { it.id == st.modularSource }; val to=patch.graph.nodes.first { it.id == st.modularDestination }
        val out=from.kind.outputs[socket]
        if (tapped) { ModularControls.output(ctl,from,out); return }
        val i=to.kind.inputs.indices.firstOrNull { inputs[it].contains(x,y) } ?: return
        val input=to.kind.inputs[i]
        if (!input.type.accepts(out.type)) session.note("${out.type.caption} cannot feed ${input.type.caption}.")
        else ModularControls.connect(ctl,patch,from,out,to,input)
    }
    override fun cancel() {
        owner?.let { val p=StudioSoundView.panel(it.state); if (p.cancelTouch === cancelPointer) p.cancelTouch=null }
        owner=null; jack=-1; fader.finish(session)
        cableToken?.let { session.endGesture(it) }; cableToken=null
        if (heldPad) { heldPad=false; if (!ctl.auditionHeld) session.releaseAudition() }
    }
}
