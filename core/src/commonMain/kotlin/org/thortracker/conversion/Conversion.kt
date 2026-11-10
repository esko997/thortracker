package org.thortracker.conversion

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import org.thortracker.system.SoundSystem

enum class ConversionImpact { CHANGE, LOSS, BLOCKER }
data class ConversionIssue(val impact: ConversionImpact, val location: String, val detail: String)
data class ConversionLane(val source: Int, val target: Int)
data class ConversionResult(val song: Song?, val lanes: List<ConversionLane>, val issues: List<ConversionIssue>) {
    val canApply: Boolean get() = song != null && issues.none { it.impact == ConversionImpact.BLOCKER }
}

/** Pure conversion: the original document and its immutable sound resources are never edited. */
object SongConversion {
    fun preview(source: Song, target: SoundSystem): ConversionResult {
        if (source.system.id == target.id) return ConversionResult(source,
            source.system.channels.indices.map { ConversionLane(it, it) }, emptyList())
        val issues = mutableListOf<ConversionIssue>()
        fun block(location: String, detail: String) { issues += ConversionIssue(ConversionImpact.BLOCKER, location, detail) }
        source.system.structuralErrors(source).forEach { block("Source", it) }
        if (issues.isNotEmpty()) return ConversionResult(null, emptyList(), issues)
        val lanes = ConversionVoices.lanes(source, target)
        if (source.orders.size > target.maxOrders) block("Arrangement",
            "${source.orders.size} orders exceed ${target.name}'s ${target.maxOrders}-order capacity; no orders were removed.")
        for (channel in source.system.channels.indices.filter { ch -> lanes.none { it.source == ch } }) {
            val name = source.system.channels[channel].name
            issues += ConversionIssue(ConversionImpact.LOSS, "Channel ${channel + 1}: $name", "No compatible destination lane; this entire lane is dropped.")
            source.patterns.forEachIndexed { p, pattern -> pattern.tracks[channel].forEachIndexed { row, cell ->
                if (!cell.isBlank) {
                    val parts = mutableListOf<String>()
                    if (cell.note != Cell.EMPTY) parts += "note ${source.system.noteName(cell.note)}"
                    if (cell.instrument != 0) parts += "instrument ${cell.instrument}"
                    for (column in 0..cell.lastEffectColumn) {
                        val code = cell.effectCode(column)
                        val param = cell.effectParam(column)
                        if (code != 0 || param != 0) parts += "effect ${column + 1}: ${EffectSpec.charOf(code)}${param.toString(16).uppercase().padStart(2, '0')} (${source.system.describeEffect(source, channel, code, param)})"
                    }
                    issues += ConversionIssue(ConversionImpact.LOSS, location(p, channel, row),
                        "Dropped lane content, including any song-wide control: ${parts.joinToString("; ")}.")
                }
            } }
        }
        val voices = ConversionVoices.prepare(source, target, lanes, issues)
        if (issues.any { it.impact == ConversionImpact.BLOCKER }) return ConversionResult(null, lanes, issues)
        // Keep even unused patterns and hidden rows; only the channel dimension changes.
        var destination = source.copy(sound = voices.song.sound, patterns = source.patterns.map { pattern ->
            pattern.copy(tracks = List(target.channels.size) { List(Songs.ROWS) { Cell.BLANK } })
        })
        destination = destination.copy(ticksPerRow = ConversionEffects.speed(source, destination, issues))
        val patterns = destination.patterns.toMutableList()
        val firstPattern = source.orders.withIndex().firstOrNull { it.index !in source.parkedOrders }?.value
        val preservedVoices = ConversionVoices.preserves(source.sound, destination.sound)
        for (lane in lanes) {
            val slots = voices.slotsBySourceChannel[lane.source]
            val sourceDefault = source.system.defaultInstrument(source, source.system.channels[lane.source].bank)
            val possible = linkedSetOf<Int>()
            source.patterns.forEach { pattern -> pattern.tracks[lane.source].forEach { cell ->
                if (cell.instrument > 0) possible += cell.instrument
            } }
            if (possible.isEmpty()) possible += sourceDefault
            source.patterns.forEachIndexed { p, pattern ->
                var inherited = if (possible.size == 1) possible.first() else 0
                var selectedInstrument = false
                val track = pattern.tracks[lane.source].mapIndexed { row, original ->
                    if (original.isBlank) return@mapIndexed Cell.BLANK
                    if (original.instrument > 0) { inherited = original.instrument; selectedInstrument = true }
                    val where = location(p, lane.source, row)
                    val slot = if (original.instrument == 0) 0 else slots.getOrElse(original.instrument) { 0 }
                    if (original.instrument > 0 && slot == 0) block(where, "Instrument ${original.instrument} has no destination slot.")
                    if (!preservedVoices && p == firstPattern && row < pattern.length &&
                        original.note >= 0 && !selectedInstrument) {
                        block(where, "Playback starts with an inherited instrument before any source instrument is selected. Editor defaults do not establish the driver's initial sound; enter the source instrument explicitly before converting.")
                    }
                    val effective = if (slot > 0) slot else slots.getOrElse(inherited) { 0 }
                    val note = if (original.note >= 0 && inherited == 0) {
                        // Shared patterns can be entered with different voices. Only one stored key is possible.
                        val candidateIssues = mutableListOf<ConversionIssue>()
                        val pitches = possible.map { candidate ->
                            ConversionVoices.note(source, destination, lane, original.note,
                                slots.getOrElse(candidate) { 0 }, where, candidateIssues, sourceInstrument = candidate)
                        }.distinct()
                        issues += candidateIssues.distinct()
                        if (pitches.size > 1) block(where,
                            "Inherited source instruments ${possible.joinToString()} require different destination pitches ${pitches.joinToString()}; a shared row cannot retain all of them.")
                        pitches.first()
                    } else ConversionVoices.note(source, destination, lane, original.note, effective, where, issues,
                        sourceInstrument = inherited)
                    val mapped = Cell(note = note, instrument = slot)
                    ConversionEffects.cell(source, destination, lane, p, row, original, mapped, issues)
                }
                val tracks = patterns[p].tracks.toMutableList()
                tracks[lane.target] = track
                patterns[p] = patterns[p].copy(tracks = tracks)
            }
        }
        destination = destination.copy(patterns = patterns)
        val structural = target.structuralErrors(destination)
        structural.forEach { block("Destination", it) }
        if (structural.isEmpty()) {
            target.errors(destination).distinct().forEach { block("Destination", it) }
            target.warnings(destination).distinct().forEach {
                issues += ConversionIssue(ConversionImpact.CHANGE, "Destination warning", it)
            }
        }
        return ConversionResult(if (issues.any { it.impact == ConversionImpact.BLOCKER }) null else destination, lanes, issues)
    }

