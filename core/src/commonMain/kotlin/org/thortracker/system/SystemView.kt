package org.thortracker.system

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.model.Song
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * A view whose content belongs to a [SoundSystem] (its instrument editor, the Game Boy's wave tables): the controller
 * logic [ViewController] delegates to while such a view is showing, and its painter. Cursor fields live in the
 * pane's [ViewState] like every other view's, so they swap and persist with it.
 */
abstract class SystemView {
    /** Keeps the view's cursor fields in [v]'s state addressable for the current song. */
    abstract fun normalise(v: ViewController)

    /** The subject a lock holds this view on ([ViewState.lockedSubject]). */
    abstract fun subject(state: ViewState): Int

    /** Holds [state] on locked [subject]. */
    abstract fun applyLock(state: ViewState, subject: Int)

    /** Applies the shared [selection] to a following, unlocked view. */
    abstract fun follow(state: ViewState, selection: Selection, song: Song)

    /** Undo label of a held edit. */
    abstract val gestureLabel: String

    abstract fun direction(v: ViewController, dx: Int, dy: Int)
    open fun editPress(v: ViewController) {}
    abstract fun editRelease(v: ViewController, tap: Boolean)
    abstract fun clear(v: ViewController)

    /** Sounds the subject (Audition held, or the listening octave changed while sounding). */
    abstract fun audition(v: ViewController)

    /** Commands other than navigation; false lets the host handle [a]. */
    abstract fun command(v: ViewController, a: Action): Boolean

    abstract fun hints(v: ViewController, h: MutableList<Hint>)

    /** Draws the view and handles its touch ([Painter] is internal to core, so this hook is too). */
    internal abstract fun painter(pane: PaneView): Painter
}
