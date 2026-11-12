package org.thortracker.system.gba

import org.thortracker.backend.GbaExportConstraints
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import kotlin.math.abs
import kotlin.math.round

/** XM 1.04's deliberately bounded eight-lane subset. Export constraints are shared with the canonical converter. */
object GbaRules {
    const val CHANNELS = 8
    const val MAX_ORDERS = 199
    val NOTES = 0..95
    val EFFECTS: List<EffectSpec> = listOf(
        effect(0, "Arpeggio", "xy: semitones", "Cycle base, +x and +y on ticks; 000 is no effect."),
        effect(1, "Slide up", "00–DF", "Slide period up on ticks after the row's first; 00 recalls the last speed."),
        effect(2, "Slide down", "00–DF", "Slide period down on ticks after the row's first; 00 recalls the last speed."),
        effect(3, "Tone portamento", "xx: speed", "Move the existing voice to the row's target note; 00 recalls speed."),
        effect(4, "Vibrato", "xy: speed/depth", "Pitch vibrato; a zero nibble recalls its previous value."),
        effect(5, "Portamento + volume slide", "x0 up / 0y down", "Continue tone portamento with volume slide; 00 recalls slide."),
        effect(6, "Vibrato + volume slide", "x0 up / 0y down", "Continue vibrato with volume slide; 00 recalls slide."),
        effect(7, "Tremolo", "xy: speed/depth", "Volume modulation; a zero nibble recalls its previous value."),
        effect(8, "Pan", "00 left–FF right", "Set the voice's stereo pan."),
        effect(9, "Sample offset", "xx × 256 frames", "Start the row's sample at an offset; converter restrictions are checked."),
        effect(10, "Volume slide", "x0 up / 0y down", "Slide volume on later ticks; 00 recalls slide."),
        effect(11, "Order jump", "xx: order", "Jump to an absolute order; parked-order targets follow the arrangement."),
        effect(12, "Volume", "00–40", "Set volume 0–64."),
        effect(13, "Pattern break", "00–63 decimal digits", "Continue at this decimal row in the next order (BCD)."),
        effect(14, "Extended", "Exy: supported subcommands", "E1/E2 fine slides, E4/E7 waveform, E6 loop, E8 pan, E9 retrigger, EA/EB fine volume, EC cut, ED delay, EE pattern delay. Unsupported subcommands are rejected."),
        effect(15, "Speed / BPM", "01–1F speed, 20–FF BPM", "Set ticks per row below 32, otherwise BPM. F00 is unsupported."),
    )
    private fun effect(code: Int, name: String, param: String, text: String) = EffectSpec(code, name, param, text, true)

    fun sampleProblem(sample: GbaSample): String? = when {
        sample.pcm.isEmpty() -> "Sample has no PCM; import or generate a sample"
        sample.pcm.size > GbaSound.MAX_PCM_BYTES -> "Sample exceeds the 512 KiB backend PCM budget; trim or resample it"
        sample.pcm.any { it !in -128..127 } -> "PCM values must be signed eight-bit values (-128–127)"
        sample.sampleRate !in 1..GbaSample.MAX_RATE -> "Sample rate must be 1–48000 Hz"
        sample.rootNote !in NOTES -> "Sample root note must be C0–B7"
        sample.fineTune !in -128..127 -> "Sample fine tuning must be -128–127 (1/128 semitone)"
        sample.volume !in 0..64 -> "Sample volume must be 0–64"
        sample.pan !in 0..255 -> "Sample pan must be 0–255"
        sample.loopStart == 0 && sample.loopEnd == 0 -> null
        sample.loopStart < 0 || sample.loopEnd > sample.pcm.size || sample.loopStart >= sample.loopEnd ->
            "Loop must be a nonempty forward range inside the PCM, or both endpoints zero for one-shot"
        else -> null
    }

