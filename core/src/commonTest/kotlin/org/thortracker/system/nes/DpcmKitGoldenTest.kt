package org.thortracker.system.nes

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The starter kit's bytes on every platform (JVM and Kotlin/Native run this): its synthesis is integer-only, so a song
 * made on the Deck holds the same samples as one made on Android.
 */
class DpcmKitGoldenTest {
    @Test
    fun kitBytesAreTheSameEverywhere() {
        val kit = DpcmStarterKit.samples()
        assertEquals(listOf(753, 673, 257, 1041, 1665), kit.map { it.data.size })
        assertEquals(EXPECTED_HASH, kit.fold(0x811C9DC5.toInt()) { h, s -> s.data.fold(h) { x, b -> (x xor b) * 0x01000193 } })
    }

    private companion object {
        /** FNV-1a over all kit bytes. */
        const val EXPECTED_HASH = 254745553
    }
}
