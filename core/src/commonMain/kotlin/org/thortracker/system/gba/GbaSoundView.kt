package org.thortracker.system.gba

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.replaced
import org.thortracker.model.Song
import org.thortracker.system.SystemView
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/** One sampler document, two presentations. Every parameter uses the same setter for touch and controller. */
object GbaSoundView : SystemView() {
    internal enum class Row(val label: String) {
        MODE("Editor"), NAME("Name"), PAGE("Page"), LISTEN("Listen lane"), KEY("Key / listen note"),
        SAMPLE("Sample library"), MAP("Key mapping"), VOLUME("Sample volume"), PAN("Sample pan"), ROOT("Root note"),
        RATE("Source rate Hz"), TUNE("Fine tune /128"), LOOP("Forward loop"), LOOP_START("Loop first frame"), LOOP_END("Loop end excl."),
        TOOLS("Sample tools"), ENABLED("Envelope on"), POINT("Point"), TICK("Point tick"), VALUE("Point value"),
        SUSTAIN("Sustain point"), ENV_LOOP_START("Loop first point"), ENV_LOOP_END("Loop last point"),
        ADD_POINT("Add point"), REMOVE_POINT("Delete point"), FADEOUT("OFF fadeout /32")
    }
    private val soundRows = listOf(Row.MODE, Row.NAME, Row.KEY, Row.SAMPLE, Row.MAP, Row.VOLUME, Row.PAN, Row.LOOP, Row.TOOLS)
    private val sampleRows = listOf(Row.SAMPLE, Row.ROOT, Row.RATE, Row.TUNE, Row.VOLUME, Row.PAN, Row.LOOP, Row.LOOP_START, Row.LOOP_END, Row.TOOLS)
    private val mapRows = listOf(Row.KEY, Row.SAMPLE, Row.MAP, Row.TOOLS)
    private val envRows = listOf(Row.ENABLED, Row.POINT, Row.TICK, Row.VALUE, Row.SUSTAIN, Row.ENV_LOOP_START, Row.ENV_LOOP_END, Row.ADD_POINT, Row.REMOVE_POINT, Row.FADEOUT)
    private val advancedRows = listOf(sampleRows, mapRows, envRows, envRows).map {
        listOf(Row.MODE, Row.NAME, Row.PAGE, Row.LISTEN) + it
    }
    internal val pages = listOf("Sample", "Keymap", "Volume envelope", "Pan envelope")
    internal fun advanced(st: ViewState) = st.soundPanel("gba-maxmod").advanced
    internal fun rows(st: ViewState): List<Row> = if (!advanced(st)) soundRows else advancedRows[st.envelope.coerceIn(0, 3)]
    internal fun row(st: ViewState) = rows(st)[st.instField.coerceIn(0, rows(st).lastIndex)]
    internal fun instrument(song: Song, st: ViewState) = song.gba.instrument(st.instrument) ?: GbaInstrument()
    internal fun sample(song: Song, st: ViewState) = song.gba.samples.getOrNull(st.dpcmSample)
    internal fun envelope(song: Song, st: ViewState): GbaEnvelope = instrument(song, st).let { if (st.envelope == 3) it.panEnvelope else it.volumeEnvelope }
    override val gestureLabel = "Edit GBA sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        st.instrument = st.instrument.coerceIn(1, GbaSound.SLOTS)
        st.listenChannel = st.listenChannel.coerceIn(0, 7)
        st.envelope = st.envelope.coerceIn(0, 3)
        st.instField = st.instField.coerceIn(0, rows(st).lastIndex)
        st.dpcmKey = (st.octave.coerceIn(0, 7) * 12 + st.dpcmKey.mod(12)).coerceIn(0, 95)
        st.dpcmSample = st.dpcmSample.coerceIn(0, maxOf(0, v.session.song.gba.samples.lastIndex))
        st.envStep = st.envStep.coerceIn(0, maxOf(0, envelope(v.session.song, st).points.lastIndex))
        st.textPos = st.textPos.coerceIn(0, 21)
    }
    override fun subject(state: ViewState) = state.instrument
    override fun applyLock(state: ViewState, subject: Int) {
        if (state.instrument != subject) state.soundPanel("gba-maxmod").invalidate()
        state.instrument = subject.coerceIn(1, GbaSound.SLOTS)
    }
    override fun follow(state: ViewState, selection: Selection, song: Song) {
        if (state.instrument != selection.instrument || state.listenChannel != selection.channel) state.soundPanel("gba-maxmod").invalidate()
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }
    internal fun publish(v: ViewController) {
        v.syncLock()
        v.session.select(v.session.selection.copy(bank = 0, instrument = v.state.instrument, context = ViewKind.KEYBOARD))
    }
    internal fun selectMode(v: ViewController, advanced: Boolean) {
        val panel = v.state.soundPanel("gba-maxmod")
        if (panel.advanced == advanced) return
        panel.invalidate()
        if (panel.advanced) panel.advancedField = v.state.instField else panel.soundField = v.state.instField
        panel.advanced = advanced
        v.state.instField = (if (advanced) panel.advancedField else panel.soundField).coerceIn(0, rows(v.state).lastIndex)
        if (v.editHeld) { v.useEdit(); panel.editBlocked = true }
    }
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val next = (st.instrument + delta).coerceIn(1, GbaSound.SLOTS)
        if (next == st.instrument) return
        st.soundPanel("gba-maxmod").invalidate()
        if (v.editHeld) { v.useEdit(); st.soundPanel("gba-maxmod").editBlocked = true }
        st.instrument = next
        val mapped = instrument(v.session.song, st).keymap.getOrNull(st.dpcmKey) ?: -1
        if (mapped >= 0) st.dpcmSample = mapped
        publish(v)
        if (v.auditionHeld) audition(v)
    }
    internal fun selectPage(v: ViewController, page: Int) {
        val st = v.state
        st.soundPanel("gba-maxmod").invalidate()
        st.envelope = page.mod(4)
        st.envStep = 0
        st.instField = rows(st).indexOf(Row.PAGE)
        if (v.editHeld) { v.useEdit(); st.soundPanel("gba-maxmod").editBlocked = true }
    }
    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        if (v.editHeld) {
            v.useEdit()
            if (st.soundPanel("gba-maxmod").editBlocked) return
            if (row(st) == Row.NAME && dy != 0) { st.textPos = (st.textPos - dy).coerceIn(0, 21); return }
            adjust(v, dx + dy * coarse(v, row(st)))
        } else if (dy != 0) moveSlot(v, -dy)
        else {
            st.soundPanel("gba-maxmod").invalidate()
            st.instField = (st.instField + dx).mod(rows(st).size)
            st.textPos = 0
        }
    }
    internal fun coarse(v: ViewController, row: Row): Int = when (row) {
        Row.KEY, Row.ROOT -> 12
        Row.RATE -> 1000
        Row.LOOP_START, Row.LOOP_END -> maxOf(1, (sample(v.session.song, v.state)?.pcm?.size ?: 1) / 32)
        Row.TUNE, Row.PAN -> 16
        Row.VOLUME, Row.TICK, Row.VALUE -> 8
        Row.FADEOUT -> 8
        else -> 1
    }
    internal fun range(v: ViewController, row: Row): IntRange? {
        val st = v.state
        val sample = sample(v.session.song, st)
        val env = envelope(v.session.song, st)
        if (sample == null && row in sampleRows && row != Row.TOOLS) return null
        if (env.points.isEmpty() && row in listOf(Row.POINT, Row.TICK, Row.VALUE, Row.SUSTAIN)) return null
        if (env.points.size < 2 && row in listOf(Row.ENV_LOOP_START, Row.ENV_LOOP_END)) return null
        return when (row) {
            Row.LISTEN -> 0..7
            Row.KEY, Row.ROOT -> 0..95
            Row.SAMPLE -> 0..maxOf(0, v.session.song.gba.samples.lastIndex)
            Row.MAP -> -1..v.session.song.gba.samples.lastIndex
            Row.VOLUME, Row.VALUE -> 0..64
            Row.PAN -> 0..255
            Row.RATE -> 1..GbaSample.MAX_RATE
            Row.TUNE -> -128..127
            Row.LOOP, Row.ENABLED -> 0..1
            Row.LOOP_START -> if (sample != null && sample.loopEnd > 0) 0..(sample.loopEnd - 1) else null
            Row.LOOP_END -> if (sample != null && sample.loopEnd > 0) (sample.loopStart + 1)..sample.pcm.size else null
            Row.POINT -> 0..maxOf(0, env.points.lastIndex)
            Row.TICK -> if (st.envStep == 0) 0..0 else ((env.points.getOrNull(st.envStep - 1)?.tick ?: -1) + 1)..
                ((env.points.getOrNull(st.envStep + 1)?.tick ?: 256) - 1)
            Row.SUSTAIN -> -1..env.points.lastIndex
            Row.ENV_LOOP_START -> -1..maxOf(0, env.points.lastIndex - 1)
            Row.ENV_LOOP_END -> -1..env.points.lastIndex
            Row.FADEOUT -> 0..255
            else -> null
        }
    }
    internal fun value(v: ViewController, row: Row): Int {
        val st = v.state
        val sample = sample(v.session.song, st)
        val env = envelope(v.session.song, st)
        return when (row) {
            Row.LISTEN -> st.listenChannel
            Row.KEY -> st.dpcmKey
            Row.SAMPLE -> st.dpcmSample
            Row.MAP -> instrument(v.session.song, st).keymap.getOrNull(st.dpcmKey) ?: -1
            Row.VOLUME -> sample?.volume ?: 64
            Row.PAN -> sample?.pan ?: 128
            Row.ROOT -> sample?.rootNote ?: 48
            Row.RATE -> sample?.sampleRate ?: 8363
            Row.TUNE -> sample?.fineTune ?: 0
            Row.LOOP -> if (sample != null && sample.loopEnd > sample.loopStart) 1 else 0
            Row.LOOP_START -> sample?.loopStart ?: 0
            Row.LOOP_END -> sample?.loopEnd ?: 0
            Row.ENABLED -> if (env.enabled) 1 else 0
            Row.POINT -> st.envStep
            Row.TICK -> env.points.getOrNull(st.envStep)?.tick ?: 0
            Row.VALUE -> env.points.getOrNull(st.envStep)?.value ?: 64
            Row.SUSTAIN -> env.sustain
            Row.ENV_LOOP_START -> env.loopStart
            Row.ENV_LOOP_END -> env.loopEnd
            Row.FADEOUT -> instrument(v.session.song, st).fadeout / 32
            else -> 0
        }
    }
    internal fun adjust(v: ViewController, delta: Int) {
        if (delta == 0) return
        when (val row = row(v.state)) {
            Row.MODE -> selectMode(v, delta > 0)
            Row.PAGE -> selectPage(v, v.state.envelope + delta)
            Row.NAME -> safely(v) { v.session.editGbaInstrument(v.state.instrument, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, v.state.textPos, delta, 22))
            } }
            else -> range(v, row)?.let { set(v, row, (value(v, row) + delta).coerceIn(it)) }
        }
    }
    internal fun set(v: ViewController, row: Row, value: Int) {
        val st = v.state
        val n = range(v, row)?.let { value.coerceIn(it) } ?: return
        when (row) {
            Row.LISTEN -> { st.listenChannel = n; if (v.auditionHeld) audition(v); return }
            Row.KEY -> { st.dpcmKey = n; st.octave = n / 12; if (v.auditionHeld) audition(v); return }
            Row.SAMPLE -> { st.dpcmSample = n; return }
            Row.POINT -> { st.envStep = n; return }
            else -> Unit
        }
        safely(v) {
            when (row) {
                Row.MAP -> v.session.editGbaInstrument(st.instrument, "Map key") { it.copy(keymap = it.keymap.replaced(st.dpcmKey, n)) }
                Row.FADEOUT -> v.session.editGbaInstrument(st.instrument, "Set fadeout") { it.copy(fadeout = n * 32) }
                Row.ENABLED, Row.TICK, Row.VALUE, Row.SUSTAIN, Row.ENV_LOOP_START, Row.ENV_LOOP_END -> editEnvelope(v, row.label) { e ->
                    when (row) {
                        Row.ENABLED -> e.copy(enabled = n != 0)
                        Row.TICK -> e.copy(points = e.points.replaced(st.envStep, e.points[st.envStep].copy(tick = n)))
                        Row.VALUE -> e.copy(points = e.points.replaced(st.envStep, e.points[st.envStep].copy(value = n)))
                        Row.SUSTAIN -> e.copy(sustain = n)
                        Row.ENV_LOOP_START -> if (n < 0) e.copy(loopStart = -1, loopEnd = -1) else e.copy(loopStart = n, loopEnd = maxOf(n + 1, e.loopEnd))
                        else -> if (n < 0) e.copy(loopStart = -1, loopEnd = -1) else if (n > 0) e.copy(loopStart = e.loopStart.coerceIn(0, n - 1), loopEnd = n) else e
                    }
                }
                else -> v.session.editGbaSample(st.dpcmSample, "Set ${row.label.lowercase()}") { s -> when (row) {
                    Row.VOLUME -> s.copy(volume = n)
                    Row.PAN -> s.copy(pan = n)
                    Row.ROOT -> s.copy(rootNote = n)
                    Row.RATE -> s.copy(sampleRate = n)
                    Row.TUNE -> s.copy(fineTune = n)
                    Row.LOOP -> if (n == 0) s.copy(loopStart = 0, loopEnd = 0) else s.copy(loopStart = 0, loopEnd = s.pcm.size)
                    Row.LOOP_START -> s.copy(loopStart = n)
                    Row.LOOP_END -> s.copy(loopEnd = n)
                    else -> s
                } }
            }
        }
    }
    internal fun editEnvelope(v: ViewController, label: String, change: (GbaEnvelope) -> GbaEnvelope): Boolean =
        v.session.editGbaInstrument(v.state.instrument, label) { ins ->
            if (v.state.envelope == 3) ins.copy(panEnvelope = change(ins.panEnvelope)) else ins.copy(volumeEnvelope = change(ins.volumeEnvelope))
        }
    internal fun safely(v: ViewController, action: () -> Boolean) {
        try { if (action()) heard(v) } catch (e: IllegalArgumentException) { v.session.note(e.message ?: "Cannot edit sound") }
    }
    /** Accepted touch changes audition even after the pointer blocks held-direction edits. */
    internal fun heard(v: ViewController) { if (v.auditionHeld || v.editHeld) audition(v) }
    override fun editPress(v: ViewController) {
        v.state.soundPanel("gba-maxmod").editBlocked = false
        if (row(v.state) !in listOf(Row.MODE, Row.PAGE, Row.NAME, Row.TOOLS)) audition(v)
    }
    override fun editRelease(v: ViewController, tap: Boolean) {
        if (!v.auditionHeld) v.session.releaseAudition()
        val panel = v.state.soundPanel("gba-maxmod")
        if (panel.editBlocked) { panel.editBlocked = false; return }
        if (tap) activate(v)
    }
    internal fun activate(v: ViewController) {
        val st = v.state
        if (sample(v.session.song, st) == null && row(st) in sampleRows && row(st) != Row.TOOLS) {
            v.session.note("Add a WAV or generated sample in Sample tools first")
            return
        }
        if (row(st) in listOf(Row.LOOP_START, Row.LOOP_END) && range(v, row(st)) == null) {
            v.session.note("Enable Forward loop before setting its frame bounds")
            return
        }
        when (row(st)) {
            Row.MODE -> selectMode(v, !advanced(st))
            Row.PAGE -> selectPage(v, st.envelope + 1)
            Row.TOOLS -> GbaSampleMenu.open(v)
            Row.ADD_POINT -> safely(v) { editEnvelope(v, "Add envelope point", GbaEnvelopeEdits::append).also {
                st.envStep = envelope(v.session.song, st).points.lastIndex
            } }
            Row.REMOVE_POINT -> safely(v) { editEnvelope(v, "Remove envelope point") { GbaEnvelopeEdits.remove(it, st.envStep) }.also { normalise(v) } }
            Row.LOOP, Row.ENABLED -> set(v, row(st), 1 - value(v, row(st)))
            Row.NAME -> requestValue(v)
            else -> requestValue(v)
        }
    }
    internal fun requestValue(v: ViewController) {
        val st = v.state
        val row = row(st)
        val before = v.session.song
        val slot = st.instrument
        val field = st.instField
        val revision = st.soundPanel("gba-maxmod").revision
        val version = v.session.documentVersion
        val sample = st.dpcmSample
        val key = st.dpcmKey
        val point = st.envStep
        val page = st.envelope
        fun live() = v.state === st && st.instrument == slot && st.instField == field && v.session.song === before &&
            v.session.documentVersion == version && st.dpcmSample == sample && st.dpcmKey == key &&
            st.envStep == point && st.envelope == page &&
            st.soundPanel("gba-maxmod").revision == revision && st.kind == ViewKind.INSTRUMENT
        if (row == Row.NAME) {
            v.session.requestText("Instrument name", instrument(before, st).name, 22) { text ->
                if (live()) safely(v) { v.session.editGbaInstrument(slot, "Rename sound") { it.copy(name = text) } }
            }
        } else {
            val range = range(v, row)
            if (range == null) { v.session.note("Add an envelope point before editing this setting"); return }
            if (range.first == range.last) { v.session.note("${row.label} is fixed at ${range.first}"); return }
            v.session.requestMenu { menu ->
                GbaSampleMenu.number(menu, row.label, value(v, row), range) { n -> if (live()) set(v, row, n) }
            }
        }
    }
    override fun clear(v: ViewController) {
        if (v.state.soundPanel("gba-maxmod").editBlocked && v.editHeld) return
        v.state.soundPanel("gba-maxmod").invalidate()
        val row = row(v.state)
        when (row) {
            Row.NAME -> safely(v) { v.session.editGbaInstrument(v.state.instrument, "Rename sound") {
                it.copy(name = ViewController.deleteChar(it.name, v.state.textPos))
            } }
            Row.MAP, Row.SUSTAIN, Row.ENV_LOOP_START, Row.ENV_LOOP_END -> set(v, row, -1)
            Row.LOOP, Row.ENABLED, Row.FADEOUT -> set(v, row, 0)
            Row.VALUE -> set(v, row, if (v.state.envelope == 3) 32 else 64)
            Row.TUNE -> set(v, row, 0)
            Row.VOLUME -> set(v, row, 64)
            Row.PAN -> set(v, row, 128)
            Row.ROOT -> set(v, row, 48)
            Row.REMOVE_POINT, Row.POINT -> safely(v) { editEnvelope(v, "Delete envelope point") { GbaEnvelopeEdits.remove(it, v.state.envStep) }.also { normalise(v) } }
            else -> Unit
        }
    }
    override fun audition(v: ViewController) {
        val st = v.state
        st.dpcmKey = (st.octave.coerceIn(0, 7) * 12 + st.dpcmKey.mod(12)).coerceIn(0, 95)
        val sampleIndex = instrument(v.session.song, st).keymap.getOrNull(st.dpcmKey) ?: -1
        if (v.session.song.gba.samples.getOrNull(sampleIndex)?.pcm?.isNotEmpty() != true) {
            v.session.releaseAudition()
            v.session.note("${v.session.song.system.noteName(st.dpcmKey)} is unmapped. Add a sample in Tools or set its key mapping.")
            return
        }
        v.startAudition(st.listenChannel, st.dpcmKey, st.instrument)
    }
    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        if (a in listOf(Action.PASTE, Action.CUT, Action.DUPLICATE, Action.PREV_CHANNEL, Action.NEXT_CHANNEL)) {
            st.soundPanel("gba-maxmod").invalidate()
            if (v.editHeld) { v.useEdit(); st.soundPanel("gba-maxmod").editBlocked = true }
        }
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                st.listenChannel = (st.listenChannel + if (a == Action.NEXT_CHANNEL) 1 else 7).mod(8)
                if (v.auditionHeld) audition(v)
            }
            Action.COPY -> v.session.copyGbaInstrument(st.instrument)
            Action.PASTE -> safely(v) { v.session.pasteGbaInstrument(st.instrument) }
            Action.CUT -> { v.session.copyGbaInstrument(st.instrument); safely(v) {
                v.session.editGbaInstrument(st.instrument, "Cut sound") { GbaInstrument() }
            } }
            Action.DUPLICATE -> {
                if (st.instrument == GbaSound.SLOTS) { v.session.note("Already at the last instrument slot"); return true }
                val ins = instrument(v.session.song, st)
                safely(v) { v.session.editGbaInstrument(st.instrument + 1, "Duplicate sound") { ins } }
                moveSlot(v, 1)
            }
            else -> return false
        }
        return true
    }
    override fun hints(v: ViewController, h: MutableList<Hint>) {
        h += Hint(v.ud(), "Instrument slot")
        h += Hint(v.lr(), "Parameter")
        h += Hint(v.editLr(), "Adjust ${row(v.state).label.lowercase()}")
        h += Hint(v.editUd(), if (row(v.state) == Row.NAME) "Letter position" else "Coarse adjustment")
        h += Hint(v.k(Action.EDIT), "Enter / tools")
        h += Hint(v.k(Action.AUDITION), "Hold note; release OFF")
        h += Hint(v.channels(), "Listen lane")
        h += Hint(v.octaves(), "Listen / key octave")
        h += Hint(v.copyPaste(), "Copy/paste instrument")
    }
    internal override fun painter(pane: PaneView): Painter = GbaSoundPainter(pane)
}
