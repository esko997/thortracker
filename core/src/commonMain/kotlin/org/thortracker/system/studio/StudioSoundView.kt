package org.thortracker.system.studio

import org.thortracker.editor.Action
import org.thortracker.editor.Clip
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Song
import org.thortracker.system.SystemView
import org.thortracker.system.panel.MusicalPanelMode
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/** Sound and Advanced edit the very same integers in the stored patch. */
internal object StudioSoundView : SystemView() {
    const val MODE = -1
    const val NAME = 1
    private const val FIRST_PARAM = 2
    private const val NAME_LIMIT = 22
    private val init = MonoPatch()
    private data class PatchClip(val patch: MonoPatch) : Clip

    enum class Param(val label: String, val help: String, val min: Int = 0, val max: Int = 100, val coarse: Int = 10) {
        WAVE("Wave", "Saw is bright; square is hollow.", max = 1, coarse = 0),
        CUTOFF("Cutoff", "Open the filter for more brightness."),
        RESONANCE("Resonance", "Emphasize the cutoff frequency."),
        ENVELOPE("Envelope", "How far the filter opens on a note."),
        DECAY("Decay", "How slowly the filter closes."),
        ACCENT("Accent", "Boost on accented rows."),
        GLIDE("Glide", "Pitch glide time on tied notes."),
        TUNE("Tune", "Transpose this patch in semitones.", -24, 24, 12),
        LEVEL("Level", "Set this patch's output level.");

        val field: Int get() = ordinal + FIRST_PARAM
        val undoLabel = "Set ${label.lowercase()}"

        fun read(p: MonoPatch): Int = when (this) {
            WAVE -> p.waveform
            CUTOFF -> p.cutoff
            RESONANCE -> p.resonance
            ENVELOPE -> p.envelope
            DECAY -> p.decay
            ACCENT -> p.accent
            GLIDE -> p.glide
            TUNE -> p.tune
            LEVEL -> p.level
        }

        fun write(p: MonoPatch, value: Int): MonoPatch {
            val n = value.coerceIn(min, max)
            if (read(p) == n) return p
            return when (this) {
                WAVE -> p.copy(waveform = n)
                CUTOFF -> p.copy(cutoff = n)
                RESONANCE -> p.copy(resonance = n)
                ENVELOPE -> p.copy(envelope = n)
                DECAY -> p.copy(decay = n)
                ACCENT -> p.copy(accent = n)
                GLIDE -> p.copy(glide = n)
                TUNE -> p.copy(tune = n)
                LEVEL -> p.copy(level = n)
            }
        }
    }

    fun panel(st: ViewState) = st.soundPanel(StudioSystem.ID)
    fun patch(song: Song, st: ViewState) = song.studio.patch(st.instrument) ?: init
    fun param(st: ViewState): Param? = Param.entries.getOrNull(st.instField - FIRST_PARAM)
    fun waveName(value: Int) = if (value == 0) "Saw" else "Square"
    override val gestureLabel = "Edit mono patch"

    override fun normalise(v: ViewController) {
        val st = v.state
        st.instrument = st.instrument.coerceIn(1, StudioSound.SLOTS)
        val lane = st.listenChannel.coerceIn(v.song.studio.tracks.indices)
        if (st.listenChannel != lane) {
            panel(st).invalidate()
            st.listenChannel = lane
        }
        st.instField = when {
            st.instField < 0 -> MODE
            st.instField == 0 -> Param.WAVE.field
            else -> st.instField.coerceAtMost(Param.LEVEL.field)
        }
        st.textPos = st.textPos.coerceIn(0, NAME_LIMIT - 1)
    }

