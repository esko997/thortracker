package org.thortracker.model

/** A view of [list] that cannot be modified, even through a cast to [MutableList]. */
internal expect fun <T> readOnlyList(list: List<T>): List<T>
