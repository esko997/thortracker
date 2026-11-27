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
    StudioKind.WAVETABLE -> wavetablePresets
    StudioKind.PLUCK -> pluckPresets
    // Sampler patches retain their own imported asset, region and slices rather than borrowing an unrelated file.
    else -> emptyList()
}

private val wavetablePresets by lazy {
    // Original phase-aligned cycles, stored directly rather than regenerated when opening the library.
    val waves = StudioWaves.of(listOf(
        listOf(
            0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126,
            127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12,
            0, -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
            -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12
        ),
        listOf(
            0, 21, 42, 62, 79, 94, 107, 116, 123, 126, 127, 126, 122, 118, 112, 105,
            99, 92, 86, 80, 75, 70, 65, 60, 55, 50, 44, 38, 32, 24, 16, 8,
            0, -8, -16, -24, -32, -38, -44, -50, -55, -60, -65, -70, -75, -80, -86, -92,
            -99, -105, -112, -118, -122, -126, -127, -126, -123, -116, -107, -94, -79, -62, -42, -21
        ),
        listOf(
            0, 40, 75, 102, 120, 127, 125, 116, 103, 90, 79, 72, 69, 69, 71, 73,
            73, 73, 71, 69, 69, 72, 79, 90, 103, 116, 125, 127, 120, 102, 75, 40,
            0, -40, -75, -102, -120, -127, -125, -116, -103, -90, -79, -72, -69, -69, -71, -73,
            -73, -73, -71, -69, -69, -72, -79, -90, -103, -116, -125, -127, -120, -102, -75, -40
        ),
        listOf(
            0, 21, 38, 49, 54, 57, 61, 70, 83, 100, 116, 125, 127, 120, 107, 93,
            81, 72, 67, 61, 52, 38, 18, -4, -22, -34, -37, -31, -21, -10, -3, 0,
            0, 0, 3, 10, 21, 31, 37, 34, 22, 4, -18, -38, -52, -61, -67, -72,
            -81, -93, -107, -120, -127, -125, -116, -100, -83, -70, -61, -57, -54, -49, -38, -21
        )
    ))
    listOf(
        WavetablePatch("Round Lantern", mapOf(0 to 0, 1 to 0, 2 to 0, 3 to 10, 4 to 180, 5 to 68), waves),
        WavetablePatch("Reed Drift", mapOf(0 to 150, 1 to 35, 2 to 18, 3 to 35, 4 to 450, 5 to 60), waves),
        WavetablePatch("Folded Ribbon", mapOf(0 to 240, 1 to 120, 2 to 20, 3 to 8, 4 to 250, 5 to 55), waves),
        WavetablePatch("Edge Runner", mapOf(0 to 220, 1 to 0, 2 to 0, 3 to 2, 4 to 65, 5 to 55))
    )
}

private val pluckPresets by lazy {
    listOf(
        PluckPatch("Felt Tine", mapOf(0 to 0, 1 to 15, 2 to 80, 3 to 650, 4 to 100, 5 to 75)),
        PluckPatch("Copper Wire", mapOf(0 to 35, 1 to 75, 2 to 35, 3 to 1700, 4 to 400, 5 to 65)),
        PluckPatch("Glass Pin", mapOf(0 to 90, 1 to 90, 2 to 20, 3 to 2400, 4 to 700, 5 to 55)),
        PluckPatch("Dry Reed", mapOf(0 to 0, 1 to 65, 2 to 90, 3 to 180, 4 to 30, 5 to 75))
    )
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
