package org.thortracker.system.studio

import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.PlaybackEngine
import org.thortracker.system.midi.MusicalEvent
import org.thortracker.system.midi.musicalRow
import kotlin.math.pow
import kotlin.math.roundToInt

/** STM2: bounded little-endian score. PCM travels separately only when the bank changes. Rows share the USB reducer;
 * event time is a fraction of a row, not a MIDI timestamp or a host-specific sample count. */
object StudioEngine : PlaybackEngine {
    override val id = StudioSystem.ID
    override val driver = "Studio instruments"
    override val seekTicks = 96
    internal const val PATCH_BASE = 1328
    internal const val PATCH_BYTES = 312
    internal const val GRAPH_BASE = PATCH_BASE + StudioSound.SLOTS * PATCH_BYTES
    internal const val STRIP_BYTES = 136
    internal const val ROW_INDEX = GRAPH_BASE + 10 * STRIP_BYTES
    internal const val MAX_EVENTS = 32768
    override val imageSize = ROW_INDEX + 2 * (256 * Songs.ROWS + 1) + MAX_EVENTS * 8
    override fun note(song: Song, channel: Int, note: Int, instrument: Int) = note
    override fun fine(channel: Int, note: Int, fine: Int) = fine
    override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int) =
        song.system.auditionProblem(song, channel, note, instrument, fine)

    override fun lower(song: Song, out: ByteArray) {
        require(song.studio.tracks.none { it.target == StudioTarget.USB_MIDI }) {
            "USB MIDI requires live Android output. Assign every track to Internal for local-only playback or WAV export."
        }
        lowerInternal(song, out)
    }

    /** Android's mixed router is responsible for validating/binding *all* external destinations before play. */
    fun lowerInternal(song: Song, out: ByteArray) {
        require(out.size == imageSize)
        song.system.errors(song).firstOrNull()?.let { throw IllegalArgumentException(it) }
        val doc = song.system.arranged(song)
        val state = doc.studio
        val orders=state.previewPrefix+doc.orders
        val patterns = orders.distinct()
        fun byte(at: Int, n: Int) { out[at] = n.toByte() }
        fun word(at: Int, n: Int) { byte(at, n); byte(at + 1, n ushr 8) }
        fun int(at: Int, n: Int) { word(at, n); word(at + 2, n ushr 16) }
        out.fill(0, 0, ROW_INDEX)
        int(0, 0x324d5453); byte(4, state.tracks.size); byte(5, doc.ticksPerRow)
        byte(6, state.previewPrefix.size+doc.loopOrder); byte(7,state.buses.size)
        word(8, orders.size); word(10, patterns.size);byte(14,state.previewPrefix.size)
        state.tracks.forEachIndexed { i, t ->
            int(16 + 6 * i, t.midi.id); byte(20 + 6 * i, t.midi.velocity); byte(21 + 6 * i, if (t.target == StudioTarget.MONO) 1 else 0)
        }
        for (n in 0..115) {
            val pitch = doc.tuning?.let { it.root + it.degreeCents(it.nearestDegree(100.0 * (n - it.root))) / 100.0 } ?: n.toDouble()
            int(64 + n * 4, (440.0 * 2.0.pow((pitch - 57) / 12.0)).toFloat().toBits())
        }
        state.patches.forEachIndexed { i, p ->
            val at=PATCH_BASE+i*PATCH_BYTES
            byte(at,p.kind.wire)
            for(param in p.kind.parameters) int(at+4+param.id*4,p.value(param.id))
            if(p is SamplerPatch) {
                byte(at+1,p.assetId ?: 0); byte(at+2,(p.defaultSlice ?: -1)+1)
                int(at+40,p.start); int(at+44,p.end); int(at+48,p.loopStart); int(at+52,p.loopEnd)
                for(slice in p.slices) { int(at+56+8*slice.id,slice.start); int(at+60+8*slice.id,slice.end) }
            }
        }
        fun strip(index:Int,gain:Int,pan:Int,a:Int,b:Int,inserts:List<StudioInsert>) {
            val at=GRAPH_BASE+index*STRIP_BYTES
            byte(at,gain); byte(at+1,pan+100); byte(at+2,a); byte(at+3,b); byte(at+4,inserts.size)
            inserts.forEachIndexed { j,fx ->
                val pos=at+8+j*32; int(pos,fx.id); byte(pos+4,fx.kind.wire); byte(pos+5,if(fx.bypass) 1 else 0)
                for(param in fx.kind.parameters) int(pos+8+4*param.id,fx.value(param.id))
            }
        }
        state.tracks.forEachIndexed { i,t -> if(t.target==StudioTarget.MONO) strip(i,t.gain,t.pan,t.sends[0],t.sends[1],t.inserts) }
        state.buses.forEachIndexed { i,b -> strip(8+i,b.gain,0,0,0,b.inserts) }
        orders.forEachIndexed { i, p -> byte(816 + i, patterns.indexOf(p)) }
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
                            ((b and 255).toLong() shl 8) or ((c and 255).toLong() shl 16) or ((d and 255).toLong() shl 24)
                    }
                    if (cell.instrument > 0) event(0, 0, cell.instrument)
                    // Resolve a stable document identity against this image's routing. Both the event table
                    // and routing swap together; the machine carries overrides by identity, not by position.
                    for(col in 0..cell.more.size) {
                        val code=cell.effectCode(col); val value=cell.effectParam(col)
                        if(code==13) event(0,9,value)
                        if(code==9 || code==10) event(0,code-2,value)
                        if(code in 14..35) {
                            val binding=track.bindings.getValue(code)
                            val actual=binding.kind.parameters.first { it.id==binding.parameter }.fromHex(value)
                            val target=if(binding.instance==0) 0 else 1+track.inserts.indexOfFirst { it.id==binding.instance }
                            // UInt16 includes negative tune/trim (two's complement); crusher rate may exceed 32767.
                            event(0,10,target,(binding.kind.wire shl 4) or binding.parameter,actual,actual ushr 8)
                        }
                    }
                    for ((offset, _, action) in musicalRow(track.midi, cell, 65536L)) {
                        val t = offset.toInt()
                        when (action) {
                            is MusicalEvent.Attack -> event(t, 1, action.pitch, (action.strength * 255).roundToInt(), action.accent, if (action.tie) 1 else 0)
                            is MusicalEvent.Release -> event(t, 2, action.pitch)
                            MusicalEvent.Cut -> event(t, 3, 0)
                            is MusicalEvent.Pitch -> event(t, 4, 0)
                            is MusicalEvent.PitchStep -> { val n = (action.semitones * 500).roundToInt(); event(t, 5, n, n shr 8) }
                            is MusicalEvent.ExpressionStep -> { val n = (action.amount * 635).roundToInt(); event(t, 6, n, n shr 8) }
                            is MusicalEvent.Parameter -> Unit // emitted above in effect-column order with bound controllers
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

    /** Optional inline bank for offline export or live publication after an asset edit.
     * No path or native address enters a wire image. An empty trailer clears the old bank. */
    fun withBank(score:ByteArray,assets:StudioAssets):ByteArray {
        val ids=assets.ids.sorted()
        val out=score.copyOf(score.size+8+ids.sumOf { 16+assets[it]!!.sampleCount*4 })
        var pos=score.size
        fun put(n:Int) { for(b in 0..3) out[pos++]=(n ushr (b*8)).toByte() }
        put(0x314d4350); put(ids.size)
        for(id in ids) {
            val pcm=assets[id]!!
            put(id);put(pcm.channels);put(pcm.sampleRate);put(pcm.frames)
            for(i in 0 until pcm.sampleCount) put(pcm.sample(i).toBits())
        }
        return out
    }
}
