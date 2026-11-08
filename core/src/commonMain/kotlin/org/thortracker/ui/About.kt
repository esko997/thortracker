package org.thortracker.ui

import org.thortracker.BuildInfo
import org.thortracker.input.Control
import org.thortracker.input.Keymap
import org.thortracker.model.Song
import kotlin.time.TimeSource

/**
 * Help → About: what the app is, where to report bugs, and the diagnostics text a bug report can carry. Everything
 * stays on the device: the user copies the text and pastes it into an issue themselves. No network, no telemetry.
 */
object About {
    /** GitHub's issue chooser (the bug report form in .github/ISSUE_TEMPLATE). */
    const val ISSUES_URL = "https://github.com/esko997/thortracker/issues/new/choose"

    /** "0.13.0 (build 25)". */
    val version: String get() = "${BuildInfo.VERSION_NAME} (build ${BuildInfo.VERSION_CODE})"

    /**
     * The diagnostics text: version, the [platform] lines (device, OS, displays, audio statistics) the host measured,
     * the current [song]'s system, the [keymap]'s file version and how many controls it rebinds, and the recent
     * status and error messages ([RecentMessages]). No song content, no file paths beyond what messages quote.
     */
    fun diagnostics(platform: List<Pair<String, String>>, song: Song, keymap: Keymap): String = buildString {
        appendLine("Thor Tracker diagnostics (made on request; nothing was sent anywhere)")
        appendLine("Version: $version")
        for ((key, value) in platform) appendLine("$key: $value")
        val system = song.system
        appendLine("Song system: ${system.name} [${system.id}], ${song.orders.size} orders, ${song.patterns.size} patterns")
        appendLine("Driver: ${system.engine.driver}")
        val rebound = Control.entries.count { keymap.binding(it) != Keymap.DEFAULT.binding(it) }
        appendLine("Keymap: file version ${Keymap.VERSION}, ${if (rebound == 0) "default bindings" else "$rebound of ${Control.entries.size} controls rebound"}")
        val recent = RecentMessages.all()
        appendLine("Recent messages (time since start):")
        if (recent.isEmpty()) appendLine("  none")
        for (line in recent) appendLine("  $line")
    }
}

/**
 * The last [SIZE] status and error messages the hosts showed, for the diagnostics; kept in memory only. Main thread
 * only (the hosts show messages there).
 */
object RecentMessages {
    const val SIZE = 20
    private val start = TimeSource.Monotonic.markNow()
    private val entries = ArrayDeque<String>()
    private var last: String? = null

    /** Remembers [message] of [kind] ("status", "error", "audio"); a repeat of the previous message is skipped. */
    fun record(kind: String, message: String) {
        val text = message.replace('\n', ' ')
        if (text == last) return
        last = text
        val s = start.elapsedNow().inWholeSeconds
        entries.addLast("+${s / 60}:${(s % 60).toString().padStart(2, '0')} $kind: $text")
        while (entries.size > SIZE) entries.removeFirst()
    }

    fun all(): List<String> = entries.toList()

    fun clear() {
        entries.clear()
        last = null
    }
}
