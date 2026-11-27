package org.thortracker.system.studio

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.midi.MidiTrack

/** Original, small signal paths: useful instruments first, readable patching examples second. */
internal fun studioModularPresets(): List<StudioPatch> = modularPresets

private val modularPresets by lazy {
    listOf(patchworkBass(), krellGarden(), wireCabinet(), feedbackLantern())
}

private fun node(id: Int, kind: ModularKind, vararg values: Pair<Int, Int>) =
    ModularNode(id, kind, values.toMap())
private fun target(node: Int, parameter: Int, from: Int, to: Int) =
    ModularTarget(node, parameter, from, to)
private fun macro(name: String, vararg targets: ModularTarget) = ModularMacro(name, targets.toList())

/** Cable IDs remain stable within each authored graph. No saved project relies on helper ordering. */
private class Cables {
    val values = mutableListOf<ModularConnection>()
    fun add(source: Int, output: Int, destination: Int, input: Int, scale: Int = 1000, offset: Int = 0) {
        values += ModularConnection(values.size + 1, source, output, destination, input, scale, offset)
    }
}

private fun patchworkBass(): ModularPatch {
    val cables = Cables().apply {
        add(1, 0, 2, 0); add(1, 0, 3, 0)
        add(2, 0, 4, 0); add(3, 0, 4, 1)
        add(4, 0, 5, 0); add(1, 2, 6, 0)
        add(6, 0, 5, 1, 4000)
        add(5, 0, 7, 0); add(6, 0, 7, 1); add(7, 0, 8, 0)
    }
    return ModularPatch("Patchwork Bass", ModularGraph(
        nodes = listOf(
            node(1, ModularKind.NOTE), node(2, ModularKind.OSC, 0 to 1),
            node(3, ModularKind.OSC, 0 to 2, 1 to -12, 2 to 500),
            node(4, ModularKind.MIX), node(5, ModularKind.FILTER),
            node(6, ModularKind.FUNCTION), node(7, ModularKind.VCA), node(8, ModularKind.OUT)
        ), connections = cables.values, macros = listOf(
            macro("Cutoff", target(5, 0, 160, 7000)),
            macro("Resonance", target(5, 1, 0, 850)),
            macro("Length", target(6, 1, 40, 1400)),
            macro("Attack", target(6, 0, 2, 400)),
            macro("Sub blend", target(4, 0, 1000, 0), target(4, 1, 0, 1000)),
            macro("Pulse width", target(3, 2, 100, 900)),
            macro("Transpose", target(2, 1, 0, 12), target(3, 1, -12, 0)),
            macro("Level", target(8, 0, 0, 1000))
        )), mapOf(0 to 12, 1 to 24, 2 to 20, 3 to 1, 4 to 30, 5 to 64, 6 to 0, 7 to 80))
}

private fun krellGarden(): ModularPatch {
    val cables = Cables().apply {
        add(1, 0, 4, 0); add(1, 2, 3, 0)
        add(2, 1, 3, 0); add(3, 1, 2, 2, 1500)
        add(3, 0, 4, 1, 12000)
        add(4, 0, 8, 0); add(8, 0, 5, 0)
        add(2, 0, 5, 1, 3000)
        add(5, 0, 6, 0); add(2, 0, 6, 1); add(6, 0, 7, 0)
    }
    return ModularPatch("Krell Garden", ModularGraph(
        nodes = listOf(
            node(1, ModularKind.NOTE), node(2, ModularKind.FUNCTION, 2 to 1),
            node(3, ModularKind.RANDOM), node(4, ModularKind.OSC),
            node(5, ModularKind.FILTER), node(6, ModularKind.VCA),
            node(7, ModularKind.OUT), node(8, ModularKind.FOLD)
        ), connections = cables.values, seed = 0x4B52454C, macros = listOf(
            macro("Rise", target(2, 0, 100, 1800)),
            macro("Fall", target(2, 1, 200, 3000)),
            macro("Drift", target(3, 0, 1, 3000)),
            macro("Fold", target(8, 0, 1000, 6000)),
            macro("Cutoff", target(5, 0, 160, 6000)),
            macro("Resonance", target(5, 1, 0, 750)),
            macro("Transpose", target(4, 1, 0, 12)),
            macro("Level", target(7, 0, 0, 800))
        )), mapOf(0 to 45, 1 to 50, 2 to 35, 3 to 18, 4 to 18, 5 to 42, 6 to 0, 7 to 72))
}

