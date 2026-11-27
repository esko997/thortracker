package org.thortracker.system.studio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.roundToInt
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.*

internal class StudioStripPainter(v: PaneView) : Painter(v) {
    private val fields=Array(11){Rect()}
    private val prev=Rect(); private val next=Rect(); private val action=Rect(); private val rail=Rect(); private val plot=Rect()
    private val drag=PanelDrag<StripDrag>()
    private class StripDrag(val st:ViewState,val addr:Int,val pedal:Int,val field:Int,val range:IntRange,val l:Float,val r:Float,var version:Long)
    private var drawnVersion=-1L; private var drawnState:ViewState?=null; private var drawnPedal=0; private var drawnAddr=0; private var drawnField=0
    private fun box(c:Surface,r:Rect,text:String,selected:Boolean,accent:Int) {
        val p=ink.palette; ink.round(c,r,ink.dp(3f),if(selected) Palette.mix(p.BAR,accent,.2f) else p.LANE)
        if(selected) ink.outline(c,r,p.FOCUS,ink.dp(1.7f),ink.dp(3f))
        val size=minOf(ink.unit,r.height()*.49f); ink.label(c,ink.fit(text,r.width()-ink.dp(6f),size,selected),r.centerX(),r.top,r.height(),p.TEXT,selected,size,Align.CENTER)
    }
    override fun draw(c:Surface,area:Rect) {
        drawnState=null
        val s=song.studio; val pedal=StudioStripView.pedal(s,st); val u=ink.unit; val p=ink.palette
        val active=if(pedal==null) st.stripField else st.pedalField
        val accent=pedal?.let { StudioGraphics.color(it.kind,p) } ?: p.channel(st.channel)
        val top=header(c,area,StudioStripView.routeName(s,st),if(pedal!=null) "${pedal.kind.faceName}  /  ${if(pedal.bypass) "Bypassed" else "Engaged"}" else if(StudioStripView.usb(ctl)) "USB MIDI · strip inactive" else "Internal stereo")
        val pad=u*.48f; val left=area.left+pad; val right=area.right-pad; val h=area.bottom-top
        if(h<=0 || right<=left) return
        val foot=minOf(u*4.5f,h*.31f); val bottom=area.bottom-pad; val split=bottom-foot-pad
        val tabH=minOf(u*2.7f,h*.2f); val gap=ink.dp(2f)
        for(r in fields) r.set(0f,0f,0f,0f)
        val n=if(pedal==null) StudioStripView.lastStripField(st)+1 else pedal.kind.parameters.size+3
        if(pedal==null) {
            // A vertical signal trace joins the four numbered sockets. The right side is its post-fader branch.
            val mid=left+(right-left)*.56f; val y=top+pad
            fields[0].set(left,y,mid-gap,y+tabH); fields[1].set(mid+gap,y,right,y+tabH)
            val rackTop=y+tabH+pad; val rh=(split-rackTop)/4; val x=left+u*.8f
            c.line(x,rackTop+rh/2,x,rackTop+3.5f*rh,accent,ink.dp(2f))
            for(i in 0..3) {
                val cy=rackTop+(i+.5f)*rh
                c.fillCircle(x,cy,ink.dp(3.4f),if(i<StudioStripView.chain(s,st).size) accent else p.RULE)
                fields[2+i].set(x+u*.7f,rackTop+i*rh+gap,mid-gap,rackTop+(i+1)*rh-gap)
            }
            val outputRows=n-6; val oh=(split-rackTop)/outputRows
            for(i in 0 until outputRows) fields[6+i].set(mid+gap,rackTop+i*oh+gap,right,rackTop+(i+1)*oh-gap)
        } else {
            fields[0].set(left,top+pad,left+(right-left)*.34f-gap,top+pad+tabH)
            fields[1].set(fields[0].right+gap*2,top+pad,right-(right-left)*.32f-gap,top+pad+tabH)
            fields[n-1].set(fields[1].right+gap*2,top+pad,right,top+pad+tabH)
            val y=fields[0].bottom+pad
            val graphH=(split-y)*.53f
            plot.set(left,y,right,y+graphH)
            StudioGraphics.pedal(ink,c,plot,pedal,accent)
            val count=pedal.kind.parameters.size
            val cols=if(right-left < ink.dp(400f)) minOf(3,count) else count
            val rows=(count+cols-1)/cols; val w=(right-left)/cols; val rh=(split-plot.bottom)/rows
            for(i in 0 until count) fields[i+2].set(left+(i%cols)*w+gap,plot.bottom+(i/cols)*rh+gap,left+(i%cols+1)*w-gap,plot.bottom+(i/cols+1)*rh-gap)
        }
        for(i in 0 until n) {
            var label=StudioStripView.label(ctl,i)
            if(pedal==null && i in 2..5 && StudioStripView.chain(s,st).getOrNull(i-2)?.bypass==true) label+=" (off)"
            box(c,fields[i],label,active==i,accent)
        }
        val arrow=minOf(foot,(right-left)*.13f)
        prev.set(left,split+pad,left+arrow,bottom); next.set(right-arrow,split+pad,right,bottom)
        val value=StudioStripView.value(ctl); val x0=prev.right+pad; val x1=next.left-pad
        action.set(x0,split+pad,x1,if(value==null) bottom else bottom-foot*.48f)
        val label=StudioStripView.label(ctl,active)
        val param=pedal?.kind?.parameters?.getOrNull(active-2)
        val shown=if(param!=null && value!=null) "${param.display(value)}  /  ${fxHex(param.nearestByte(value))}" else "$value %"
        box(c,action,if(value==null) "$label…" else "$label   $shown",false,accent)
        box(c,prev,"‹",false,accent); box(c,next,"›",false,accent)
        rail.set(x0,action.bottom,x1,bottom)
        if(value!=null && rail.width()>0f) {
            val range=StudioStripView.range(ctl)!!
            PanelWidgets.rail(ink,c,rail.left+pad,rail.right-pad,rail.centerY(),(value-range.first).toFloat()/(range.last-range.first),range.last-range.first,accent,true,ctl.editHeld)
        }
        drawnState=st; drawnVersion=session.documentVersion; drawnAddr=StudioStripView.address(st); drawnPedal=st.stripPedal; drawnField=active
    }
    override fun down(x:Float,y:Float):Boolean {
        if(drawnState!==st || st.kind!=ViewKind.STUDIO_STRIP || drawnVersion!=session.documentVersion || drawnAddr!=StudioStripView.address(st) || drawnPedal!=st.stripPedal || drawnField!=(if(st.stripPedal==0) st.stripField else st.pedalField)) return false
        StudioSoundView.blockHeldEdit(ctl)
        if(prev.contains(x,y) || next.contains(x,y)) StudioStripView.step(ctl,if(next.contains(x,y)) 1 else -1)
        else if(action.contains(x,y)) StudioStripView.activate(ctl)
        else if(StudioStripView.value(ctl)!=null && rail.contains(x,y)) {
            val pad=ink.unit*.48f
            drag.begin(session,"Edit ${StudioStripView.label(ctl,drawnField)}",StripDrag(st,drawnAddr,drawnPedal,drawnField,StudioStripView.range(ctl)!!,rail.left+pad,rail.right-pad,drawnVersion)); move(x,y)
        } else {
            val i=fields.indexOfFirst { it.width()>0 && it.contains(x,y) }; if(i<0) return false
            StudioStripView.focus(ctl,i)
            if(StudioStripView.value(ctl)==null || i==drawnField || (st.stripPedal==0 && i in 8..9 && i-8 !in song.studio.buses.indices)) StudioStripView.activate(ctl)
        }
        return true
    }
    override fun move(x:Float,y:Float) {
        val d=drag.live(session) ?: return
        if(d.st!==st || st.kind!=ViewKind.STUDIO_STRIP || d.addr!=StudioStripView.address(st) || d.pedal!=st.stripPedal || d.field!=(if(st.stripPedal==0) st.stripField else st.pedalField) || d.version!=session.documentVersion) { cancel(); return }
        StudioStripView.set(ctl,PanelWidgets.railValue(x,d.l,d.r,d.range.first,d.range.last)); d.version=session.documentVersion
    }
    override fun up(x:Float,y:Float)=cancel()
    override fun cancel() { drag.finish(session) }
}

