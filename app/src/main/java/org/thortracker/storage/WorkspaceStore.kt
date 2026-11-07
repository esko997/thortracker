package org.thortracker.storage

import android.content.Context
import org.json.JSONObject
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap

/** Device-local navigation/layout settings, deliberately outside musical project files. */
class WorkspaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    fun load(): MutableList<ViewState> = WorkspaceCodec.decode(prefs.getString("views", "[]") ?: "[]")

    fun save(states: List<ViewState>, focused: Int) {
        prefs.edit().putString("views", WorkspaceCodec.encode(states)).putInt("focus", focused).apply()
    }

    fun focused(): Int = prefs.getInt("focus", 0).coerceIn(0, 1)

    /**
     * The controller keymap (versioned JSON, the same format as exported keymap files). Upgrading the app keeps
     * it; uninstalling or clearing data removes it, so users export a copy. Remaps saved by 0.1-0.4 are
     * converted once. An unreadable stored keymap falls back to the defaults.
     */
    fun keymap(): Keymap {
        prefs.getString("keymap", null)?.let { text ->
            return try { Keymap.fromJson(text) } catch (_: IllegalArgumentException) { Keymap.DEFAULT }
        }
        val legacy = HashMap<Int, Int>()
        try {
            val objectValue = JSONObject(prefs.getString("bindings", "{}") ?: "{}")
            objectValue.keys().forEach { key -> key.toIntOrNull()?.let { legacy[it] = objectValue.getInt(key) } }
        } catch (_: Exception) { }
        val migrated = Keymap.fromLegacyRemap(legacy)
        saveKeymap(migrated)
        return migrated
    }

    fun saveKeymap(keymap: Keymap) {
        prefs.edit().putString("keymap", keymap.toJson()).remove("bindings").apply()
    }
}
