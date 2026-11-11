package org.thortracker.system.ym2612

import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.model.Song
import org.thortracker.system.panel.EnvelopeContour

/** PSG and XGM2 sample-kit controls edit their native patches, never an FM approximation. */
internal object YmAuxSoundControls {
    private val psgNames = arrayOf("Contour", "Time", "Bend", "Grain", "Clock", "Listen")
    private val pcmNames = arrayOf("Key", "Recording", "Tune", "Level", "Listen")
    private val bends = arrayOf(YmSweep.NONE, YmSweep(12, 0, 24), YmSweep(-12, 0, 24))
    private val bendNames = arrayOf("Still", "Drop", "Rise")
    private val clocks = arrayOf("Bright", "Middle", "Low", "Pitched")

    fun pcm(song: Song, st: ViewState): Boolean = YmInstrumentView.kind(song, st) == YmInstrumentView.Kind.PCM
    fun count(song: Song, st: ViewState): Int = if (pcm(song, st)) pcmNames.size else psgNames.size
    fun name(song: Song, st: ViewState, index: Int): String = (if (pcm(song, st)) pcmNames else psgNames)[index]
    fun source(song: Song, st: ViewState): YmInstrument = song.ymInstrument(st.instrument) ?: defaultYmInstrument(st.instrument, st.listenChannel)

    fun maximum(song: Song, st: ViewState, index: Int): Int = if (index == count(song, st) - 1) {
        if (pcm(song, st)) 2 else 3
    } else if (pcm(song, st)) when (index) {
        0 -> YmRules.NOTES.last
        1 -> song.ym.pcmSamples.size
        2 -> YmPcm.PITCH.last - YmPcm.PITCH.first
        else -> YmPcm.LEVEL_MAX
    } else when (index) { 0 -> 2; 1 -> EnvelopeContour.lengths.lastIndex; 2 -> bends.lastIndex; 3 -> 1; else -> 3 }

    fun value(song: Song, st: ViewState, index: Int, ins: YmInstrument = source(song, st)): Int = if (index == count(song, st) - 1) {
        YmInstrumentView.listenChannel(st, ins) - if (pcm(song, st)) YmRules.PCM_FIRST else YmRules.PSG_FIRST
    } else when (ins) {
        is YmPsgInstrument -> when (index) {
            0 -> EnvelopeContour.read(ins.volume)?.shape?.ordinal ?: -1
            1 -> EnvelopeContour.read(ins.volume)?.let { EnvelopeContour.lengths.indexOf(it.ticks) } ?: -1
            2 -> if (!ins.sweep.active) 0 else bends.indexOf(ins.sweep)
            3 -> if (ins.noise and YmPsgInstrument.WHITE != 0) 1 else 0
            else -> ins.noise and 3
        }
        is YmPcmInstrument -> when (index) {
            0 -> st.dpcmKey
            1 -> (ins.pcm[st.dpcmKey]?.sample ?: -1) + 1
            2 -> (ins.pcm[st.dpcmKey]?.pitch ?: 0) - YmPcm.PITCH.first
            else -> ins.level
        }
        else -> 0
    }

    fun enabled(song: Song, st: ViewState, index: Int): Boolean = if (index == count(song, st) - 1) true else when (val ins = source(song, st)) {
        is YmPsgInstrument -> when (index) {
            1 -> EnvelopeContour.read(ins.volume)?.shape?.let { it != EnvelopeContour.Shape.HELD } ?: false
            3, 4 -> st.listenChannel == YmRules.NOISE
            else -> true
        }
        is YmPcmInstrument -> when (index) { 1 -> song.ym.pcmSamples.isNotEmpty(); 2 -> ins.pcm.containsKey(st.dpcmKey); else -> true }
        else -> false
    }

    fun label(song: Song, st: ViewState, index: Int): String {
        if (index == count(song, st) - 1) return song.system.channels[YmInstrumentView.listenChannel(st, source(song, st))].name
        val value = value(song, st, index)
        if (value < 0) return "Custom"
        return if (pcm(song, st)) when (index) {
            0 -> YmSystem.noteName(value)
            1 -> if (value == 0) "Unmapped" else song.ym.pcmSamples.getOrNull(value - 1)?.name ?: "Missing recording"
            2 -> (value + YmPcm.PITCH.first).let { if (it > 0) "+$it st" else "$it st" }
            else -> when (value) { 0 -> "Silent"; 15 -> "Original"; else -> "-${(15 - value) * 2} dB" }
        } else when (index) {
            0 -> EnvelopeContour.Shape.entries[value].label
            1 -> if (!enabled(song, st, index)) "Held / custom" else "${EnvelopeContour.lengths[value]} ticks"
            2 -> bendNames[value]
            3 -> if (value == 0) "Metal" else "Air"
            else -> clocks[value]
        }
    }

