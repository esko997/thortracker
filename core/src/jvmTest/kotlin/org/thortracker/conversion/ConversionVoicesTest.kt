package org.thortracker.conversion

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.system.SoundSystems
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.nes.*
import org.thortracker.system.opl3.Opl3Sound
import org.thortracker.system.opl3.Opl3System
import org.thortracker.system.opn.*
import org.thortracker.system.sid.SidSystem
import org.thortracker.system.vsu.VsuSystem
import org.thortracker.system.ws.*
import org.thortracker.system.ym2612.*

class ConversionVoicesTest {
    private fun song(base: Song, vararg cells: Triple<Int, Int, Cell>): Song {
        val tracks = Pattern.blank(base.system.channels.size).tracks.map { it.toMutableList() }
        cells.forEach { (ch, row, cell) -> tracks[ch][row] = cell }
        return base.copy(patterns = listOf(Pattern("Voice conversion", tracks)), orders = listOf(0))
    }

    @Test fun allCurrentTargetsHaveSelfContainedAuditionableMappedVoices() {
        val source = song(GbSystem.blank(), Triple(0, 0, Cell(60, 1)), Triple(2, 0, Cell(60, 1)), Triple(3, 0, Cell(60, 1)))
        for (target in SoundSystems.ALL) {
            val lanes = ConversionVoices.lanes(source, target)
            val issues = mutableListOf<ConversionIssue>()
            val plan = ConversionVoices.prepare(source, target, lanes, issues)
            for (lane in lanes) {
                if (lane.source !in listOf(0, 2, 3)) continue
                val instrument = plan.slotsBySourceChannel[lane.source][1]
                assertTrue("${target.id}: missing instrument", instrument > 0)
                val note = ConversionVoices.note(source, plan.song, lane, 60, instrument, "p0 c${lane.source} r0", issues, 1)
                assertTrue("${target.id}: removed playable note", note >= 0)
                assertNull("${target.id}: ${issues.joinToString()}", target.engine.auditionProblem(plan.song, lane.target, note, instrument, 0))
            }
            assertFalse("${target.id}: ${issues.joinToString()}", issues.any { it.impact == ConversionImpact.BLOCKER })
            assertTrue("${target.id}: structural sound", target.structuralErrors(plan.song).isEmpty())
        }
    }

    @Test fun compatibleRolesAreReservedBeforeEarlierApproximateVoices() {
        val source = YmSystem.blank()
        val lanes = ConversionVoices.lanes(source, GbSystem)
        assertEquals(3, lanes.single { it.source == YmRules.NOISE }.target)
        assertEquals(listOf(0, 1), lanes.filter { it.source in 6..8 }.map { it.target })
        assertEquals(2, lanes.single { it.source == 0 }.target)
    }

    @Test fun equalInstrumentBytesNeverMergeEditableSourceSlots() {
        val base = Opl3System.blank()
        val sound = base.sound as Opl3Sound
        val original = sound.instrument(1)!!
        val source = song(base.copy(sound = sound.withInstrument(2, original)),
            Triple(0, 0, Cell(60, 1)), Triple(0, 1, Cell(62, 2)), Triple(1, 0, Cell(64, 1)))
        val issues = mutableListOf<ConversionIssue>()
        val plan = ConversionVoices.prepare(source, SidSystem, ConversionVoices.lanes(source, SidSystem), issues)
        assertNotEquals(plan.slotsBySourceChannel[0][1], plan.slotsBySourceChannel[0][2])
        assertEquals(plan.slotsBySourceChannel[0][1], plan.slotsBySourceChannel[1][1])
        assertEquals(original, (source.sound as Opl3Sound).instrument(2))
    }

    @Test fun hiddenRowsAndUnusedPatternsParticipateInCapacityBlocking() {
        val base = Opl3System.blank()
        var sound = base.sound as Opl3Sound
        for (slot in 1..16) sound = sound.withInstrument(slot, sound.instrument(1)!!.copy(name = "Source $slot"))
        val parked = song(base.copy(sound = sound), *(1..16).map { Triple(0, it + 10, Cell(60, it)) }.toTypedArray()).patterns.single().copy(length = 1)
        val source = base.copy(sound = sound, patterns = listOf(Pattern.blank(base.system.channels.size), parked), orders = listOf(0))
        val issues = mutableListOf<ConversionIssue>()
        ConversionVoices.prepare(source, GbSystem, ConversionVoices.lanes(source, GbSystem), issues)
        assertTrue(issues.any { it.impact == ConversionImpact.BLOCKER && it.location.contains("slot 16") })
    }

    @Test fun inheritedNotesDoNotConsumeAnUnusedDefaultSlotAtCapacity() {
        val base = Opl3System.blank()
        var sound = base.sound as Opl3Sound
        for (slot in 2..16) sound = sound.withInstrument(slot, sound.instrument(1)!!.copy(name = "Source $slot"))
        val cells = (2..16).map { Triple(0, (it - 2) * 2, Cell(60, it)) } + Triple(0, 1, Cell(62, 0))
        val source = song(base.copy(sound = sound), *cells.toTypedArray())
        val issues = mutableListOf<ConversionIssue>()
        val plan = ConversionVoices.prepare(source, GbSystem, ConversionVoices.lanes(source, GbSystem), issues)
        assertFalse(issues.toString(), issues.any { it.impact == ConversionImpact.BLOCKER })
        assertEquals(0, plan.slotsBySourceChannel[0][1])
        assertEquals((1..15).toList(), (2..16).map { plan.slotsBySourceChannel[0][it] })
    }

