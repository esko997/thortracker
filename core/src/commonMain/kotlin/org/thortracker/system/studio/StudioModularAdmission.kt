package org.thortracker.system.studio

import org.thortracker.model.Song

/** Host policy, never serialized into an instrument or used by the offline renderer. */
data class StudioModularLimits(
    val name: String,
    val workUnits: Long,
    val reservationBytes: Long,
    val rackCopies: Int,
    val bankCopies: Int,
) {
    init {
        require(name.isNotBlank() && workUnits >= 0 && reservationBytes > 0)
        require(rackCopies in 1..16 && bankCopies in 1..16)
    }
}

data class StudioModularDemand(val workUnits: Long, val reservationBytes: Long, val modularSlots: Int)

/**
 * Whole-project admission reserve, not measured CPU usage or process RSS. Graph work agrees with
 * the standalone DSP catalogue. Legacy voice/effect weights reserve their work alongside it;
 * host limits must be qualified by combined-workload measurements, not this arithmetic alone.
 */
object StudioModularAdmission {
    fun demand(song: Song, limits: StudioModularLimits, auditionSlot: Int? = null): StudioModularDemand? {
        val studio = song.sound as? StudioSound ?: return null
        val graphs = studio.patches.filterIsInstance<ModularPatch>()
        if (graphs.isEmpty()) return null
        val costs = LongArray(StudioSound.SLOTS) { slot -> voiceWork(studio.patches[slot]) }
        val local = studio.tracks.indices.filter { studio.tracks[it].target == StudioTarget.MONO }
        val patterns = studio.previewPrefix + song.orders.filterIndexed { index, _ -> index !in song.parkedOrders }
        var voices = 0L
        for (lane in local) {
            var slot = 1
            var peak = 0L
            for (patternId in patterns) {
                val pattern = song.patterns.getOrNull(patternId) ?: continue // structural validation reports it
                val rows = pattern.tracks.getOrNull(lane) ?: continue
                for (row in 0 until minOf(pattern.length, rows.size)) {
                    val cell = rows[row]
                    if (cell.instrument in 1..StudioSound.SLOTS) slot = cell.instrument
                    // Instrument-only changes can alter an already held sound.
                    if (cell.note >= 0 || cell.instrument > 0) peak = maxOf(peak, costs[slot - 1])
                }
            }
            voices += peak
        }
        if (auditionSlot != null) {
            require(auditionSlot in 1..StudioSound.SLOTS)
            voices += costs[auditionSlot - 1]
        }
        val inserts = local.flatMap { studio.tracks[it].inserts } + studio.buses.flatMap { it.inserts }
        // A bypassed insert still processes its history and can be re-enabled during playback.
        val effectWork = inserts.sumOf { effectWork(it.kind) } * if (auditionSlot == null) 1L else 2L
        val planBytes = graphs.sumOf { it.graph.resources.planBytes } + ModularResources.BANK_BYTES
        val graphVoices = local.size.toLong() * ModularResources.VOICE_BYTES
        val effectsBytes = inserts.sumOf { effectBytes(it.kind) }
        val waves = studio.patches.count { it is WavetablePatch }.toLong() * 32768L
        val pcm = studio.assets.decodedBytes.toLong()
        // Includes worst distinct prepared/retiring banks, not just the one currently audible.
        // Fixed reserve covers the editor/history/serialization and native score/rack bookkeeping.
        val reservation = 96L * MIB + (planBytes + waves + pcm) * limits.bankCopies +
            (graphVoices + effectsBytes + MIB) * limits.rackCopies + pcm * 2
        return StudioModularDemand(voices + effectWork, reservation, graphs.size)
    }

    fun problem(song: Song, limits: StudioModularLimits, auditionSlot: Int? = null): String? {
        val demand = demand(song, limits, auditionSlot) ?: return null
        if (demand.reservationBytes > limits.reservationBytes) {
            return "Modular playback exceeds ${limits.name}'s memory reserve " +
                "(${mib(demand.reservationBytes)} / ${mib(limits.reservationBytes)} MiB). " +
                "Reduce sample/effect/graph demand or use a larger device. Your project remains editable."
        }
        if (demand.workUnits > limits.workUnits) {
            return "Modular playback exceeds ${limits.name}'s processing reserve " +
                "(${demand.workUnits} / ${limits.workUnits} work units). " +
                "Simplify active patches/effects or reduce tracks. Editing and offline WAV remain available."
        }
        return null
    }

    private fun voiceWork(patch: StudioPatch): Long = when (patch) {
        is ModularPatch -> patch.graph.resources.workUnits.toLong()
        is MonoPatch -> 40L
        is SamplerPatch -> 24L
        is FmPatch -> 64L
        is PercussionPatch -> 40L
        is WavetablePatch -> 48L
        is PluckPatch -> 48L
    }

    private fun effectWork(kind: StudioKind): Long = when (kind) {
        StudioKind.DELAY -> 16L
        StudioKind.DRIVE -> 12L
        StudioKind.CRUSHER -> 8L
        StudioKind.REVERB -> 40L
        StudioKind.CHORUS -> 20L
        else -> error("Not an insert: $kind")
    }

    /** 48 kHz render buffers, plus conservative per-instance metadata/alignment. */
    private fun effectBytes(kind: StudioKind): Long = 4096L + when (kind) {
        StudioKind.DELAY -> 8L * (96000 + 2)
        StudioKind.REVERB -> 8L * (5760 + 2) + 4L * (2130 + 2898 + 3879 + 4512 + 8)
        StudioKind.CHORUS -> 8L * (1056 + 2)
        StudioKind.DRIVE, StudioKind.CRUSHER -> 0L
        else -> error("Not an insert: $kind")
    }

    private fun mib(bytes: Long) = (bytes + MIB - 1) / MIB
    private const val MIB = 1024L * 1024L
}
