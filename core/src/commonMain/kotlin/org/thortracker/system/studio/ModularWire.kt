package org.thortracker.system.studio

/** Little-endian int32 words; native compiler checks the entire plan before publication. */
data class ModularResources(val nodes: Int, val connections: Int, val targets: Int, val workUnits: Int,
    val planBytes: Long, val wireBytes: Int) {
    companion object {
        const val BANK_BYTES = 264L  // 64-bit producer bank: refs, mask, 32 slot pointers
        const val VOICE_BYTES = 10240L  // includes DSP migration scratch; independent of render rate
    }
}
private val workByKind = intArrayOf(1,8,2,10,2,6,4,3,2,2,4,12)

/** Portable upper bound on native payload, excluding allocator overhead, not a playback gate. */
val ModularGraph.resources: ModularResources get() {
    val n=nodes.size; val e=connections.size; val t=macros.sumOf { it.targets.size }
    val raw=4*wordCount
    return ModularResources(n,e,t,nodes.sumOf { workByKind[it.kind.wire] }+e,
        288L+24L*n+20L*e+12L*t+16L+raw,8+raw)
}

internal val ModularGraph.wordCount get() = 5 + nodes.sumOf { 2 + it.kind.parameters.size } + connections.size*7 + macros.sumOf { 5*it.targets.size }

internal inline fun ModularGraph.writeWords(put: (Int) -> Unit) {
    put(1); put(seed); put(nodes.size); put(connections.size); put(macros.sumOf { it.targets.size })
    for (node in nodes) {
        put(node.id); put(node.kind.wire)
        for (p in node.kind.parameters) put(node.kind.physical(p.id,node.value(p.id)).toBits())
    }
    for (c in connections) {
        put(c.id); put(c.source); put(c.output); put(c.destination); put(c.input)
        put((c.scale/1000f).toBits()); put((c.offset/1000f).toBits())
    }
    for ((i,m) in macros.withIndex()) for (t in m.targets) {
        val kind = nodes.first { it.id == t.node }.kind
        put(i); put(t.node); put(t.parameter)
        put(kind.physical(t.parameter,t.minimum).toBits()); put(kind.physical(t.parameter,t.maximum).toBits())
    }
}
