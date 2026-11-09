package org.thortracker.system.opn

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import org.thortracker.backend.OpnPlayerImage
import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.Song
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind

/** A copied OPN-family sound, or one SSG envelope. */
sealed interface OpnClip : Clip {
    data class Sound(val instrument: OpnInstrument) : OpnClip
    data class Envelope(val kind: NesEnvelopeKind, val envelope: NesEnvelope, val relative: Boolean) : OpnClip
}

/**
 * OPN-family document edits: pure song transformations of the one slot list ([OpnSound.instruments]) and the YM2608's
 * ADPCM-B samples ([OpnSound.samples]), and the undoable [EditorSession] commands the instrument editor runs them as.
 * Every transformation returns the same [Song] instance when nothing changes. Sample authoring decodes the ADPCM-B data
 * ([OpnAdpcm.decode]), changes the PCM and encodes it again ([OpnAdpcm.encode]); trimming the end only drops whole
 * 32-byte units, so it is exact.
 */
object OpnEdits {
    /** SSG envelopes hold the EPSM SSG's ranges (the same YM2149-style SSG): [NesEnvelopeKind.fixed] needs the chip. */
    val SSG_CHIP = NesChip.EPSM

    /** The SSG envelopes, in page order. */
    val SSG_ENVELOPES = listOf(NesEnvelopeKind.VOLUME, NesEnvelopeKind.ARPEGGIO, NesEnvelopeKind.PITCH, NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE)

    /** PCM samples per 32-byte ADPCM-B unit (two 4-bit codes a byte). */
    const val UNIT_SAMPLES = 2 * OpnPlayerImage.ADPCM_ALIGN

    // ---- reading ------------------------------------------------------------------------------------------

    /** Sound [slot] (1-based), or null. */
    fun sound(song: Song, slot: Int): OpnInstrument? = song.opn.instrument(slot)

    /** The first channel of [chip] that plays [part]'s sounds, or -1 when the chip has none. */
    fun firstChannel(chip: OpnChip, part: OpnPart): Int = when (part) {
        OpnPart.FM -> 0
        OpnPart.SSG -> chip.ssgFirst
        OpnPart.RHYTHM -> chip.rhythmFirst
        OpnPart.ADPCM -> chip.adpcmChannel
    }

    /** The sound an empty [slot] gets when first edited as [part]: the part's default sound, named after the slot. */
    fun newSound(chip: OpnChip, part: OpnPart, slot: Int): OpnInstrument = OpnPresets.default(chip, part).named("Sound ${hex2(slot)}")

    fun defaultFm(chip: OpnChip, slot: Int): OpnFmSound = newSound(chip, OpnPart.FM, slot) as? OpnFmSound ?: OpnFmSound(name = "Sound ${hex2(slot)}")
    fun defaultSsg(chip: OpnChip, slot: Int): OpnSsgSound = newSound(chip, OpnPart.SSG, slot) as? OpnSsgSound ?: OpnSsgSound(name = "Sound ${hex2(slot)}")
    fun defaultRhythm(chip: OpnChip, slot: Int): OpnRhythmSound = newSound(chip, OpnPart.RHYTHM, slot) as? OpnRhythmSound ?: OpnRhythmSound(name = "Sound ${hex2(slot)}")
    fun defaultAdpcm(chip: OpnChip, slot: Int): OpnAdpcmSound = newSound(chip, OpnPart.ADPCM, slot) as? OpnAdpcmSound ?: OpnAdpcmSound(name = "Sound ${hex2(slot)}")

    /** Envelope [kind] of an SSG sound. */
    fun envelope(s: OpnSsgSound, kind: NesEnvelopeKind): NesEnvelope = when (kind) {
        NesEnvelopeKind.ARPEGGIO -> s.arpeggio
        NesEnvelopeKind.PITCH -> s.pitch
        NesEnvelopeKind.MIXER -> s.mixer
        NesEnvelopeKind.NOISE -> s.noise
        else -> s.volume
    }

    /** [s] with envelope [kind] replaced by [e] made legal. */
    fun withEnvelope(s: OpnSsgSound, kind: NesEnvelopeKind, e: NesEnvelope): OpnSsgSound {
        val f = kind.fixed(e, SSG_CHIP)
        return when (kind) {
            NesEnvelopeKind.ARPEGGIO -> s.copy(arpeggio = f)
            NesEnvelopeKind.PITCH -> s.copy(pitch = f)
            NesEnvelopeKind.MIXER -> s.copy(mixer = f)
            NesEnvelopeKind.NOISE -> s.copy(noise = f)
            else -> s.copy(volume = f)
        }
    }

