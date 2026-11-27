package org.thortracker.system.studio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable class ModularNode private constructor(val id: Int, val kind: ModularKind, private val controls: Controls) {
    constructor(id: Int, kind: ModularKind, values: Map<Int,Int> = emptyMap()) : this(id,kind,Controls(values))
    val values get() = controls.values
    init {
        require(id > 0) { "Node ID must be positive" }
        for ((i,v) in values) require(kind.parameters.getOrNull(i)?.let { v in it.min..it.max } == true) { "Invalid ${kind.label} parameter $i: $v" }
    }
    fun value(parameter: Int) = controls.get(parameter) ?: kind.parameters[parameter].default
    fun copy(id: Int=this.id, kind: ModularKind=this.kind, values: Map<Int,Int> =this.values) = ModularNode(id,kind,values)
    override fun equals(other: Any?) = other is ModularNode && id == other.id && kind == other.kind && controls == other.controls
    override fun hashCode() = 31*(31*id+kind.hashCode())+controls.hashCode()
}

@Serializable data class ModularConnection(val id: Int, val source: Int, val output: Int, val destination: Int, val input: Int,
    val scale: Int=1000, val offset: Int=0) {
    init {
        require(id > 0 && source > 0 && destination > 0 && input >= 0 && output >= 0) { "Invalid cable identity or port" }
        require(scale in -ModularGraph.MAX_TRANSFORM..ModularGraph.MAX_TRANSFORM && offset in -ModularGraph.MAX_TRANSFORM..ModularGraph.MAX_TRANSFORM) { "Cable transform outside ±16" }
    }
}

@Serializable data class ModularTarget(val node: Int, val parameter: Int, val minimum: Int, val maximum: Int)

@Serializable class ModularMacro private constructor(val name: String,
    @SerialName("targets") @Serializable(with=TargetsWire::class) private val destinations: List<ModularTarget>) {
    constructor(name: String, targets: Collection<ModularTarget> = emptyList()) : this(name,Frozen(targets,ModularGraph.MAX_TARGETS))
    val targets get() = destinations
    init { require(name.length in 1..ModularGraph.MAX_MACRO_NAME) { "Macro name must have 1–22 characters" } }
    fun copy(name: String=this.name, targets: List<ModularTarget> =this.targets) = ModularMacro(name, targets as Collection<ModularTarget>)
    override fun equals(other: Any?) = other is ModularMacro && name == other.name && targets == other.targets
    override fun hashCode() = 31*name.hashCode()+targets.hashCode()
}

