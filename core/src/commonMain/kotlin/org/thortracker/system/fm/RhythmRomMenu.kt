package org.thortracker.system.fm

import org.thortracker.editor.MenuContext

/**
 * The rhythm ROM import (main menu on systems with [org.thortracker.system.SoundSystem.rhythmRom], and the rhythm
 * pages of their Instrument views): a picked file is checked ([Ym2608RhythmRom.problem]: exactly 8192 bytes with the
 * known SHA-256), then the host keeps it as device firmware and installs it in the native engine
 * ([MenuContext.installRhythmRom]). The bytes never go into a project or an export.
 */
object RhythmRomMenu {
    const val LABEL = "YM2608 rhythm ROM…"

    /** Largest file the picker reads: well above the ROM's 8 KiB, so a wrong file is reported by size, not refused unread. */
    const val MAX_FILE = 64 * 1024

    fun open(menu: MenuContext) {
        menu.pickFile("YM2608 rhythm ROM (${Ym2608RhythmRom.FILE_NAME})", Ym2608RhythmRom.EXTENSIONS, MAX_FILE) { name, extension, bytes ->
            import(menu, name + extension, bytes)
        }
    }

    /** Checks [bytes] read from [file] and installs them; the result goes to [menu]'s status or error line. */
    fun import(menu: MenuContext, file: String, bytes: ByteArray) {
        val problem = Ym2608RhythmRom.problem(bytes) ?: menu.installRhythmRom(bytes)
        if (problem != null) menu.error("$file was not installed: $problem")
        else menu.status("YM2608 rhythm ROM installed from $file: the six rhythm drums play")
    }
}