private fun wireCabinet(): ModularPatch {
    val cables = Cables().apply {
        add(1, 2, 3, 0); add(2, 0, 4, 0); add(3, 0, 4, 1)
        add(4, 0, 5, 0); add(1, 0, 5, 1)
        add(5, 0, 6, 0); add(6, 0, 7, 0)
    }
    return ModularPatch("Wire Cabinet", ModularGraph(
        nodes = listOf(
            node(1, ModularKind.NOTE), node(2, ModularKind.NOISE),
            node(3, ModularKind.FUNCTION), node(4, ModularKind.VCA),
            node(5, ModularKind.RESONATOR), node(6, ModularKind.FILTER), node(7, ModularKind.OUT)
        ), connections = cables.values, seed = 0x57495245, macros = listOf(
            macro("Ring", target(5, 1, 100, 2500)),
            macro("Strike", target(3, 1, 1, 60)),
            macro("Brightness", target(6, 0, 500, 12000)),
            macro("Attack", target(3, 0, 1, 50)),
            macro("Excitation", target(4, 0, 0, 2000)),
            macro("Transpose", target(5, 0, 0, 24)),
            macro("Resonance", target(6, 1, 0, 700)),
            macro("Level", target(7, 0, 0, 1000))
        )), mapOf(0 to 50, 1 to 15, 2 to 65, 3 to 0, 4 to 65, 5 to 0, 6 to 10, 7 to 90))
}

private fun feedbackLantern(): ModularPatch {
    val cables = Cables().apply {
        add(1, 0, 2, 0); add(2, 0, 3, 0); add(6, 0, 3, 1)
        add(3, 0, 4, 0); add(4, 0, 5, 0); add(5, 0, 6, 0)
        add(1, 2, 7, 0); add(5, 0, 8, 0); add(7, 0, 8, 1); add(8, 0, 9, 0)
    }
    return ModularPatch("Feedback Lantern", ModularGraph(
        nodes = listOf(
            node(1, ModularKind.NOTE), node(2, ModularKind.OSC),
            node(3, ModularKind.MIX, 0 to 250), node(4, ModularKind.FOLD),
            node(5, ModularKind.FILTER), node(6, ModularKind.DELAY),
            node(7, ModularKind.FUNCTION), node(8, ModularKind.VCA), node(9, ModularKind.OUT)
        ), connections = cables.values, macros = listOf(
            macro("Feedback", target(3, 1, 0, 950)),
            macro("Fold", target(4, 0, 1000, 8000)),
            macro("Cutoff", target(5, 0, 100, 14000)),
            macro("Resonance", target(5, 1, 0, 650)),
            macro("Attack", target(7, 0, 1, 150)),
            macro("Length", target(7, 1, 60, 2000)),
            macro("Transpose", target(2, 1, 0, 12)),
            macro("Level", target(9, 0, 0, 700))
        )), mapOf(0 to 75, 1 to 25, 2 to 35, 3 to 20, 4 to 0, 5 to 35, 6 to 0, 7 to 80))
}

/** A portable authored example, also used by the export fixtures and downloadable project. */
fun studioModularDemo(): Song {
    val patches = MutableList<StudioPatch>(StudioSound.SLOTS) { MonoPatch() }
    modularPresets.forEachIndexed { index, patch -> patches[index] = patch }
    val tracks = listOf(
        StudioTrack(MidiTrack(1, 0, gate = 100), gain = 70, pan = -12,
            bindings = mapOf(14 to StudioBinding(0, StudioKind.MODULAR, 0))),
        StudioTrack(MidiTrack(2, 1, gate = 100), gain = 32, pan = 24, sends = listOf(20, 0),
            bindings = mapOf(15 to StudioBinding(0, StudioKind.MODULAR, 3))),
        StudioTrack(MidiTrack(3, 2, gate = 100), gain = 60, pan = -25, sends = listOf(15, 0)),
        StudioTrack(MidiTrack(4, 3, gate = 100), gain = 45, pan = 15,
            bindings = mapOf(14 to StudioBinding(0, StudioKind.MODULAR, 0)))
    )
    val patterns = List(2) { variation ->
        val rows = List(4) { MutableList(Songs.ROWS) { Cell.BLANK } }
        val bass = if (variation == 0) listOf(24, 24, 31, 27, 24, 34, 31, 27)
            else listOf(20, 27, 24, 27, 22, 29, 26, 31)
        for (i in bass.indices) rows[0][i * 4] = Cell(bass[i], 1, 14, 0x18 + i * 8)
        for (row in 0 until 32) {
            var evolving = Cell(if (variation == 0) 48 else 43, 2, 12, if (row == 31) 0 else 1)
            if (row % 8 == 0) evolving = evolving.withEffect(1, 15, 0x18 + row * 2)
            rows[1][row] = evolving
        }
        for (i in 0 until 4) {
            rows[2][i * 8 + 3] = Cell(60 + listOf(0, 7, 3, 10)[i], 3)
            rows[3][i * 8 + 4] = Cell(48 + listOf(0, 7, 3, 5)[i], 4, 14, 0x30 + i * 0x18)
        }
        Pattern(if (variation == 0) "Garden" else "Branches", rows, length = 32)
    }
    return Song(title = "Patch Garden", artist = "Thor Tracker", ticksPerRow = 6,
        comment = "Four original modular patches. Track 1 E: cutoff; Track 2 C: tie, F: fold; " +
            "Track 4 E: feedback. Open an instrument's Build face to explore its connections.",
        patterns = patterns, orders = listOf(0, 1),
        sound = StudioSound(tracks = tracks, patches = patches, nextId = 5, nextInstance = 2,
            buses = listOf(StudioBus(listOf(StudioInsert(1, StudioKind.REVERB,
                values = mapOf(1 to 1200, 4 to 100)))))))
}
