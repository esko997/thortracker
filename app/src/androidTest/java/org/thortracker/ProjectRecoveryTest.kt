package org.thortracker

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Songs
import org.thortracker.storage.ProjectStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ProjectRecoveryTest {
    @Test
    fun stalledExternalSaveDoesNotPreventNewerEditsFromReachingRecovery() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "recovery-probe-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val store = ProjectStore(context)
        val original = Songs.blank().copy(title = "Saved snapshot")
        val edited = original.copy(title = "Later unsaved edits")
        val recovery = File(directory, "recovery.thor")
        val pipe = File(directory, "blocked-destination.thor")
        // ContentResolver's real file output blocks opening this FIFO until a reader arrives.
        Os.mkfifo(pipe.path, 0x180)
        val externalDone = CountDownLatch(1)
        val externalBytes = AtomicReference<ByteArray>()
        fun recoveredTitle(): String? = try { ProjectCodec.decode(recovery.readText()).title } catch (_: Exception) { null }
        fun waitForTitle(title: String): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 2000
            while (recoveredTitle() != title && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            return recoveredTitle() == title
        }
        try {
            store.autosave(original, immediate = true)
            assertTrue("initial recovery was not written", waitForTitle(original.title))
            store.save(Uri.fromFile(pipe), original) { externalDone.countDown() }
            store.autosave(edited, immediate = true)
            assertTrue("a stalled provider prevented recovery of newer edits", waitForTitle(edited.title))
            assertEquals("external write should still be blocked", 1L, externalDone.count)
        } finally {
            val reader = thread(name = "recovery-probe-reader", isDaemon = true) {
                externalBytes.set(pipe.inputStream().use { it.readBytes() })
            }
            externalDone.await(5, TimeUnit.SECONDS)
            reader.join(5000)
            waitForTitle(edited.title)
            store.close()
            directory.deleteRecursively()
        }
        assertEquals("external save must retain its captured snapshot", original,
            ProjectCodec.decode(externalBytes.get().toString(Charsets.UTF_8)))
    }
}