    /** Slots (1-based) of the ADPCM sounds playing sample [index]. */
    fun soundsUsing(song: Song, index: Int): List<Int> =
        song.opn.instruments.indices.filter { (song.opn.instruments[it] as? OpnAdpcmSound)?.sample == index }.map { it + 1 }

    /** Pattern rows of the arrangement (each pattern counted once) whose note names one of [slots]. */
    fun rowsUsing(song: Song, slots: Set<Int>): Int {
        if (slots.isEmpty()) return 0
        var n = 0
        for (pat in song.orders.toSet()) for (track in song.patterns[pat].tracks) for (cell in track) if (cell.instrument in slots) n++
        return n
    }

    /** ADPCM-B memory still free once every sample of the song is placed (negative when they do not fit). */
    fun free(song: Song): Int = OpnPlayerImage.ADPCM_MEMORY - song.opn.adpcmUsed

    /** The sample's largest absolute PCM value. */
    fun peak(sample: OpnaSample): Int {
        var p = 0
        for (s in OpnAdpcm.decode(sample.data)) { val a = abs(s.toInt()); if (a > p) p = a }
        return p
    }

    // ---- sounds ---------------------------------------------------------------------------------------------

    private fun Song.withOpn(sound: OpnSound): Song = if (sound == opn) this else copy(sound = sound)

    /** [slot] (1-based) set to [instrument]; null empties it. */
    fun setSound(song: Song, slot: Int, instrument: OpnInstrument?): Song {
        if (slot !in 1..OpnSound.SLOTS || song.opn.instrument(slot) == instrument) return song
        return song.withOpn(song.opn.withInstrument(slot, instrument))
    }

    /** [slot] made a [part] sound: that part's default sound, keeping the old sound's name. */
    fun withPart(song: Song, slot: Int, part: OpnPart): Song {
        val cur = song.opn.instrument(slot)
        if (cur?.part == part) return song
        val next = newSound(song.opn.chip, part, slot)
        return setSound(song, slot, cur?.let { next.named(it.name) } ?: next)
    }

    /** FM 3's mode set to [mode]. */
    fun withFm3Mode(song: Song, mode: OpnFm3Mode): Song = song.withOpn(song.opn.copy(fm3Mode = mode))

    // ---- samples --------------------------------------------------------------------------------------------

    /** [sample] appended to the song's samples (unchanged when the list is full). */
    fun addSample(song: Song, sample: OpnaSample): Song =
        if (song.opn.samples.size >= OpnSound.MAX_SAMPLES) song else song.withOpn(song.opn.copy(samples = song.opn.samples + sample))

    /**
     * [sample] appended, and sound [slot] made to play it: an ADPCM sound points at it, an empty slot gets a new ADPCM
     * sound named after it; any other sound is left alone. Unchanged when the list is full.
     */
    fun addAndUse(song: Song, slot: Int, sample: OpnaSample): Song {
        val added = addSample(song, sample)
        if (added === song) return song
        val index = added.opn.samples.lastIndex
        return when (val cur = added.opn.instrument(slot)) {
            is OpnAdpcmSound -> setSound(added, slot, cur.copy(sample = index))
            null -> setSound(added, slot, OpnPresets.adpcm(index, sample.name))
            else -> added
        }
    }

    /** Sample [index] replaced by [sample]; the sounds playing it keep playing it. */
    fun replaceSample(song: Song, index: Int, sample: OpnaSample): Song {
        if (index !in song.opn.samples.indices || song.opn.samples[index] == sample) return song
        return song.withOpn(song.opn.withSample(index, sample))
    }

    /**
     * Sample [index] removed: the sounds playing it are emptied (callers ask first, and refuse while the arrangement
     * uses them) and every ADPCM sound playing a later sample follows it down one, so each keeps its recording.
     */
    fun deleteSample(song: Song, index: Int): Song {
        val opn = song.opn
        if (index !in opn.samples.indices) return song
        val instruments = opn.instruments.map { ins ->
            when {
                ins !is OpnAdpcmSound -> ins
                ins.sample == index -> null
                ins.sample > index -> ins.copy(sample = ins.sample - 1)
                else -> ins
            }
        }
        return song.withOpn(opn.copy(instruments = instruments, samples = opn.samples.filterIndexed { i, _ -> i != index }))
    }

