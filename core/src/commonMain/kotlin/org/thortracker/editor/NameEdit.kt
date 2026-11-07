package org.thortracker.editor

/** Editing names (song title, artist, sounds) with the controller: one character at a time from a fixed set. */
object NameEdit {
    /** Characters available when editing names with the controller. */
    const val CHARSET = " ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.'!#&()+/:"

    fun cycleChar(c: Char, delta: Int): Char {
        val i = CHARSET.indexOf(c).coerceAtLeast(0)
        return CHARSET[(i + delta).mod(CHARSET.length)]
    }

    /** Replaces (or appends) the character at [pos] by cycling it [delta] steps; trims trailing spaces. */
    fun edit(name: String, pos: Int, delta: Int, maxLen: Int): String {
        val padded = StringBuilder(name)
        while (padded.length <= pos && padded.length < maxLen) padded.append(' ')
        if (pos >= padded.length) return name
        padded[pos] = cycleChar(padded[pos], delta)
        return padded.toString().trimEnd()
    }
}
