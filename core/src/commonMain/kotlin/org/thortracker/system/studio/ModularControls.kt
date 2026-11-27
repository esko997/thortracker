package org.thortracker.system.studio

import org.thortracker.editor.MenuContext
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.replaced

internal val ModularNode.caption: String get() = "${fxHex(id)} ${kind.label}"
internal val ModularPortType.caption: String get() = when (this) {
    ModularPortType.AUDIO -> "audio"; ModularPortType.MOD -> "mod"; ModularPortType.PITCH -> "pitch"
    ModularPortType.GATE -> "gate"; ModularPortType.EVENT -> "event"
}

/** A popup or pointer acts only on the document and the two sockets it saw. Never rebase a cable by list index. */
internal class ModularCapture(v: ViewController) {
    val state = v.state
    private val slot = state.instrument
    private val lane = state.listenChannel
    private val field = state.instField
    private val source = state.modularSource
    private val destination = state.modularDestination
    private val parameter = state.modularParameter
    private val panel = StudioSoundView.panel(state)
    private val advanced = panel.advanced
    private val revision = panel.revision
    var version = v.session.documentVersion
    fun valid(v: ViewController) = v.state === state && state.kind == ViewKind.INSTRUMENT &&
        state.instrument == slot && state.listenChannel == lane && state.instField == field &&
        state.modularSource == source && state.modularDestination == destination && state.modularParameter == parameter &&
        panel.revision == revision && panel.advanced == advanced && v.session.documentVersion == version
}