    private fun editSample(song: Song, index: Int, f: (OpnaSample) -> OpnaSample): Song {
        val cur = song.opn.samples.getOrNull(index) ?: return song
        return replaceSample(song, index, f(cur))
    }

    /** Sample [index] named [name] (at most [OpnaSample.MAX_NAME] characters). */
    fun renameSample(song: Song, index: Int, name: String): Song = editSample(song, index) { it.copy(name = name.take(OpnaSample.MAX_NAME)) }

    /** [pcm] encoded as [sample]'s new data (at [rate]). */
    private fun encoded(sample: OpnaSample, pcm: ShortArray, rate: Int = sample.rate): OpnaSample =
        sample.copy(rate = rate, data = AdpcmBytes(OpnAdpcm.encode(pcm)))

    /** Sample [index]'s PCM through [f] and encoded again; unchanged when [f] returns null. */
    private fun editPcm(song: Song, index: Int, f: (ShortArray) -> ShortArray?): Song = editSample(song, index) { s ->
        f(OpnAdpcm.decode(s.data))?.let { encoded(s, it) } ?: s
    }

    /** The first [count] PCM samples cut from sample [index] (at least one 64-sample unit stays). */
    fun trimStart(song: Song, index: Int, count: Int): Song = editPcm(song, index) { pcm ->
        val cut = count.coerceAtMost(pcm.size - UNIT_SAMPLES)
        if (cut <= 0) null else pcm.copyOfRange(cut, pcm.size)
    }

    /** The last [count] PCM samples (whole 32-byte units) cut from sample [index], exactly: no re-encoding. */
    fun trimEnd(song: Song, index: Int, count: Int): Song = editSample(song, index) { s ->
        val keep = (s.data.size - count / 2).coerceAtLeast(minOf(OpnPlayerImage.ADPCM_ALIGN, s.data.size))
        if (keep >= s.data.size) s else s.copy(data = AdpcmBytes(s.data.toByteArray().copyOf(keep)))
    }

    /** Sample [index] made [db] decibels louder (negative: quieter), clipped at full scale. */
    fun gain(song: Song, index: Int, db: Double): Song {
        if (db == 0.0) return song
        val factor = 10.0.pow(db / 20.0)
        return editPcm(song, index) { pcm -> ShortArray(pcm.size) { (pcm[it] * factor).roundToInt().coerceIn(-32768, 32767).toShort() } }
    }

    /** Sample [index] scaled so its loudest point reaches full scale; unchanged when silent or already there. */
    fun normalize(song: Song, index: Int): Song = editPcm(song, index) { pcm ->
        var peak = 0
        for (s in pcm) { val a = abs(s.toInt()); if (a > peak) peak = a }
        if (peak == 0 || peak >= NORMAL_PEAK) null
        else {
            val factor = NORMAL_PEAK.toDouble() / peak
            ShortArray(pcm.size) { (pcm[it] * factor).roundToInt().coerceIn(-32768, 32767).toShort() }
        }
    }

    /** Sample [index] played backwards. */
    fun reverse(song: Song, index: Int): Song = editPcm(song, index) { pcm -> pcm.reversedArray() }

    /** Sample [index] rising from silence over its first [count] PCM samples. */
    fun fadeIn(song: Song, index: Int, count: Int): Song = editPcm(song, index) { pcm ->
        val m = count.coerceAtMost(pcm.size)
        if (m <= 0) null else ShortArray(pcm.size) { i -> if (i < m) (pcm[i] * i.toDouble() / m).roundToInt().toShort() else pcm[i] }
    }

    /** Sample [index] falling to silence over its last [count] PCM samples. */
    fun fadeOut(song: Song, index: Int, count: Int): Song = editPcm(song, index) { pcm ->
        val m = count.coerceAtMost(pcm.size)
        if (m <= 0) null else {
            val start = pcm.size - m
            ShortArray(pcm.size) { i -> if (i >= start) (pcm[i] * (pcm.size - 1 - i).toDouble() / m).roundToInt().toShort() else pcm[i] }
        }
    }

    /** Sample [index] resampled to [rate] Hz: the same sound at its root note, larger or smaller. */
    fun resample(song: Song, index: Int, rate: Int): Song = editSample(song, index) { s ->
        val to = rate.coerceIn(OpnPlayerImage.RATE_MIN, OpnPlayerImage.RATE_MAX)
        if (to == s.rate) s else encoded(s, OpnAdpcm.resample(OpnAdpcm.decode(s.data), s.rate, to), to)
    }

