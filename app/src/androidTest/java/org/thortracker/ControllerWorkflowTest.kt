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
import org.thortracker.editor.Workspace
import org.thortracker.model.Cell
import org.thortracker.model.Songs
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesEpsmSystem
import org.thortracker.system.nes.nes
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
    private var midiTimingProbe: org.thortracker.audio.ActivityMidiTimingProbe? = null

    private fun launchActivity() {
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + 10000
        var ready = false
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val view = findTracker(activity.window.decorView)
                if (view != null) tracker = view
                ready = view != null && findEnabledPlay(activity.window.decorView)
            }
            if (ready) break
            SystemClock.sleep(40)
        }
        assertTrue("Actual audio initialization must complete", ready)
    }

    @Before fun setUp() {
        launchActivity()
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
        midiTimingProbe?.save(File(instrumentation.targetContext.cacheDir, "physical-midi-activity-clock.txt"))
    }

    /** Restores default bindings through the app's own apply path (router, views and saved prefs together). */
    private fun resetControls() {
        val apply = MainActivity::class.java.getDeclaredMethod("applyKeymap", Keymap::class.java, String::class.java)
            .apply { isAccessible = true }
        instrumentation.runOnMainSync { apply.invoke(activity, Keymap.DEFAULT, null) }
    }

    @Test fun patternTouchKeepsItsFirstPointerWhenFingersLiftOrReorder() {
        instrumentation.runOnMainSync {
            val before = tracker.session.song
            val bitmap = android.graphics.Bitmap.createBitmap(tracker.width, tracker.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            data class Finger(val id: Int, val y: Float)
            val first = Finger(0, tracker.height * .35f)
            val second = Finger(2, tracker.height * .65f)
            fun send(action: Int, vararg fingers: Finger) {
                val props = fingers.map { finger -> MotionEvent.PointerProperties().apply {
                    id = finger.id; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }.toTypedArray()
                val coords = fingers.map { finger -> MotionEvent.PointerCoords().apply {
                    x = tracker.width * .3f; y = finger.y; pressure = 1f; size = 1f
                } }.toTypedArray()
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, action, fingers.size, props, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { tracker.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            fun reset() {
                tracker.cancelInteraction()
                tracker.state.row = 0
                tracker.state.channel = 0
                tracker.refresh()
                tracker.draw(canvas)
            }
            fun position() = tracker.state.row to tracker.state.channel
            try {
                reset()
                send(MotionEvent.ACTION_DOWN, first)
                send(MotionEvent.ACTION_UP, first)
                val expected = position()
                reset()
                send(MotionEvent.ACTION_DOWN, second)
                send(MotionEvent.ACTION_UP, second)
                assertNotEquals("Probe points select different cells", expected, position())
                for (firstLiftsFirst in listOf(true, false)) {
                    reset()
                    send(MotionEvent.ACTION_DOWN, first)
                    send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
                    send(MotionEvent.ACTION_MOVE, second, first) // array order is not pointer ownership
                    if (firstLiftsFirst) {
                        send(MotionEvent.ACTION_POINTER_UP, first, second)
                        send(MotionEvent.ACTION_MOVE, second)
                        send(MotionEvent.ACTION_UP, second)
                    } else {
                        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
                        send(MotionEvent.ACTION_UP, first)
                    }
                    assertEquals("The original finger chooses the cell", expected, position())
                    assertFalse(tracker.pane.touchActive)
                    assertEquals(before, tracker.session.song)
                }
                reset()
                send(MotionEvent.ACTION_DOWN, first)
                tracker.cancelInteraction()
                val canceled = position()
                send(MotionEvent.ACTION_UP, second)
                assertEquals("A canceled gesture cannot transfer to another finger", canceled, position())
                assertFalse(tracker.pane.touchActive)
                assertEquals(before, tracker.session.song)
            } finally { bitmap.recycle() }
        }
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

    /** Missing key-ups must not leave an edit, modifier or audition alive after the host loses input. */
    @Test fun focusLossCancelsHeldInputWithoutSynthesizingATap() =
        assertInputInterruption { activity.onWindowFocusChanged(false); activity.onWindowFocusChanged(true) }

    @Test fun controllerRemovalCancelsHeldInputWithoutSynthesizingATap() =
        assertInputInterruption { activity.onInputDeviceRemoved(-123) }

    @Test fun controllerReconfigurationCancelsHeldInputWithoutSynthesizingATap() =
        assertInputInterruption { activity.onInputDeviceChanged(-123) }

    @Test fun interruptedBindingCaptureCannotRetainAnInvisibleModifier() {
        val popups = MainActivity::class.java.getDeclaredField("popups").apply { isAccessible = true }
            .get(activity) as org.thortracker.ui.Popups
        val interruptions = listOf<() -> Unit>(
            { activity.onWindowFocusChanged(false); activity.onWindowFocusChanged(true) },
            { activity.onInputDeviceRemoved(-123) },
            { activity.onInputDeviceChanged(-123) },
            { activity.onConfigurationChanged(android.content.res.Configuration(activity.resources.configuration)) },
        )
        for (interrupt in interruptions) {
            resetControls()
            tap(KeyEvent.KEYCODE_MENU)
            clickRow("Controls")
            clickRow(Control.AUDITION.label)
            down(KeyEvent.KEYCODE_BUTTON_L2)
            instrumentation.runOnMainSync {
                assertNotNull(popups.top?.capture)
                interrupt() // The modifier's key-up is deliberately missing.
                assertNull("Interrupted capture must no longer accept a binding", popups.top?.capture)
                assertEquals(Keymap.DEFAULT.binding(Control.AUDITION), tracker.keymap.binding(Control.AUDITION))
            }
            clickRow(Control.AUDITION.label)
            tap(KeyEvent.KEYCODE_BUTTON_THUMBR)
            clickRow("Replace")
            instrumentation.runOnMainSync {
                assertEquals(Binding(KeyEvent.KEYCODE_BUTTON_THUMBR), tracker.keymap.binding(Control.AUDITION))
            }
            up(KeyEvent.KEYCODE_BUTTON_L2)
            tap(KeyEvent.KEYCODE_MENU)
            down(KeyEvent.KEYCODE_BUTTON_THUMBR)
            tap(KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.runOnMainSync { assertTrue("Fresh unmodified R3 must listen", tracker.controller.auditionHeld) }
            up(KeyEvent.KEYCODE_BUTTON_THUMBR)
        }
    }
    private fun assertInputInterruption(interrupt: () -> Unit) {
        chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_Y) // Keyboard
        val audio = MainActivity::class.java.getDeclaredField("audio").apply { isAccessible = true }
            .get(activity) as org.thortracker.audio.ChipAudio
        instrumentation.runOnMainSync { assertEquals(ViewKind.KEYBOARD, tracker.state.kind) }
        assertEquals("Fixture has not auditioned yet", 0L, audio.diagnostics().getValue("lastAuditionPrepNs"))
        down(KeyEvent.KEYCODE_BUTTON_X)
        awaitTrue("accepted native audition") {
            assertNull(audio.error())
            audio.diagnostics().getValue("lastAuditionPrepNs") > 0
        }
        instrumentation.runOnMainSync {
            assertTrue(tracker.controller.auditionHeld)
            interrupt()
            assertFalse("Interruption must cancel Listen even without a key-up", tracker.controller.auditionHeld)
        }
        up(KeyEvent.KEYCODE_BUTTON_X)
        chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_DPAD_UP) // Pattern
        val original = tracker.session.song
        down(KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.runOnMainSync {
            assertTrue(tracker.controller.editHeld)
            interrupt()
            assertFalse("Interruption must cancel Edit even without a key-up", tracker.controller.editHeld)
            assertEquals("Cancellation must not write a tap", original, tracker.session.song)
        }
        // The old release must not act, and the next direction must navigate rather than edit.
        up(KeyEvent.KEYCODE_BUTTON_A)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.runOnMainSync {
            assertEquals(1, tracker.state.field)
            assertEquals(original, tracker.session.song)
        }
        down(KeyEvent.KEYCODE_BUTTON_R2)
        instrumentation.runOnMainSync {
            interrupt()
            assertTrue("No invisible view-switch modifier may survive", tracker.heldModifiers.isEmpty())
        }
        tap(KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.runOnMainSync { assertEquals(ViewKind.PATTERN, tracker.state.kind) }
        up(KeyEvent.KEYCODE_BUTTON_R2)

    }

    @Test fun usbMidiBetaEditsRecoverAndReturnToChip() {
        fun tracks() { openFiles(); clickRow("MIDI tracks and USB outputs…") }
        openFiles()
        clickRow("New USB MIDI song (Beta)")
        clickRow("Continue")
        tracks()
        clickRow("Add track")
        tap(KeyEvent.KEYCODE_BUTTON_R1)
        instrumentation.runOnMainSync {
            assertEquals(1, tracker.state.channel)
            val cell = Cell(note = 48, instrument = 1, effect = 8, param = 100)
                .withEffect(1, 6, 32).withEffect(2, 7, 2).withEffect(3, 9, 32)
            tracker.session.setCell(0, 1, 0, cell)
        }
        tracks()
        clickRow("Remove track 2")
        clickRow("Remove track 2")
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.runOnMainSync {
            assertEquals(2, tracker.session.song.system.channels.size)
            assertEquals(3, tracker.session.song.patterns[0].tracks[1][0].lastEffectColumn)
            activity.finish()
        }
        instrumentation.waitForIdleSync()
        launchActivity()
        instrumentation.runOnMainSync {
            assertTrue(tracker.session.song.sound is org.thortracker.system.midi.MidiSound)
            assertEquals(48, tracker.session.song.patterns[0].tracks[1][0].note)
            tracker.session.replaceSong(Songs.blank())
            assertSame(GbSystem, tracker.session.song.system)
        }
    }

    @Test fun studioPatchesTracksAndPlaybackSurviveRecovery() {
        fun sound() = tracker.session.song.sound as org.thortracker.system.studio.StudioSound
        fun tracks() { openFiles(); clickRow("Studio tracks and destinations…") }
        fun screenshot(name: String) {
            instrumentation.runOnMainSync {
                val image = android.graphics.Bitmap.createBitmap(tracker.width, tracker.height,
                    android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    tracker.draw(android.graphics.Canvas(image))
                    File(activity.cacheDir, name).outputStream().use {
                        check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { image.recycle() }
            }
        }
        openFiles()
        clickRow("New Studio song")
        clickRow("Continue")
        chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_B)
        instrumentation.runOnMainSync {
            assertEquals(ViewKind.INSTRUMENT, tracker.state.kind)
            assertEquals(1, tracker.state.instrument)
        }
        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        chord(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.runOnMainSync {
            assertEquals(2, tracker.state.instrument)
            assertEquals(1, sound().patch(2)!!.waveform)
            assertEquals("Editing slot 2 must not change slot 1", 0, sound().patch(1)!!.waveform)
        }
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.runOnMainSync { assertEquals(0, sound().patch(2)!!.waveform) }
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_B)
        instrumentation.runOnMainSync { assertEquals(1, sound().patch(2)!!.waveform) }
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        chord(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_LEFT)
        instrumentation.runOnMainSync {
            assertEquals("Left/right must stay on the same instrument", 2, tracker.state.instrument)
            assertEquals(59, sound().patch(2)!!.cutoff)
        }
        val patch = sound().patch(2)
        repeat(3) { tap(KeyEvent.KEYCODE_DPAD_LEFT) }
        tap(KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.runOnMainSync {
            assertTrue(tracker.state.soundPanel("studio-mono").advanced)
            assertEquals("Changing editor face preserves the exact patch", patch, sound().patch(2))
        }
        screenshot("studio-advanced.png")
        tap(KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.runOnMainSync {
            assertFalse(tracker.state.soundPanel("studio-mono").advanced)
            assertEquals(patch, sound().patch(2))
        }
        screenshot("studio-sound.png")
        chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_DPAD_UP)
        tracks()
        clickRow("Add track")
        tap(KeyEvent.KEYCODE_BUTTON_R1)
        tap(KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.runOnMainSync {
            assertEquals(1, tracker.state.channel)
            val cell = tracker.session.song.patterns[0].tracks[1][0]
            assertTrue("Controller entry creates a playable note", cell.note >= 0)
            assertEquals("Pattern assignment uses the selected saved patch", 2, cell.instrument)
        }
        tracks()
        clickRow("Remove track 2")
        clickRow("Remove track 2")
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.runOnMainSync {
            assertEquals(2, sound().tracks.size)
            assertEquals(2, tracker.session.song.patterns[0].tracks[1][0].instrument)
        }
        tap(KeyEvent.KEYCODE_BUTTON_START)
        awaitPlayback(true)
        awaitTrue("Studio advances through its pattern") {
            var advancing = false
            instrumentation.runOnMainSync { advancing = tracker.playback.playing && tracker.playback.row >= 2 }
            advancing
        }
        tap(KeyEvent.KEYCODE_BUTTON_START)
        awaitPlayback(false)
        var saved = ""
        instrumentation.runOnMainSync {
            saved = org.thortracker.model.ProjectCodec.encode(tracker.session.song)
            activity.finish()
        }
        instrumentation.waitForIdleSync()
        launchActivity()
        instrumentation.runOnMainSync {
            assertEquals("Recovery preserves patch edits, destinations and pattern assignments",
                saved, org.thortracker.model.ProjectCodec.encode(tracker.session.song))
            tracker.session.replaceSong(Songs.blank())
            assertSame(GbSystem, tracker.session.song.system)
        }
    }

    @Test fun midiRouteControlsAndDocumentPickerRoundTrip() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("physicalMidiCapture") == "yes")
        fun tracks() { openFiles(); clickRow("MIDI tracks and USB outputs…") }
        fun backend() = MainActivity::class.java.getDeclaredField("audio").apply { isAccessible = true }
            .get(activity) as org.thortracker.audio.MidiAudio
        fun setNumber(label: String, number: String) {
            tracks()
            clickRow(label)
            fun field(view: View): android.widget.EditText? = when (view) {
                is android.widget.EditText -> view
                is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { field(view.getChildAt(it)) }
                else -> null
            }
            instrumentation.runOnMainSync { requireNotNull(field(activity.window.decorView)).setText(number) }
            clickRow("Apply")
        }
        openFiles()
        clickRow("New USB MIDI song (Beta)")
        clickRow("Continue")
        // The disconnected track must refuse playback before a route is explicitly selected.
        tap(KeyEvent.KEYCODE_BUTTON_START)
        clickRow("OK")
        awaitTrue("physical port discovery") { backend().endpoints().size == 1 }
        val endpoint = backend().endpoints().single()
        tracks()
        clickRow("Bind USB output")
        clickRow(endpoint.label)
        awaitTrue("USB open") { backend().ready(endpoint.key) }
        setNumber("Modulation CC · 1", "71")
        tracks()
        clickRow("Clock / Start / Stop · off")
        if (InstrumentationRegistry.getArguments().getString("recordActivityClock") == "yes") {
            midiTimingProbe = org.thortracker.audio.ActivityMidiTimingProbe(backend(), endpoint.key)
        }
        tap(KeyEvent.KEYCODE_BUTTON_A)
        tap(KeyEvent.KEYCODE_BUTTON_START)
        awaitPlayback(true)
        val interactionSeconds = InstrumentationRegistry.getArguments().getString("interactionSeconds")
        if (interactionSeconds != null) {
            val deadline = SystemClock.elapsedRealtime() + interactionSeconds.toInt().also { require(it in 1..660) } * 1000L
            instrumentation.runOnMainSync {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            var edits = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                tap(KeyEvent.KEYCODE_DPAD_DOWN)
                tap(KeyEvent.KEYCODE_BUTTON_A)
                // Exercise the editor's live validation and recovery paths while the clock is running.
                instrumentation.runOnMainSync {
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val touch = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                            tracker.width * .45f, tracker.height * .55f, 0)
                        try { tracker.dispatchTouchEvent(touch) } finally { touch.recycle() }
                    }
                    val row = (edits + 1) % Songs.ROWS
                    tracker.session.setCell(0, 0, row, Cell(note = 45 + edits % 12, effect = 7, param = 2)
                        .withEffect(1, 1, 4).withEffect(2, 3, 3).withEffect(3, 9, edits % 128))
                    midiTimingProbe?.markEdit()
                }
                chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_A)
                chord(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_A)
                awaitPlayback(true)
                SystemClock.sleep(900)
                edits++
            }
            assertTrue(edits > 0)
            instrumentation.runOnMainSync { assertTrue(activity.moveTaskToBack(true)) }
            awaitTrue("background transport stop") { !backend().position().playing }
            SystemClock.sleep(750)
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            awaitTrue("foreground return") {
                var focused = false
                instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
                focused
            }
            assertFalse(backend().position().playing)
            tap(KeyEvent.KEYCODE_BUTTON_START)
            awaitPlayback(true)
            val keyguard = activity.getSystemService(android.app.KeyguardManager::class.java)
            if (!keyguard.isDeviceSecure) {
                fun shell(command: String) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
                }
                shell("input keyevent KEYCODE_SLEEP")
                try {
                    awaitTrue("screen-off transport stop") { !backend().position().playing }
                    SystemClock.sleep(750)
                } finally {
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                }
                awaitTrue("focus after wake") {
                    var focused = false
                    instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
                    focused
                }
                assertFalse(backend().position().playing)
                tap(KeyEvent.KEYCODE_BUTTON_START)
                awaitPlayback(true)
            }
        }
        tap(KeyEvent.KEYCODE_BUTTON_START)
        awaitPlayback(false)
        val expected = tracker.session.song
        assertEquals(71, (expected.sound as org.thortracker.system.midi.MidiSound).tracks.single().bindings.first().controller)
        assertTrue(expected.patterns.first().tracks.single().any { it.note >= 0 })
        val file = File(activity.cacheDir, "midi-roundtrip-${System.nanoTime()}.thor")
        fun picker(action: String) = Instrumentation.ActivityMonitor(
            IntentFilter(action).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") },
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))), true)
        val save = picker(Intent.ACTION_CREATE_DOCUMENT)
        val open = picker(Intent.ACTION_OPEN_DOCUMENT)
        try {
            instrumentation.addMonitor(save)
            openFiles(); clickRow("Save as…")
            awaitTrue("MIDI file save") {
                runCatching { org.thortracker.model.ProjectCodec.decode(file.readText(), androidMidi = true) == expected }.getOrDefault(false)
            }
            instrumentation.runOnMainSync { tracker.session.replaceSong(Songs.blank()) }
            tap(KeyEvent.KEYCODE_BUTTON_START)
            awaitPlayback(true)
            tap(KeyEvent.KEYCODE_BUTTON_START)
            awaitPlayback(false)
            instrumentation.addMonitor(open)
            openFiles(); clickRow("Open project"); clickRow("Continue")
            awaitTrue("MIDI file reopen") { tracker.session.song == expected }
        } finally {
            instrumentation.removeMonitor(save)
            instrumentation.removeMonitor(open)
            file.delete()
        }
    }

    @Test fun twentyChannelPatternNavigatesEditsAndRestoresItsLastLane() {
        val demo = NesEpsmSystem.demo()
        val last = demo.system.channels.lastIndex
        val instrument = demo.nes.instruments.indexOfFirst { it.chip == NesChip.EPSM } + 1
        val patterns = demo.patterns.toMutableList()
        patterns[0] = patterns[0].copy(tracks = patterns[0].tracks.mapIndexed { channel, rows ->
            if (channel == last) rows.mapIndexed { row, cell -> if (row == 0) Cell(instrument = instrument) else cell }
            else rows
        })
        val before = demo.copy(patterns = patterns)
        instrumentation.runOnMainSync {
            tracker.session.replaceSong(before)
            tracker.state.field = 1
            tracker.refresh()
        }
        instrumentation.waitForIdleSync()
        repeat(last) { tap(KeyEvent.KEYCODE_BUTTON_R1) }
        instrumentation.runOnMainSync {
            assertEquals(19, tracker.state.channel)
            assertEquals(1, tracker.state.field)
        }
        tap(KeyEvent.KEYCODE_BUTTON_B)
        instrumentation.runOnMainSync {
            assertEquals(0, tracker.session.song.patterns[0].tracks[last][0].instrument)
            assertEquals(before.patterns[0].tracks.dropLast(1), tracker.session.song.patterns[0].tracks.dropLast(1))
        }
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.runOnMainSync { assertEquals(before, tracker.session.song) }
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        launchActivity()
        instrumentation.runOnMainSync {
            assertEquals(before, tracker.session.song)
            assertEquals(19, tracker.state.channel)
            assertEquals(1, tracker.state.field)
        }
        instrumentation.runOnMainSync {
            val gb = Songs.demo()
            tracker.session.replaceSong(gb)
            assertEquals(gb, tracker.session.song)
            assertEquals(gb.system.channels.lastIndex, tracker.state.channel)
        }
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

    /**
     * L2+R2+A shows Song on the other display (the only one on a single screen) with the triggers pressed in either
     * order, and never undoes (L2+A) or switches the focused view (R2+A). Letting a trigger go first leaks nothing.
     */
    @Test fun bothTriggersShowAViewOnTheOtherDisplay() {
        down(KeyEvent.KEYCODE_BUTTON_A); tap(KeyEvent.KEYCODE_DPAD_RIGHT); up(KeyEvent.KEYCODE_BUTTON_A)
        val changed = tracker.session.song
        val panes = MainActivity::class.java.getDeclaredField("panes").apply { isAccessible = true }.get(activity) as Workspace
        var target = 0
        instrumentation.runOnMainSync {
            val hosts = MainActivity::class.java.getDeclaredField("hosts").apply { isAccessible = true }.get(activity) as Map<*, *>
            InstrumentationRegistry.getArguments().getString("requiredDisplays")?.toInt()?.let {
                assertEquals("The requested display topology must actually be attached", it, hosts.size)
            }
            target = if (hosts.size > 1) 1 else 0
            if (target != 0) panes.panes[target].kind = ViewKind.KEYBOARD
        }
        fun shown(): ViewKind { var k = ViewKind.PATTERN; instrumentation.runOnMainSync { k = panes.panes[target].kind }; return k }
        val before = shown()
        down(KeyEvent.KEYCODE_BUTTON_L2); down(KeyEvent.KEYCODE_BUTTON_R2)
        tap(KeyEvent.KEYCODE_BUTTON_A)
        up(KeyEvent.KEYCODE_BUTTON_L2); up(KeyEvent.KEYCODE_BUTTON_R2)
        assertEquals(ViewKind.SONG, shown())
        instrumentation.runOnMainSync {
            assertEquals(0, panes.focus)
            if (target == 1) assertEquals(ViewKind.PATTERN, tracker.state.kind)
        }
        assertEquals("L2+R2+A must not undo", changed, tracker.session.song)
        // Reverse order, and R2 let go before A: the same chord, back to the previous view.
        down(KeyEvent.KEYCODE_BUTTON_R2); down(KeyEvent.KEYCODE_BUTTON_L2)
        down(KeyEvent.KEYCODE_BUTTON_A)
        up(KeyEvent.KEYCODE_BUTTON_R2)
        up(KeyEvent.KEYCODE_BUTTON_A)
        up(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(before, shown())
        assertEquals("A's release must not edit", changed, tracker.session.song)
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

    @Test fun canceledKeyUpsDoNotActOnReleaseOrLeaveATriggerHeld() {
        instrumentation.runOnMainSync {
            tracker.session.replaceSong(Songs.blank())
            tracker.state.row = 0
            tracker.state.field = 0
            tracker.session.step = 1
            tracker.session.select(Selection())
        }
        val blank = tracker.session.song
        tap(KeyEvent.KEYCODE_BUTTON_A)
        assertNotEquals("An ordinary A press enters a note", blank, tracker.session.song)
        assertEquals("An ordinary A release advances the cursor", 1, tracker.state.row)
        chord(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(blank, tracker.session.song)
        instrumentation.runOnMainSync { tracker.state.row = 0 }
        fun canceledUp(code: Int) {
            instrumentation.runOnMainSync {
                activity.dispatchKeyEvent(KeyEvent.changeFlags(
                    KeyEvent(KeyEvent.ACTION_UP, code), KeyEvent.FLAG_CANCELED))
            }
            instrumentation.waitForIdleSync()
        }
        down(KeyEvent.KEYCODE_BUTTON_A)
        val entered = tracker.session.song
        assertNotEquals(blank, entered)
        canceledUp(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("Cancellation preserves edits already made on press", entered, tracker.session.song)
        assertEquals("A canceled release must not advance the cursor", 0, tracker.state.row)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("The canceled edit must no longer be held", entered, tracker.session.song)
        instrumentation.runOnMainSync { tracker.state.field = 0 }
        down(KeyEvent.KEYCODE_BUTTON_L2)
        canceledUp(KeyEvent.KEYCODE_BUTTON_L2)
        tap(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals("B must clear, not run L2+B's redo", Cell.EMPTY,
            tracker.session.song.patterns[0].tracks[0][0].note)
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

    @Test fun projectSaveUsesNormalSafWithoutAnAppScopedRoot() {
        var launched: Intent? = null
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                launched = Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        try {
            instrumentation.addMonitor(picker)
            openFiles()
            clickRow("Save as…")
            instrumentation.waitForIdleSync()
            val intent = requireNotNull(launched) { "Save as did not launch the system document picker" }
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertEquals("application/octet-stream", intent.type)
            assertFalse(intent.hasExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI))
            val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            assertEquals(grants, intent.flags and grants)
            assertTrue(requireNotNull(intent.getStringExtra(Intent.EXTRA_TITLE)).endsWith(".thor"))
        } finally {
            instrumentation.removeMonitor(picker)
        }
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
            clickRow(GbSystem.name)
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
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == instrumentation.targetContext.packageName &&
                destination.readBytes().contentEquals(bytes.get())) SystemClock.sleep(20)
            assertArrayEquals("saving the new song overwrote the previous song's destination",
                bytes.get(), destination.readBytes())
        } finally {
            instrumentation.removeMonitor(picker)
            if (reader == null) reader = thread(isDaemon = true) { destination.inputStream().use { it.readBytes() } }
            reader.join(3000)
            awaitTrue("Save picker settles") { instrumentation.uiAutomation.rootInActiveWindow != null }
            val foreground = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            if (foreground != instrumentation.targetContext.packageName) {
                assertTrue("Only this test's document picker may be dismissed",
                    foreground == "com.android.documentsui" || foreground == "com.google.android.documentsui")
                val time = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(
                        KeyEvent(time, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0), true))
                }
                awaitTrue("Tracker regains focus after its document picker") {
                    var focused = false
                    instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
                    focused
                }
            }
            destination.delete()
        }
    }

    /** Rebinds through the real Controls screen with real key events, then exports, resets and imports the keymap. */
    @Test fun controlsScreenRebindsChordsAndRoundTripsAKeymapFile() {
        val apply = MainActivity::class.java.getDeclaredMethod("applyKeymap", Keymap::class.java, String::class.java)
            .apply { isAccessible = true }
        fun keymap(): Keymap { var k = Keymap.DEFAULT; instrumentation.runOnMainSync { k = tracker.keymap }; return k }
        val l3b = Binding(KeyEvent.KEYCODE_BUTTON_B, setOf(KeyEvent.KEYCODE_BUTTON_THUMBL))
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
            instrumentation.removeMonitor(importMonitor)

            // A two-trigger capture is a separate chord; releasing one trigger before the button
            // must instead capture only the remaining trigger.
            val bothLeft = Binding(KeyEvent.KEYCODE_DPAD_LEFT,
                setOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2))
            tap(KeyEvent.KEYCODE_MENU)
            clickRow("Controls")
            clickRow("Undo")
            down(KeyEvent.KEYCODE_BUTTON_L2); down(KeyEvent.KEYCODE_BUTTON_R2)
            tap(KeyEvent.KEYCODE_DPAD_LEFT)
            up(KeyEvent.KEYCODE_BUTTON_L2); up(KeyEvent.KEYCODE_BUTTON_R2)
            assertEquals(bothLeft, keymap().binding(Control.UNDO))
            clickRow("Redo")
            down(KeyEvent.KEYCODE_BUTTON_L2); down(KeyEvent.KEYCODE_BUTTON_R2)
            up(KeyEvent.KEYCODE_BUTTON_L2)
            tap(KeyEvent.KEYCODE_DPAD_LEFT)
            up(KeyEvent.KEYCODE_BUTTON_R2)
            val rightLeft = Binding(KeyEvent.KEYCODE_DPAD_LEFT, setOf(KeyEvent.KEYCODE_BUTTON_R2))
            assertEquals(rightLeft, keymap().binding(Control.REDO))
            assertEquals(bothLeft, keymap().binding(Control.UNDO))

            file.delete()
            instrumentation.addMonitor(exportMonitor)
            clickRow("Export keymap…")
            awaitTrue("two-trigger keymap export") {
                file.exists() && runCatching { Keymap.fromJson(file.readText()).binding(Control.UNDO) == bothLeft }.getOrDefault(false)
            }
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
            awaitTrue("two-trigger keymap import") { keymap().binding(Control.UNDO) == bothLeft }
            assertEquals(rightLeft, keymap().binding(Control.REDO))
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
        val engine = audioField.get(activity) as org.thortracker.audio.ChipAudio
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
        clickToolbarButton("Pattern")
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
        assertEquals(listOf(36, 40, 43, 36, 40, 43), phrase.take(6))
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

    private fun clickToolbarButton(text: String) {
        fun find(view: View): android.widget.Button? {
            if (view is android.widget.Button && view.text.toString() == text) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        fun toolbar(view: View): org.thortracker.ui.ShellToolbar? {
            if (view is org.thortracker.ui.ShellToolbar) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) toolbar(view.getChildAt(index))?.let { return it }
            return null
        }
        fun button() = toolbar(activity.window.decorView)?.let(::find)
        var shown = false
        instrumentation.runOnMainSync { shown = button()?.isShown == true }
        if (!shown) chord(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START)
        instrumentation.runOnMainSync {
            val button = checkNotNull(button())
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