internal object ModularControls {
    const val SOURCE = 200
    const val DESTINATION = 201
    const val PARAMETER = 202
    const val OVERVIEW = 203
    const val CABLES = 204
    const val MACROS = 205
    const val SEED = 206
    const val LIBRARY = StudioModuleControls.LIBRARY
    private val performance = intArrayOf(0,1,2,3,4,5,6,7,LIBRARY)
    private val build = intArrayOf(SOURCE,DESTINATION,PARAMETER,OVERVIEW,CABLES,MACROS,SEED,LIBRARY)
    fun fields(st: ViewState) = if (StudioSoundView.panel(st).advanced) build else performance
    fun focus(v: ViewController, key: Int) {
        val index = fields(v.state).indexOf(key)
        if (index >= 0) StudioSoundView.selectField(v, index + 2)
    }
    fun normalise(st: ViewState, g: ModularGraph) {
        val source = g.nodes.firstOrNull { it.id == st.modularSource } ?: g.nodes.first()
        st.modularSource = source.id
        if (g.nodes.none { it.id == st.modularDestination }) st.modularDestination =
            g.connections.firstOrNull { it.source == source.id }?.destination ?: g.nodes.last().id
        if (source.kind.parameters.none { it.id == st.modularParameter })
            st.modularParameter = source.kind.parameters.firstOrNull()?.id ?: -1
    }
    fun selectNode(v: ViewController, source: Boolean, node: ModularNode) {
        if (source) {
            v.state.modularSource = node.id
            v.state.modularParameter = node.kind.parameters.firstOrNull()?.id ?: -1
        } else v.state.modularDestination = node.id
    }
    fun set(v: ViewController, key: Int, n: Int) {
        val patch = StudioSoundView.patch(v.song,v.state) as? ModularPatch ?: return
        if (key in 0..7) {
            val value = n.coerceIn(0,127)
            if (value != patch.value(key)) StudioInstruments.store(v.session,v.state.instrument,
                patch.withValue(key,value),"Set ${patch.graph.macros[key].name}")
        } else if (key == PARAMETER) {
            val node = patch.graph.nodes.first { it.id == v.state.modularSource }
            val p = node.kind.parameters.firstOrNull { it.id == v.state.modularParameter } ?: return
            patch.graph.macros.firstOrNull { macro -> macro.targets.any { it.node == node.id && it.parameter == p.id } }?.let {
                v.session.note("${p.name} is controlled by ${it.name}. Open the control to adjust its macro or binding.")
                return
            }
            val value = n.coerceIn(p.min,p.max)
            if (value != node.value(p.id)) change(v,patch,"Set ${p.name}") { g ->
                g.copy(nodes=g.nodes.map { if (it.id != node.id) it else it.copy(values=it.values + (p.id to value)) })
            }
        }
    }
    fun adjust(v: ViewController, key: Int, dx: Int, dy: Int) {
        val patch = StudioSoundView.patch(v.song,v.state) as ModularPatch
        val nodes = patch.graph.nodes
        when (key) {
            SOURCE, DESTINATION -> {
                val current = if (key == SOURCE) v.state.modularSource else v.state.modularDestination
                selectNode(v,key == SOURCE,nodes[(nodes.indexOfFirst { it.id == current } + dx + dy).mod(nodes.size)])
            }
            PARAMETER -> {
                val node = nodes.first { it.id == v.state.modularSource }
                val p = node.kind.parameters.firstOrNull { it.id == v.state.modularParameter } ?: return
                set(v,key,node.value(p.id) + dx + dy * maxOf(1,(p.max-p.min)/16))
            }
            in 0..7 -> set(v,key,patch.value(key) + dx + 8 * dy)
        }
    }
    fun clear(v: ViewController, patch: ModularPatch) {
        when (val key = fields(v.state).getOrNull(v.state.instField - 2)) {
            PARAMETER -> patch.graph.nodes.first { it.id == v.state.modularSource }.kind.parameters
                .firstOrNull { it.id == v.state.modularParameter }?.let { set(v,PARAMETER,it.default) }
            null -> Unit
            else -> if (key in 0..7) set(v,key,patch.kind.parameters[key].default)
        }
    }
    private inline fun change(v: ViewController, patch: ModularPatch, label: String, edit: (ModularGraph) -> ModularGraph): Boolean {
        return try {
            val graph = edit(patch.graph)
            if (graph == patch.graph) false else StudioInstruments.store(v.session,v.state.instrument,patch.copy(graph=graph),label)
        } catch (e: IllegalArgumentException) { v.session.note(e.message ?: "Invalid patch"); false }
    }
    fun connect(v: ViewController, patch: ModularPatch, source: ModularNode, out: ModularPort, dest: ModularNode, input: ModularPort): Boolean =
        change(v,patch,"Connect ${source.kind.label} to ${dest.kind.label}") { g ->
            g.copy(connections=g.connections + ModularConnection(g.nextConnectionId(),source.id,out.id,dest.id,input.id))
        }

    fun activate(v: ViewController, key: Int) = open(v) { m -> m.open(key) }
    fun output(v: ViewController, node: ModularNode, port: ModularPort) = open(v) { it.destinations(node,port) }
    fun input(v: ViewController, node: ModularNode, port: ModularPort) = open(v) { it.sources(node,port) }
    private inline fun open(v: ViewController, crossinline open: (Inspector) -> Unit) {
        val patch = StudioSoundView.patch(v.song,v.state) as? ModularPatch ?: return
        val capture = ModularCapture(v)
        v.session.requestMenu { menu -> if (capture.valid(v)) open(Inspector(v,menu,patch,capture)) }
    }

