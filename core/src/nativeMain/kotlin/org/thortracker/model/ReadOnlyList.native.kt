package org.thortracker.model

/** Implements only [List], so a cast to [MutableList] fails. */
internal actual fun <T> readOnlyList(list: List<T>): List<T> = ReadOnly(list)

private class ReadOnly<T>(private val list: List<T>) : List<T> by list {
    override fun equals(other: Any?): Boolean = list == other
    override fun hashCode(): Int = list.hashCode()
    override fun toString(): String = list.toString()
}
