package org.thortracker.system.studio

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure

/** Standard structural List equality; no mutable collection is exposed or retained from callers. */
internal class Frozen<T>(source: Collection<T>, limit: Int) : AbstractList<T>() {
    init { require(source.size <= limit) { "Collection exceeds $limit entries" } }
    private val items: List<T> = if (source is Frozen<T>) source.items else source.toList()
    override val size get() = items.size
    override fun get(index: Int) = items[index]
}

@OptIn(ExperimentalSerializationApi::class)
internal open class BoundedList<T>(private val element: KSerializer<T>, private val limit: Int) : KSerializer<List<T>> {
    private val list = ListSerializer(element)
    override val descriptor = list.descriptor
    override fun serialize(encoder: Encoder, value: List<T>) = encoder.encodeSerializableValue(list, value)
    override fun deserialize(decoder: Decoder): List<T> = decoder.decodeStructure(descriptor) {
        val items = ArrayList<T>()
        fun readAt(i: Int) {
            require(i == items.size && i < limit) { "Modular collection exceeds $limit entries" }
            items.add(decodeSerializableElement(descriptor,i,element))
        }
        if (decodeSequentially()) {
            val n = decodeCollectionSize(descriptor); require(n in 0..limit)
            repeat(n) { readAt(it) }
        } else while (true) {
            val i = decodeElementIndex(descriptor); if (i == CompositeDecoder.DECODE_DONE) break
            readAt(i)
        }
        Frozen(items, limit)
    }
}

internal object NodesWire : BoundedList<ModularNode>(ModularNode.serializer(), ModularGraph.MAX_NODES)
internal object CablesWire : BoundedList<ModularConnection>(ModularConnection.serializer(), ModularGraph.MAX_CONNECTIONS)
internal object MacrosWire : BoundedList<ModularMacro>(ModularMacro.serializer(), ModularGraph.MACROS)
internal object TargetsWire : BoundedList<ModularTarget>(ModularTarget.serializer(), ModularGraph.MAX_TARGETS)

@Serializable internal class Setting(override val key: Int, override val value: Int) : Map.Entry<Int,Int> {
    override fun equals(other: Any?) = other is Map.Entry<*,*> && other.key == key && other.value == value
    override fun hashCode() = key.hashCode() xor value.hashCode()
}
internal object SettingsWire : BoundedList<Setting>(Setting.serializer(), 8)

/** Stored in key order; at most eight controls. Keep the backing map inaccessible. */
@Serializable internal class Controls private constructor(@Serializable(with=SettingsWire::class) private val settings: List<Setting>) {
    constructor(values: Map<Int,Int>) : this(values.let {
        require(it.size <= 8); Frozen(it.map { (k,v) -> Setting(k,v) }.sortedBy { it.key }, 8)
    })
    init { require(settings.size <= 8 && settings.zipWithNext().all { (a,b) -> a.key < b.key }) { "Duplicate or unordered modular controls" } }
    @Transient val values: Map<Int,Int> = object : AbstractMap<Int,Int>() {
        override val entries: Set<Map.Entry<Int,Int>> = object : AbstractSet<Map.Entry<Int,Int>>() {
            override val size get() = settings.size
            override fun iterator(): Iterator<Map.Entry<Int,Int>> = settings.iterator()
        }
        override fun get(key: Int) = settings.firstOrNull { it.key == key }?.value
    }
    fun get(id: Int) = settings.firstOrNull { it.key == id }?.value
    override fun equals(other: Any?) = other is Controls && settings == other.settings
    override fun hashCode() = settings.hashCode()
}