    @Test fun sameSourceSlotSplitsOnlyForDifferentTargetVoiceKinds() {
        val source = song(Opl3System.blank(), Triple(0, 0, Cell(60, 1)), Triple(1, 0, Cell(60, 1)))
        val issues = mutableListOf<ConversionIssue>()
        val plan = ConversionVoices.prepare(source, GbSystem, listOf(ConversionLane(0, 0), ConversionLane(1, 2)), issues)
        assertTrue(plan.slotsBySourceChannel[0][1] > 0)
        assertTrue(plan.slotsBySourceChannel[1][1] > 0)
        assertTrue(issues.any { it.detail.contains("split") })
        assertNull(GbSystem.engine.auditionProblem(plan.song, 2, 60, plan.slotsBySourceChannel[1][1], 0))
    }

    @Test fun nesExpansionVoicesUseTheActualExpansionInstrumentKind() {
        val source = song(GbSystem.blank(), Triple(0, 0, Cell(60, 1)))
        for (target in SoundSystems.ALL) {
            val targetSound = target.blank().sound as? NesSound ?: continue
            if (targetSound.expansion == NesExpansion.NONE) continue
            val issues = mutableListOf<ConversionIssue>()
            val lane = ConversionLane(0, 5)
            val plan = ConversionVoices.prepare(source, target, listOf(lane), issues)
            val slot = plan.slotsBySourceChannel[0][1]
            val expected = if (targetSound.expansion == NesExpansion.MMC5) NesChip.APU else targetSound.expansion.chip
            assertEquals(expected, (plan.song.sound as NesSound).instruments[slot - 1].chip)
            assertNull(target.id, target.engine.auditionProblem(plan.song, 5, 60, slot, 0))
        }
    }

    @Test fun opnPromotionMovesSsgAndPreservesEditablePayloads() {
        val source = OpnSystem.blank()
        val lanes = ConversionVoices.lanes(source, OpnaSystem)
        assertEquals(listOf(0, 1, 2, 6, 7, 8), lanes.map { it.target })
        val issues = mutableListOf<ConversionIssue>()
        val plan = ConversionVoices.prepare(source, OpnaSystem, lanes, issues)
        assertEquals((source.sound as OpnSound).instruments, (plan.song.sound as OpnSound).instruments)
        assertEquals((source.sound as OpnSound).timerB, (plan.song.sound as OpnSound).timerB)
        assertFalse(issues.any { it.impact != ConversionImpact.CHANGE })
        assertTrue(OpnaSystem.structuralErrors(plan.song).isEmpty())
    }

    @Test fun wonderSwanPromotionRetainsWavesAndAllSettings() {
        val source = WsSystem.blank()
        val issues = mutableListOf<ConversionIssue>()
        val plan = ConversionVoices.prepare(source, WscSystem, ConversionVoices.lanes(source, WscSystem), issues)
        assertEquals((source.sound as WsSound).copy(color = true), plan.song.sound)
        assertEquals(4, ConversionVoices.lanes(source, WscSystem).size)
    }

    @Test fun nesPromotionRetainsApuSamplesRatherThanCreatingEmptyMappings() {
        val base = NesSystem.blank()
        val source = base.copy(sound = (base.sound as NesSound).copy(samples = listOf(DpcmSample("Test", List(17) { 85 }))))
        val issues = mutableListOf<ConversionIssue>()
        val plan = ConversionVoices.prepare(source, NesVrc6System, ConversionVoices.lanes(source, NesVrc6System), issues)
        assertEquals((source.sound as NesSound).samples, (plan.song.sound as NesSound).samples)
        assertEquals((source.sound as NesSound).instruments, (plan.song.sound as NesSound).instruments)
    }

    @Test fun trianglePitchAndOutOfRangeNotesAreExplicitlyAdjusted() {
        val source = song(NesSystem.blank(), Triple(2, 0, Cell(60, 1)))
        val lane = ConversionLane(2, 0)
        val issues = mutableListOf<ConversionIssue>()
        val plan = ConversionVoices.prepare(source, GbSystem, listOf(lane), issues)
        assertEquals(48, ConversionVoices.note(source, plan.song, lane, 60, 1, "triangle", issues, 1))
        assertEquals(24, ConversionVoices.note(source, plan.song, lane, 21, 1, "low", issues, 1))
        assertTrue(issues.any { it.location == "low" && it.detail.contains("clamped") })
        assertEquals(Cell.CUT, ConversionVoices.note(source, plan.song, lane, Cell.CUT, 1, "cut", issues, 1))
        assertEquals(Cell.EMPTY, ConversionVoices.note(source, plan.song, lane, Cell.EMPTY, 1, "empty", issues, 1))
    }