    fun envelopeProblem(envelope: GbaEnvelope): String? = when {
        envelope.points.size > 12 -> "Envelope holds at most 12 points"
        envelope.points.any { it.tick !in 0..255 || it.value !in 0..64 } -> "Envelope ticks must be 0–255 and values 0–64"
        envelope.points.zipWithNext().any { (a, b) -> a.tick >= b.tick } -> "Envelope ticks must be strictly increasing"
        envelope.sustain != -1 && envelope.sustain !in envelope.points.indices -> "Envelope sustain must name a point or be -1 (off)"
        (envelope.loopStart == -1) != (envelope.loopEnd == -1) -> "Set both envelope loop points, or disable both with -1"
        envelope.loopStart != -1 && (envelope.loopStart !in envelope.points.indices || envelope.loopEnd !in envelope.points.indices || envelope.loopStart > envelope.loopEnd) -> "Envelope loop must name an ordered pair of points"
        else -> null
    }

    fun instrumentProblem(instrument: GbaInstrument, sampleCount: Int): String? = when {
        instrument.keymap.size != 96 -> "Instrument keymap needs exactly 96 notes (C0–B7)"
        instrument.keymap.any { it < -1 || it >= sampleCount } -> "Keymap refers to a missing sample; choose an existing sample or -1 for silence"
        instrument.fadeout !in 0..65535 -> "Instrument fadeout must be 0–65535"
        else -> envelopeProblem(instrument.volumeEnvelope)?.let { "Volume $it" }
            ?: envelopeProblem(instrument.panEnvelope)?.let { "Pan $it" }
    }