    override fun subject(state: ViewState) = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        val slot = subject.coerceIn(1, StudioSound.SLOTS)
        if (state.instrument != slot) panel(state).invalidate()
        state.instrument = slot
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        val slot = selection.instrument.coerceIn(1, StudioSound.SLOTS)
        if (state.instrument != slot || state.listenChannel != selection.channel) panel(state).invalidate()
        state.instrument = slot
        state.listenChannel = selection.channel
    }

    fun selectMode(v: ViewController, advanced: Boolean, keepModeFocus: Boolean = false) {
        MusicalPanelMode.select(v, panel(v.state), advanced, keepModeFocus)
        normalise(v)
    }

    fun selectSlot(v: ViewController, slot: Int) {
        if (slot !in 1..StudioSound.SLOTS) return
        val st = v.state
        panel(st).invalidate()
        blockHeldEdit(v)
        val before = st.instrument
        st.instrument = slot
        v.syncLock()
        v.session.select(v.session.selection.copy(bank = 0, instrument = st.instrument, context = ViewKind.KEYBOARD))
        if (st.instrument != before && v.auditionHeld) audition(v)
    }

    fun selectField(v: ViewController, field: Int) {
        val st = v.state
        if (st.instField == field) return
        val p = panel(st)
        p.invalidate()
        if (field == MODE && st.instField >= NAME) {
            if (p.advanced) p.advancedField = st.instField else p.soundField = st.instField
        }
        st.instField = field
        st.textPos = 0
    }

    private fun cycleField(v: ViewController, delta: Int) {
        val current = v.state.instField.coerceAtLeast(0)
        val next = (current + delta).mod(Param.LEVEL.field + 1)
        selectField(v, if (next == 0) MODE else next)
    }

    fun blockHeldEdit(v: ViewController) {
        if (v.clearHeld) v.useClear()
        if (v.editHeld) {
            v.useEdit()
            panel(v.state).editBlocked = true
        }
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val p = panel(st)
        if (!v.editHeld) {
            if (dy != 0) selectSlot(v, st.instrument - dy)
            else if (dx != 0) cycleField(v, dx)
            return
        }
        v.useEdit()
        if (p.editBlocked) return
        when (st.instField) {
            MODE -> if (dx != 0) selectMode(v, dx > 0, keepModeFocus = true)
            NAME -> if (dx == 0) st.textPos = (st.textPos - dy).coerceIn(0, NAME_LIMIT - 1)
                else rename(v, NameEdit.edit(patch(v.song, st).name, st.textPos, dx, NAME_LIMIT))
            else -> param(st)?.let { adjust(v, it, dx + dy * it.coarse, preview = true) }
        }
    }

    fun set(v: ViewController, param: Param, value: Int, preview: Boolean = false) {
        val current = patch(v.song, v.state)
        val next = param.write(current, value)
        if (next !== current && store(v, next, param.undoLabel) &&
            (v.auditionHeld || (preview && v.editHeld))) playPatch(v, reportMissingTrack = v.auditionHeld)
    }

    fun adjust(v: ViewController, param: Param, delta: Int, preview: Boolean = false) =
        set(v, param, param.read(patch(v.song, v.state)) + delta, preview)

    private fun rename(v: ViewController, name: String) {
        val current = patch(v.song, v.state)
        val next = name.take(NAME_LIMIT)
        if (next != current.name) store(v, current.copy(name = next), "Rename sound")
    }

    private fun store(v: ViewController, value: MonoPatch?, label: String): Boolean {
        val slot = v.state.instrument
        return v.session.apply(label) { s -> s.copy(sound = s.studio.withPatch(slot, value)) }
    }

    override fun editPress(v: ViewController) {
        val p = panel(v.state)
        if (p.cancelTouch != null) p.invalidate()
        p.editBlocked = false
        if (param(v.state) != null && !v.auditionHeld) playPatch(v, reportMissingTrack = false)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        val st = v.state
        val p = panel(st)
        val blocked = p.editBlocked
        p.editBlocked = false
        if (blocked || !tap) return
        when {
            st.instField == MODE -> selectMode(v, !p.advanced, keepModeFocus = true)
            st.instField == NAME -> requestEntry(v)
            p.advanced -> requestEntry(v)
        }
    }

    override fun clear(v: ViewController) {
        if (v.editHeld && panel(v.state).editBlocked) return
        panel(v.state).invalidate()
        if (v.state.instField == NAME) rename(v, ViewController.deleteChar(patch(v.song, v.state).name, v.state.textPos))
        else param(v.state)?.let { set(v, it, it.read(init)) }
    }

    /** Menus may outlive their drawer. Reject delivery after an edit, focus/slot/mode change, swap or project load. */
    fun requestEntry(v: ViewController) {
        val st = v.state
        val p = panel(st)
        p.invalidate()
        val revision = p.revision
        val version = v.session.documentVersion
        val slot = st.instrument
        val field = st.instField
        val current = patch(v.song, st)
        var consumed = false
        fun accept(): Boolean {
            if (consumed) return false
            consumed = true
            return v.state === st && st.kind == ViewKind.INSTRUMENT && st.instField == field && st.instrument == slot &&
                p.revision == revision && v.session.documentVersion == version
        }
        when (val param = param(st)) {
            null -> if (field == NAME) v.session.requestText("Sound name", current.name, NAME_LIMIT) {
                if (accept()) rename(v, it)
            }
            Param.WAVE -> v.session.requestMenu { menu ->
                menu.menu("Wave", List(2) { wave -> waveName(wave) to { if (accept()) set(v, param, wave) } }, current.waveform)
            }
            else -> v.session.requestMenu { menu ->
                menu.text("${param.label} (${param.min}–${param.max})", param.read(current).toString(), 4) { text ->
                    if (accept()) {
                        val n = text.trim().toIntOrNull()
                        if (n == null || n < param.min || n > param.max) {
                            menu.error("Enter a whole number from ${param.min} to ${param.max}")
                        } else set(v, param, n)
                    }
                }
            }
        }
    }

    override fun audition(v: ViewController) = playPatch(v, reportMissingTrack = true)

    private fun playPatch(v: ViewController, reportMissingTrack: Boolean) {
        val st = v.state
        val tracks = v.song.studio.tracks
        // Sound slots never address USB ports. Pattern/Keyboard audition still respects the track destination.
        val lane = if (tracks.getOrNull(st.listenChannel)?.target == StudioTarget.MONO) st.listenChannel
            else tracks.indexOfFirst { it.target == StudioTarget.MONO }
        if (lane >= 0) v.startAudition(lane, v.defaultNote(), st.instrument)
        else if (reportMissingTrack) v.session.note("Assign a Mono track to audition patches")
    }

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                // A shoulder navigation is also a target change while Edit is held.
                blockHeldEdit(v)
                cycleField(v, if (a == Action.NEXT_CHANNEL) 1 else -1)
            }
            Action.COPY -> v.session.hold(PatchClip(patch(v.song, st)))
            Action.CUT, Action.PASTE, Action.DUPLICATE -> {
                panel(st).invalidate()
                blockHeldEdit(v)
                when (a) {
                    Action.CUT -> {
                        v.session.hold(PatchClip(patch(v.song, st)))
                        store(v, null, "Cut sound")
                    }
                    Action.PASTE -> (v.session.clipboard as? PatchClip)?.let { store(v, it.patch, "Paste sound") }
                    else -> if (st.instrument == StudioSound.SLOTS) v.session.note("Already at the last sound slot") else {
                        val source = patch(v.song, st)
                        val slot = st.instrument + 1
                        v.session.apply("Duplicate sound") { s -> s.copy(sound = s.studio.withPatch(slot, source)) }
                        selectSlot(v, slot)
                    }
                }
                if (v.auditionHeld && a != Action.DUPLICATE) audition(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        val st = v.state
        h += Hint(v.ud(), "Sound slot")
        h += Hint(v.lr(), "Parameter")
        h += Hint(v.editLr(), when (st.instField) { MODE -> "Switch face"; NAME -> "Change letter"; else -> "Adjust" })
        h += Hint(v.k(Action.AUDITION), "Listen")
        if (st.instField == NAME) h += Hint(v.editUd(), "Letter position")
        h += Hint(v.octaves(), "Listen pitch")
        h += Hint(v.copyPaste(), "Copy/paste sound")
    }

    override fun painter(pane: PaneView): Painter = StudioSoundPainter(pane)
}
