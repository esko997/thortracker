package org.thortracker.system.studio

/** Original starting points. Loading one copies values into the selected patch or pedal. */
internal fun studioInstrumentPresets(kind: StudioKind): List<StudioPatch> = when (kind) {
    StudioKind.MONO -> listOf(
        MonoPatch(name = "Copper Step", waveform = 1, cutoff = 37, resonance = 55,
            envelope = 63, decay = 33, accent = 50, glide = 18, level = 65),
        MonoPatch(name = "Round Current", waveform = 1, cutoff = 22, resonance = 12,
            envelope = 18, decay = 48, glide = 8, level = 70),
        MonoPatch(name = "Wire Spark", cutoff = 80, resonance = 35,
            envelope = 25, decay = 12, glide = 0, tune = 12, level = 55)
    )
    StudioKind.FM -> listOf(
        FmPatch("Felt Keys", mapOf(0 to 100, 1 to 100, 2 to 115, 3 to 8,
            4 to 6, 5 to 850, 6 to 18, 7 to 280, 8 to 65)),
        FmPatch("Glass Chime", mapOf(0 to 100, 1 to 350, 2 to 420, 3 to 5,
            4 to 2, 5 to 1500, 6 to 0, 7 to 700, 8 to 60)),
        FmPatch("Rubber Reed", mapOf(0 to 100, 1 to 200, 2 to 160, 3 to 12,
            4 to 12, 5 to 350, 6 to 60, 7 to 120, 8 to 60)),
        FmPatch("Copper Bell", mapOf(0 to 100, 1 to 141, 2 to 530, 3 to 15,
            4 to 2, 5 to 2300, 6 to 0, 7 to 900, 8 to 55))
    )
    StudioKind.PERCUSSION -> listOf(
        PercussionPatch("Low Circuit", mapOf(0 to 0, 1 to 80, 2 to 30, 3 to 25, 4 to 420, 5 to 75)),
        PercussionPatch("Paper Snare", mapOf(0 to 1, 1 to 50, 2 to 80, 3 to 65, 4 to 160, 5 to 65)),
        PercussionPatch("Closed Foil", mapOf(0 to 2, 1 to 15, 2 to 75, 3 to 85, 4 to 55, 5 to 50)),
        PercussionPatch("Open Foil", mapOf(0 to 2, 1 to 20, 2 to 65, 3 to 70, 4 to 250, 5 to 40))
    )
    // Sampler patches retain their own imported asset, region and slices rather than borrowing an unrelated file.
    else -> emptyList()
}

internal fun studioPedalPresets(kind: StudioKind): List<Pair<String, Map<Int, Int>>> = when (kind) {
    StudioKind.DELAY -> listOf(
        "Skipping Stones" to mapOf(0 to 2, 2 to 38, 3 to 4200, 4 to 28),
        "Tape Slap" to mapOf(0 to 0, 1 to 95, 2 to 14, 3 to 2800, 4 to 24),
        "Long Hallway" to mapOf(0 to 4, 2 to 58, 3 to 2100, 4 to 35)
    )
    StudioKind.DRIVE -> listOf(
        "Warm Edge" to mapOf(0 to 6, 1 to 6500, 2 to -4),
        "Rust Box" to mapOf(0 to 22, 1 to 2300, 2 to -12)
    )
    StudioKind.CRUSHER -> listOf(
        "Pocket Radio" to mapOf(0 to 8, 1 to 11025, 2 to 70, 3 to -3),
        "Broken Glass" to mapOf(0 to 4, 1 to 4200, 2 to 55, 3 to -6)
    )
    StudioKind.REVERB -> listOf(
        "Small Room" to mapOf(0 to 25, 1 to 650, 2 to 6500, 3 to 8, 4 to 20),
        "Night Platform" to mapOf(0 to 75, 1 to 3600, 2 to 3200, 3 to 32, 4 to 32),
        "Shared Room" to mapOf(0 to 55, 1 to 1800, 2 to 4800, 3 to 18, 4 to 100)
    )
    StudioKind.CHORUS -> listOf(
        "Slow Water" to mapOf(0 to 28, 1 to 4500, 2 to 35),
        "Soft Double" to mapOf(0 to 90, 1 to 1800, 2 to 24)
    )
    else -> emptyList()
}
