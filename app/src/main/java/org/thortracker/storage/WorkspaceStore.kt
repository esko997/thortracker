package org.thortracker.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap
import org.thortracker.model.Scale
import org.thortracker.model.ScaleKind

/** Device-local navigation/layout settings, deliberately outside musical project files. */
class WorkspaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    fun load(): MutableList<ViewState> {
        val defaults = mutableListOf(ViewState(kind = ViewKind.PATTERN), ViewState(kind = ViewKind.KEYBOARD))
        return try {
            val array = JSONArray(prefs.getString("views", "[]"))
            for (index in 0 until minOf(array.length(), defaults.size)) {
                val item = array.getJSONObject(index)
                defaults[index] = ViewState(
                    kind = ViewKind.entries.firstOrNull { it.name == item.optString("kind") } ?: defaults[index].kind,
                    pinned = item.optBoolean("pinned", true),
                    followSelection = item.optBoolean("follow", true),
                    lockedSubject = item.optInt("locked", -1),
                    order = item.optInt("order", 0).coerceAtLeast(0),
                    row = item.optInt("row", 0).coerceAtLeast(0),
                    channel = item.optInt("channel", 0).coerceAtLeast(0),
                    field = item.optInt("field", 0).coerceIn(0, 3),
                    instrument = item.optInt("instrument", 1).coerceAtLeast(1),
                    wave = item.optInt("wave", 0).coerceAtLeast(0),
                    scroll = item.optInt("scroll", 0).coerceAtLeast(0),
                    octave = item.optInt("octave", 4).coerceIn(3, 8),
                    bank = item.optInt("bank", 0).coerceAtLeast(0),
                    followPlayback = item.optBoolean("followPlayback", true),
                    entryMode = item.optBoolean("entryMode", false),
                    songItem = item.optInt("songItem", ViewState.SONG_HEADER_ITEMS).coerceAtLeast(0),
                    songField = item.optInt("songField", 0).coerceAtLeast(0),
                    instField = item.optInt("instField", 0).coerceAtLeast(0),
                    waveRow = item.optInt("waveRow", 1).coerceAtLeast(0),
                    waveCol = item.optInt("waveCol", 0).coerceAtLeast(0),
                    waveLevel = item.optInt("waveLevel", 8).coerceAtLeast(0),
                    kbRow = item.optInt("kbRow", 1).coerceAtLeast(0),
                    kbField = item.optInt("kbField", 0).coerceAtLeast(0),
                    kbKey = item.optInt("kbKey", 0).coerceAtLeast(0),
                    helpPage = item.optInt("helpPage", 0).coerceAtLeast(0),
                    textPos = item.optInt("textPos", 0).coerceAtLeast(0),
                    sketchScale = ScaleKind.entries.firstOrNull { it.name == item.optString("sketchScale") }
                        ?.let { Scale(item.optInt("sketchRoot", 0).coerceIn(0, 11), it) },
                    sketchRow = item.optInt("sketchRow", 1).coerceIn(0, 2),
                    sketchField = item.optInt("sketchField", 0).coerceIn(0, 4),
                    sketchChord = item.optInt("sketchChord", 0).coerceAtLeast(0),
                    sketchTone = item.optInt("sketchTone", 0).coerceAtLeast(0),
                )
            }
            defaults
        } catch (_: Exception) { defaults }
    }

    fun save(states: List<ViewState>, focused: Int) {
        val array = JSONArray()
        for (s in states) array.put(JSONObject().apply {
            put("kind", s.kind.name); put("pinned", s.pinned); put("follow", s.followSelection)
            put("locked", s.lockedSubject); put("order", s.order); put("row", s.row)
            put("channel", s.channel); put("field", s.field); put("instrument", s.instrument)
            put("wave", s.wave); put("scroll", s.scroll); put("octave", s.octave)
            put("bank", s.bank); put("followPlayback", s.followPlayback); put("entryMode", s.entryMode)
            put("songItem", s.songItem); put("songField", s.songField); put("instField", s.instField)
            put("waveRow", s.waveRow); put("waveCol", s.waveCol); put("waveLevel", s.waveLevel)
            put("kbRow", s.kbRow); put("kbField", s.kbField); put("kbKey", s.kbKey)
            put("helpPage", s.helpPage); put("textPos", s.textPos)
            s.sketchScale?.let { put("sketchRoot", it.root); put("sketchScale", it.kind.name) }
            put("sketchRow", s.sketchRow); put("sketchField", s.sketchField)
            put("sketchChord", s.sketchChord); put("sketchTone", s.sketchTone)
        })
        prefs.edit().putString("views", array.toString()).putInt("focus", focused).apply()
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