@Serializable class ModularGraph private constructor(
    @SerialName("nodes") @Serializable(with=NodesWire::class) private val modules: List<ModularNode>,
    @SerialName("connections") @Serializable(with=CablesWire::class) private val cables: List<ModularConnection>,
    @SerialName("macros") @Serializable(with=MacrosWire::class) private val controls: List<ModularMacro>, val seed: Int,
    private val lastNodeId: Int, private val lastConnectionId: Int) {
    constructor(nodes: Collection<ModularNode>, connections: Collection<ModularConnection> = emptyList(),
        macros: Collection<ModularMacro> = List(MACROS){ ModularMacro("Macro ${it+1}") }, seed: Int=1) :
        this(Frozen(nodes,MAX_NODES),Frozen(connections,MAX_CONNECTIONS),Frozen(macros,MACROS),seed,
            nodes.maxOfOrNull { it.id } ?: 0, connections.maxOfOrNull { it.id } ?: 0)
    val nodes get() = modules
    val connections get() = cables
    val macros get() = controls
    init {
        require(nodes.size in 1..MAX_NODES && nodes.count { it.kind == ModularKind.OUT } == 1) { "A graph needs exactly one Output" }
        require(lastNodeId >= nodes.maxOf { it.id } && lastConnectionId >= (connections.maxOfOrNull { it.id } ?: 0)) { "Invalid graph ID history" }
        require(macros.size == MACROS) { "Expected eight macros" }
        val byId = nodes.withIndex().associate { it.value.id to it.index }
        require(byId.size == nodes.size) { "Duplicate node ID" }
        require(connections.size <= MAX_CONNECTIONS && connections.distinctBy { it.id }.size == connections.size) { "Duplicate cable ID or too many cables" }
        val endpoints = HashSet<Long>()
        val dependencies = IntArray(nodes.size)
        val outgoing = Array(nodes.size) { mutableListOf<Int>() }
        for (c in connections) {
            val a = byId[c.source] ?: throw IllegalArgumentException("Unknown source ${c.source}")
            val b = byId[c.destination] ?: throw IllegalArgumentException("Unknown destination ${c.destination}")
            val output = nodes[a].kind.outputs.getOrNull(c.output) ?: throw IllegalArgumentException("Unknown output ${c.output}")
            val input = nodes[b].kind.inputs.getOrNull(c.input) ?: throw IllegalArgumentException("Unknown input ${c.input}")
            require(input.type.accepts(output.type)) { "${output.type} cannot feed ${input.type}" }
            require(endpoints.add(((a*64L+b)*16+c.output)*16+c.input)) { "Cable already exists" }
            if (input.type == ModularPortType.GATE || input.type == ModularPortType.EVENT)
                require(c.scale == 1000 && c.offset == 0) { "Gate and event inputs require unity scale and zero offset" }
            if (output.type != ModularPortType.EVENT && nodes[a].kind != ModularKind.DELAY) { dependencies[b]++; outgoing[a].add(b) }
        }
        val ready = ArrayDeque<Int>(); for (i in nodes.indices) if (dependencies[i] == 0) ready.addLast(i)
        var visited = 0
        while (ready.isNotEmpty()) {
            val i = ready.removeFirst(); visited++
            for (b in outgoing[i]) if (--dependencies[b] == 0) ready.addLast(b)
        }
        require(visited == nodes.size) { "Instantaneous cycle: insert a unit delay" }
        val assigned = HashSet<Pair<Int,Int>>()
        for (m in macros) for (t in m.targets) {
            val i = byId[t.node] ?: throw IllegalArgumentException("Unknown macro destination ${t.node}")
            val p = nodes[i].kind.parameters.getOrNull(t.parameter) ?: throw IllegalArgumentException("Unknown macro parameter ${t.parameter}")
            require(!p.discrete && t.minimum in p.min..p.max && t.maximum in p.min..p.max) { "Invalid range for ${p.name}" }
            require(assigned.add(t.node to t.parameter)) { "Parameter already assigned to a macro" }
        }
    }
    fun copy(nodes: List<ModularNode> =this.nodes, connections: List<ModularConnection> =this.connections,
        macros: List<ModularMacro> =this.macros, seed: Int=this.seed) = ModularGraph(
            Frozen(nodes,MAX_NODES),Frozen(connections,MAX_CONNECTIONS),Frozen(macros,MACROS),seed,
            maxOf(lastNodeId,nodes.maxOfOrNull { it.id } ?: 0), maxOf(lastConnectionId,connections.maxOfOrNull { it.id } ?: 0))
    fun nextNodeId() = nextId(lastNodeId)
    fun nextConnectionId() = nextId(lastConnectionId)
    fun withoutNode(id: Int) = copy(nodes=nodes.filterNot { it.id==id }, connections=connections.filterNot { it.source==id || it.destination==id },
        macros=macros.map { macro -> macro.copy(targets=macro.targets.filterNot { it.node==id }) })
    override fun equals(other: Any?) = this === other || other is ModularGraph && seed == other.seed &&
        lastNodeId == other.lastNodeId && lastConnectionId == other.lastConnectionId && nodes == other.nodes &&
        connections == other.connections && macros == other.macros
    override fun hashCode() = 31*(31*(31*(31*(31*seed+lastNodeId)+lastConnectionId)+nodes.hashCode())+connections.hashCode())+macros.hashCode()

    companion object {
        const val MAX_NODES=64; const val MAX_CONNECTIONS=256; const val MACROS=8; const val MAX_TARGETS=16
        const val MAX_MACRO_NAME=22; const val MAX_TRANSFORM=16000
        private fun nextId(last: Int): Int {
            require(last != Int.MAX_VALUE) { "Graph IDs exhausted" }
            return last + 1
        }
        fun initial() = ModularGraph(nodes=listOf(ModularNode(1,ModularKind.NOTE), ModularNode(2,ModularKind.OSC),
            ModularNode(3,ModularKind.VCA), ModularNode(4,ModularKind.OUT)),connections=listOf(
            ModularConnection(1,1,0,2,0),ModularConnection(2,2,0,3,0),ModularConnection(3,1,3,3,1),ModularConnection(4,3,0,4,0)))
    }
}

@Serializable @SerialName("modular") class ModularPatch private constructor(override val name: String, val graph: ModularGraph, private val controls: Controls) : StudioPatch {
    constructor(name: String="Modular", graph: ModularGraph=ModularGraph.initial(), values: Map<Int,Int> = emptyMap()) : this(name,graph,Controls(values))
    override val kind get() = StudioKind.MODULAR
    val values get() = controls.values
    init { require(name.length <= 22 && values.all { (k,v) -> k in 0..7 && v in 0..127 }) { "Invalid modular patch name or macro value" } }
    internal fun macro(id: Int): Int { require(id in 0..7); return controls.get(id) ?: 0 }
    fun copy(name: String=this.name, graph: ModularGraph=this.graph, values: Map<Int,Int> =this.values) = ModularPatch(name,graph,values)
    override fun equals(other: Any?) = other is ModularPatch && name == other.name && graph == other.graph && controls == other.controls
    override fun hashCode() = 31*(31*name.hashCode()+graph.hashCode())+controls.hashCode()
}