    private fun location(pattern: Int, channel: Int, row: Int) = "Pattern ${pattern + 1}, channel ${channel + 1}, row $row"

    /** Full, deterministic report; display pagination must never truncate this export. */
    fun report(source: Song, target: SoundSystem, result: ConversionResult): String = buildString {
        appendLine("Thor Tracker system conversion")
        appendLine("Song: ${source.title}")
        appendLine("From: ${source.system.name}")
        appendLine("To: ${target.name}")
        appendLine(if (source.system.id == target.id) "Unchanged: same system." else if (result.canApply) "Ready to apply after explicit confirmation." else "BLOCKED: cannot apply.")
        appendLine("The source document remains unchanged until Apply. Cross-chip sound is not rendered-audio equivalence.")
        appendLine()
        appendLine("Lane mapping")
        for (lane in result.lanes) appendLine("${lane.source + 1}: ${source.system.channels[lane.source].name} -> ${lane.target + 1}: ${target.channels[lane.target].name}")
        for (channel in target.channels.indices.filter { ch -> result.lanes.none { it.target == ch } })
            appendLine("${channel + 1}: ${target.channels[channel].name} <- blank")
        appendLine()
        appendLine("Changes, losses and blockers (${result.issues.size})")
        result.issues.forEach { issue -> appendLine("${issue.impact}${if (issue.location.isEmpty()) "" else " [${issue.location}]"}: ${issue.detail}") }
    }
}
