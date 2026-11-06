package org.thortracker.model

import java.util.Collections

/** Mutators throw [UnsupportedOperationException]. */
internal actual fun <T> readOnlyList(list: List<T>): List<T> = Collections.unmodifiableList(list)
