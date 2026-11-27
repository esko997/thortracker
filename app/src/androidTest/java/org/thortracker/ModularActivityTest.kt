package org.thortracker

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.thortracker.audio.ChipAudio
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.input.Keymap
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.system.studio.*
import org.thortracker.ui.Popups
import org.thortracker.ui.TrackerView

/** Operator-only. Uses the Activity's working project and recovery; the operator cold-backs up and restores app data. */
@RunWith(AndroidJUnit4::class)
class ModularActivityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var activity: MainActivity
    private lateinit var tracker: TrackerView
    private lateinit var output: File
    private var savedKeymap: Keymap? = null
    private val held = linkedSetOf<Int>()
    private val report = StringBuilder()

    @Test fun controllerTouchBuildAndRecovery() {
        assumeTrue(args.getString("modularActivity") == "true")
        assumeTrue("The editor scenario uses Patchwork Bass; the soak accepts either fixture", fixtureName() == DEMO)
        val source = loadFixture(DEMO)
        runCase("editor") {
            seed(source)
            val original = patch()
            assertEquals("Patchwork Bass",original.name)
            chord(KeyEvent.KEYCODE_BUTTON_R2,KeyEvent.KEYCODE_BUTTON_B)
            assertEquals(ViewKind.INSTRUMENT,ui { tracker.state.kind })
            assertFalse(buildFace())
            focusField(2)
            val audio = engine()
            val old = original.value(0)
            down(KeyEvent.KEYCODE_BUTTON_X)
            try {
                await("Audition reached native voice") { audio.diagnostics()["auditionActive"] == 1L }
                chord(A,RIGHT)
                assertEquals(original.withValue(0,old+1),patch())
                assertTrue(ui { tracker.controller.auditionHeld })
                assertEquals(1L,audio.diagnostics().getValue("auditionActive"))
            } finally { up(KeyEvent.KEYCODE_BUTTON_X) }
            val heldEdit = patch()
            screenshot("performance")

            dragRibbon(1)
            val drag = patch()
            assertTrue(drag.value(1)>heldEdit.value(1))
            assertEquals(heldEdit.withValue(1,drag.value(1)),drag)
            chord(KeyEvent.KEYCODE_BUTTON_L2,A)
            assertEquals("One Undo restores all drag moves, not the preceding controller edit",heldEdit,patch())

            focusField(-1); tap(A)
            assertTrue(buildFace()); assertEquals(heldEdit,patch()); screenshot("build")
            focusField(6); tap(A) // Cables: use an audio path rather than an event/gate's fixed unity transform.
            choose("02 Oscillator:Audio → 04 Mixer:A")
            choose("Amount  "); chooseDigits("-450")
            val g = heldEdit.graph
            val cable = g.connections.single { it.source==2 && it.output==0 && it.destination==4 && it.input==0 }
            val connected = g.copy(connections=g.connections.map { if (it.id==cable.id) it.copy(scale=-450) else it })
            assertEquals(heldEdit.copy(graph=connected),patch())
            focusField(7); tap(A) // Bindings: edit one destination of this two-oscillator macro.
            choose("7  Transpose"); choose("02 Oscillator:Tune"); choose("Edit endpoints")
            chooseDigits("-7")
            assertEquals("First endpoint is only a draft",heldEdit.copy(graph=connected),patch())
            screenshot("second-endpoint"); chooseDigits("5")
            val macros = connected.macros.mapIndexed { i,m -> if(i!=6) m else m.copy(targets=m.targets.map { t ->
                if (t.node==2 && t.parameter==1) t.copy(minimum=-7,maximum=5) else t
            }) }
            val expected = heldEdit.copy(graph=connected.copy(macros=macros))
            assertEquals(expected,patch()); assertEquals(2,expected.graph.macros[6].targets.size)
            focusField(-1); tap(A); assertFalse(buildFace()); assertEquals(expected,patch())
            val bytes = ProjectCodec.encodeBytes(source.copy(sound=source.studio.withPatch(1,expected)))
            assertArrayEquals(bytes,ProjectCodec.encodeBytes(song()))
            report.appendLine("slot=1 macro0=$old→${expected.value(0)} cable=${cable.id} amount=-450 macro7Oscillator2Tune=-7..5")
            finishActivity() // onPause queues the real atomic recovery save; reopen with no project extra or seeding.
            launch()
            assertTrue("ProjectStore restored the working document",ui { field(activity,"recoveredWorkingSong")==true })
            assertArrayEquals("New Activity recovered the complete edited document",bytes,ProjectCodec.encodeBytes(song()))
            assertEquals(expected,patch()); screenshot("reopened")
            report.appendLine("recoveredBytes=${bytes.size} activityRecreated=true")
        }
    }

    @Test fun modularRowsRetainTheirNativeClockAndLiveImageVersion() {
        assumeTrue(args.getString("modularActivity") == "true")
        assumeTrue("The native row-clock scenario uses Patchwork Bass", fixtureName() == DEMO)
        val fixture = loadFixture(DEMO)
        fun pattern(length: Int): Pattern {
            val blank = Pattern.blank(fixture.studio.tracks.size)
            return blank.copy(length = length, tracks = blank.tracks.mapIndexed { lane, cells ->
                if (lane != 0) cells else cells.mapIndexed { row, cell ->
                    if (row < length) Cell(note = 36 + row, instrument = 1) else cell
                }
            })
        }
        val song = fixture.copy(ticksPerRow = 4, loopOrder = 1, orders = listOf(0, 1),
            patterns = listOf(pattern(3), pattern(4)))
        val original = song.studio.patch(1) as ModularPatch
        val edited = original.withValue(0, (original.value(0) + 1) % 128)
        runCase("row-clock") {
            // Use the production mixed Studio row/version reader without opening a physical MIDI port.
            ChipAudio(instrumentation.targetContext, mixedStudio = true).use { audio ->
                val stamp = LongArray(5)
                var cursor = -1L
                var deadline = Long.MIN_VALUE
                fun nextRow(order: Int, row: Int): StudioPatch {
                    var heard: Song? = null
                    await("native Studio row $order:$row") {
                        assertNull(audio.error())
                        heard = audio.studioRow(cursor, stamp)
                        heard != null
                    }
                    if (cursor >= 0) assertEquals("Row cursor advances once", cursor + 1, stamp[0])
                    assertEquals(order.toLong(), stamp[1])
                    assertEquals(row.toLong(), stamp[2])
                    assertTrue("Presentation deadline must advance at $order:$row", stamp[3] > deadline)
                    cursor = stamp[0]
                    deadline = stamp[3]
                    return checkNotNull(checkNotNull(heard).studio.patch(1))
                }
                fun awaitPlaying() = await("native Studio transport") {
                    assertNull(audio.error())
                    audio.position().playing
                }
                audio.update(song)
                audio.play(0, 1)
                awaitPlaying()
                assertEquals(original, nextRow(0, 1))
                val originalVersion = stamp[4]
                for ((order, row) in listOf(0 to 2, 1 to 0, 1 to 1, 1 to 2, 1 to 3, 1 to 0)) {
                    assertEquals(original, nextRow(order, row))
                    assertEquals(originalVersion, stamp[4])
                }
                await("presentation playhead reaches the loop order") {
                    val pos = audio.position()
                    pos.playing && pos.order == 1 && pos.row in 0..3
                }
                audio.update(song.copy(sound = song.studio.withPatch(1, edited)))
                var row = 1
                var changed = false
                repeat(8) {
                    if (!changed) {
                        val heard = nextRow(1, row)
                        row = (row + 1) % 4
                        changed = heard == edited
                        if (!changed) {
                            assertEquals(original, heard)
                            assertEquals(originalVersion, stamp[4])
                        }
                    }
                }
                assertTrue("Live edit reached a later native row", changed && stamp[4] > originalVersion)
                val editedVersion = stamp[4]
                assertEquals(edited, nextRow(1, row))
                assertEquals(editedVersion, stamp[4])
                audio.stop()
                await("Studio transport stops") { audio.position().let { !it.playing && !it.pending } }
                audio.play(0, 2)
                awaitPlaying()
                cursor = -1L
                assertEquals(edited, nextRow(0, 2))
                assertEquals(editedVersion, stamp[4])
                assertEquals(edited, nextRow(1, 0))
                audio.stop()
                await("Studio transport stops before one-row loop") { audio.position().let { !it.playing && !it.pending } }
                audio.update(song.copy(sound = song.studio.withPatch(1, edited), orders = listOf(0),
                    patterns = listOf(pattern(1)), loopOrder = 0))
                audio.play(0, 0)
                awaitPlaying()
                cursor = -1L
                repeat(8) { assertEquals(edited, nextRow(0, 0)) }
                assertEquals(0L, audio.diagnostics().getValue("driverFaults"))
                report.appendLine("nativeRows=seek,order,loop,live,stop,restart,one-row-loop imageVersion=$originalVersion→$editedVersion")
            }
        }
    }

    @Test fun foregroundPlaybackSoak() {
        assumeTrue(args.getString("modularActivity") == "true")
        assumeTrue(args.getString("modularSoak") == "true")
        val name = fixtureName()
        val fixture = loadFixture(name)
        val rack = args.getString("modularMaxRack") == "true"
        require(!rack || name==CAPACITY)
        val tracks = args.getString("modularActiveTracks")?.toInt() ?: when { rack -> 3; name==CAPACITY -> 4; else -> fixture.studio.tracks.size }
        require(tracks in 1..fixture.studio.tracks.size)
        val duration = args.getString("observeMillis")?.toLong()?.coerceIn(5000,180000) ?: 30000L
        val audition = args.getString("modularAudition") == "true"
        val edits = args.getString("modularEdits") == "true"
        require(!edits || audition)
        val auditionTrack = args.getString("modularAuditionTrack")?.toInt()
            ?: if (tracks < fixture.studio.tracks.size) tracks else 0
        require(auditionTrack in fixture.studio.tracks.indices)
        val staged = soakSong(fixture,tracks,rack)
        runCase("soak") {
            seed(staged)
            val audio = engine()
            report.appendLine("fixture=$name activeTracks=$tracks maxRack=$rack audition=$audition auditionTrack=$auditionTrack edits=$edits requestedMs=$duration")
            // All measurements come from the backend selected by MainActivity, never a second ChipAudio.
            // ChipAudio queues song changes; wait for its probe command behind the fixture update
            // before recording a baseline (the previous song may have used another native engine).
            audio.setTimingProbe(true)
            try {
                await("Studio fixture and timing probe ready") {
                    check(audio.error()==null) { "Audio refused: ${audio.error()}" }
                    audio.diagnostics()["timingEnabled"]==1L
                }
                if (audition) {
                    chord(KeyEvent.KEYCODE_BUTTON_R2,KeyEvent.KEYCODE_BUTTON_B)
                    assertEquals(ViewKind.INSTRUMENT,ui { tracker.state.kind })
                    focusField(2)
                    // Prefer a blank lane so audition adds a voice instead of borrowing a sounding one.
                    ui { tracker.state.listenChannel = auditionTrack }
                }
                val initial = audio.diagnostics()
                report.appendLine("beforePlay=$initial")
                tap(KeyEvent.KEYCODE_BUTTON_START)
                await("Activity transport starts: ${staged.title}") {
                    check(audio.error()==null) { "Audio refused: ${audio.error()}" }
                    audio.position().playing && ui { tracker.playback.playing } && audio.diagnostics()["timingEnabled"]==1L
                }
                await("A real song refill is timed") { audio.diagnostics().getValue("timingSongCount")>0L }
                val startup = audio.diagnostics()
                report.appendLine("startupXruns=${startup.getValue("xruns")}")
                screenshot("playing")
                val startPss = Debug.getPss()
                val begin = audio.diagnostics()
                val start = SystemClock.elapsedRealtime()
                val first = audio.position()
                var advanced = false
                var editsPerformed = 0
                var nextEdit = 2000L
                var nextCheckpoint = 2000L
                lateinit var end: Map<String,Long>
                try {
                    while (true) {
                        val elapsed = SystemClock.elapsedRealtime() - start
                        if (elapsed >= duration) break
                        if (audition) {
                            // Three seconds held, then nine seconds released: exercise the full
                            // eight-second effect tail before the next real controller attack.
                            if (elapsed % 12000L < 3000L) {
                                if (KeyEvent.KEYCODE_BUTTON_X !in held) {
                                    down(KeyEvent.KEYCODE_BUTTON_X)
                                    await("Foreground held audition") { audio.diagnostics()["auditionActive"] == 1L }
                                }
                            } else up(KeyEvent.KEYCODE_BUTTON_X)
                        }
                        if (edits && elapsed >= nextEdit) {
                            val before = patch().value(0)
                            val delta = if (editsPerformed % 2 == 0) 1 else -1
                            chord(A,if (delta > 0) RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                            assertEquals(before + delta,patch().value(0))
                            editsPerformed++
                            nextEdit += 2000L
                        }
                        SystemClock.sleep((minOf(duration, nextCheckpoint, elapsed + 500L) - elapsed).coerceAtLeast(1L))
                        check(ui { activity.hasWindowFocus() && !activity.isInMultiWindowMode && tracker.isShown }) { "Activity lost full-screen focus" }
                        val pos = audio.position()
                        assertNull("Foreground audio error",audio.error()); assertTrue("Unexpected transport stop: $pos",pos.playing)
                        advanced = advanced || pos.row!=first.row || pos.order!=first.order
                        val observed = SystemClock.elapsedRealtime() - start
                        if (nextCheckpoint <= duration && observed >= nextCheckpoint) {
                            val xruns = audio.diagnostics().getValue("xruns")
                            report.appendLine("checkpointMs=$nextCheckpoint observedMs=$observed xruns=$xruns")
                            nextCheckpoint = if (nextCheckpoint == 2000L) 10000L else Long.MAX_VALUE
                        }
                    }
                } finally {
                    end = audio.diagnostics()
                    report.appendLine("endXruns=${end.getValue("xruns")}")
                    report.appendLine("measuredMs=${SystemClock.elapsedRealtime()-start} advanced=$advanced pssKiB=$startPss→${Debug.getPss()}")
                    report.appendLine("controllerMacroEdits=$editsPerformed")
                    report.appendLine("measurementStart=$begin\nmeasurementEnd=$end")
                    for (key in listOf("xruns","driverFaults","timingNoneCount","timingSongCount","timingAuditionCount","timingBothCount")) {
                        report.appendLine("${key}Delta=${end.getValue(key)-begin.getValue(key)}")
                    }
                    for (group in listOf("None", "Song", "Audition", "Both")) {
                        val fields = listOf("CpuTotalNs", "FrameTotal", "OutputFrameTotal").joinToString(" ") { field ->
                            val key = "timing$group$field"
                            "${key}Delta=${end.getValue(key)-begin.getValue(key)}"
                        }
                        report.appendLine(fields)
                    }
                }
                assertTrue("No change in native play position",advanced)
                if (audition) up(KeyEvent.KEYCODE_BUTTON_X)
                tap(KeyEvent.KEYCODE_BUTTON_START)
                await("Activity transport stops") { !audio.position().playing && !audio.position().pending && ui { !tracker.playback.playing } }
                val stopped = audio.diagnostics(); report.appendLine("afterStop=$stopped")
                assertTrue("No refill measurements in observation window",end.getValue("timingSongCount")>begin.getValue("timingSongCount"))
                assertEquals("Driver faults, including startup/stop",0L,stopped.getValue("driverFaults")-initial.getValue("driverFaults"))
                // AAudio owns this counter per stream: compare while the measured stream is still open.
                assertTrue("AAudio xrun counter unavailable",initial.getValue("xruns")>=0 && end.getValue("xruns")>=0)
                assertEquals("AAudio xruns, including startup",0L,end.getValue("xruns")-initial.getValue("xruns"))
            } finally {
                report.appendLine("finalDiagnostics=${audio.diagnostics()} audioError=${audio.error()}")
                audio.setTimingProbe(false)
            }
        }
    }

    private fun fixtureName() = (args.getString("modularFixture") ?: DEMO).also { require(it==DEMO || it==CAPACITY) }
    private fun loadFixture(name: String) = File(instrumentation.targetContext.cacheDir,name).inputStream().use { input ->
        ProjectCodec.readDocument { b, offset, length -> input.read(b,offset,length) }
    }
    private fun soakSong(song: Song,active: Int,rack: Boolean): Song {
        var id=1
        val sound=if (!rack) song.studio else song.studio.copy(
            tracks=song.studio.tracks.map { track -> track.copy(inserts=List(4){StudioInsert(id++,StudioKind.DELAY)},
                sends=listOf(25,25),bindings=track.bindings.filterValues { it.instance==0 }) },
            buses=List(2){StudioBus(List(4){StudioInsert(id++,StudioKind.DELAY)})},nextInstance=id)
        return song.copy(sound=sound,patterns=song.patterns.map { p -> p.copy(tracks=p.tracks.mapIndexed { i,cells ->
            if (i<active) cells else List(cells.size){Cell.BLANK}
        }) })
    }

    private fun runCase(label: String, body: () -> Unit) {
        output=File(instrumentation.targetContext.cacheDir,"modular-activity-$label-${System.currentTimeMillis()}").also { check(it.mkdir()) }
        try {
            launch(); body(); report.appendLine("PASS")
        } catch (e: Throwable) {
            report.appendLine("FAIL ${e.stackTraceToString()}")
            throw e
        } finally {
            try {
                // Do not send input to a system overlay or another app on loss of focus. finish() invokes onPause cancellation.
                if (::activity.isInitialized && ui { !activity.isDestroyed && activity.hasWindowFocus() }) {
                    for (code in held.toList().asReversed()) up(code)
                }
            } finally {
                try {
                    if (::activity.isInitialized && ui { !activity.isDestroyed }) {
                        savedKeymap?.let { previous -> ui { applyKeymap(previous) } }
                        finishActivity()
                    }
                } finally {
                    File(output,"report.txt").writeText(report.toString())
                    instrumentation.sendStatus(0,Bundle().apply { putString("stream","MODULAR_ACTIVITY ${output.absolutePath}\n$report") })
                }
            }
        }
    }
    private fun <T> ui(block: () -> T): T {
        val task=FutureTask<T> { block() }; instrumentation.runOnMainSync(task); return task.get()
    }
    private fun await(what: String, condition: () -> Boolean) {
        val until=SystemClock.elapsedRealtime()+10000L
        while (SystemClock.elapsedRealtime()<until) { if (condition()) return; SystemClock.sleep(30) }
        fail("Timed out: $what")
    }
    private fun launch() {
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        await("MainActivity window focus, real audio initialization, and tracker layout") { ui {
            val root=activity.window.decorView
            val v=find(root) { it is TrackerView } as? TrackerView
            if(v!=null && v.isShown && v.width>0 && v.height>0 && activity.hasWindowFocus() && !activity.isInMultiWindowMode &&
                find(root) { it is Button && it.text.toString()=="Play" && it.isEnabled }!=null) { tracker=v; true } else false
        } }
    }
    private fun finishActivity() {
        val old=activity
        ui { old.finish() }; await("Old MainActivity destroyed") { ui { old.isDestroyed } }
    }
    private fun find(v: View,match: (View)->Boolean): View? {
        if (match(v)) return v
        if (v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i),match)?.let { return it }
        return null
    }
    private fun field(target: Any,name: String): Any? = target.javaClass.getDeclaredField(name).run { isAccessible=true; get(target) }
    private fun applyKeymap(map: Keymap) = MainActivity::class.java.getDeclaredMethod("applyKeymap",Keymap::class.java,String::class.java)
        .run { isAccessible=true; invoke(activity,map,null) }
    private fun seed(song: Song) = ui {
        savedKeymap=tracker.keymap
        applyKeymap(Keymap.DEFAULT)
        val workspace=field(activity,"panes") as Workspace
        val state=ViewState(kind=ViewKind.PATTERN,instrument=1,pinned=true,followSelection=false,followPlayback=false)
        workspace.panes[0]=state; tracker.state=state
        tracker.session.replaceSong(song); tracker.session.select(Selection()); tracker.session.step=0
        MainActivity::class.java.getDeclaredMethod("setFocus",Int::class.javaPrimitiveType).run { isAccessible=true; invoke(activity,0) }
        assertSame(workspace.panes[0],tracker.state); assertTrue(tracker.controllerFocused); assertFalse(popups().isOpen)
    }
    private fun song() = ui { tracker.session.song }
    private fun patch() = song().studio.patch(1) as ModularPatch
    private fun engine() = ui { field(activity,"audio") as ChipAudio }
    private fun popups() = field(activity,"popups") as Popups
    private fun buildFace() = ui { tracker.state.soundPanel("studio-mono").advanced }

    private fun down(code: Int) {
        check(ui { activity.hasWindowFocus() }) { "No Activity window focus before key $code" }
        check(held.add(code)); instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN,code)); instrumentation.waitForIdleSync()
    }
    private fun up(code: Int) {
        if (code !in held) return
        if (ui { activity.hasWindowFocus() }) instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP,code))
        held.remove(code); instrumentation.waitForIdleSync()
    }
    private fun tap(code: Int) { down(code); up(code) }
    private fun chord(modifier: Int,code: Int) { down(modifier); try { tap(code) } finally { up(modifier) } }
    private fun focusField(field: Int) {
        repeat(14) { if(ui { tracker.state.instField }==field) return; tap(RIGHT) }
        error("Instrument control $field unreachable; focus=${ui { tracker.state.instField }}")
    }
    /** Popup contents are read, never selected through the model; every move and confirmation is an actual key. */
    private fun choose(label: String) {
        val distance=ui {
            val spec=checkNotNull(popups().top) { "Expected a menu containing $label" }; check(spec.text==null)
            val target=spec.items.indexOfFirst { it.label==label }.takeIf { it>=0 }
                ?: spec.items.indices.singleOrNull { spec.items[it].label?.startsWith(label)==true }
                ?: error("$label missing or ambiguous in ${spec.title}: ${spec.items.map { it.label }}")
            target-spec.selected
        }
        repeat(abs(distance)) { tap(if(distance>0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
        assertTrue("D-pad did not reach $label",ui { popups().top?.let { it.items.getOrNull(it.selected)?.label?.startsWith(label) }==true })
        tap(A)
    }
    private fun chooseDigits(n: String) {
        choose("Choose digits"); for (ch in n) choose(if (ch=='-') "Minus" else ch.toString()); choose("Apply $n")
    }
    private fun nextFrame() {
        val frame=CountDownLatch(1)
        ui { tracker.postOnAnimation { tracker.postOnAnimation { frame.countDown() } } }
        assertTrue("No foreground frames",frame.await(3,TimeUnit.SECONDS))
    }
    private fun dragRibbon(index: Int) {
        nextFrame()
        val (x,y,dx)=ui {
            val painters=field(tracker.pane,"painters") as Map<*,*>
            val drawn=checkNotNull(field(checkNotNull(painters[ViewKind.INSTRUMENT]),"drawn"))
            val ribbons=field(drawn,"ribbons") as Array<*>
            val r=ribbons[index] as org.thortracker.ui.Rect
            val xy=IntArray(2); tracker.getLocationOnScreen(xy)
            check(r.width()>0 && r.height()>0 && tracker.isShown && activity.hasWindowFocus())
            Triple(xy[0]+r.left+r.width()*.2f,xy[1]+r.centerY(),r.width()*.42f)
        }
        val start=SystemClock.uptimeMillis()
        fun send(kind: Int,at: Float) {
            check(ui { activity.hasWindowFocus() })
            val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),kind,at,y,0)
            try { e.source=InputDevice.SOURCE_TOUCHSCREEN; instrumentation.sendPointerSync(e); instrumentation.waitForIdleSync() } finally { e.recycle() }
        }
        send(MotionEvent.ACTION_DOWN,x)
        try { send(MotionEvent.ACTION_MOVE,x+dx*.5f); send(MotionEvent.ACTION_MOVE,x+dx); send(MotionEvent.ACTION_UP,x+dx) }
        finally { if(ui { tracker.pane.touchActive && activity.hasWindowFocus() }) send(MotionEvent.ACTION_CANCEL,x+dx) }
    }
    private fun screenshot(label: String) {
        nextFrame()
        val done=CountDownLatch(1); var result=-1
        val image=ui {
            check(activity.hasWindowFocus())
            val decor=activity.window.decorView
            Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888).also { bmp ->
                PixelCopy.request(activity.window,bmp,{ status -> result=status; done.countDown() },Handler(Looper.getMainLooper()))
            }
        }
        check(done.await(5,TimeUnit.SECONDS)) { "Window capture timed out: $label" }
        try {
            assertEquals("Window capture $label",PixelCopy.SUCCESS,result)
            File(output,"$label.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { image.recycle() }
    }
    private companion object {
        const val DEMO="studio-modular.thor"
        const val CAPACITY="studio-modular-capacity.thor"
        const val A=KeyEvent.KEYCODE_BUTTON_A
        const val RIGHT=KeyEvent.KEYCODE_DPAD_RIGHT
    }
}
