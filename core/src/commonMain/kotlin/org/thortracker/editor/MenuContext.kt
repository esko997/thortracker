package org.thortracker.editor

/**
 * What a view may ask of its frontend's menus: choice popups and files (Android's document picker, the Deck's
 * controller file browser). The shared [org.thortracker.ui.AppMenus] implements it; a view reaches it through
 * [EditorSession.requestMenu], so a system's own view (the NES sample import) needs no platform code.
 */
interface MenuContext {
    /** A popup of [entries]; choosing one runs it. [selected] is where the cursor starts. */
    fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int = 0)

    /**
     * Lets the user choose a file ending in one of [extensions] (".wav", lower case); [picked] receives its name
     * without the extension and its bytes (at most [maxBytes]). Failures are reported through [error].
     */
    fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (name: String, extension: String, bytes: ByteArray) -> Unit)

    fun status(message: String)
    fun error(message: String)
}
