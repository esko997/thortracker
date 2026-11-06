package org.thortracker

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.model.Songs
import org.thortracker.ui.TrackerView
import org.thortracker.input.Binding
import org.thortracker.input.Control
import org.thortracker.input.Keymap
import android.app.Activity
import android.app.Instrumentation
import android.content.IntentFilter
import android.net.Uri
import android.system.Os
import android.graphics.Rect
import android.view.MotionEvent
import org.thortracker.storage.ProjectStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real Activity/input smoke, run only on a dedicated emulator or explicitly leased device. */
@RunWith(AndroidJUnit4::class)
class ControllerWorkflowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var tracker: TrackerView

    @Before fun setUp() {
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + 10000
        var ready = false
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                tracker = findTracker(activity.window.decorView) ?: error("Pattern view missing")
                ready = findEnabledPlay(activity.window.decorView)
            }
            if (ready) break
            SystemClock.sleep(40)
        }
        assertTrue("Actual audio initialization must complete", ready)
        // Bindings and controller focus persist in the app's prefs; every test starts from the documented
        // defaults with the controller on the primary display's tracker.
        resetControls()
        val setFocus = MainActivity::class.java.getDeclaredMethod("setFocus", Int::class.javaPrimitiveType).apply { isAccessible = true }
        instrumentation.runOnMainSync { setFocus.invoke(activity, 0) }
        instrumentation.runOnMainSync {
            tracker.session.replaceSong(Songs.demo())
            tracker.state.kind = ViewKind.PATTERN
            tracker.state.order = 0
            tracker.state.row = 0
            tracker.state.channel = 0
            tracker.state.field = 0
            tracker.state.followPlayback = false
            tracker.session.step = 0
            tracker.session.select(Selection())
            tracker.controllerFocused = true
            tracker.refresh()
        }
        instrumentation.waitForIdleSync()
    }

    @After fun tearDown() {
        resetControls()
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    /** Restores default bindings through the app's own apply path (router, views and saved prefs together). */
    private fun resetControls() {
        val apply = MainActivity::class.java.getDeclaredMethod("applyKeymap", Keymap::class.java, String::class.java)
            .apply { isAccessible = true }
        instrumentation.runOnMainSync { apply.invoke(activity, Keymap.DEFAULT, null) }
    }

    @Test fun heldNoteAdjustmentIsOneUndoStep() {
        val before = tracker.session.song
        down(KeyEvent.KEYCODE_BUTTON_A)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        up(KeyEvent.KEYCODE_BUTTON_A)
        val changed = tracker.session.song
        val pattern = before.orders[0]
        assertEquals(before.patterns[pattern].tracks[0][0].note + 3, changed.patterns[pattern].tracks[0][0].note)
        tap(KeyEvent.KEYCODE_BUTTON_L2)
        tap(KeyEvent.KEYCODE_BUTTON_R2)
        assertEquals("Bare triggers must not consume undo history", changed, tracker.session.song)
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("Whole held adjustment must undo together", before, tracker.session.song)
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(changed, tracker.session.song)
    }

    /**
     * R2+B shows Instrument pinned on the focused display; R2+B again restores Pattern and its unpinned state.
     * R2+B, R2+A, R2+A ends on Instrument. Nothing is edited.
     */
    @Test fun viewChordShowsTheViewAndTogglesBack() {
        val before = tracker.session.song
        instrumentation.runOnMainSync { tracker.state.pinned = false }
        chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_B)
        instrumentation.runOnMainSync { assertEquals(ViewKind.INSTRUMENT, tracker.state.kind); assertTrue(tracker.state.pinned) }
        chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_B)
        instrumentation.runOnMainSync { assertEquals(ViewKind.PATTERN, tracker.state.kind); assertFalse(tracker.state.pinned) }
        chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_B)
        chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.runOnMainSync { assertEquals(ViewKind.SONG, tracker.state.kind) }
        chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.runOnMainSync { assertEquals(ViewKind.INSTRUMENT, tracker.state.kind) }
        assertEquals("View chords must not edit or release a held B/A", before, tracker.session.song)
    }

    /** L2+Y loops the focused pattern view's order: playback stays on it past its end, and L2+Y again releases it. */
    @Test fun loopPatternChordKeepsPlaybackOnThatOrder() {
        instrumentation.runOnMainSync {
            assertTrue("demo needs two orders", tracker.session.song.orders.size >= 2)
            tracker.session.select(Selection(order = 1, row = 0, channel = 0))
            tracker.state.order = 1
        }
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_Y)
        instrumentation.runOnMainSync { assertTrue(tracker.playback.looping); assertEquals(1, tracker.playback.order) }
        tap(KeyEvent.KEYCODE_BUTTON_START)
        val song = tracker.session.song
        val p = song.patterns[song.orders[1]]
        // Long enough to pass the pattern's end at least once.
        val loopMs = p.length * song.ticksPerRow * 1000L / 60 + 1500
        val until = SystemClock.uptimeMillis() + loopMs
        var played = false
        var wrapped = false
        var last = -1
        while (SystemClock.uptimeMillis() < until) {
            instrumentation.runOnMainSync {
                val pos = tracker.playback
                if (pos.playing) {
                    played = true
                    assertEquals("loop left its order at row ${pos.row}", 1, pos.order)
                    if (pos.row < last) wrapped = true
                    last = pos.row
                }
            }
            SystemClock.sleep(20)
        }
        assertTrue("playback started", played)
        assertTrue("the pattern replayed", wrapped)
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_Y)
        instrumentation.runOnMainSync { assertFalse(tracker.playback.looping) }
        tap(KeyEvent.KEYCODE_BUTTON_START)
    }

    /** Inserting an order before the looped one (and undoing it) keeps looping the same pattern. */
    @Test fun loopFollowsItsPatternWhenOrdersShift() {
        var pattern = -1
        instrumentation.runOnMainSync {
            assertTrue("demo needs three orders", tracker.session.song.orders.size >= 3)
            tracker.session.select(Selection(order = 2, row = 0, channel = 0))
            tracker.state.order = 2
            pattern = tracker.session.song.orders[2]
        }
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_Y)
        tap(KeyEvent.KEYCODE_BUTTON_START)
        SystemClock.sleep(600)
        instrumentation.runOnMainSync {
            assertTrue(tracker.playback.playing && tracker.playback.looping)
            assertEquals(2, tracker.playback.order)
            assertTrue(tracker.session.insertOrder(0, tracker.session.song.orders[0]))
            assertNotEquals("the insert must change what order 02 plays", pattern, tracker.session.song.orders[2])
        }
        SystemClock.sleep(300)
        instrumentation.runOnMainSync {
            assertEquals("loop stayed on pattern $pattern", 3, tracker.playback.order)
            assertEquals(pattern, tracker.session.song.orders[3])
            tracker.session.undo()
        }
        SystemClock.sleep(300)
        instrumentation.runOnMainSync { assertTrue(tracker.playback.looping); assertEquals(2, tracker.playback.order) }
        tap(KeyEvent.KEYCODE_BUTTON_START)
    }

    /** Song playback never lands on a parked order; Start on the parked order loops it alone until stopped. */
    @Test fun parkedOrdersAreSkippedAndPlayAloneOnRequest() {
        instrumentation.runOnMainSync {
            val s = tracker.session
            assertTrue("demo needs three orders", s.song.orders.size >= 3)
            s.setTicksPerRow(1)                                    // about a second per 64-row pattern
            assertTrue(s.toggleParked(1))
            s.select(Selection(order = 0, row = 0, channel = 0))
            tracker.state.order = 0
        }
        tap(KeyEvent.KEYCODE_BUTTON_START)
        val seen = HashSet<Int>()
        val until = SystemClock.uptimeMillis() + 3500
        while (SystemClock.uptimeMillis() < until) {
            instrumentation.runOnMainSync { tracker.playback.let { if (it.playing) seen += it.order } }
            SystemClock.sleep(20)
        }
        tap(KeyEvent.KEYCODE_BUTTON_START)
        assertTrue("played past the parked order: $seen", 2 in seen)
        assertFalse("the parked order was played: $seen", 1 in seen)
        // Start on the parked order: it loops on its own, and stopping ends that loop.
        instrumentation.runOnMainSync { tracker.session.select(Selection(order = 1, row = 0, channel = 0)); tracker.state.order = 1 }
        SystemClock.sleep(300)
        tap(KeyEvent.KEYCODE_BUTTON_START)
        var wrapped = false
        var last = -1
        val loopUntil = SystemClock.uptimeMillis() + 2500
        while (SystemClock.uptimeMillis() < loopUntil) {
            instrumentation.runOnMainSync {
                val pos = tracker.playback
                if (pos.playing) {
                    assertTrue(pos.looping)
                    assertEquals("left the parked order at row ${pos.row}", 1, pos.order)
                    if (pos.row < last) wrapped = true
                    last = pos.row
                }
            }
            SystemClock.sleep(20)
        }
        assertTrue("the parked pattern replayed", wrapped)
        tap(KeyEvent.KEYCODE_BUTTON_START)
        SystemClock.sleep(300)
        instrumentation.runOnMainSync { assertFalse("stopping ended the parked preview loop", tracker.playback.looping) }
    }

    @Test fun releasingTheTriggerBeforeAStillConsumesTheChord() {
        val before = tracker.session.song
        down(KeyEvent.KEYCODE_BUTTON_A)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        up(KeyEvent.KEYCODE_BUTTON_A)
        val changed = tracker.session.song
        down(KeyEvent.KEYCODE_BUTTON_L2)
        down(KeyEvent.KEYCODE_BUTTON_A)
        up(KeyEvent.KEYCODE_BUTTON_L2)
        up(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("Undo's A release must not re-enter the edited note", before, tracker.session.song)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Chord release must not leave the edit modifier held", before, tracker.session.song)
        down(KeyEvent.KEYCODE_BUTTON_L2)
        down(KeyEvent.KEYCODE_BUTTON_B)
        up(KeyEvent.KEYCODE_BUTTON_L2)
        up(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(changed, tracker.session.song)
    }

    @Test fun transportUsesTheFocusedPatternAndSupportsCursorStart() {
        instrumentation.runOnMainSync {
            tracker.session.replaceSong(Songs.blank().copy(orders = listOf(0, 0), ticksPerRow = 255))
            tracker.state.order = 1
            tracker.state.row = 40
            tracker.state.followSelection = false
            tracker.session.select(Selection(order = 0, row = 12))
        }
        tap(KeyEvent.KEYCODE_BUTTON_START)
        val fromTop = awaitPlayback(true)
        assertEquals(1, fromTop.order)
        assertEquals(0, fromTop.row)
        tap(KeyEvent.KEYCODE_BUTTON_START)
        awaitPlayback(false)
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_START)
        val fromCursor = awaitPlayback(true)
        assertEquals(1, fromCursor.order)
        assertEquals(40, fromCursor.row)
        tap(KeyEvent.KEYCODE_BUTTON_START)
        awaitPlayback(false)
    }

    private fun awaitPlayback(playing: Boolean): org.thortracker.audio.PlaybackPosition {
        val deadline = SystemClock.uptimeMillis() + 5000
        var position = org.thortracker.audio.PlaybackPosition()
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { position = tracker.playback }
            if (position.playing == playing) return position
            SystemClock.sleep(10)
        }
        fail("Transport did not reach playing=$playing; last position=$position")
        return position
    }

    @Test fun l2RightTogglesThePatternPanelWithoutMovingTheCursor() {
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.runOnMainSync {
            assertTrue(tracker.state.scalePanel)
            assertEquals(0, tracker.state.field)
            assertEquals(0, tracker.state.channel)
        }
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.runOnMainSync { assertFalse(tracker.state.scalePanel) }
        chord(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.runOnMainSync {
            assertFalse("Select+Right is no longer the panel shortcut", tracker.state.scalePanel)
            assertEquals(1, tracker.state.field)
        }
    }

    @Test fun openingMenuCancelsHeldEditModifier() {
        val before = tracker.session.song
        down(KeyEvent.KEYCODE_BUTTON_A)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        tap(KeyEvent.KEYCODE_MENU)
        tap(KeyEvent.KEYCODE_BACK)
        // A key-up was lost to the modal. Navigation after dismissal must not edit a note.
        val afterAdjustment = tracker.session.song
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(afterAdjustment, tracker.session.song)
        assertFalse(before == afterAdjustment)
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(before, tracker.session.song)
    }

    @Test fun finishingAnOlderSaveDoesNotAttachItsDestinationToANewSong() {
        val original = tracker.session.song
        val destination = File(activity.cacheDir, "late-save-${System.nanoTime()}.thor")
        Os.mkfifo(destination.path, 0x180)
        val bytes = AtomicReference<ByteArray>()
        var reader: Thread? = null
        val picker = Instrumentation.ActivityMonitor(
            IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
            }, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply {
                data = Uri.fromFile(destination)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }), true)
        try {
            instrumentation.addMonitor(picker)
            openFiles()
            clickRow("Save as…")
            instrumentation.waitForIdleSync()
            assertEquals("Save as did not launch the document picker", 1, picker.hits)
            instrumentation.removeMonitor(picker)
            openFiles()
            clickRow("New blank song")
            clickRow("Continue")
            assertNotEquals(original.title, tracker.session.song.title)
            reader = thread(isDaemon = true) { bytes.set(destination.inputStream().use { it.readBytes() }) }
            reader.join(5000)
            assertFalse("original save did not finish", reader.isAlive)
            destination.delete()
            destination.writeBytes(bytes.get())

            // Read through the same real provider API after that write, so its completion
            // callback has reached the main loop before requesting another save.
            val barrier = CountDownLatch(1)
            val probe = ProjectStore(activity)
            probe.open(Uri.fromFile(destination)) { barrier.countDown() }
            assertTrue(barrier.await(5, TimeUnit.SECONDS))
            probe.close()
            openFiles()
            clickRow("Save")
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (SystemClock.elapsedRealtime() < deadline &&
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "org.thortracker" &&
                destination.readBytes().contentEquals(bytes.get())) SystemClock.sleep(20)
            assertArrayEquals("saving the new song overwrote the previous song's destination",
                bytes.get(), destination.readBytes())
        } finally {
            instrumentation.removeMonitor(picker)
            if (reader == null) reader = thread(isDaemon = true) { destination.inputStream().use { it.readBytes() } }
            reader.join(3000)
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != "org.thortracker")
                tap(KeyEvent.KEYCODE_BACK)
            destination.delete()
        }
    }

    /** Rebinds through the real Controls screen with real key events, then exports, resets and imports the keymap. */
    @Test fun controlsScreenRebindsChordsAndRoundTripsAKeymapFile() {
        val apply = MainActivity::class.java.getDeclaredMethod("applyKeymap", Keymap::class.java, String::class.java)
            .apply { isAccessible = true }
        fun keymap(): Keymap { var k = Keymap.DEFAULT; instrumentation.runOnMainSync { k = tracker.keymap }; return k }
        val l3b = Binding(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_THUMBL)
        val file = File(activity.cacheDir, "keymap-${System.nanoTime()}.json")
        val exportMonitor = Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply { data = Uri.fromFile(file) }), true)
        val importMonitor = Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply { data = Uri.fromFile(file) }), true)
        instrumentation.runOnMainSync { apply.invoke(activity, Keymap.DEFAULT, null) }
        try {
            // Undo moves to the L3+B chord.
            tap(KeyEvent.KEYCODE_MENU)
            clickRow("Controls")
            clickRow("Undo")
            down(KeyEvent.KEYCODE_BUTTON_THUMBL); tap(KeyEvent.KEYCODE_BUTTON_B); up(KeyEvent.KEYCODE_BUTTON_THUMBL)
            assertEquals(l3b, keymap().binding(Control.UNDO))
            tap(KeyEvent.KEYCODE_MENU) // closes every popup
            val before = tracker.session.song
            down(KeyEvent.KEYCODE_BUTTON_A); tap(KeyEvent.KEYCODE_DPAD_RIGHT); up(KeyEvent.KEYCODE_BUTTON_A)
            assertNotEquals(before, tracker.session.song)
            down(KeyEvent.KEYCODE_BUTTON_THUMBL)
            instrumentation.runOnMainSync { assertTrue(tracker.hints.contains("L3+B" to "Undo")) }
            tap(KeyEvent.KEYCODE_BUTTON_B)
            up(KeyEvent.KEYCODE_BUTTON_THUMBL)
            assertEquals("L3+B must undo", before, tracker.session.song)

            // Taking a chord from another control asks first and leaves that control unbound.
            tap(KeyEvent.KEYCODE_MENU)
            clickRow("Controls")
            clickRow("Redo")
            down(KeyEvent.KEYCODE_BUTTON_THUMBL); tap(KeyEvent.KEYCODE_BUTTON_B); up(KeyEvent.KEYCODE_BUTTON_THUMBL)
            clickRow("Replace")
            assertEquals(l3b, keymap().binding(Control.REDO))
            assertEquals(null, keymap().binding(Control.UNDO))
            // Edit can never be left without a button.
            clickRow("Listen (hold)")
            tap(KeyEvent.KEYCODE_BUTTON_A)
            clickRow("OK")
            assertEquals(Keymap.DEFAULT.binding(Control.AUDITION), keymap().binding(Control.AUDITION))
            assertEquals(Keymap.DEFAULT.binding(Control.EDIT), keymap().binding(Control.EDIT))

            instrumentation.addMonitor(exportMonitor)
            clickRow("Export keymap…")
            awaitTrue("keymap export") { file.exists() && file.readText().contains("\"REDO\": \"L3+B\"") }
            instrumentation.removeMonitor(exportMonitor)

            tap(KeyEvent.KEYCODE_MENU)
            clickRow("Controls")
            clickRow("Reset all to defaults")
            clickRow("Reset")
            assertEquals(Keymap.DEFAULT, keymap())
            tap(KeyEvent.KEYCODE_MENU)

            instrumentation.addMonitor(importMonitor)
            tap(KeyEvent.KEYCODE_MENU)
            clickRow("Controls")
            clickRow("Import keymap…")
            awaitTrue("keymap import") { keymap().binding(Control.REDO) == l3b }
            assertEquals(null, keymap().binding(Control.UNDO))
        } finally {
            instrumentation.removeMonitor(exportMonitor)
            instrumentation.removeMonitor(importMonitor)
            instrumentation.runOnMainSync { apply.invoke(activity, Keymap.DEFAULT, null) }
            file.delete()
        }
    }

    /** Files through the app menu: the toolbar's File button is hidden on narrow single displays. */
    private fun openFiles() {
        tap(KeyEvent.KEYCODE_MENU)
        clickRow("Files")
    }
    /** Polls [condition] from the test thread; conditions touching app state use runOnMainSync themselves. */
    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        fail("$what did not complete")
    }

    /**
     * Clicks a row of the visible in-app popup by its exact text, like a tap. Searches only the popup layer, so
     * toolbar buttons with the same label are never hit, and rows scrolled out of view still work. Uses the
     * view hierarchy rather than accessibility, which some vendor builds (Retroid) report unreliably.
     */
    private fun clickRow(text: String) {
        instrumentation.waitForIdleSync()
        fun popups(v: View): List<View> = when {
            v is org.thortracker.ui.PopupLayer -> if (v.visibility == View.VISIBLE) listOf(v) else emptyList()
            v is ViewGroup -> (0 until v.childCount).flatMap { popups(v.getChildAt(it)) }
            else -> emptyList()
        }
        fun row(v: View): View? {
            if (v is android.widget.TextView && v.text?.toString() == text) {
                return if (v.isClickable) v else (v.parent as? android.widget.LinearLayout)?.takeIf { it.isClickable }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) row(v.getChildAt(i))?.let { return it }
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < deadline) {
            var clicked = false
            instrumentation.runOnMainSync {
                popups(activity.window.decorView).firstNotNullOfOrNull { row(it) }?.let { it.performClick(); clicked = true }
            }
            if (clicked) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(20)
        }
        fail("Row not found: $text")
    }

    @Test fun sketchArpeggioRepeatsWithoutWritingAndCancelsAtReleaseOrMenu() {
        val original = tracker.session.song
        val audioField = MainActivity::class.java.getDeclaredField("audio").apply { isAccessible = true }
        val engine = audioField.get(activity) as org.thortracker.audio.HugeAudio
        val sounded = mutableListOf<Int>()
        instrumentation.runOnMainSync {
            audioField.set(activity, object : org.thortracker.audio.TrackerAudio by engine {
                override fun audition(channel: Int, note: Int, instrument: Int, detune: Int) {
                    sounded += note
                    engine.audition(channel, note, instrument, detune)
                }
            })
            tracker.state.sketchScale = null
            tracker.state.sketchField = 0
            tracker.state.sketchRow = 1
            tracker.state.sketchChord = 0
            tracker.state.sketchCircle = false
            tracker.state.octave = 4
        }
        clickHostButton("Pattern")
        clickRow("Sketch")
        // View selection leaves the controller in the revealed toolbar until Back returns it to the editor.
        tap(KeyEvent.KEYCODE_BACK)
        assertEquals(ViewKind.SKETCH, tracker.state.kind)
        // Start from the demo's chromatic guide and choose C major with the actual field controls.
        tap(KeyEvent.KEYCODE_DPAD_UP)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        down(KeyEvent.KEYCODE_BUTTON_A)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        up(KeyEvent.KEYCODE_BUTTON_A)
        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        tap(KeyEvent.KEYCODE_BUTTON_START)
        val before = awaitPlayback(true)
        down(KeyEvent.KEYCODE_BUTTON_X)
        val deadline = SystemClock.uptimeMillis() + 4000
        var count = 0
        while (count < 6 && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { count = sounded.size }
            SystemClock.sleep(20)
        }
        var phrase = emptyList<Int>()
        instrumentation.runOnMainSync { phrase = sounded.toList() }
        assertTrue("Held chord must repeat", phrase.size >= 6)
        assertEquals(listOf(12, 16, 19, 12, 16, 19), phrase.take(6))
        up(KeyEvent.KEYCODE_BUTTON_X)
        instrumentation.runOnMainSync { count = sounded.size }
        SystemClock.sleep(450)
        instrumentation.runOnMainSync {
            assertEquals("Released arpeggio must not retrigger", count, sounded.size)
            assertFalse(tracker.controller.sketchAuditioning)
            assertEquals(original, tracker.session.song)
            assertFalse(tracker.session.canUndo)
        }
        val after = awaitPlayback(true)
        assertEquals(before.order, after.order)
        assertTrue("Preview must not restart song playback", after.row > before.row)
        assertTrue(engine.diagnostics().getValue("lastAuditionPrepNs") > 0)
        assertEquals(0L, engine.diagnostics().getValue("driverFaults"))
        assertNull(engine.error())
        down(KeyEvent.KEYCODE_BUTTON_A)
        tap(KeyEvent.KEYCODE_MENU)
        instrumentation.runOnMainSync { count = sounded.size }
        SystemClock.sleep(450)
        instrumentation.runOnMainSync {
            assertEquals("Menu must cancel the held arpeggio even without key-up", count, sounded.size)
            assertFalse(tracker.controller.sketchAuditioning)
        }
        up(KeyEvent.KEYCODE_BUTTON_A)
        tap(KeyEvent.KEYCODE_BACK)
        tap(KeyEvent.KEYCODE_BUTTON_START)
        awaitPlayback(false)
        instrumentation.runOnMainSync { audioField.set(activity, engine) }
    }

    private fun clickHostButton(text: String) {
        fun find(view: View): android.widget.Button? {
            if (view is android.widget.Button && view.text.toString() == text) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        var shown = false
        instrumentation.runOnMainSync { shown = find(activity.window.decorView)?.isShown == true }
        if (!shown) chord(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START)
        instrumentation.runOnMainSync {
            val button = checkNotNull(find(activity.window.decorView))
            check(button.isShown) { "Toolbar button is hidden: $text" }
            button.performClick()
        }
        instrumentation.waitForIdleSync()
    }

    private fun down(code: Int) {
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code))
        instrumentation.waitForIdleSync()
    }
    private fun up(code: Int) {
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code))
        instrumentation.waitForIdleSync()
    }
    private fun tap(code: Int) { down(code); up(code) }
    private fun chord(modifier: Int, code: Int) { down(modifier); tap(code); up(modifier) }

    private fun findTracker(view: View): TrackerView? {
        if (view is TrackerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findTracker(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun findEnabledPlay(view: View): Boolean {
        if (view is android.widget.Button && view.text.toString() == "Play" && view.isEnabled) return true
        if (view is ViewGroup) for (index in 0 until view.childCount) if (findEnabledPlay(view.getChildAt(index))) return true
        return false
    }
}