    /** The peak [normalize] scales to: full scale. */
    const val NORMAL_PEAK = 32767

    // ---- session commands -------------------------------------------------------------------------------------

    /** Replaces sound [slot] ([instrument] null empties it). */
    fun set(session: EditorSession, slot: Int, instrument: OpnInstrument?, label: String): Boolean =
        session.apply(label) { setSound(it, slot, instrument) }

    /**
     * Edits [slot]'s sound as a [T] (the class of [part]); an empty slot starts from [blank]. False (nothing changes)
     * when the slot holds a sound of another part.
     */
    private inline fun <reified T : OpnInstrument> edit(session: EditorSession, slot: Int, label: String, blank: () -> T, f: (T) -> T): Boolean {
        if (slot !in 1..OpnSound.SLOTS) return false
        val cur = sound(session.song, slot)
        if (cur != null && cur !is T) return false
        val next = f((cur as T?) ?: blank())
        return (cur == null || next != cur) && set(session, slot, next, label)
    }

    fun editFm(session: EditorSession, slot: Int, label: String, f: (OpnFmSound) -> OpnFmSound): Boolean =
        edit(session, slot, label, { defaultFm(session.song.opn.chip, slot) }, f)

    fun editSsg(session: EditorSession, slot: Int, label: String, f: (OpnSsgSound) -> OpnSsgSound): Boolean =
        edit(session, slot, label, { defaultSsg(session.song.opn.chip, slot) }, f)

    fun editRhythm(session: EditorSession, slot: Int, label: String, f: (OpnRhythmSound) -> OpnRhythmSound): Boolean =
        edit(session, slot, label, { defaultRhythm(session.song.opn.chip, slot) }, f)

    fun editAdpcm(session: EditorSession, slot: Int, label: String, f: (OpnAdpcmSound) -> OpnAdpcmSound): Boolean =
        edit(session, slot, label, { defaultAdpcm(session.song.opn.chip, slot) }, f)

    /** Edits envelope [kind] of SSG sound [slot]; the result is made legal. */
    fun editEnvelope(session: EditorSession, slot: Int, kind: NesEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope): Boolean =
        editSsg(session, slot, label) { withEnvelope(it, kind, f(envelope(it, kind))) }

    /** Renames [slot]'s sound (any part). */
    fun rename(session: EditorSession, slot: Int, f: (String) -> String): Boolean {
        val cur = sound(session.song, slot) ?: return false
        return set(session, slot, cur.named(f(cur.name)), "Rename sound")
    }

    fun copySound(session: EditorSession, slot: Int) {
        val i = sound(session.song, slot) ?: run { session.note("Sound ${hex2(slot)} is empty"); return }
        session.hold(OpnClip.Sound(i))
        session.note("Copied sound ${hex2(slot)} ${i.name}")
    }

    fun copyEnvelope(session: EditorSession, slot: Int, kind: NesEnvelopeKind) {
        val i = sound(session.song, slot) as? OpnSsgSound ?: return
        session.hold(OpnClip.Envelope(kind, envelope(i, kind), i.pitchRelative))
        session.note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
    }

    /**
     * Pastes the clipboard into [slot]: a sound replaces it (refused when the song's chip has no such part); an
     * envelope goes into SSG envelope [kind] (values clamped to its range, the release kept only on volume); null
     * [kind] (not an envelope page) takes sounds only.
     */
    fun paste(session: EditorSession, slot: Int, kind: NesEnvelopeKind?): Boolean = when (val clip = session.clipboard) {
        is OpnClip.Sound -> if (clip.instrument.part !in session.song.opn.chip.parts) {
            session.note("The ${session.song.opn.chip.label} has no ${clip.instrument.part.name.lowercase()} sounds"); false
        } else set(session, slot, clip.instrument, "Paste sound").also { session.note("Pasted into sound ${hex2(slot)}") }
        is OpnClip.Envelope -> if (kind == null || sound(session.song, slot) !is OpnSsgSound) {
            session.note("An envelope pastes into an SSG sound's envelope page"); false
        } else editSsg(session, slot, "Paste ${kind.label.lowercase()} envelope") {
            val with = withEnvelope(it, kind, clip.envelope)
            if (kind == NesEnvelopeKind.PITCH && clip.kind == NesEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
        }.also { session.note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
        else -> { session.note("Clipboard holds no OPN sound or envelope"); false }
    }
}
