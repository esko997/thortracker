package org.thortracker.model

import org.junit.Assert.assertEquals
import org.junit.Test
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.sid.SidSystem
import java.io.File
import java.security.MessageDigest

/**
 * Multiple effect columns ([Cell.more]) are an OPL3 feature; Game Boy, NES and SID project files must not change by a
 * byte. The hashes were taken from the encoder before effect columns existed (commit a5782bd) and every song here
 * must still encode to exactly those bytes, and re-encode identically after a decode.
 */
class OneColumnProjectBytesTest {
    private fun example(name: String) = ProjectCodec.decode(File("../examples/$name.thor").readText())

    private val songs: Map<String, () -> Song> = linkedMapOf(
        "gb-demo" to { Songs.demo() },
        "gb-blank" to { Songs.blank() },
        "nes-demo" to { NesSystem.demo() },
        "nes-blank" to { NesSystem.blank() },
        "sid-demo" to { SidSystem.demo() },
        "sid-blank" to { SidSystem.blank() },
        "thor-groove" to { example("thor-groove") },
        "controller-study" to { example("controller-study") },
    )

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun encodingsAreThoseFromBeforeEffectColumns() {
        val actual = songs.mapValues { (_, song) -> sha256(ProjectCodec.encode(song())) }
        println(actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," })
        assertEquals(PINNED, actual)
    }

    @Test
    fun decodeThenEncodeIsByteIdentical() {
        for ((name, song) in songs) {
            val text = ProjectCodec.encode(song())
            assertEquals(name, text, ProjectCodec.encode(ProjectCodec.decode(text)))
        }
    }

    private companion object {
        val PINNED: Map<String, String> = linkedMapOf(
            "gb-demo" to "a6f95eab9e8f657486c75a82e6622fb174ad4290e9866477f539a3425958c3a7",
            "gb-blank" to "e011dce2856a2bd204142ade0f9fdef5a989aa715e2b98a4fc25806bee75bd99",
            "nes-demo" to "f02c5cb24192b10b39930186dc6c44b20514e9119cabc58c85de9ae23ab75be4",
            "nes-blank" to "c56e486b19f839d2b0c388d18644a6ad68d8b095e606c7435d3e787d2205845b",
            "sid-demo" to "5dbf02061a0ca7343d3365094cb40ca50c98b8db2aaa35565a0a67d5ccccb7b4",
            "sid-blank" to "968bfefe31f0f15687c405d96b0b869688a8c5252214d56b9cf03ace85fbc20a",
            "thor-groove" to "a6f95eab9e8f657486c75a82e6622fb174ad4290e9866477f539a3425958c3a7",
            "controller-study" to "f204ae43f113a930f3eeeb20c7479d8aba1939f68de4f83ccc0cd9fcf01d9645",
        )
    }
}
