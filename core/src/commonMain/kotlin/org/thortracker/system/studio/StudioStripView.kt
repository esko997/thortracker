package org.thortracker.system.studio

import org.thortracker.editor.*
import org.thortracker.model.Song
import org.thortracker.system.SystemView
import org.thortracker.ui.PaneView

/** The strip addresses one track or one shared return. A pedal stays selected by ID when moved. */
internal object StudioStripView : SystemView() {
    const val ROUTE = 0
    const val SOURCE = 1
    const val FIRST_SLOT = 2
    const val GAIN = 6
    const val PAN = 7
    const val SEND_A = 8
    const val SEND_B = 9
    const val WRITE = 10
    private data class PedalClip(val kind: StudioKind, val values: Map<Int, Int>, val bypass: Boolean) : Clip

    override val gestureLabel = "Edit strip"
    fun address(s: ViewState) = if (s.stripBus < 0) s.channel else -1 - s.stripBus
    fun chain(s: StudioSound, st: ViewState): List<StudioInsert> = if (st.stripBus < 0) s.tracks[st.channel].inserts else s.buses[st.stripBus].inserts
    fun pedal(s: StudioSound, st: ViewState): StudioInsert? = if (st.stripPedal == 0) null else chain(s, st).firstOrNull { it.id == st.stripPedal }
    fun lastStripField(st: ViewState) = if (st.stripBus < 0) WRITE else GAIN
    fun routeName(s: StudioSound, st: ViewState) = if (st.stripBus < 0) "Track ${s.tracks[st.channel].midi.id}" else "Return ${'A' + st.stripBus}"
    fun usb(v: ViewController) = v.state.stripBus < 0 && v.song.studio.tracks[v.state.channel].target == StudioTarget.USB_MIDI

    override fun normalise(v: ViewController) {
        val st = v.state
        val s = v.song.studio
        if (st.stripBus !in s.buses.indices) st.stripBus = -1
        st.stripField = st.stripField.coerceIn(0, lastStripField(st))
        val pedal = pedal(s, st)
        if (pedal == null || usb(v)) st.stripPedal = 0 else st.pedalField = st.pedalField.coerceIn(0, pedal.kind.parameters.size + 2)
    }
    override fun subject(state: ViewState) = state.channel
    override fun applyLock(state: ViewState, subject: Int) { state.channel = subject }
    override fun follow(state: ViewState, selection: Selection, song: Song) {
        if (state.channel != selection.channel) state.stripPedal = 0
        state.channel = selection.channel
        state.instrument = selection.instrument
        state.order = selection.order
        state.row = selection.row
    }

    fun focus(v: ViewController, field: Int) {
        if (v.state.stripPedal == 0) v.state.stripField = field else v.state.pedalField = field
    }
    fun step(v: ViewController, delta: Int) {
        val st = v.state
        val p = pedal(v.song.studio, st)
        if (p == null) st.stripField = (st.stripField + delta).mod(lastStripField(st) + 1)
        else st.pedalField = (st.pedalField + delta).mod(p.kind.parameters.size + 3)
    }
    fun route(v: ViewController, channel: Int, bus: Int) {
        val st = v.state
        st.channel = channel
        st.stripBus = bus
        st.stripPedal = 0
        st.stripField = ROUTE
        v.syncLock()
        if (bus < 0) v.session.select(v.session.selection.copy(channel = channel, context = ViewKind.PATTERN))
    }
    override fun direction(v: ViewController, dx: Int, dy: Int) {
        if (v.editHeld) {
            v.useEdit()
            if (!StudioSoundView.panel(v.state).editBlocked) adjust(v, dx + dy * 10)
        } else step(v, if (dx != 0) dx else -dy)
    }
    override fun editPress(v: ViewController) { StudioSoundView.panel(v.state).editBlocked = false }
    override fun editRelease(v: ViewController, tap: Boolean) {
        val p = StudioSoundView.panel(v.state)
        val blocked = p.editBlocked
        p.editBlocked = false
        if (tap && !blocked) activate(v)
    }
    override fun audition(v: ViewController) {
        if (v.state.stripBus >= 0 || usb(v)) { v.session.note("Choose an internal source track to audition through its strip"); return }
        v.startAudition(v.state.channel, v.defaultNote(), v.state.instrument)
    }

