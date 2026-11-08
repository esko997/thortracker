package org.thortracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The diagnostics' message history: bounded, one line per message, refreshes of the same message kept once. */
class RecentMessagesTest {
    @Test
    fun keepsTheLastMessagesOnceEachOnOneLine() {
        RecentMessages.clear()
        RecentMessages.record("error", "Could not\nwrite")
        RecentMessages.record("status", "Could not\nwrite")   // the same text again (a status refresh): skipped
        repeat(30) { RecentMessages.record("status", "message $it") }
        RecentMessages.record("status", "message 29")
        val all = RecentMessages.all()
        assertEquals(RecentMessages.SIZE, all.size)
        assertTrue(all.first(), all.first().endsWith("status: message 10"))
        assertTrue(all.last(), all.last().endsWith("status: message 29"))
        RecentMessages.clear()
        RecentMessages.record("error", "Could not\nwrite")
        assertTrue(RecentMessages.all().single().endsWith("error: Could not write"))
        RecentMessages.clear()
    }
}