    fun hint(song: Song, st: ViewState, index: Int): String = if (index == count(song, st) - 1) {
        "Listening lane only; no patch or pattern change"
    } else if (pcm(song, st)) when (index) {
        0 -> "Choose the key to map and audition"
        1 -> if (song.ym.pcmSamples.isEmpty()) "Add recordings in Advanced" else "Only this key changes; other mappings stay"
        2 -> if (!enabled(song, st, index)) "Choose a recording first" else "Pitch and duration change together"
        else -> "Kit-wide level; recordings stay untouched"
    } else when (index) {
        0 -> "Choose a volume recipe; other programs stay"
        1 -> if (!enabled(song, st, index)) "Choose Pluck or Swell for a time control" else "Software ticks: 50 Hz PAL / 60 Hz NTSC"
        2 -> "A note-start octave bend; pitch program stays"
        else -> if (st.listenChannel != YmRules.NOISE) "Choose the Noise listening lane"
            else if ((source(song, st) as YmPsgInstrument).noise and 3 == 3) "Pitched noise borrows PSG 3; its tone is silent"
            else "Noise colour; played notes keep their own pitch"
    }

    fun set(v: ViewController, index: Int, raw: Int, original: YmInstrument? = null, heldTouch: Boolean = false) {
        val st = v.state
        val song = v.session.song
        if (!enabled(song, st, index)) return
        val value = raw.coerceIn(0, maximum(song, st, index))
        if (index == count(song, st) - 1) {
            st.listenChannel = (if (pcm(song, st)) YmRules.PCM_FIRST else YmRules.PSG_FIRST) + value
            if (v.auditionHeld || v.editHeld) YmInstrumentView.audition(v)
            return
        }
        val ins = original ?: source(song, st)
        if (ins is YmPcmInstrument && index == 0) {
            st.dpcmKey = value
            if (v.auditionHeld || v.editHeld) YmInstrumentView.audition(v)
            return
        }
        val next: YmInstrument = if (this.value(song, st, index, ins) == value) ins else when (ins) {
            is YmPsgInstrument -> when (index) {
                0 -> ins.copy(volume = EnvelopeContour.choose(ins.volume, EnvelopeContour.Shape.entries[value]))
                1 -> ins.copy(volume = EnvelopeContour.duration(ins.volume, EnvelopeContour.lengths[value]))
                2 -> ins.copy(sweep = bends[value])
                3 -> ins.copy(noise = (ins.noise and 3) or (if (value == 1) YmPsgInstrument.WHITE else 0))
                else -> ins.copy(noise = (ins.noise and YmPsgInstrument.WHITE) or value)
            }
            is YmPcmInstrument -> when (index) {
                1 -> ins.copy(pcm = if (value == 0) ins.pcm - st.dpcmKey else
                    ins.pcm + (st.dpcmKey to (ins.pcm[st.dpcmKey] ?: YmPcmMapping(value - 1)).copy(sample = value - 1)))
                2 -> ins.copy(pcm = ins.pcm + (st.dpcmKey to ins.pcm.getValue(st.dpcmKey).copy(pitch = value + YmPcm.PITCH.first)))
                else -> ins.copy(level = value)
            }
            else -> return
        }
        if (v.session.setYmInstrument(st.instrument, next, "Set ${name(song, st, index)}")) {
            YmInstrumentView.publish(v)
            if (heldTouch && v.editHeld) YmInstrumentView.audition(v) else YmSoundFace.heard(v)
        }
    }

    fun step(v: ViewController, index: Int, delta: Int) {
        val current = value(v.session.song, v.state, index)
        set(v, index, if (current < 0) (if (delta < 0) maximum(v.session.song, v.state, index) else 0) else current + delta)
    }

    fun nextLane(v: ViewController, delta: Int = 1) {
        val st = v.state
        st.ymPanel.invalidate()
        if (v.editHeld) { v.useEdit(); st.ymPanel.editBlocked = true }
        if (v.clearHeld) v.useClear()
        val first = if (pcm(v.session.song, st)) YmRules.PCM_FIRST else YmRules.PSG_FIRST
        val count = if (pcm(v.session.song, st)) 3 else 4
        val current = YmInstrumentView.listenChannel(st, source(v.session.song, st))
        st.listenChannel = first + (current - first + delta).mod(count)
        if (v.auditionHeld) YmInstrumentView.audition(v)
    }
}