    fun label(v: ViewController, field: Int): String {
        val st = v.state; val s = v.song.studio; val p = pedal(s, st)
        if (p != null) return when (field) {
            0 -> "Back to strip"
            1 -> if (p.bypass) "Bypassed" else "Engaged"
            p.kind.parameters.size + 2 -> "Pedal options"
            else -> p.kind.parameters[field - 2].name
        }
        return when (field) {
            ROUTE -> routeName(s, st)
            SOURCE -> if (st.stripBus >= 0) "Post-fader inputs" else "Instrument ${fxHex(st.instrument)}"
            in FIRST_SLOT..FIRST_SLOT+3 -> "${field - FIRST_SLOT + 1}  " + (chain(s,st).getOrNull(field-FIRST_SLOT)?.kind?.faceName ?: "Add pedal")
            GAIN -> if (st.stripBus >= 0) "Return gain" else "Output gain"
            PAN -> "Pan"
            SEND_A -> "Send A"
            SEND_B -> "Send B"
            else -> "Write parameter"
        }
    }
    fun value(v: ViewController): Int? {
        val st = v.state; val s = v.song.studio; val p = pedal(s, st)
        if (p != null) return p.kind.parameters.getOrNull(st.pedalField - 2)?.let { p.value(it.id) }
        val tr = s.tracks[st.channel]
        return when (st.stripField) {
            GAIN -> if (st.stripBus < 0) tr.gain else s.buses[st.stripBus].gain
            PAN -> tr.pan
            SEND_A, SEND_B -> tr.sends[st.stripField - SEND_A]
            else -> null
        }
    }
    fun range(v: ViewController): IntRange? {
        val st = v.state; val p = pedal(v.song.studio,st)
        if (p != null) return p.kind.parameters.getOrNull(st.pedalField-2)?.let { it.min..it.max }
        return when (st.stripField) { GAIN -> 0..200; PAN -> -100..100; SEND_A,SEND_B -> 0..100; else -> null }
    }
    fun set(v: ViewController, n: Int) {
        if (usb(v)) { v.session.note("USB sends MIDI only. Assign an internal destination to use this strip."); return }
        val st = v.state; val s = v.song.studio; val p = pedal(s,st)
        if (v.state.stripField in SEND_A..SEND_B && p == null && st.stripField - SEND_A !in s.buses.indices) {
            v.session.note("Open this send to create its shared return first."); return
        }
        val range = range(v) ?: return
        val bounded = n.coerceIn(range)
        if (value(v) == bounded) return
        if (p != null) {
            val param = p.kind.parameters[st.pedalField-2]
            replace(v, p.withValue(param.id, bounded), "Set ${param.name}"); return
        }
        v.session.apply("Set ${label(v,st.stripField)}") { song ->
            if (st.stripBus >= 0) song.copy(sound = s.copy(buses = s.buses.replaced(st.stripBus, s.buses[st.stripBus].copy(gain = bounded))))
            else {
                val tr = s.tracks[st.channel]
                val next = when (st.stripField) { GAIN -> tr.copy(gain=bounded); PAN -> tr.copy(pan=bounded)
                    else -> tr.copy(sends=tr.sends.replaced(st.stripField-SEND_A,bounded)) }
                song.copy(sound=s.copy(tracks=s.tracks.replaced(st.channel,next)))
            }
        }
    }
    fun adjust(v: ViewController, delta: Int) { value(v)?.let { set(v,it+delta) } }
    private fun updateChain(v: ViewController, next: List<StudioInsert>, label: String) {
        val st = v.state; val s = v.song.studio
        v.session.apply(label) { song -> if (st.stripBus >= 0) song.copy(sound=s.copy(buses=s.buses.replaced(st.stripBus,s.buses[st.stripBus].copy(inserts=next))))
            else song.copy(sound=s.copy(tracks=s.tracks.replaced(st.channel,s.tracks[st.channel].copy(inserts=next)))) }
    }
    private fun replace(v: ViewController, next: StudioInsert, label: String) {
        val chain = chain(v.song.studio,v.state); val i = chain.indexOfFirst { it.id == next.id }
        if (i >= 0) updateChain(v,chain.replaced(i,next),label)
    }
    private fun openPedal(v: ViewController, id: Int) { v.state.stripPedal=id; v.state.pedalField=2 }
    private fun closePedal(v: ViewController) {
        val st=v.state; val index=chain(v.song.studio,st).indexOfFirst { it.id==st.stripPedal }
        st.stripPedal=0; st.stripField=FIRST_SLOT+index.coerceAtLeast(0)
    }

