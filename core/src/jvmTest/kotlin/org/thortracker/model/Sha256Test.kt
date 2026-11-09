package org.thortracker.model

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The common SHA-256: FIPS 180-4 test vectors, and the JVM's digest on every padding boundary. */
class Sha256Test {
    @Test
    fun knownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()))
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.hex(ByteArray(1_000_000) { 'a'.code.toByte() }))
    }

    @Test
    fun matchesTheJvmDigestAcrossBlockBoundaries() {
        val random = Random(2608)
        for (size in (0..200) + listOf(8191, 8192, 8193)) {
            val data = ByteArray(size).also { random.nextBytes(it) }
            assertEquals(MessageDigest.getInstance("SHA-256").digest(data).toList(), Sha256.digest(data).toList(), "size $size")
        }
    }
}