    @Test fun sampleAndNoiseKeysAreNotClaimedToPreservePitchOrResources() {
        val source = song(NesSystem.blank(), Triple(4, 0, Cell(48, 1)))
        val issues = mutableListOf<ConversionIssue>()
        val lane = ConversionLane(4, 0)
        val plan = ConversionVoices.prepare(source, SidSystem, listOf(lane), issues)
        ConversionVoices.note(source, plan.song, lane, 48, plan.slotsBySourceChannel[4][1], "sample-key", issues, 1)
        assertTrue(issues.any { it.location == "sample-key" && it.detail.contains("not a musical pitch") })
        assertTrue(issues.any { it.location == "Sound resources" && it.detail.contains("DPCM") })
        assertTrue(issues.any { it.detail.contains("Unused or unmapped") })
    }

    @Test fun inheritedNotesProvisionTheSourceDefaultWithoutUsingSlotZero() {
        val source = song(YmSystem.blank(), Triple(6, 0, Cell(60, 0)))
        val issues = mutableListOf<ConversionIssue>()
        val lane = ConversionLane(6, 0)
        val plan = ConversionVoices.prepare(source, GbSystem, listOf(lane), issues)
        val original = source.system.defaultInstrument(source, source.system.channels[6].bank)
        val converted = plan.slotsBySourceChannel[6][original]
        assertTrue(converted > 0)
        assertEquals(0, plan.slotsBySourceChannel[6][0])
        assertNull(GbSystem.engine.auditionProblem(plan.song, 0, 60, converted, 0))
    }

    @Test fun ordinaryVoicesNeverConsumeSampleOrFirmwareLanes() {
        val source = Opl3System.blank()
        assertTrue(ConversionVoices.lanes(source, YmSystem).none { YmRules.isPcm(it.target) })
        assertTrue(ConversionVoices.lanes(source, WscSystem).none { it.target == WsRules.HYPER_VOICE })
        assertTrue(ConversionVoices.lanes(source, OpnaSystem).all {
            val part = (OpnaSystem.blank().sound as OpnSound).chip.part(it.target)
            part == OpnPart.FM || part == OpnPart.SSG
        })
        assertTrue(ConversionVoices.lanes(source, NesEpsmSystem).none {
            it.target == NesRules.DPCM || NesRules.isEpsmRhythm(NesExpansion.EPSM, it.target)
        })
    }

    @Test fun n163WaveLengthChangesActualPitchAndIsNotSilentlyDiscarded() {
        val system = NesN163System.ALL.first()
        val base = system.blank()
        val sound = base.sound as NesSound
        val instrument = NesInstrument(chip = NesChip.N163,
            wave = N163Wave(size = 32, waves = listOf(N163Presets.sine(32))))
        val source = song(base.copy(sound = sound.copy(instruments = sound.instruments.toMutableList().also { it[0] = instrument })),
            Triple(5, 0, Cell(60, 1)))
        val issues = mutableListOf<ConversionIssue>()
        val lane = ConversionLane(5, 0)
        val plan = ConversionVoices.prepare(source, GbSystem, listOf(lane), issues)
        assertEquals(48, ConversionVoices.note(source, plan.song, lane, 60, 1, "long-wave", issues, 1))
        assertTrue(issues.any { it.location == "long-wave" && it.detail.contains("32-sample") })
    }

    @Test fun multifunctionNoiseLanesAreReservedAndProvisionedAsNoise() {
        val source = song(GbSystem.blank(), Triple(3, 0, Cell(60, 1)))
        val issues = mutableListOf<ConversionIssue>()
        val lanes = ConversionVoices.lanes(source, WsSystem)
        assertEquals(3, lanes.single { it.source == 3 }.target)
        val plan = ConversionVoices.prepare(source, WsSystem, lanes, issues)
        val slot = plan.slotsBySourceChannel[3][1]
        assertEquals(WsKind.NOISE, (plan.song.sound as WsSound).instrument(slot)!!.kind)
        assertNull(WsSystem.engine.auditionProblem(plan.song, 3, 60, slot, 0))
        val reverse = song(plan.song, Triple(3, 0, Cell(60, slot)))
        assertEquals(3, ConversionVoices.lanes(reverse, GbSystem).single { it.source == 3 }.target)
    }

    @Test fun inheritedNoiseKeepsItsCompatibleLaneWithoutRepeatedInstrumentNumbers() {
        val base = WsSystem.blank()
        val sound = base.sound as WsSound
        val source = song(base.copy(sound = sound.withInstrument(2, sound.instrument(1)!!.copy(kind = WsKind.NOISE))),
            Triple(3, 0, Cell(60, 2)), Triple(3, 1, Cell(62, 0)))
        val explicit = song(source, Triple(3, 0, Cell(60, 2)), Triple(3, 1, Cell(62, 2)))
        assertEquals(5, ConversionVoices.lanes(source, VsuSystem).single { it.source == 3 }.target)
        assertEquals(ConversionVoices.lanes(explicit, VsuSystem), ConversionVoices.lanes(source, VsuSystem))
    }
}
