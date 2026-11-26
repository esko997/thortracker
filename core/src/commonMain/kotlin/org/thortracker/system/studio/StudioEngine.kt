package org.thortracker.system.studio

import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.PlaybackEngine
import org.thortracker.system.midi.MusicalEvent
import org.thortracker.system.midi.musicalRow
import kotlin.math.pow
import kotlin.math.roundToInt

/** STM1: bounded little-endian image consumed by studio/studio_chip.c. Rows share the USB musical reducer;
 * event time is a fraction of a row, not a MIDI timestamp or a host-specific sample count. */
object StudioEngine : PlaybackEngine {
    override val id = StudioSystem.ID
    override val driver = "Studio mono synth"
    override val seekTicks = 96
    internal const val ROW_INDEX = 1328
    internal const val MAX_EVENTS = 32768
    override val imageSize = ROW_INDEX + 2 * (256 * Songs.ROWS + 1) + MAX_EVENTS * 8
    override fun note(song: Song, channel: Int, note: Int, instrument: Int) = note
    override fun fine(channel: Int, note: Int, fine: Int) = fine
    override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int) =
        song.system.auditionProblem(song, channel, note, instrument, fine)

    override fun lower(song: Song, out: ByteArray) {
        require(song.studio.tracks.none { it.target == StudioTarget.USB_MIDI }) {
            "USB MIDI requires live Android output. Assign every track to Mono for local-only playback or WAV export."
        }
        lowerInternal(song, out)
    }

    /** Android's mixed router is responsible for validating/binding *all* external destinations before play. */
    fun lowerInternal(song: Song, out: ByteArray) {
        require(out.size == imageSize)
        song.system.errors(song).firstOrNull()?.let { throw IllegalArgumentException(it) }
        val doc = song.system.arranged(song)
        val state = doc.studio
        val patterns = doc.orders.distinct()
        fun byte(at: Int, n: Int) { out[at] = n.toByte() }
        fun word(at: Int, n: Int) { byte(at, n); byte(at + 1, n ushr 8) }
        fun int(at: Int, n: Int) { word(at, n); word(at + 2, n ushr 16) }
        int(0, 0x314d5453); byte(4, state.tracks.size); byte(5, doc.ticksPerRow); byte(6, doc.loopOrder)
        word(8, doc.orders.size); word(10, patterns.size)
        state.tracks.forEachIndexed { i, t ->
            int(16 + 6 * i, t.midi.id); byte(20 + 6 * i, t.midi.velocity); byte(21 + 6 * i, if (t.target == StudioTarget.MONO) 1 else 0)
        }
        for (n in 0..115) {
            val pitch = doc.tuning?.let { it.root + it.degreeCents(it.nearestDegree(100.0 * (n - it.root))) / 100.0 } ?: n.toDouble()
            int(64 + n * 4, (440.0 * 2.0.pow((pitch - 57) / 12.0)).toFloat().toBits())
        }
        state.patches.forEachIndexed { i, p ->
            val at = 528 + i * 9
            byte(at, p.waveform); byte(at+1, p.cutoff); byte(at+2, p.resonance); byte(at+3, p.envelope)
            byte(at+4, p.decay); byte(at+5, p.accent); byte(at+6, p.glide); byte(at+7, p.tune + 24); byte(at+8, p.level)
        }
        doc.orders.forEachIndexed { i, p -> byte(816 + i, patterns.indexOf(p)) }
        val base = ROW_INDEX + (patterns.size * Songs.ROWS + 1) * 2
        var count = 0
        // Numeric sort keeps fraction, lane and emission order as its high bits and the native payload
        // in its low bits. Fixed workspace: at most 16 attacks + releases and four columns per lane.
        val events = LongArray(StudioSound.MAX_TRACKS * 48)
        patterns.forEachIndexed { p, index ->
            val pattern = doc.patterns[index]
            byte(1072 + p, pattern.length)
            for (row in 0 until Songs.ROWS) {
                word(ROW_INDEX + (p * Songs.ROWS + row) * 2, count)
                if (row >= pattern.length) continue
                var used = 0
                for ((lane, track) in state.tracks.withIndex()) {
                    if (track.target != StudioTarget.MONO) continue
                    val cell = pattern.tracks[lane][row]
                    if (cell.isBlank) continue
                    var ordinal = 0
                    fun event(t: Int, op: Int, a: Int, b: Int = 0, c: Int = 0, d: Int = 0) {
                        events[used++] = (t.toLong() shl 45) or (lane.toLong() shl 42) or
                            (ordinal++.toLong() shl 36) or (op.toLong() shl 32) or (a and 255).toLong() or
                            ((b and 255).toLong() shl 8) or (c.toLong() shl 16) or (d.toLong() shl 24)
                    }
                    if (cell.instrument > 0) event(0, 0, cell.instrument)
                    for ((offset, _, action) in musicalRow(track.midi, cell, 65536L)) {
                        val t = offset.toInt()
                        when (action) {
                            is MusicalEvent.Attack -> event(t, 1, action.pitch, (action.strength * 255).roundToInt(), action.accent, if (action.tie) 1 else 0)
                            is MusicalEvent.Release -> event(t, 2, action.pitch)
                            MusicalEvent.Cut -> event(t, 3, 0)
                            is MusicalEvent.Pitch -> event(t, 4, 0)
                            is MusicalEvent.PitchStep -> { val n = (action.semitones * 500).roundToInt(); event(t, 5, n, n shr 8) }
                            is MusicalEvent.ExpressionStep -> { val n = (action.amount * 635).roundToInt(); event(t, 6, n, n shr 8) }
                            is MusicalEvent.Parameter -> event(t, 7 + action.slot, (action.level * 127).roundToInt())
                        }
                    }
                }
                require(count + used <= MAX_EVENTS) { "Studio arrangement exceeds $MAX_EVENTS internal events in distinct played patterns" }
                events.sort(0, used)
                for (i in 0 until used) {
                    val e = events[i]
                    val at = base + count++ * 8
                    word(at, (e ushr 45).toInt()); byte(at+2, (e ushr 42).toInt() and 7); byte(at+3, (e ushr 32).toInt() and 15)
                    int(at+4, e.toInt())
                }
            }
        }
        word(12, count); word(base - 2, count)
    }
}
