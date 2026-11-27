package org.thortracker.system.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.midi.MidiTrack

class StudioModularAdmissionTest {
    private val generous = StudioModularLimits("test large", 100000, 4L * 1024 * 1024 * 1024, 6, 8)
    private fun heavy(): ModularPatch {
        val nodes = mutableListOf(ModularNode(1, ModularKind.NOTE), ModularNode(2, ModularKind.OSC))
        val cables = mutableListOf(ModularConnection(1, 1, 0, 2, 0))
        var previous = 2
        for (id in 3..22) {
            nodes += ModularNode(id, ModularKind.FILTER)
            cables += ModularConnection(cables.size + 1, previous, 0, id, 0)
            previous = id
        }
        nodes += ModularNode(23, ModularKind.OUT)
        cables += ModularConnection(cables.size + 1, previous, 0, 23, 0)
        return ModularPatch("Large", ModularGraph(nodes, cables))
    }
    private fun song(lanes: Int = 1): Song {
        val patches = MutableList<StudioPatch>(32) { MonoPatch() }
        patches[0] = ModularPatch()
        patches[1] = heavy()
        val tracks = List(lanes) { StudioTrack(MidiTrack(it + 1, it)) }
        return Song(patterns = listOf(Pattern(tracks = List(lanes) {
            List(Songs.ROWS) { row -> if (row == 0) Cell(57, 1) else Cell.BLANK }
        }, length = 4)), orders = listOf(0), sound = StudioSound(tracks, patches, nextId = lanes + 1))
    }

    @Test fun unplayedGraphReservesMemoryButNotLaneWorkUntilAuditioned() {
        val source = song()
        val smallBank = source.copy(sound = source.studio.withPatch(2, MonoPatch()))
        val full = StudioModularAdmission.demand(source, generous)!!
        val small = StudioModularAdmission.demand(smallBank, generous)!!
        assertEquals(small.workUnits, full.workUnits)
        assertTrue(full.reservationBytes > small.reservationBytes)
        val withAudition = StudioModularAdmission.demand(source, generous, 2)!!
        val limited = generous.copy(workUnits = full.workUnits)
        assertNull(StudioModularAdmission.problem(source, limited))
        assertNotNull(StudioModularAdmission.problem(source, limited, 2))
        assertTrue(withAudition.workUnits > full.workUnits)
    }

    @Test fun limitsApplyToAllTracksAndBypassedEffectsNotJustOnePatch() {
        val one = song()
        val units = StudioModularAdmission.demand(one, generous)!!.workUnits
        val limited = generous.copy(workUnits = units)
        assertNull(StudioModularAdmission.problem(one, limited))
        assertNotNull(StudioModularAdmission.problem(song(2), limited))
        val insert = StudioInsert(1, StudioKind.REVERB, bypass = true)
        val withEffect = one.copy(sound = one.studio.copy(
            tracks = listOf(one.studio.tracks[0].copy(inserts = listOf(insert))), nextInstance = 2))
        assertNotNull(StudioModularAdmission.problem(withEffect, limited))
    }

    @Test fun previewPrefixAndRememberedInstrumentCount() {
        val source = song()
        val initial = source.patterns.single()
        val before = initial.copy(tracks = listOf(initial.tracks[0].mapIndexed { row, cell ->
            if (row == 0) Cell(57, 2) else cell
        }))
        val after = initial.copy(tracks = listOf(initial.tracks[0].mapIndexed { row, cell ->
            if (row == 0) Cell(57, 0) else cell
        }))
        val preview = source.copy(patterns = listOf(before, after), orders = listOf(1),
            sound = source.studio.copy(previewPrefix = listOf(0)))
        val plain = preview.copy(sound = source.studio)
        val limit = generous.copy(workUnits = StudioModularAdmission.demand(plain, generous)!!.workUnits)
        assertNull(StudioModularAdmission.problem(plain, limit))
        assertNotNull(StudioModularAdmission.problem(preview, limit))
    }

    @Test fun refusedProjectRoundTripsWithoutPruningOrChangingThePatch() {
        val source = song(2)
        val low = generous.copy(workUnits = 0)
        assertNotNull(StudioModularAdmission.problem(source, low))
        val reopened = ProjectCodec.decodeBytes(ProjectCodec.encodeBytes(source))
        assertEquals(source, reopened)
        assertNotNull(StudioModularAdmission.problem(reopened, low))
        assertNull(StudioModularAdmission.problem(reopened, generous))
        assertEquals(source.studio.patches, reopened.studio.patches)
    }

    @Test fun usbLaneDoesNotConsumeLocalVoiceWorkAndMemoryRefusalIsSeparate() {
        val local = song(2)
        val usb = local.copy(sound = local.studio.copy(tracks = local.studio.tracks.mapIndexed { index, track ->
            if (index == 1) track.copy(target = StudioTarget.USB_MIDI) else track
        }))
        assertTrue(StudioModularAdmission.demand(usb, generous)!!.workUnits <
            StudioModularAdmission.demand(local, generous)!!.workUnits)
        val memory = generous.copy(reservationBytes = 1)
        assertTrue(StudioModularAdmission.problem(local, memory)!!.contains("memory reserve"))
        val legacy = local.copy(sound = local.studio.copy(patches = List(32) { MonoPatch() }))
        assertNull(StudioModularAdmission.problem(legacy, memory))
    }
    @Test fun deviceCapacityChangesAdmissionButNeverThePortableProject() {
        val base = song(8)
        val project = base.copy(sound = base.studio.withPatch(1, heavy()))
        val encoded = ProjectCodec.encodeBytes(project)
        assertNull(StudioModularAdmission.problem(project, StudioModularProfiles.android("AYN", "AYN Thor", "QCS8550")))
        assertNull(StudioModularAdmission.problem(project, StudioModularProfiles.android("Retroid", "Retroid Pocket Nova", "QCS8550")))
        assertNull(StudioModularAdmission.problem(project, StudioModularProfiles.linux(false, "Galileo")))
        assertNotNull(StudioModularAdmission.problem(project, StudioModularProfiles.linux(true, null)))
        assertNotNull(StudioModularAdmission.problem(project, StudioModularProfiles.android("AYN", "AYN Thor", null)))
        assertNotNull(StudioModularAdmission.problem(project, StudioModularProfiles.linux(false, null)))
        assertTrue(encoded.contentEquals(ProjectCodec.encodeBytes(project)))
        assertEquals(project, ProjectCodec.decodeBytes(encoded))
    }
}
