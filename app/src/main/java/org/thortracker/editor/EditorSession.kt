package org.thortracker.editor

import org.thortracker.model.Cell
import org.thortracker.model.Instrument
import org.thortracker.model.InstrumentKind
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Scale
import org.thortracker.model.Songs

/**
 * Selection shared by every display. Views publish their cursor here; sibling views that follow the
 * selection read it in [Follow.apply]. [context] suggests the view kind an unpinned sibling could show.
 */
data class Selection(
    val order: Int = 0,
    val row: Int = 0,
    val channel: Int = 0,
    val bank: InstrumentKind = InstrumentKind.PULSE,
    val instrument: Int = 1,
    val wave: Int = 0,
    val context: ViewKind = ViewKind.PATTERN,
)

/** Proof of ownership of an open undo group; compared by identity. */
class GestureToken internal constructor(val label: String)

/**
 * The one shared song document with document-wide undo/redo. Every mutation is an immutable
 * transformation of [song]; history stores whole immutable snapshots (structurally shared lists).
 * A gesture (held adjustment, drag) groups any number of mutations into one undo step.
 */
class EditorSession(initial: Song) {
    var song: Song = initial
        private set

    var onSongChanged: ((Song) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null
    var onAudition: ((Int, Int, Int) -> Unit)? = null
    var onReleaseAudition: (() -> Unit)? = null
    var onTransport: ((Boolean) -> Unit)? = null

    var selection: Selection = Selection()
        private set

    var clipboard: Clip? = null
        private set

    /** Rows the cursor advances after step entry (0 = stay). */
    var step: Int = 1
        set(value) {
            val v = value.coerceIn(0, MAX_STEP)
            if (v != field) { field = v; stateChanged() }
        }

    /** Short status text describing the last command, for the views to show. */
    var message: String? = null
        private set

    /** Incremented on every selection publish, so views can tell whether it changed since they last looked. */
    var selectionVersion: Long = 0
        private set

    private class Entry(val label: String, val before: Song, val after: Song, val beforeSel: Selection, val afterSel: Selection)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    /** Open gesture owners. Overlapping owners (e.g. two displays) share one undo step. */
    private val openGestures = ArrayList<GestureToken>()
    private var gestureStart: Song? = null
    private var gestureSel: Selection = selection
    private var gestureLabel = ""
    private var gestureInner = ""
    private var gestureEdits = 0

    val canUndo: Boolean get() = undoStack.isNotEmpty() || gestureChanged()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label
    val inGesture: Boolean get() = openGestures.isNotEmpty()

    private fun gestureChanged() = inGesture && gestureStart != null && gestureStart !== song

    fun replaceSong(song: Song) {
        openGestures.clear()
        gestureStart = null
        undoStack.clear()
        redoStack.clear()
        this.song = song
        selection = clampSelection(selection)
        selectionVersion++
        message = null
        onSongChanged?.invoke(song)
        stateChanged()
    }

    fun setTitle(title: String) = apply("Rename song") { it.copy(title = title.take(MAX_TEXT)) }
    fun setArtist(artist: String) = apply("Set artist") { it.copy(artist = artist.take(MAX_TEXT)) }
    fun setComment(comment: String) = apply("Set comment") { it.copy(comment = comment) }

    // ---- history -------------------------------------------------------------------------------

    /**
     * Opens an undo group owned by the caller. Every change until the last open owner calls
     * [endGesture] is one undo step; an owner can only close the group it opened.
     */
    fun beginGesture(label: String): GestureToken {
        if (openGestures.isEmpty()) {
            gestureStart = song
            gestureSel = selection
            gestureLabel = label
            gestureEdits = 0
        }
        return GestureToken(label).also { openGestures += it }
    }

    /** Undo, redo and document replacement invalidate every active gesture owner. */
    fun isGestureOpen(token: GestureToken): Boolean = openGestures.any { it === token }

    /** Closes [token]'s part of the group. Unknown or already closed tokens are ignored. */
    fun endGesture(token: GestureToken) {
        if (!openGestures.remove(token)) return
        if (openGestures.isEmpty()) commitGesture()
    }

    private fun commitGesture() {
        openGestures.clear()
        val start = gestureStart ?: return
        gestureStart = null
        if (start !== song && start != song) {
            val label = if (gestureEdits == 1) gestureInner else gestureLabel
            push(Entry(label, start, song, gestureSel, selection))
        }
        stateChanged()
    }

    /** Undo first closes any open group (all owners), so the history stays linear. */
    fun undo() {
        if (inGesture) commitGesture()
        val e = undoStack.removeLastOrNull() ?: run { note("Nothing to undo"); return }
        redoStack.addLast(e)
        restore(e.before, e.beforeSel, "Undid ${e.label.lowercaseFirst()}")
    }

    fun redo() {
        if (inGesture) commitGesture()
        val e = redoStack.removeLastOrNull() ?: run { note("Nothing to redo"); return }
        undoStack.addLast(e)
        restore(e.after, e.afterSel, "Redid ${e.label.lowercaseFirst()}")
    }

    private fun restore(target: Song, sel: Selection, msg: String) {
        song = target
        selection = clampSelection(sel)
        selectionVersion++
        message = msg
        onSongChanged?.invoke(song)
        stateChanged()
    }

    private fun push(e: Entry) {
        undoStack.addLast(e)
        while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * Applies an immutable command. Returns true if the document changed. Outside a gesture each
     * change is its own undo step; inside a gesture the gesture is one step.
     */
    fun apply(label: String, transform: (Song) -> Song): Boolean {
        val before = song
        val after = transform(before)
        if (after === before || after == before) return false
        song = after
        if (!inGesture) push(Entry(label, before, after, selection, selection))
        else { redoStack.clear(); gestureEdits++; gestureInner = label }
        message = null
        onSongChanged?.invoke(after)
        stateChanged()
        return true
    }

    // ---- selection / status ---------------------------------------------------------------------

    fun select(sel: Selection) {
        val s = clampSelection(sel)
        if (s == selection) return
        selection = s
        selectionVersion++
        stateChanged()
    }

    fun note(text: String?) {
        message = text
        stateChanged()
    }

    private fun stateChanged() = onStateChanged?.invoke()

    private fun clampSelection(s: Selection): Selection {
        val order = s.order.coerceIn(0, song.orders.size - 1)
        val rows = song.rowCount(song.patternAt(order))
        return s.copy(
            order = order,
            row = s.row.coerceIn(0, rows - 1),
            channel = s.channel.coerceIn(0, song.channelCount() - 1),
            instrument = s.instrument.coerceIn(1, BANK_SIZE),
            wave = s.wave.coerceIn(0, song.waves.size - 1),
        )
    }

    // ---- transport / audition --------------------------------------------------------------------

    fun audition(channel: Int, note: Int, instrument: Int) {
        if (note !in 0..Songs.NOTE_MAX) return
        onAudition?.invoke(channel, note, instrument)
    }

    fun releaseAudition() { onReleaseAudition?.invoke() }

    fun transport(play: Boolean) { onTransport?.invoke(play) }

    // ---- pattern cells ---------------------------------------------------------------------------

    fun setCell(pattern: Int, channel: Int, row: Int, cell: Cell): Boolean =
        apply("Edit cell") { s -> s.withPattern(pattern, s.patterns[pattern].withCell(channel, row, normaliseCell(cell))) }

    fun editCell(pattern: Int, channel: Int, row: Int, label: String = "Edit cell", f: (Cell) -> Cell): Boolean =
        apply(label) { s ->
            val old = s.cellAt(pattern, channel, row)
            s.withPattern(pattern, s.patterns[pattern].withCell(channel, row, normaliseCell(f(old))))
        }

    fun copyBlock(pattern: Int, block: Block) {
        val p = song.patterns[pattern]
        val b = clampBlock(pattern, block)
        clipboard = Clip.Cells(List(b.width) { c -> p.tracks[b.firstChannel + c].subList(b.firstRow, b.lastRow + 1).toList() })
        note("Copied ${b.width}×${b.height} cells")
    }

    fun clearBlock(pattern: Int, block: Block, label: String = "Clear cells"): Boolean = apply(label) { s ->
        val b = clampBlock(pattern, block)
        val p = s.patterns[pattern]
        val tracks = p.tracks.mapIndexed { c, track ->
            if (c !in b.firstChannel..b.lastChannel) track
            else track.mapIndexed { r, cell -> if (r in b.firstRow..b.lastRow) Cell() else cell }
        }
        s.withPattern(pattern, p.copy(tracks = tracks))
    }

    fun cutBlock(pattern: Int, block: Block): Boolean {
        copyBlock(pattern, block)
        val changed = clearBlock(pattern, block, "Cut cells")
        note("Cut ${block.width}×${block.height} cells")
        return changed
    }

    /** Pastes the cell clipboard with its top-left at (channel,row); clipped at pattern bounds. Returns the pasted block. */
    fun pasteCells(pattern: Int, channel: Int, row: Int): Block? {
        val clip = clipboard as? Clip.Cells ?: run { note("Clipboard holds no pattern cells"); return null }
        val b = pasteBlock(pattern, clip, channel, row) ?: return null
        apply("Paste cells") { s -> s.withPattern(pattern, pasted(s.patterns[pattern], clip, b)) }
        note("Pasted ${b.width}×${b.height} cells")
        return b
    }

    /**
     * Pastes the clipboard over the top of [pattern]. A whole copied pattern ([copyPattern]) also brings its
     * editor-only scale, in the same undo step; other cell clips paste like [pasteCells] at row 0, channel 0.
     */
    fun pastePattern(pattern: Int): Block? {
        val clip = clipboard as? Clip.Cells ?: run { note("Clipboard holds no pattern cells"); return null }
        val scale = clip.scale ?: return pasteCells(pattern, 0, 0)
        val b = pasteBlock(pattern, clip, 0, 0) ?: return null
        apply("Paste pattern") { s -> s.withPattern(pattern, pasted(s.patterns[pattern], clip, b).copy(scale = scale)) }
        note("Pasted pattern into ${hex2(pattern)}")
        return b
    }

    private fun pasteBlock(pattern: Int, clip: Clip.Cells, channel: Int, row: Int): Block? {
        val lastC = minOf(song.channelCount() - 1, channel + clip.width - 1)
        val lastR = minOf(song.rowCount(pattern) - 1, row + clip.height - 1)
        return if (lastC < channel || lastR < row) null else Block(channel, lastC, row, lastR)
    }

    private fun pasted(p: Pattern, clip: Clip.Cells, b: Block): Pattern = p.copy(tracks = p.tracks.mapIndexed { c, track ->
        if (c !in b.firstChannel..b.lastChannel) track
        else track.mapIndexed { r, cell ->
            if (r in b.firstRow..b.lastRow) normaliseCell(clip.tracks[c - b.firstChannel][r - b.firstRow]) else cell
        }
    })

    /**
     * Repeats [block] directly below itself (clipped at the pattern end) without touching the
     * clipboard. Returns the written block, or null if the block already ends at the last row.
     */
    fun duplicateBlock(pattern: Int, block: Block): Block? {
        val b = clampBlock(pattern, block)
        val rows = song.rowCount(pattern)
        val start = b.lastRow + 1
        if (start >= rows) { note("No room below the selection"); return null }
        val last = minOf(rows - 1, start + b.height - 1)
        apply("Duplicate cells") { s ->
            val p = s.patterns[pattern]
            s.withPattern(pattern, p.copy(tracks = p.tracks.mapIndexed { c, t ->
                if (c !in b.firstChannel..b.lastChannel) t
                else t.mapIndexed { r, cell -> if (r in start..last) t[b.firstRow + (r - start)] else cell }
            }))
        }
        return Block(b.firstChannel, b.lastChannel, start, last)
    }

    /** Inserts a blank row at [row] in the given channels; the last row falls off. */
    fun insertRow(pattern: Int, channels: IntRange, row: Int): Boolean = apply("Insert row") { s ->
        val p = s.patterns[pattern]
        s.withPattern(pattern, p.copy(tracks = p.tracks.mapIndexed { c, t ->
            if (c !in channels) t else (t.subList(0, row) + Cell() + t.subList(row, t.size - 1))
        }))
    }

    /** Deletes [row] in the given channels; a blank row enters at the bottom. */
    fun deleteRow(pattern: Int, channels: IntRange, row: Int): Boolean = apply("Delete row") { s ->
        val p = s.patterns[pattern]
        s.withPattern(pattern, p.copy(tracks = p.tracks.mapIndexed { c, t ->
            if (c !in channels) t else (t.subList(0, row) + t.subList(row + 1, t.size) + Cell())
        }))
    }

    private fun clampBlock(pattern: Int, b: Block): Block {
        val ch = song.channelCount() - 1
        val rows = song.rowCount(pattern) - 1
        return Block(b.firstChannel.coerceIn(0, ch), b.lastChannel.coerceIn(0, ch), b.firstRow.coerceIn(0, rows), b.lastRow.coerceIn(0, rows))
    }

    // ---- patterns and orders ---------------------------------------------------------------------

    fun renamePattern(pattern: Int, name: String) =
        apply("Rename pattern") { s -> s.withPattern(pattern, s.patterns[pattern].copy(name = name.take(MAX_NAME))) }

    fun setOrderPattern(order: Int, pattern: Int): Boolean = apply("Change order") { s ->
        s.copy(orders = s.orders.replaced(order, pattern.coerceIn(0, s.patterns.size - 1)))
    }

    /** Inserts an order referencing [pattern] at [at]. Returns false if the order list is full. */
    fun insertOrder(at: Int, pattern: Int): Boolean {
        if (song.orders.size >= MAX_ORDERS) { note("The song already has $MAX_ORDERS orders"); return false }
        return apply("Insert order") { s ->
            val idx = at.coerceIn(0, s.orders.size)
            s.copy(orders = s.orders.inserted(idx, pattern.coerceIn(0, s.patterns.size - 1)), loopOrder = shiftLoopForInsert(s.loopOrder, idx))
        }
    }

    fun deleteOrder(at: Int): Boolean {
        if (song.orders.size <= 1) { note("A song needs at least one order"); return false }
        return apply("Delete order") { s ->
            val loop = when {
                s.loopOrder > at -> s.loopOrder - 1
                else -> s.loopOrder
            }.coerceIn(0, s.orders.size - 2)
            s.copy(orders = s.orders.removed(at), loopOrder = loop)
        }
    }

    /** Moves an order; the loop target stays on the same order entry wherever it ends up. */
    fun moveOrder(from: Int, to: Int): Boolean {
        if (to !in song.orders.indices || from !in song.orders.indices || from == to) return false
        return apply("Move order") { s ->
            val list = s.orders.toMutableList()
            val v = list.removeAt(from)
            list.add(to, v)
            val loop = s.loopOrder
            val newLoop = when {
                loop == from -> to
                loop in (from + 1)..to -> loop - 1
                loop in to until from -> loop + 1
                else -> loop
            }
            s.copy(orders = list, loopOrder = newLoop)
        }
    }

    /** Appends a blank pattern and inserts an order for it at [at]. Returns the new pattern index or -1. */
    fun newPatternAt(at: Int): Int {
        if (song.patterns.size >= MAX_PATTERNS) { note("The song already has $MAX_PATTERNS patterns"); return -1 }
        if (song.orders.size >= MAX_ORDERS) { note("The song already has $MAX_ORDERS orders"); return -1 }
        val index = song.patterns.size
        apply("New pattern") { s ->
            val p = Songs.blankPattern(s, "Pattern ${index.toString(16).uppercase().padStart(2, '0')}")
            val idx = at.coerceIn(0, s.orders.size)
            s.copy(patterns = s.patterns + p, orders = s.orders.inserted(idx, index), loopOrder = shiftLoopForInsert(s.loopOrder, idx))
        }
        return index
    }

    /** Duplicates the pattern used by [order] into a new pattern placed in a new order right after it. */
    fun clonePatternAt(order: Int): Int {
        if (song.patterns.size >= MAX_PATTERNS) { note("The song already has $MAX_PATTERNS patterns"); return -1 }
        if (song.orders.size >= MAX_ORDERS) { note("The song already has $MAX_ORDERS orders"); return -1 }
        val index = song.patterns.size
        apply("Clone pattern") { s ->
            val src = s.patterns[s.patternAt(order)]
            val idx = order + 1
            s.copy(
                patterns = s.patterns + src.copy(name = (src.name + " copy").take(MAX_NAME)),
                orders = s.orders.inserted(idx, index),
                loopOrder = shiftLoopForInsert(s.loopOrder, idx),
            )
        }
        return index
    }

    /** Copies a whole pattern, with its editor-only scale, to the cell clipboard. */
    fun copyPattern(pattern: Int) {
        val p = song.patterns[pattern]
        clipboard = Clip.Cells(p.tracks, p.scale)
        note("Copied pattern ${hex2(pattern)}")
    }

    /** Sets [pattern]'s editor-only note-entry scale. Stored notes, export and playback are unaffected. */
    fun setPatternScale(pattern: Int, scale: Scale): Boolean {
        if (scale.root !in 0..11) return false
        return apply("Set scale") { s -> s.withPattern(pattern, s.patterns[pattern].copy(scale = scale)) }
    }

    /** The loop target follows its order when an order is inserted at or before it. */
    private fun shiftLoopForInsert(loop: Int, at: Int) = if (loop >= at) loop + 1 else loop

    fun setLoopOrder(order: Int) = apply("Set loop") { s -> s.copy(loopOrder = order.coerceIn(0, s.orders.size - 1)) }

    fun setTicksPerRow(ticks: Int) = apply("Set tempo") { s -> s.copy(ticksPerRow = ticks.coerceIn(1, MAX_TICKS)) }

    // ---- instruments ------------------------------------------------------------------------------

    fun setInstrument(kind: InstrumentKind, slot: Int, instrument: Instrument): Boolean {
        if (slot !in 1..BANK_SIZE) return false
        return apply("Edit ${instrument.name.ifBlank { "sound" }}") { s ->
            val fixed = instrument.copy(kind = kind)
            s.withBank(kind, s.bank(kind).replaced(slot - 1, fixed))
        }
    }

    fun editInstrument(kind: InstrumentKind, slot: Int, label: String, f: (Instrument) -> Instrument): Boolean {
        val cur = song.instrument(kind, slot) ?: return false
        return apply(label) { s -> s.withBank(kind, s.bank(kind).replaced(slot - 1, f(cur).copy(kind = kind))) }
    }

    fun copyInstrument(kind: InstrumentKind, slot: Int) {
        val i = song.instrument(kind, slot) ?: return
        clipboard = Clip.Sound(i)
        note("Copied sound ${hex2(slot)} ${i.name}")
    }

    /** Pastes a copied sound; only into the same kind of bank, because parameters differ per kind. */
    fun pasteInstrument(kind: InstrumentKind, slot: Int): Boolean {
        val clip = clipboard as? Clip.Sound ?: run { note("Clipboard holds no sound"); return false }
        if (clip.instrument.kind != kind) {
            note("Clipboard holds a ${kindName(clip.instrument.kind)} sound; pick a ${kindName(clip.instrument.kind)} slot")
            return false
        }
        val ok = setInstrument(kind, slot, clip.instrument)
        note("Pasted into sound ${hex2(slot)}")
        return ok
    }

    // ---- waves -----------------------------------------------------------------------------------

    fun setWave(index: Int, samples: List<Int>, label: String = "Edit wave"): Boolean {
        if (samples.size != WaveShapes.SAMPLES) return false
        val clamped = samples.map { it.coerceIn(0, WaveShapes.MAX) }
        return apply(label) { s -> s.copy(waves = s.waves.replaced(index, clamped)) }
    }

    fun setWaveSample(index: Int, sample: Int, value: Int): Boolean {
        val w = song.waves[index]
        if (sample !in w.indices) return false
        return setWave(index, w.replaced(sample, value.coerceIn(0, WaveShapes.MAX)), "Draw wave")
    }

    fun copyWave(index: Int) {
        clipboard = Clip.Wave(song.waves[index])
        note("Copied wave ${hex1(index)}")
    }

    fun pasteWave(index: Int): Boolean {
        val clip = clipboard as? Clip.Wave ?: run { note("Clipboard holds no wave"); return false }
        val ok = setWave(index, clip.samples, "Paste wave")
        note("Pasted into wave ${hex1(index)}")
        return ok
    }

    companion object {
        const val BANK_SIZE = 15
        const val HISTORY_LIMIT = 400
        const val MAX_STEP = 16
        /** hUGEDriver order count limit; the editor refuses to grow the list past it. */
        const val MAX_ORDERS = org.thortracker.backend.HugeLimits.MAX_ORDERS
        /** Pattern indices are kept below 256; the backend's distinct-track limit is reported by validation. */
        const val MAX_PATTERNS = 256
        const val MAX_TICKS = 255
        const val MAX_NAME = 24
        const val MAX_TEXT = 255

        /** Hex text is drawn for every visible cell each frame; byte values come from a table. */
        private val HEX2 = Array(256) { it.toString(16).uppercase().padStart(2, '0') }
        private val HEX1 = Array(16) { it.toString(16).uppercase() }

        fun hex1(v: Int): String = if (v in 0..15) HEX1[v] else v.toString(16).uppercase()
        fun hex2(v: Int): String = if (v in 0..255) HEX2[v] else v.toString(16).uppercase().padStart(2, '0')
        fun kindName(k: InstrumentKind) = when (k) {
            InstrumentKind.PULSE -> "pulse"
            InstrumentKind.WAVE -> "wave"
            InstrumentKind.NOISE -> "noise"
        }
    }
}

private fun String.lowercaseFirst() = if (isEmpty()) this else this[0].lowercaseChar() + substring(1)

/** Applies the shared selection to a following view's subject without touching its view kind or pin. */
object Follow {
    /** Returns true if [state] changed. */
    fun apply(state: ViewState, session: EditorSession): Boolean {
        if (!state.followSelection) return false
        val sel = session.selection
        val song = session.song
        val before = state.copy()
        when (state.kind) {
            ViewKind.PATTERN -> {
                state.order = sel.order
                state.row = sel.row
                if (state.lockedSubject < 0) state.channel = sel.channel
            }
            ViewKind.SONG -> {
                state.order = sel.order
                state.songItem = ViewState.SONG_HEADER_ITEMS + sel.order
            }
            ViewKind.INSTRUMENT -> if (state.lockedSubject < 0) {
                state.bank = sel.bank.ordinal
                state.instrument = sel.instrument
            }
            ViewKind.WAVE -> if (state.lockedSubject < 0) state.wave = sel.wave
            ViewKind.KEYBOARD -> {
                if (state.lockedSubject < 0) state.channel = sel.channel
                if (Songs.channelKind(song, state.channel.coerceIn(0, song.channelCount() - 1)) == sel.bank) {
                    state.instrument = sel.instrument
                }
            }
            ViewKind.HELP -> Unit
        }
        if (state.lockedSubject >= 0) applyLock(state, song.channelCount())
        state.order = state.order.coerceIn(0, song.orders.size - 1)
        return state != before
    }

    /** Holds a view on its locked subject: INSTRUMENT bank*16+slot, WAVE wave, PATTERN/KEYBOARD channel. */
    fun applyLock(state: ViewState, channels: Int = Int.MAX_VALUE) {
        val s = state.lockedSubject
        if (s < 0) return
        when (state.kind) {
            ViewKind.INSTRUMENT -> {
                state.bank = (s / 16).coerceIn(0, InstrumentKind.entries.size - 1)
                state.instrument = (s % 16).coerceIn(1, EditorSession.BANK_SIZE)
            }
            ViewKind.WAVE -> state.wave = s.coerceIn(0, 15)
            ViewKind.PATTERN, ViewKind.KEYBOARD -> state.channel = s.coerceIn(0, channels - 1)
            else -> Unit
        }
    }
}