/** Plots describe the stored controls, not a fabricated real-time signal or a CPU meter. */
internal object StudioGraphics {
    private val waves=FloatArray(256){ sin(it*2.0*PI/256).toFloat() }
    fun sinCycle(cycles:Float)=waves[(cycles*256).roundToInt() and 255]
    fun color(k:StudioKind,p:Palette):Int {
        if(p===Palette.CONTRAST) return p.LISTEN
        val color=when(k) {
            StudioKind.SAMPLER,StudioKind.DELAY -> 0xff79d8c6.toInt()
            StudioKind.FM,StudioKind.CHORUS -> 0xffc9a9ff.toInt()
            StudioKind.PERCUSSION,StudioKind.DRIVE -> 0xffffa66c.toInt()
            StudioKind.REVERB -> 0xff8ebcf8.toInt()
            else -> 0xffefcf69.toInt()
        }
        return if(p===Palette.PAPER) Palette.mix(color,0xff102035.toInt(),.65f) else color
    }
    fun pedal(ink:Ink,c:Surface,r:Rect,p:StudioInsert,accent:Int) {
        if(r.width()<1 || r.height()<1) return
        val muted=if(p.bypass) ink.palette.DIM else accent
        ink.round(c,r,ink.dp(3f),Palette.mix(ink.palette.BG,muted,.09f))
        val title=when(p.kind) {StudioKind.DELAY->"Echo spacing";StudioKind.DRIVE->"Saturation";StudioKind.CRUSHER->"Sample / hold";StudioKind.REVERB->"Room decay";else->"Stereo sweep"}
        val pad=ink.unit*.55f; val h=minOf(ink.unit*1.4f,r.height()*.28f)
        ink.label(c,title,r.left+pad,r.top,h,muted,true,minOf(ink.unit,h*.86f))
        val left=r.left+pad; val w=r.width()-2*pad; val mid=(r.top+h+r.bottom)/2; val amp=(r.bottom-r.top-h)*.39f
        if(w<=0 || amp<=0) return
        c.line(left,mid,left+w,mid,ink.palette.RULE,ink.dp(1f))
        when(p.kind) {
            StudioKind.DELAY -> {
                val clk=p.value(0); val beat=when(clk){1->.5f;2->.75f;3->1f/3;4->1f;5->1.5f;6->2f/3;7->.25f;8->.375f;9->1f/6;else->p.value(1)/1000f}
                val stride=w*(beat*.20f).coerceIn(.014f,.5f); var vol=1f; var x=left
                for(i in 0..24) { if(x>left+w) break; c.line(x,mid-amp*vol,x,mid+amp*vol*.65f,muted,ink.dp(2.5f)); x+=stride; vol=if(i==0) p.value(4)/100f else vol*p.value(2)/100f }
            }
            StudioKind.REVERB -> {
                val gap=w*p.value(3)/1000f; val length=.17f+.83f*p.value(1)/8000f; val peaks=12+p.value(0)/3
                for(i in 0..peaks) { val t=i.toFloat()/peaks; val x=left+gap+(w-gap)*t*length; val mag=(1f-t)*(1f-t)*amp*(.6f+.4f*((i*13)%17)/16f); c.line(x,mid-mag,x,mid+mag,muted,ink.dp(1.6f)) }
            }
            else -> {
                fun y(t:Float,other:Boolean=false):Float = when(p.kind) {
                    StudioKind.DRIVE -> { val z=(t*2-1)*(1+p.value(0)/5f); mid-amp*z/(1+kotlin.math.abs(z)) }
                    StudioKind.CRUSHER -> { val levels=1 shl minOf(12,p.value(0)-1); val steps=5+39*p.value(1)/48000; val held=(t*steps).toInt().toFloat()/steps; mid-amp*(sinCycle(held)*levels).roundToInt()/levels }
                    else -> mid-amp*sinCycle(t*(1f+p.value(0)/150f)+(if(other) .25f else 0f))*(.12f+.88f*p.value(1)/10000f)
                }
                for(trace in 0 until if(p.kind==StudioKind.CHORUS) 2 else 1) { var py=y(0f,trace==1)
                    for(i in 1..96) { val t=i/96f; val ny=y(t,trace==1); val px=left+w*(i-1)/96f; val nx=left+w*t
                        if(p.kind==StudioKind.CRUSHER) { c.line(px,py,nx,py,muted,ink.dp(1.7f)); c.line(nx,py,nx,ny,muted,ink.dp(1.4f)) }
                        else c.line(px,py,nx,ny,if(trace==1) ink.palette.DIM else muted,ink.dp(1.7f)); py=ny
                    }
                }
            }
        }
    }
}
