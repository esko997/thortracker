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
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
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
        chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(changed, tracker.session.song)
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
        down(KeyEvent.KEYCODE_BUTTON_R2)
        down(KeyEvent.KEYCODE_BUTTON_A)
        up(KeyEvent.KEYCODE_BUTTON_R2)
        up(KeyEvent.KEYCODE_BUTTON_A)
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
        chord(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_START)
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
        up(KeyEvent.KEYCODE_BUTTON_A)
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
            clickHostButton("File")
            clickDialogItem("Save as…")
            instrumentation.waitForIdleSync()
            assertEquals("Save as did not launch the document picker", 1, picker.hits)
            instrumentation.removeMonitor(picker)
            clickHostButton("File")
            clickDialogItem("New blank song")
            clickDialogItem("Continue")
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
            clickHostButton("File")
            clickDialogItem("Save")
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

    private fun clickDialogItem(text: String) {
        instrumentation.uiAutomation.waitForIdle(250, 3000)
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }
            if (node != null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val now = SystemClock.uptimeMillis()
                for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.waitForIdle(250, 3000)
                return
            }
            SystemClock.sleep(20)
        }
        fail("Dialog item not found: $text")
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