    /** Popup delivery is bound to both document and pane target, including Undo/replacement. */
    private fun guard(v: ViewController): () -> Boolean {
        val st=v.state; val doc=v.session.documentVersion; val addr=address(st); val id=st.stripPedal
        val f=if(id==0) st.stripField else st.pedalField
        return { v.state === st && st.kind==ViewKind.STUDIO_STRIP && v.session.documentVersion==doc && address(st)==addr && st.stripPedal==id && (if(id==0) st.stripField else st.pedalField)==f }
    }
    fun activate(v: ViewController) {
        val st=v.state; val s=v.song.studio; val p=pedal(s,st); val valid=guard(v)
        if (p==null && st.stripField==ROUTE) { v.session.requestMenu { m ->
            m.menu("Track or shared return",buildList {
                for ((i,t) in s.tracks.withIndex()) add("Track ${t.midi.id}" + (if(t.target==StudioTarget.USB_MIDI) " · USB" else "") to { if(valid()) route(v,i,-1) })
                for(i in s.buses.indices) add("Return ${'A'+i}" to { if(valid()) route(v,st.channel,i) })
                if(s.buses.size<2) add("Add return ${'A'+s.buses.size}" to { if(valid()) {
                    v.session.apply("Add shared return") { it.copy(sound=s.copy(buses=s.buses+StudioBus())) }; route(v,st.channel,s.buses.size)
                } })
                if(st.stripBus<0) add("Clone track and pattern rows" to { if(valid()) clone(v) })
            }) }; return }
        if(usb(v)) { v.session.note("USB sends MIDI only. Change the destination under Files → Studio tracks."); return }
        if(p!=null && st.pedalField==0) { closePedal(v); return }
        if(p!=null && st.pedalField==1) { replace(v,p.copy(bypass=!p.bypass),"Toggle ${p.kind.faceName}"); return }
        if(p!=null && st.pedalField==p.kind.parameters.size+2) { options(v,p); return }
        if(p==null && st.stripField==SOURCE) {
            if(st.stripBus<0) { st.listenChannel=st.channel; st.switchKind(ViewKind.INSTRUMENT); st.pinned=true }
            else v.session.requestMenu { menu -> menu.menu("Post-fader inputs to ${'A'+st.stripBus}",s.tracks.mapIndexed { i,t ->
                "Track ${t.midi.id} · ${t.sends[st.stripBus]} %" to { if(valid()) { val bus=st.stripBus; route(v,i,-1); st.stripField=SEND_A+bus } }
            }) }; return
        }
        if(p==null && st.stripField in FIRST_SLOT..FIRST_SLOT+3) {
            val chain=chain(s,st); val index=st.stripField-FIRST_SLOT
            val current=chain.getOrNull(index)
            if(current!=null) openPedal(v,current.id) else v.session.requestMenu { m ->
                m.menu("Add insert ${chain.size+1}",StudioKind.entries.filterNot { it.instrument }.map { kind ->
                    kind.faceName to { if(valid()) {
                        if(s.nextInstance==Int.MAX_VALUE) m.error("Pedal identifiers exhausted") else {
                            v.session.apply("Add ${kind.faceName}") { it.addStudioInsert(address(st),kind) }; openPedal(v,s.nextInstance)
                        }
                    } }
                }) }; return
        }
        if(p==null && st.stripField==WRITE) {
            v.session.requestMenu { m -> m.menu("Track automation",listOf(
                "Write into Pattern cell…" to { if(valid()) StudioAssignments.choose(v.session,m,st) },
                "Manage E–Z bindings…" to { if(valid()) StudioAssignments.bindings(v,m) })) }
            return
        }
        if(p==null && st.stripField in SEND_A..SEND_B && st.stripField-SEND_A !in s.buses.indices) {
            val bus=st.stripField-SEND_A
            v.session.requestMenu { m -> m.menu("Send ${'A'+bus} needs a return",listOf("Create shared return" to {
                if(valid()) v.session.apply("Create shared return") { it.copy(sound=s.copy(buses=s.buses+List(bus+1-s.buses.size){StudioBus()})) }; Unit
            })) }; return
        }
        val param=p?.kind?.parameters?.getOrNull(st.pedalField-2)
        val n=value(v) ?: return; val range=range(v) ?: return
        v.session.requestMenu { m ->
            fun exact() = studioExact(m,param?.name ?: label(v,st.stripField),n,range,param?.labels.orEmpty()) { if(valid()) set(v,it) }
            if(param!=null && st.stripBus<0) m.menu(param.name,listOf("Set ${param.display(n)}…" to ::exact,
                "Write to Pattern…" to { if(valid()) StudioAssignments.writeParameter(v.session,m,st,StudioBinding(p!!.id,p.kind,param.id),param,n) }))
            else exact()
        }
    }
    private fun options(v: ViewController, p: StudioInsert) {
        val st=v.state; val s=v.song.studio; val chain=chain(s,st); val i=chain.indexOfFirst { it.id==p.id }; val valid=guard(v)
        v.session.requestMenu { m ->
            fun move(to:Int) { if(valid()) { val next=chain.toMutableList(); next.removeAt(i); next.add(to,p); updateChain(v,next,"Move ${p.kind.faceName}") } }
            m.menu("${p.kind.faceName} · insert ${i+1}",buildList {
                if(i>0) add("Move earlier" to { move(i-1) }); if(i<chain.lastIndex) add("Move later" to { move(i+1) })
                add("Copy pedal settings" to { if(valid()) v.session.hold(PedalClip(p.kind,p.values,p.bypass)) })
                val paste=v.session.clipboard as? PedalClip
                if(paste?.kind==p.kind) add("Paste settings" to { if(valid()) replace(v,p.copy(values=paste.values,bypass=paste.bypass),"Paste pedal settings") })
                add("Presets…" to { m.menu(p.kind.faceName,studioPedalPresets(p.kind).map { (name,values) -> name to { if(valid()) replace(v,p.copy(values=values),"Apply $name") } }) })
                add("Reset controls" to { if(valid()) replace(v,p.copy(values=emptyMap()),"Reset ${p.kind.faceName}") })
                val codes=if(st.stripBus<0) s.tracks[st.channel].bindings.filterValues { it.instance==p.id }.keys else emptySet()
                val detail=if(codes.isEmpty()) "Remove this pedal?" else "Remove pedal and all ${codes.sorted().joinToString { fxLetter(it) }} commands on this track?"
                add("Remove pedal…" to { m.menu(detail,listOf("Remove${if(codes.isEmpty()) "" else " and clear commands"}" to {
                    if(valid()) { v.session.apply("Remove ${p.kind.faceName}") { it.removeStudioInsert(address(st),p.id,true) }; st.stripPedal=0; st.stripField=FIRST_SLOT+i }
                })) })
            })
        }
    }
    private fun clone(v:ViewController) {
        val s=v.song.studio; val st=v.state
        if(s.tracks.size>=StudioSound.MAX_TRACKS || s.nextId==Int.MAX_VALUE || s.tracks[st.channel].inserts.size>Int.MAX_VALUE-s.nextInstance) { v.session.note("Cannot clone: track or identifier limit reached"); return }
        v.session.apply("Clone Studio track") { it.cloneStudioTrack(st.channel) }; route(v,s.tracks.size,-1)
    }
    override fun clear(v: ViewController) {
        val p=pedal(v.song.studio,v.state); val spec=p?.kind?.parameters?.getOrNull(v.state.pedalField-2)
        if(value(v)!=null) set(v,spec?.default ?: if(v.state.stripField==GAIN) 100 else 0)
    }
    override fun command(v: ViewController, a: Action): Boolean {
        val st=v.state; val s=v.song.studio; val p=pedal(s,st)
        when(a) {
            Action.PREV_CHANNEL,Action.NEXT_CHANNEL -> { StudioSoundView.blockHeldEdit(v); route(v,(st.channel+if(a==Action.NEXT_CHANNEL) 1 else -1).mod(s.tracks.size),-1) }
            Action.DUPLICATE -> if(st.stripBus<0) clone(v) else v.session.note("Only tracks can be cloned")
            Action.COPY -> if(p!=null) v.session.hold(PedalClip(p.kind,p.values,p.bypass)) else v.session.note("Open a pedal to copy its settings")
            Action.PASTE -> { val clip=v.session.clipboard as? PedalClip; if(p!=null && clip?.kind==p.kind) replace(v,p.copy(values=clip.values,bypass=clip.bypass),"Paste pedal settings") else v.session.note("Open a pedal matching the copied type") }
            else -> return false
        }
        return true
    }
    override fun hints(v: ViewController,h:MutableList<Hint>) {
        h+=Hint(v.lr(),"Select control"); h+=Hint(v.k(Action.EDIT),"Open"); h+=Hint(v.editLr(),"Adjust"); h+=Hint(v.channels(),"Track"); h+=Hint(v.k(Action.AUDITION),"Listen")
    }
    override fun painter(pane: PaneView) = StudioStripPainter(pane)
}

internal fun studioExact(menu: MenuContext, label:String, n:Int, range:IntRange, labels:List<String> = emptyList(), set:(Int)->Unit) {
    if(labels.isNotEmpty()) menu.menu(label,labels.mapIndexed { i,name -> name to { set(i+range.first) } },n-range.first)
    else menu.text("$label (${range.first}–${range.last})",n.toString(),12) { text ->
        val value=text.trim().toIntOrNull(); if(value==null || value !in range) menu.error("Enter a whole number from ${range.first} to ${range.last}.") else set(value)
    }
}
