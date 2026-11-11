package org.thortracker.system.gb

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.backend.HugeLowering
import org.thortracker.editor.EditorSession

class GbMusicalMacrosTest {
    private val custom = GbInstrument(name = "Exact patch", volume = 9, envelopeDirection = 1,
        envelopePace = 5, length = 17, lengthEnabled = true, sweepTime = 6,
        sweepDirection = 1, sweepShift = 3, duty = 3, wave = 11, waveVolume = 3, noiseWidth = 1)

    @Test
    fun waveChoicesOwnOnlyTheirFieldAndKeepAllSharedSampleReferences() {
        val session = EditorSession(GbSystem.blank())
        val source = custom.copy(kind = GbBank.WAVE, length = 231)
        session.setInstrument(GbBank.WAVE, 1, source)
        val waves = session.song.gb.waves
        val packed = waves.map(HugeLowering::packWave)
        for (control in 0..2) for (rank in 0 until GbMusicalMacros.steps(GbBank.WAVE, control)) {
            val changed = GbMusicalMacros.apply(control, source, rank)
            assertEquals(rank, GbMusicalMacros.value(control, changed))
            assertSame(changed, GbMusicalMacros.apply(control, changed, rank))
            assertEquals(when (control) {
                0 -> source.copy(wave = rank)
                1 -> source.copy(waveVolume = intArrayOf(0, 3, 2, 1)[rank])
                else -> source.copy(lengthEnabled = rank < 3, length = if (rank < 3) intArrayOf(224, 192, 128)[rank] else 231)
            }, changed)
            session.setInstrument(GbBank.WAVE, 1, changed)
            assertSame(waves, session.song.gb.waves)
            for (index in waves.indices) {
                assertSame(waves[index], session.song.gb.waves[index])
                assertArrayEquals(packed[index], HugeLowering.packWave(session.song.gb.waves[index]))
            }
        }
    }

    @Test
    fun waveLengthIsARealGateAndDisabledRawLengthIsAnExactNoOp() {
        val wave = custom.copy(kind = GbBank.WAVE, length = 231)
        assertNull(GbMusicalMacros.value(2, wave))
        for (rank in 0..2) {
            val changed = GbMusicalMacros.apply(2, wave, rank)
            assertEquals(doubleArrayOf(0.125, 0.25, 0.5)[rank], SoundTimeline.cutSeconds(changed)!!, 0.0)
            assertEquals(wave.waveVolume, changed.waveVolume)
        }
        val held = wave.copy(lengthEnabled = false)
        assertEquals(3, GbMusicalMacros.value(2, held))
        assertSame(held, GbMusicalMacros.apply(2, held, 3))
        assertNull(SoundTimeline.cutSeconds(held))
        for (rank in 0..3) {
            val changed = GbMusicalMacros.apply(1, wave, rank)
            assertEquals(intArrayOf(0, 3, 7, 15)[rank], SoundTimeline.level(changed, 0.0))
        }
    }

    @Test
    fun noiseGrainOwnsWidthAndUsesTheActualTwoHardwareSequences() {
        val source = custom.copy(kind = GbBank.NOISE)
        for (rank in 0..1) {
            val changed = GbMusicalMacros.apply(0, source, rank)
            assertEquals(source.copy(noiseWidth = rank), changed)
            assertSame(changed, GbMusicalMacros.apply(0, changed, rank))
            assertEquals(rank shl 3, SoundTimeline.noiseNr43(changed, HugeRules.NOTE_OFFSET + 20) and 8)
        }
        val air = BooleanArray(64).also { SoundTimeline.lfsrBits(0, it) }
        val metal = BooleanArray(64).also { SoundTimeline.lfsrBits(1, it) }
        assertFalse(air.contentEquals(metal))
    }

    @Test
    fun noiseContourSharesThePulseEnvelopeWithoutConvertingBankOrTouchingOtherFields() {
        val source = custom.copy(kind = GbBank.NOISE)
        assertNull(GbMusicalMacros.value(1, source))
        assertNull(GbMusicalMacros.value(2, source))
        for (shape in 0..2) for (rank in 0..3) {
            val shaped = GbMusicalMacros.apply(1, source, shape)
            val changed = GbMusicalMacros.apply(2, shaped, rank)
            val pulse = GbPulseMacros.apply(GbPulseControl.LENGTH,
                GbPulseMacros.apply(GbPulseControl.SHAPE, custom, shape), rank)
            assertEquals(pulse.copy(kind = GbBank.NOISE), changed)
            assertEquals(source.copy(volume = changed.volume, envelopeDirection = changed.envelopeDirection,
                envelopePace = changed.envelopePace, length = changed.length, lengthEnabled = changed.lengthEnabled), changed)
            assertEquals(shape, GbMusicalMacros.value(1, changed))
            assertEquals(rank, GbMusicalMacros.value(2, changed))
            assertFalse(SoundTimeline.dacOff(changed))
            assertTrue(SoundTimeline.level(changed, 0.0) > 0)
            assertSame(changed, GbMusicalMacros.apply(1, changed, shape))
            assertSame(changed, GbMusicalMacros.apply(2, changed, rank))
        }
        val adopted = GbMusicalMacros.apply(2, source, 2)
        assertEquals(0, GbMusicalMacros.value(1, adopted))
        assertEquals(0.75, SoundTimeline.envelopeEndSeconds(adopted)!!, 0.0)
        val customGate = source.copy(volume = 12, envelopePace = 0)
        assertEquals(1, GbMusicalMacros.value(1, customGate))
        assertNull(GbMusicalMacros.value(2, customGate))
        assertSame(customGate, GbMusicalMacros.apply(1, customGate, 1))
    }

    @Test
    fun musicalChoicesLowerToTheNativeDriverBytesWithoutSyntheticInstrumentData() {
        val session = EditorSession(GbSystem.blank())
        val wave = GbMusicalMacros.apply(2, GbMusicalMacros.apply(1,
            custom.copy(kind = GbBank.WAVE), 2), 1)
        val noise = GbMusicalMacros.apply(2, GbMusicalMacros.apply(1,
            custom.copy(kind = GbBank.NOISE), 0), 2)
        session.setInstrument(GbBank.WAVE, 1, wave)
        session.setInstrument(GbBank.NOISE, 1, noise)
        val native = HugeLowering.lower(session.song)
        assertArrayEquals(byteArrayOf(192.toByte(), 0x40, 11, 0, 0, 0xC0.toByte()), native.wave[0])
        assertArrayEquals(byteArrayOf(0xC4.toByte(), 0, 0, 0x91.toByte(), 0, 0), native.noise[0])
        assertArrayEquals(HugeLowering.waveBytes(wave), native.wave[0])
        assertArrayEquals(HugeLowering.noiseBytes(noise), native.noise[0])
        for (index in session.song.gb.waves.indices)
            assertArrayEquals(HugeLowering.packWave(session.song.gb.waves[index]), native.waves[index])
        val uge = GbSystem.exports.first { it.extension == "uge" }
        val editedBytes = uge.export(session.song)
        session.undo()
        session.redo()
        assertArrayEquals(editedBytes, uge.export(session.song))
    }
}
