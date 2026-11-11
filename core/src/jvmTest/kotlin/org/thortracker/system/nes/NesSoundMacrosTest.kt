package org.thortracker.system.nes

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.system.ym2612.YmOperator

class NesSoundMacrosTest {
    private fun patch() = NesInstrument(
        name = "Keep this patch",
        volume = NesEnvelope(listOf(13, 8, 4, 0), loop = 1, release = 3),
        duty = NesEnvelope(listOf(0, 1, 2), loop = 0),
        arpeggio = NesEnvelope(listOf(0, 7, 12), loop = 0),
        pitch = NesEnvelope(listOf(1, -1), loop = 0), pitchRelative = true,
        dpcm = mapOf(48 to DpcmMapping(0, 9, true, 17), 49 to DpcmMapping(1)),
        pcm = mapOf(48 to PcmMapping(0, -12, true), 49 to PcmMapping(1)),
    )

    @Test fun everyExpansionChannelHasAnHonestVoiceAndBoundedControls() {
        val seen = mutableSetOf<NesVoice>()
        for (expansion in NesExpansion.entries) for (channel in expansion.system.channels.indices) {
            val voice = NesVoice.of(expansion, channel, NesRules.chip(expansion, channel))
            seen += voice
            val controls = NesSoundMacros.controls(voice)
            assertTrue("$expansion/$channel", controls.size in 3..6)
            assertEquals(NesSoundControl.VOICE, controls.first())
            assertEquals(controls.size, controls.toSet().size)
        }
        assertEquals(NesVoice.entries.toSet(), seen)
        for (expansion in NesExpansion.entries.filter { it.chip == NesChip.N163 }) {
            for (channel in 5 until expansion.system.channels.size)
                assertEquals(NesVoice.N163, NesVoice.of(expansion, channel, NesChip.N163))
        }
    }

    @Test fun customSequencesStayExplicitAndRecipesOwnOneEnvelopeOnly() {
        val before = patch()
        for (control in listOf(NesSoundControl.CHARACTER, NesSoundControl.CONTOUR, NesSoundControl.TRANSPOSE))
            assertNull(NesSoundMacros.value(control, before, NesVoice.PULSE))
        val width = NesSoundMacros.apply(NesSoundControl.CHARACTER, before, NesVoice.PULSE, 3)
        assertEquals(NesEnvelope(listOf(3)), width.duty)
        assertEquals(before, width.copy(duty = before.duty))
        val grain = NesSoundMacros.apply(NesSoundControl.GRAIN, before, NesVoice.NOISE, 1)
        assertEquals(NesEnvelope(listOf(1)), grain.duty)
        assertEquals(before, grain.copy(duty = before.duty))
        val register = NesSoundMacros.apply(NesSoundControl.TRANSPOSE, before, NesVoice.PULSE, 48)
        assertEquals(NesEnvelope(listOf(24)), register.arpeggio)
        assertEquals(before, register.copy(arpeggio = before.arpeggio))
        for (shape in 0..3) {
            val changed = NesSoundMacros.apply(NesSoundControl.CONTOUR, before, NesVoice.PULSE, shape)
            assertEquals(before, changed.copy(volume = before.volume))
            assertEquals(shape, NesSoundMacros.value(NesSoundControl.CONTOUR, changed, NesVoice.PULSE))
            assertSame(changed, NesSoundMacros.apply(NesSoundControl.CONTOUR, changed, NesVoice.PULSE, shape))
            val gate = NesSoundMacros.apply(NesSoundControl.GATE, before, NesVoice.TRIANGLE, shape)
            assertTrue(gate.volume.values.all { it == 0 || it == 15 })
            assertEquals(before, gate.copy(volume = before.volume))
        }
        val static = before.copy(duty = NesEnvelope(listOf(3, 3), loop = 0, release = 1))
        assertSame(static, NesSoundMacros.apply(NesSoundControl.CHARACTER, static, NesVoice.PULSE, 3))
        for (voice in NesVoice.entries) for (control in NesSoundMacros.controls(voice)) {
            assertSame(before, NesSoundMacros.apply(control, before, voice, -1))
            assertSame(before, NesSoundMacros.apply(control, before, voice, control.maximum + 1))
        }
    }

    @Test fun vrc6WidthAndDriveDoNotReplaceOtherPrograms() {
        val before = patch().copy(chip = NesChip.VRC6)
        val wide = NesSoundMacros.apply(NesSoundControl.VRC6_WIDTH, before, NesVoice.VRC6_PULSE, 7)
        assertEquals(listOf(7), wide.duty.values)
        assertEquals(before, wide.copy(duty = before.duty))
        val full = NesSoundMacros.apply(NesSoundControl.DRIVE, before, NesVoice.SAW, 2)
        assertEquals(Vrc6SawVolume.FULL, full.sawVolume)
        assertEquals(before, full.copy(sawVolume = before.sawVolume))
    }

