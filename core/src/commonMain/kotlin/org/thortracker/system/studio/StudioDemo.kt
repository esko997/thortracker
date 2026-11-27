package org.thortracker.system.studio

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.midi.MidiTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Original synthesized source audio; no external recording or sample library is required. */
private fun circuitBreak(): StudioPcm {
    val rate = 24000
    val sliceFrames = rate / 4
    var random = 0x51A7C0DE
    var phase = 0.0
    var previousNoise = 0.0
    return StudioPcm.decode(1, rate, sliceFrames * 8) { frame ->
        val local = frame % sliceFrames
        val hit = frame / sliceFrames
        val time = local.toDouble() / rate
        if (local == 0) phase = 0.0
        random = random xor (random shl 13)
        random = random xor (random ushr 17)
        random = random xor (random shl 5)
        val noise = (random ushr 8) / 8388608.0 - 1.0
        val signal = when (hit) {
            0, 4 -> {
                phase += 2.0 * PI * (52.0 + 125.0 * exp(-time * 65.0)) / rate
                0.60 * sin(phase) * exp(-time * 24.0) + 0.06 * noise * exp(-time * 140.0)
            }
            2, 6 -> (0.35 * noise + 0.15 * sin(2.0 * PI * 180.0 * time)) * exp(-time * 32.0)
            1, 5 -> (0.30 * sin(2.0 * PI * 820.0 * time) + 0.20 * noise) * exp(-time * 100.0)
            else -> 0.25 * (noise - previousNoise) * exp(-time * 90.0)
        }
        previousNoise = noise
        (signal * min(1.0, time * 1000.0)).toFloat()
    }
}

internal fun studioModuleDemo(): Song {
    val source = circuitBreak()
    val added = StudioAssets.EMPTY.add(source)
    val sampler = SamplerPatch("Circuit Break", assetId = added.id, end = source.frames,
        slices = List(8) { StudioSlice(it, it * source.frames / 8, (it + 1) * source.frames / 8) },
        values = mapOf(0 to 48, 4 to 1, 5 to 30, 7 to 75), defaultSlice = 0)
    val patches = MutableList<StudioPatch>(StudioSound.SLOTS) { MonoPatch() }
    patches[0] = sampler
    patches[1] = studioInstrumentPresets(StudioKind.MONO)[0]
    patches[2] = studioInstrumentPresets(StudioKind.FM)[0]
    patches[3] = studioInstrumentPresets(StudioKind.PERCUSSION)[2]
    val tracks = listOf(
        StudioTrack(MidiTrack(1, 0, gate = 100), gain = 85, sends = listOf(10, 0),
            inserts = listOf(StudioInsert(1, StudioKind.CRUSHER, values = mapOf(0 to 10, 1 to 12000, 2 to 15))),
            bindings = mapOf(14 to StudioBinding(1, StudioKind.CRUSHER, 2),
                15 to StudioBinding(0, StudioKind.SAMPLER, 6))),
        StudioTrack(MidiTrack(2, 1, gate = 80), gain = 65, pan = -10,
            inserts = listOf(
                StudioInsert(2, StudioKind.DRIVE, values = mapOf(0 to 9, 1 to 4200, 2 to -6)),
                StudioInsert(3, StudioKind.DELAY, values = mapOf(0 to 2, 2 to 30, 3 to 3000, 4 to 18))),
            bindings = mapOf(14 to StudioBinding(0, StudioKind.MONO, 1))),
        StudioTrack(MidiTrack(3, 2, gate = 100), gain = 65, pan = 18, sends = listOf(28, 0),
            inserts = listOf(StudioInsert(4, StudioKind.CHORUS, values = mapOf(0 to 28, 1 to 3000, 2 to 25))),
            bindings = mapOf(14 to StudioBinding(0, StudioKind.FM, 2))),
        StudioTrack(MidiTrack(4, 3, gate = 100), gain = 45, pan = 12, sends = listOf(8, 0))
    )
    val sound = StudioSound(tracks = tracks, patches = patches, nextId = 5, nextInstance = 6,
        assets = added.assets, buses = listOf(StudioBus(listOf(StudioInsert(5, StudioKind.REVERB,
            values = mapOf(0 to 45, 1 to 1400, 2 to 4200, 3 to 12, 4 to 100))))))
    val patterns = List(2) { variation ->
        val rows = List(4) { MutableList(Songs.ROWS) { Cell.BLANK } }
        val sliceOrder = if (variation == 0) listOf(0, 1, 2, 3, 4, 5, 6, 7) else listOf(0, 4, 1, 5, 2, 6, 3, 7)
        for (step in 0 until 16) {
            val row = step * 2
            var cell = Cell(48, 1, 13, sliceOrder[step % 8])
            if (row % 16 == 0) cell = cell.withEffect(1, 14, if (variation == 0) 0x26 else 0x60)
                .withEffect(2, 15, if (row == 0) 0xFF else 0xD0)
            rows[0][row] = cell
        }
        val bass = if (variation == 0) listOf(24, 24, 31, 27, 24, 34, 31, 27) else listOf(20, 27, 24, 27, 22, 29, 26, 31)
        for (step in bass.indices) {
            rows[1][step * 4] = Cell(bass[step], 2, 14, 0x50 + step * 10)
            if (step % 2 == 1) rows[1][step * 4 + 2] = Cell(bass[step] + 12, 2)
        }
        val melody = if (variation == 0) listOf(60, 67, 63, 62) else listOf(56, 63, 58, 65)
        for (step in melody.indices) rows[2][step * 8] = Cell(melody[step], 3, 14, 0x18 + step * 4)
        for (row in 1 until 32 step 2) rows[3][row] = Cell(if (row % 4 == 1) 60 else 63, 4)
        Pattern(if (variation == 0) "Circuit" else "Rewire", rows, length = 32)
    }
    return Song(title = "Pocket Circuit", artist = "Thor Tracker", ticksPerRow = 6,
        comment = "Original synthesized break. Track 1: D slices, E crusher wet, F sampler cutoff. " +
            "Track 2: E Mono cutoff. Track 3: E FM brightness. Shared room on Return A.",
        patterns = patterns, orders = listOf(0, 1), sound = sound)
}