    fun cellRangeProblem(cell: Cell): String? = when {
        cell.note !in NOTES && cell.note != Cell.EMPTY && cell.note != Cell.CUT -> "Notes must be C0–B7, blank or OFF"
        cell.instrument !in 0..GbaSound.SLOTS -> "Instrument must be 01–40 hex, or 00 to keep the voice's instrument"
        cell.effect !in 0..EffectSpec.MAX_CODE || cell.param !in 0..255 -> "Effect or parameter is out of range"
        cell.more.isNotEmpty() -> "GBA XM supports one effect column; remove extra effects"
        else -> null
    }

    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        cellRangeProblem(cell)?.let { return it }
        if (channel !in 0 until CHANNELS) return "GBA has eight sample lanes"
        GbaExportConstraints.effectError(cell.effect, cell.param)?.let { return it }
        if (cell.effect == 11 && cell.param !in song.orders.indices) return "Bxx must name an existing order"
        if (cell.effect == 13 && (cell.param shr 4) * 10 + (cell.param and 15) >= Songs.ROWS) return "Dxx row must be 00–63 in decimal digits"
        val sound = song.sound as? GbaSound ?: return "This song does not contain GBA sample data"
        if (cell.instrument > 0) {
            val instrument = sound.instrument(cell.instrument) ?: return "Choose an existing instrument"
            instrumentProblem(instrument, sound.samples.size)?.let { return it }
            if (cell.note >= 0 && instrument.keymap[cell.note] < 0) return "${GbaSystem.noteName(cell.note)} is unmapped in sound ${cell.instrument}; map a sample in Sound"
        }
        return GbaExportConstraints.sampleOffsetError(sound, cell)
    }

    fun structuralErrors(song: Song): List<String> {
        val sound = song.sound as? GbaSound ?: return listOf("Song does not contain GBA sample data")
        val out = mutableListOf<String>()
        fun report(message: String) { if (out.size < 40) out.add(message) }
        if (song.ticksPerRow !in 1..31) report("XM speed must be 1–31 ticks per row")
        if (sound.bpm !in 32..255) report("XM BPM must be 32–255")
        if (sound.instruments.size != GbaSound.SLOTS) report("GBA needs exactly 64 instrument slots")
        if (sound.samples.size > GbaSound.MAX_SAMPLES) report("GBA allows at most 128 embedded samples")
        if (sound.samples.sumOf { it.pcm.size.toLong() } > GbaSound.MAX_PCM_BYTES) report("Embedded PCM exceeds the 512 KiB backend budget; trim, resample or delete samples (this is not GBA RAM capacity)")
        sound.samples.forEachIndexed { i, sample -> sampleProblem(sample)?.let { report("Sample $i: $it") } }
        sound.instruments.forEachIndexed { i, instrument -> instrumentProblem(instrument, sound.samples.size)?.let { report("Sound ${i + 1}: $it") } }
        if (song.patterns.isEmpty()) report("Add at least one pattern")
        song.patterns.forEachIndexed { p, pattern ->
            if (pattern.length !in 1..Songs.ROWS) report("Pattern $p length must be 1–${Songs.ROWS}")
            if (pattern.scale.root !in 0..11) report("Pattern $p scale root must be C–B")
            if (pattern.tracks.size != CHANNELS) report("Pattern $p needs eight sample lanes")
            pattern.tracks.forEachIndexed { ch, track ->
                if (track.size != Songs.ROWS) report("Pattern $p lane ${ch + 1} needs ${Songs.ROWS} stored rows")
                track.forEachIndexed { row, cell -> cellRangeProblem(cell)?.let { report("Pattern $p lane ${ch + 1} row $row: $it") } }
            }
        }
        if (song.orders.isEmpty()) report("Add at least one order")
        song.orders.forEachIndexed { i, pattern -> if (pattern !in song.patterns.indices) report("Order $i names missing pattern $pattern") }
        if (song.loopOrder !in song.orders.indices) report("Loop order must name an existing order")
        if (song.parkedOrders.any { it !in song.orders.indices } || song.parkedOrders.zipWithNext().any { (a, b) -> a >= b }) report("Parked orders must be distinct ascending order indices")
        if (song.newPatternScale.root !in 0..11) report("New-pattern scale root must be C–B")
        song.tuning?.problems(GbaSystem)?.forEach { report(it) }
        return out
    }

    fun errors(document: Song): List<String> {
        val structural = structuralErrors(document)
        if (structural.isNotEmpty()) return structural
        if (document.orders.size == document.parkedOrders.size) return listOf("Every order is parked; unpark one to play or export")
        val song = GbaSystem.arranged(document)
        val out = GbaExportConstraints.errors(song.gba).toMutableList()
        if (song.orders.size > MAX_ORDERS) out.add("Maxmod supports at most 199 played orders")
        if (song.orders.distinct().size > 256) out.add("XM supports at most 256 played patterns")
        if (song.tuning?.cents?.any { abs(it / 100 - round(it / 100)) > 0.0000001 } == true) {
            out.add("GBA XM does not support per-note microtuning; remove the tuning or use exact semitone degrees")
        }
        for (p in song.orders.distinct()) {
            val pattern = song.patterns[p]
            pattern.tracks.forEachIndexed { channel, track ->
                for (row in 0 until pattern.length) {
                    if (out.size >= 40) break
                    cellProblem(song, channel, track[row])?.let { out.add("Pattern $p lane ${channel + 1} row $row: $it") }
                }
            }
        }
        return out
    }

    fun warnings(document: Song): List<String> {
        if (structuralErrors(document).isNotEmpty()) return emptyList()
        val song = GbaSystem.arranged(document)
        val out = GbaExportConstraints.warnings(song.gba).toMutableList()
        song.gba.samples.forEachIndexed { i, sample ->
            if (sample.volume == 0 || sample.pcm.all { it == 0 }) out.add("Sample $i (${sample.name}) is silent")
            if (sample.loopEnd > sample.loopStart && abs(sample.pcm[sample.loopStart] - sample.pcm[sample.loopEnd - 1]) > 32) out.add("Sample $i loop has a discontinuity; a short fade or different endpoints may prevent clicks")
        }
        for (p in song.orders.distinct()) {
            song.patterns[p].tracks.forEachIndexed { ch, track ->
                var instrument = 0
                for (row in 0 until song.patterns[p].length) {
                    val cell = track[row]
                    if (cell.instrument != 0) instrument = cell.instrument
                    if (cell.note >= 0 && instrument == 0 && out.size < 40) out.add("Pattern $p lane ${ch + 1} row $row inherits its instrument; assign one for reliable playback from this order")
                    else if (cell.note >= 0 && song.gba.instrument(instrument)?.keymap?.get(cell.note) == -1 && out.size < 40) out.add("Pattern $p lane ${ch + 1} row $row inherits an unmapped note; assign a mapped sample")
                }
            }
        }
        return out.take(40)
    }
}