    @Test fun n163WaveRecipeKeepsEverySizePositionAndSequenceSetting() {
        for (size in listOf(4, 16, 64, 128)) {
            val wave = N163Wave(size = size, position = 8, waves = listOf(List(size) { it % 16 }, List(size) { 7 }),
                repeat = listOf(3, 5), loop = 0, release = 1)
            val before = patch().copy(chip = NesChip.N163, wave = wave)
            val after = NesSoundMacros.apply(NesSoundControl.WAVE, before, NesVoice.N163, 2)
            assertEquals(N163Presets.wave(WavePreset.SAWTOOTH, size, 0, 1), after.wave.waves[0])
            assertSame(wave.waves[1], after.wave.waves[1])
            assertEquals(wave, after.wave.copy(waves = wave.waves))
            assertEquals(before, after.copy(wave = wave))
        }
    }

    @Test fun fdsWaveMotionDepthAndSpeedHaveDisjointOwnershipAndRespectAutoMode() {
        val fds = FdsSound(waves = listOf(List(64) { it }, List(64) { 32 }), repeat = listOf(2, 7), loop = 0,
            release = 1, modSpeed = 1234, modDepth = 19, modDelay = 8, autoModNumer = 3, autoModDenom = 2)
        val before = patch().copy(chip = NesChip.FDS, fds = fds)
        val wave = NesSoundMacros.apply(NesSoundControl.WAVE, before, NesVoice.FDS, 0)
        assertSame(fds.waves[1], wave.fds.waves[1])
        assertEquals(before, wave.copy(fds = wave.fds.copy(waves = fds.waves)))
        val motion = NesSoundMacros.apply(NesSoundControl.MOTION, before, NesVoice.FDS, 1)
        assertEquals(FdsPresets.modulation(WavePreset.TRIANGLE), motion.fds.modulation)
        assertEquals(before, motion.copy(fds = motion.fds.copy(modulation = fds.modulation)))
        val depth = NesSoundMacros.apply(NesSoundControl.DEPTH, before, NesVoice.FDS, 63)
        assertEquals(before.copy(fds = fds.copy(modDepth = 63)), depth)
        val speed = NesSoundMacros.apply(NesSoundControl.SPEED, before, NesVoice.FDS, 4095)
        assertEquals(before.copy(fds = fds.copy(modSpeed = 4095)), speed)
        val auto = before.copy(fds = fds.copy(autoMod = true))
        assertFalse(NesSoundMacros.enabled(NesSoundControl.SPEED, auto, NesVoice.FDS, 48))
        assertSame(auto, NesSoundMacros.apply(NesSoundControl.SPEED, auto, NesVoice.FDS, 1))
    }

    @Test fun sharedSsgResourcesAreNotSilentlyOverwrittenByContours() {
        for (chip in listOf(NesChip.S5B, NesChip.EPSM)) {
            val before = patch().copy(chip = chip, s5b = S5bEnvelope(S5bShape.TRIANGLE, false, -2, 7654),
                mixer = NesEnvelope(listOf(0, 1), loop = 0), noise = NesEnvelope(listOf(31, 4), loop = 0))
            assertFalse(NesSoundMacros.enabled(NesSoundControl.CONTOUR, before, NesVoice.SSG, 48))
            assertSame(before, NesSoundMacros.apply(NesSoundControl.CONTOUR, before, NesVoice.SSG, 0))
            val shape = NesSoundMacros.apply(NesSoundControl.ENVELOPE, before, NesVoice.SSG, 0)
            assertEquals(before.copy(s5b = before.s5b.copy(shape = S5bShape.OFF)), shape)
            val blend = NesSoundMacros.apply(NesSoundControl.MIX, before, NesVoice.SSG, 3)
            assertEquals(before.copy(mixer = NesEnvelope(listOf(3))), blend)
            assertEquals("Constant level", NesSoundMacros.label(NesSoundControl.MIX, 3))
            val noise = NesSoundMacros.apply(NesSoundControl.NOISE_COLOR, before, NesVoice.SSG, 24)
            assertEquals(before.copy(noise = NesEnvelope(listOf(24))), noise)
        }
    }

    @Test fun vrc7RomEditsStartFromSoundingRegistersAndPreserveTheCarrier() {
        for (preset in 1..15) {
            val before = patch().copy(chip = NesChip.VRC7, fm = Vrc7Patch.DEFAULT.withPreset(preset))
            val regs = before.fm.sounding
            for ((control, param, value) in listOf(
                Triple(NesSoundControl.BRIGHTNESS, Vrc7Param.LEVEL, 63 - Vrc7Param.LEVEL.get(regs, 0)),
                Triple(NesSoundControl.EDGE, Vrc7Param.FEEDBACK, Vrc7Param.FEEDBACK.get(regs, 0)),
            )) {
                assertSame(before, NesSoundMacros.apply(control, before, NesVoice.VRC7, value))
                val next = (value + 1) % (control.maximum + 1)
                val changed = NesSoundMacros.apply(control, before, NesVoice.VRC7, next)
                assertEquals(0, changed.fm.preset)
                assertEquals(param.set(regs, 0, if (control == NesSoundControl.BRIGHTNESS) 63 - next else next), changed.fm.sounding)
                assertEquals(before, changed.copy(fm = before.fm))
            }
            val custom = NesSoundMacros.apply(NesSoundControl.PRESET, before, NesVoice.VRC7, 0)
            assertEquals(regs, custom.fm.sounding)
        }
    }