    private class Inspector(val v: ViewController, val menu: MenuContext, val patch: ModularPatch, var capture: ModularCapture) {
        val g = patch.graph
        val st = v.state
        val from = node(st.modularSource)
        fun node(id: Int) = g.nodes.first { it.id == id }
        fun edit(label: String, f: (ModularGraph) -> ModularGraph) = capture.valid(v) && change(v,patch,label,f)
        fun item(label: String, run: () -> Unit): Pair<String,() -> Unit> = label to { if (capture.valid(v)) run() }
        fun open(key: Int) {
            when (key) {
                in 0..7 -> {
                    val spec = patch.kind.parameters[key].copy(name=g.macros[key].name)
                    menu.menu(spec.name,listOf(
                        item("Set value · ${patch.value(key)}") { studioExact(menu,spec.name,patch.value(key),0..127) { if(capture.valid(v)) set(v,key,it) } },
                        item("Write automation to Pattern") { StudioAssignments.writeParameter(v.session,menu,st,StudioBinding(0,patch.kind,key),spec,patch.value(key)) { capture.valid(v) } },
                        item("Edit bindings · ${g.macros[key].targets.size}") { bindings(key) }
                    ))
                }
                SOURCE, DESTINATION -> pickNode(if (key == SOURCE) "Send from / edit module" else "Receive at module",
                    if (key == SOURCE) st.modularSource else st.modularDestination) { selectNode(v,key == SOURCE,it) }
                OVERVIEW -> overview()
                PARAMETER -> parameters()
                CABLES -> cables()
                MACROS -> menu.menu("Macro bindings",g.macros.mapIndexed { i,macro -> item("${i+1}  ${macro.name} · ${macro.targets.size} targets") { bindings(i) } })
                SEED -> studioExact(menu,"Random seed",g.seed,Int.MIN_VALUE..Int.MAX_VALUE) { seed -> edit("Set random seed") { it.copy(seed=seed) } }
                LIBRARY -> StudioInstruments.library(v.session,menu,st.instrument) { capture.valid(v) }
            }
        }
        fun pickNode(title: String, selected: Int, picked: (ModularNode) -> Unit) = menu.menu(title,
            g.nodes.map { n -> item(n.caption) { picked(n) } },g.nodes.indexOfFirst { it.id == selected }.coerceAtLeast(0))
        fun parameters() {
            val params = from.kind.parameters
            if (params.isEmpty()) return menu.error("${from.caption} has no adjustable parameters. Choose another module under Send from.")
            menu.menu("${from.caption} controls",params.map { p ->
                val owner = g.macros.indexOfFirst { m -> m.targets.any { it.node == from.id && it.parameter == p.id } }
                item(if (owner >= 0) "${p.name} ← ${g.macros[owner].name}" else "${p.name}  ${p.display(from.value(p.id))}") {
                    st.modularParameter = p.id
                    capture = ModularCapture(v)
                    if (owner >= 0) open(owner) else
                        studioExact(menu,"${from.kind.label} · ${p.name}${if (p.unit.isEmpty()) "" else " (${p.unit})"}",from.value(p.id),p.min..p.max,p.labels) {
                            if (capture.valid(v)) set(v,PARAMETER,it)
                        }
                }
            },params.indexOfFirst { it.id == st.modularParameter }.coerceAtLeast(0))
        }
        fun overview() {
            menu.menu("Patch map · ${g.nodes.size} modules, ${g.connections.size} cables",buildList {
                add(item("Add module…") {
                    menu.menu("Add module",ModularKind.entries.filter { it != ModularKind.OUT }.map { kind -> item(kind.label) {
                        var added: ModularNode? = null
                        if (edit("Add ${kind.label}") {
                            val next = ModularNode(it.nextNodeId(),kind)
                            added = next
                            it.copy(nodes=it.nodes + next)
                        }) added?.let { selectNode(v,true,it) }
                    } })
                })
                for (n in g.nodes) add(item("${n.caption}   ${neighbors(g,n.id)}") { inspect(n) })
            },g.nodes.indexOfFirst { it.id == from.id }+1)
        }
        fun inspect(n: ModularNode) {
            menu.menu(n.caption,buildList {
                add(item("Edit module / send from here") { selectNode(v,true,n) })
                add(item("Receive at this module") { selectNode(v,false,n) })
                for (c in g.connections) if (c.source == n.id || c.destination == n.id) add(item(route(c)) { cable(c) })
                if (n.kind != ModularKind.OUT) add(item("Remove module…") {
                    val cables = g.connections.count { it.source == n.id || it.destination == n.id }
                    val bindings = g.macros.sumOf { m -> m.targets.count { it.node == n.id } }
                    menu.menu("Remove ${n.caption}?",listOf(item("Remove with $cables cables and $bindings bindings") {
                        edit("Remove ${n.kind.label}") { it.withoutNode(n.id) }
                    }))
                })
            })
        }
        fun route(c: ModularConnection): String {
            val a = node(c.source); val b = node(c.destination)
            return "${a.caption}:${a.kind.outputs.first { it.id == c.output }.name} → ${b.caption}:${b.kind.inputs.first { it.id == c.input }.name}"
        }
        fun cables() {
            menu.menu("Patch cables · ${g.connections.size}",buildList {
                add(item("Add cable…") {
                    val sources = g.nodes.filter { it.kind.outputs.isNotEmpty() }
                    if (sources.isEmpty()) menu.error("Add a module with an output first.")
                    else menu.menu("Send from",sources.map { n -> item(n.caption) {
                        menu.menu("${n.caption} output",n.kind.outputs.map { p -> item(portLabel(p)) { destinations(n,p) } })
                    } },sources.indexOf(from).coerceAtLeast(0))
                })
                for (c in g.connections) add(item(route(c)) { cable(c) })
            })
        }
        fun portLabel(p: ModularPort) = "${p.name} · ${p.type.caption}"
        fun available(n: ModularNode, out: ModularPort, d: ModularNode, input: ModularPort) = input.type.accepts(out.type) &&
            g.connections.none { it.source == n.id && it.output == out.id && it.destination == d.id && it.input == input.id }
        fun link(n: ModularNode,out: ModularPort,d: ModularNode,input: ModularPort) {
            if (capture.valid(v) && connect(v,patch,n,out,d,input)) { selectNode(v,true,n); selectNode(v,false,d) }
        }
        fun destinations(n: ModularNode, out: ModularPort) {
            val choices = g.nodes.filter { d -> d.kind.inputs.any { available(n,out,d,it) } }
            if (choices.isEmpty()) return menu.error("No unused compatible input for ${out.type.caption}. Add a receiving module in Patch map.")
            menu.menu("${n.caption}:${out.name} → module",choices.map { d -> item(d.caption) {
                menu.menu("${d.caption} input",d.kind.inputs.filter { available(n,out,d,it) }.map { input ->
                    item(portLabel(input)) { link(n,out,d,input) }
                })
            } },choices.indexOfFirst { it.id == st.modularDestination }.coerceAtLeast(0))
        }
        fun sources(d: ModularNode, input: ModularPort) {
            val choices = g.nodes.filter { n -> n.kind.outputs.any { available(n,it,d,input) } }
            if (choices.isEmpty()) return menu.error("No unused compatible output for ${input.type.caption}. Add a sending module in Patch map.")
            menu.menu("Module → ${d.caption}:${input.name}",choices.map { n -> item(n.caption) {
                menu.menu("${n.caption} output",n.kind.outputs.filter { available(n,it,d,input) }.map { out ->
                    item(portLabel(out)) { link(n,out,d,input) }
                })
            } },choices.indexOf(from).coerceAtLeast(0))
        }
        fun cable(c: ModularConnection) {
            val n = node(c.source); val d = node(c.destination)
            val type = d.kind.inputs.first { it.id == c.input }.type
            menu.menu(route(c),buildList {
                add(item("Show this pair in patch bay") { selectNode(v,true,n); selectNode(v,false,d) })
                fun transform(scale: Boolean) {
                    val name = if (scale) "Amount" else "Offset"
                    studioExact(menu,"$name (1000 = 1)",if (scale) c.scale else c.offset,-ModularGraph.MAX_TRANSFORM..ModularGraph.MAX_TRANSFORM) { value ->
                        edit("Set cable ${name.lowercase()}") { graph -> graph.copy(connections=graph.connections.map {
                            if (it.id != c.id) it else if (scale) it.copy(scale=value) else it.copy(offset=value)
                        }) }
                    }
                }
                if (type != ModularPortType.GATE && type != ModularPortType.EVENT) {
                    add(item("Amount  ${c.scale} / 1000") { transform(true) }); add(item("Offset  ${c.offset} / 1000") { transform(false) })
                }
                add(item("Disconnect") { edit("Disconnect cable") { it.copy(connections=it.connections.filterNot { cable -> cable.id == c.id }) } })
            })
        }
        fun macroEdit(i: Int, label: String, macro: ModularMacro) = edit(label) { it.copy(macros=it.macros.replaced(i,macro)) }
        fun bindings(i: Int) {
            val macro = g.macros[i]
            menu.menu("${macro.name} · bindings",buildList {
                add(item("Rename macro") { menu.text("Macro ${i+1} name",macro.name,ModularGraph.MAX_MACRO_NAME) { text ->
                    if (text.isBlank()) menu.error("Enter a macro name.") else if (capture.valid(v))
                        edit("Rename macro") { it.copy(macros=it.macros.replaced(i,macro.copy(name=text.trim()))) }
                } })
                add(item("Add target…") {
                    fun free(n: ModularNode, p: StudioParameter) = !p.discrete &&
                        g.macros.none { m -> m.targets.any { it.node == n.id && it.parameter == p.id } }
                    val choices = g.nodes.filter { n -> n.kind.parameters.any { free(n,it) } }
                    if (macro.targets.size >= ModularGraph.MAX_TARGETS) menu.error("Remove a binding before adding another target.")
                    else if (choices.isEmpty()) menu.error("Add a module with an unbound continuous control first.")
                    else menu.menu("${macro.name} → module",choices.map { n -> item(n.caption) {
                        menu.menu("${n.caption} parameter",n.kind.parameters.filter { free(n,it) }.map { p ->
                            item(p.name) { endpoints(i,n,p,null) }
                        })
                    } })
                })
                for (t in macro.targets) {
                    val n = node(t.node); val p = n.kind.parameters.first { it.id == t.parameter }
                    add(item("${n.caption}:${p.name}   ${p.display(t.minimum)} → ${p.display(t.maximum)}") {
                        menu.menu("${macro.name} → ${n.caption}:${p.name}",listOf(
                            item("Edit endpoints") { endpoints(i,n,p,t) },
                            item("Remove binding") { macroEdit(i,"Remove macro binding",macro.copy(targets=macro.targets - t)) }
                        ))
                    })
                }
            })
        }
        /** Commit once, only after both endpoints. Cancelling the second editor creates no half-binding. */
        fun endpoints(i: Int, n: ModularNode, p: StudioParameter, old: ModularTarget?) {
            val macro = g.macros[i]
            val range = p.min..p.max
            val unit = if (p.unit.isEmpty()) "" else " (${p.unit})"
            studioExact(menu,"${p.name} at macro 0$unit",old?.minimum ?: p.min,range) { low ->
                if (capture.valid(v)) studioExact(menu,"${p.name} at macro 127$unit",old?.maximum ?: p.max,range) { high ->
                    if (capture.valid(v)) {
                        val target = ModularTarget(n.id,p.id,low,high)
                        macroEdit(i,"Set macro binding",macro.copy(targets=if (old == null) macro.targets + target else macro.targets.map { if (it == old) target else it }))
                    }
                }
            }
        }
    }
    /** Neighbors, rather than just cable counts, preserve the route context when selecting a module. */
    fun neighbors(g: ModularGraph, id: Int): String {
        fun side(input: Boolean): String {
            val ids = linkedSetOf<Int>()
            for (c in g.connections) if (if (input) c.destination == id else c.source == id) ids += if (input) c.source else c.destination
            return if (ids.isEmpty()) "–" else ids.joinToString(",",limit=3,truncated="…") { fxHex(it) }
        }
        return "${side(true)} › ${fxHex(id)} › ${side(false)}"
    }
}