    @Test fun epsmFmUsesRolesAndKeepsSsgRhythmAndSongResources() {
        for (algorithm in 0..7) {
            val epsm = EpsmPatch(algorithm = algorithm, feedback = 2, left = false, ams = 2, pms = 5,
                operators = List(4) { YmOperator(mul = it + 1, tl = 20 + it * 10, ar = 26, d1r = 9, sl = 3, rr = 4) })
            val before = patch().copy(chip = NesChip.EPSM, epsm = epsm)
            for (control in listOf(NesSoundControl.FM_BRIGHTNESS, NesSoundControl.FM_HARMONICS, NesSoundControl.FM_CONTOUR)) {
                val after = NesSoundMacros.apply(control, before, NesVoice.EPSM_FM, if (control == NesSoundControl.FM_BRIGHTNESS) 100 else 0)
                assertEquals(before, after.copy(epsm = before.epsm))
                assertEquals(epsm, after.epsm.copy(operators = epsm.operators))
                for (i in 0..3) {
                    val old = epsm.operators[i]
                    val now = after.epsm.operators[i]
                    if (control == NesSoundControl.FM_CONTOUR) {
                        if (!epsm.isCarrier(i)) assertSame(old, now)
                        else assertEquals(old, now.copy(ar = old.ar, d1r = old.d1r, d2r = old.d2r, sl = old.sl, rr = old.rr))
                    } else if (epsm.isCarrier(i)) assertSame(old, now)
                    else assertEquals(old, if (control == NesSoundControl.FM_BRIGHTNESS) now.copy(tl = old.tl) else now.copy(mul = old.mul))
                }
            }
            val special = before.copy(epsm = epsm.copy(operators = epsm.operators.mapIndexed { i, op -> if (epsm.isCarrier(i)) op.copy(ssgEg = 10) else op }))
            assertSame(special, NesSoundMacros.apply(NesSoundControl.FM_CONTOUR, special, NesVoice.EPSM_FM, 0))
        }
    }

    @Test fun rhythmChangesOnlyFirstVolumeAndPan() {
        val before = patch().copy(chip = NesChip.EPSM)
        val hit = NesSoundMacros.apply(NesSoundControl.HIT, before, NesVoice.RHYTHM, 7)
        assertEquals(before.copy(volume = before.volume.copy(values = listOf(7, 8, 4, 0))), hit)
        val pan = NesSoundMacros.apply(NesSoundControl.PAN, before, NesVoice.RHYTHM, 2)
        assertEquals(before.copy(epsm = before.epsm.copy(left = false, right = true)), pan)
    }

    @Test fun samplesEditOnlyTheSelectedKeyAndRetainBothMappingBanks() {
        val before = patch()
        val dpcm = NesSoundMacros.apply(NesSoundControl.SAMPLE, before, NesVoice.DPCM, 2, 48, 2)
        assertEquals(before.copy(dpcm = before.dpcm + (48 to before.dpcm.getValue(48).copy(sample = 1))), dpcm)
        val pcm = NesSoundMacros.apply(NesSoundControl.SAMPLE, before, NesVoice.PCM, 2, 48, 2)
        assertEquals(before.copy(pcm = before.pcm + (48 to before.pcm.getValue(48).copy(sample = 1))), pcm)
        val pitch = NesSoundMacros.apply(NesSoundControl.RATE, before, NesVoice.DPCM, 15, 48)
        assertEquals(before.copy(dpcm = before.dpcm + (48 to before.dpcm.getValue(48).copy(pitch = 15))), pitch)
        val tune = NesSoundMacros.apply(NesSoundControl.TUNE, before, NesVoice.PCM, 72, 48)
        assertEquals(before.copy(pcm = before.pcm + (48 to before.pcm.getValue(48).copy(pitch = 24))), tune)
        for (voice in listOf(NesVoice.DPCM, NesVoice.PCM)) {
            val unmap = NesSoundMacros.apply(NesSoundControl.SAMPLE, before, voice, 0, 48, 2)
            assertSame(unmap, NesSoundMacros.apply(NesSoundControl.LOOP, unmap, voice, 1, 48))
            val remap = NesSoundMacros.apply(NesSoundControl.SAMPLE, unmap, voice, 1, 48, 2)
            if (voice == NesVoice.PCM) assertEquals(PcmMapping(0), remap.pcm[48])
            else assertEquals(DpcmMapping(0), remap.dpcm[48])
        }
    }
}
