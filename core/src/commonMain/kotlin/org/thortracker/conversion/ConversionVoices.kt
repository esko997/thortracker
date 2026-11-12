package org.thortracker.conversion

import kotlin.math.abs
import kotlin.math.roundToInt
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.ChannelKind
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound
import org.thortracker.system.ay.*
import org.thortracker.system.cbm.*
import org.thortracker.system.gb.*
import org.thortracker.system.gba.GbaSound
import org.thortracker.system.nes.*
import org.thortracker.system.opl3.*
import org.thortracker.system.opm.*
import org.thortracker.system.opn.*
import org.thortracker.system.pce.*
import org.thortracker.system.pokey.*
import org.thortracker.system.saa.*
import org.thortracker.system.scc.*
import org.thortracker.system.sid.*
import org.thortracker.system.sms.*
import org.thortracker.system.tia.*
import org.thortracker.system.vsu.*
import org.thortracker.system.ws.*
import org.thortracker.system.ym2612.*

internal data class ConversionVoicePlan(val song: Song, val slotsBySourceChannel: List<IntArray>)

/** Conversion is deliberately synthesis-first: unavailable ROMs and empty sample banks are never voices. */
internal object ConversionVoices {
    fun preserves(source: SystemSound, target: SystemSound): Boolean =
        source.system.id == target.system.id ||
            (source is NesSound && target is NesSound && source.expansion == NesExpansion.NONE) ||
            (source is WsSound && target is WsSound && !source.color && target.color) ||
            (source is OpnSound && target is OpnSound && !source.chip.opna && target.chip.opna) ||
            (source is SmsSound && target is SmsSound && source.layout == SmsLayout.PSG)

    private fun promotion(source: SystemSound, target: SystemSound): SystemSound? = when {
        source.system.id == target.system.id -> source
        source is NesSound && target is NesSound && source.expansion == NesExpansion.NONE ->
            source.copy(expansion = target.expansion)
        source is WsSound && target is WsSound && !source.color && target.color -> source.copy(color = true)
        source is OpnSound && target is OpnSound && !source.chip.opna && target.chip.opna ->
            source.copy(chip = target.chip)
        source is SmsSound && target is SmsSound && source.layout == SmsLayout.PSG -> source.copy(layout = target.layout)
        else -> null
    }

    private fun reserved(sound: SystemSound, ch: Int): Boolean = when (sound) {
        is GbaSound -> false // Original PCM starter voices are always embedded in the target song.
        is NesSound -> ch == NesRules.DPCM || NesRules.isMmc5Pcm(sound.expansion, ch) || NesRules.isEpsmRhythm(sound.expansion, ch)
        is OpnSound -> sound.chip.part(ch) == OpnPart.RHYTHM || sound.chip.part(ch) == OpnPart.ADPCM
        is YmSound -> YmRules.isPcm(ch)
        is CbmSound -> CbmRules.isDac(sound.chip, ch)
        is WsSound -> ch == WsRules.HYPER_VOICE
        else -> sound.system.channels[ch].kind == ChannelKind.SAMPLE
    }

    private fun referencedSlots(source: Song, lane: Int): List<Int> {
        val slots = mutableSetOf<Int>()
        var hasInheritedNote = false
        source.patterns.forEach { pattern -> pattern.tracks[lane].forEach { cell ->
            if (cell.instrument > 0) slots += cell.instrument
            if (cell.note >= 0 && cell.instrument == 0) hasInheritedNote = true
        } }
        // Zero retains a selected sound; it does not select the editor default again.
        // The orchestrator separately rejects ambiguous startup inheritance.
        if (slots.isEmpty() && hasInheritedNote)
            slots += source.system.defaultInstrument(source, source.system.channels[lane].bank)
        return slots.sorted()
    }

    private fun sourceRole(source: Song, lane: Int): ChannelKind {
        val fallback = source.system.channels[lane].kind
        if (source.sound is GbaSound) return ChannelKind.VOICE
        if (source.sound !is PceSound && source.sound !is WsSound) return fallback
        val slots = referencedSlots(source, lane)
        if (slots.isEmpty()) return fallback
        val roles = slots.map { slot -> when (val sound = source.sound) {
            is PceSound -> when (sound.instrument(slot)?.kind) {
                PceKind.NOISE -> ChannelKind.NOISE
                PceKind.SAMPLE -> ChannelKind.SAMPLE
                else -> fallback
            }
            is WsSound -> when (sound.instrument(slot)?.kind) {
                WsKind.NOISE -> ChannelKind.NOISE
                WsKind.SAMPLE -> ChannelKind.SAMPLE
                else -> fallback
            }
            else -> fallback
        } }.distinct()
        return roles.singleOrNull() ?: fallback
    }

    private fun noiseCapable(sound: SystemSound, lane: Int): Boolean =
        sound is GbaSound || sound.system.channels[lane].kind == ChannelKind.NOISE ||
            (sound is PceSound && lane in 4..5) || (sound is WsSound && lane == 3)

    fun lanes(source: Song, target: SoundSystem): List<ConversionLane> {
        val blank = target.blank()
        if (promotion(source.sound, blank.sound) != null) {
            // OPN inserts three FM lanes before the SSG, unlike the other append-only promotions.
            return source.system.channels.indices.map { ch ->
                ConversionLane(ch, if (source.sound is OpnSound && source.system.id != target.id && ch >= 3) ch + 3 else ch)
            }
        }
        val available = target.channels.indices.filterNot { reserved(blank.sound, it) }.toMutableSet()
        val assigned = mutableMapOf<Int, Int>()
        val roles = source.system.channels.indices.map { sourceRole(source, it) }
        // Noise-capable multifunction lanes are scarce: reserve them before ordinary wave voices.
        for (ch in source.system.channels.indices.filter { roles[it] == ChannelKind.NOISE } +
            source.system.channels.indices.filter { roles[it] != ChannelKind.NOISE }) {
            val kind = roles[ch]
            val to = available.firstOrNull {
                if (kind == ChannelKind.NOISE) noiseCapable(blank.sound, it) else target.channels[it].kind == kind
            } ?: continue
            assigned[ch] = to
            available.remove(to)
        }
        for (ch in source.system.channels.indices) {
            if (ch in assigned) continue
            val tonal = roles[ch] != ChannelKind.NOISE && roles[ch] != ChannelKind.SAMPLE
            val to = available.minByOrNull { lane ->
                val dst = target.channels[lane]
                when {
                    tonal == dst.tonal -> 0
                    dst.tonal -> 2
                    else -> 3
                }
            } ?: continue
            assigned[ch] = to
            available.remove(to)
        }
        return assigned.entries.sortedBy { it.key }.map { ConversionLane(it.key, it.value) }
    }

    fun prepare(source: Song, target: SoundSystem, lanes: List<ConversionLane>, issues: MutableList<ConversionIssue>): ConversionVoicePlan {
        val blank = target.blank()
        val maps = source.system.channels.map { IntArray(source.system.banks[it.bank].slots + 1) }
        val preserved = promotion(source.sound, blank.sound)
        if (preserved != null) {
            lanes.forEach { lane -> for (slot in 1 until maps[lane.source].size) maps[lane.source][slot] = slot }
            if (source.system.id != target.id) issues += ConversionIssue(ConversionImpact.CHANGE, "Sound", 
                "Compatible instrument slots, samples, waves and chip settings retained; added lanes are blank. Target chip layout, routing and clock behavior must not be assumed audio-identical.")
            return ConversionVoicePlan(blank.copy(sound = preserved), maps)
        }

        var sound = blank.sound
        // Except GB's genuinely separate banks, bank numbers are views over one physical instrument list.
        val next = IntArray(if (sound is GbSound) target.banks.size else 1) { 1 }
        data class Identity(val bank: Int, val slot: Int, val kind: String)
        val allocated = linkedMapOf<Identity, Int>()
        val retained = mutableSetOf<Pair<Int, Int>>()
        val kindsBySource = mutableMapOf<Pair<Int, Int>, MutableSet<String>>()
        for (lane in lanes) {
            val bank = source.system.channels[lane.source].bank
            val targetBank = target.channels[lane.target].bank
            val references = referencedSlots(source, lane.source)
            val noise = sourceRole(source, lane.source) == ChannelKind.NOISE && noiseCapable(blank.sound, lane.target)
            val kind = voiceKind(blank.sound, lane.target) + if (noise) " noise" else ""
            for (slot in references) {
                val where = "${source.system.banks[bank].name} bank $bank slot $slot"
                if (slot !in 1 until maps[lane.source].size) {
                    issues += ConversionIssue(ConversionImpact.BLOCKER, where, "Source instrument is outside its bank.")
                    continue
                }
                val identity = Identity(bank, slot, kind)
                val old = allocated[identity]
                if (old != null) {
                    maps[lane.source][slot] = old
                    continue
                }
                val physicalBank = if (sound is GbSound) targetBank else 0
                val to = next[physicalBank]
                if (to > target.banks[targetBank].slots) {
                    issues += ConversionIssue(ConversionImpact.BLOCKER, where,
                        "No target slot remains for editable source identity ($kind); ${target.banks[targetBank].slots} slots available. Instruments are not merged or truncated.")
                    continue
                }
                val name = source.system.instrumentName(source, bank, slot)
                if (name == null) {
                    issues += ConversionIssue(ConversionImpact.BLOCKER, where, "Referenced source instrument is empty.")
                    continue
                }
                sound = install(sound, blank.sound, lane.target, to, name.take(16), noise)
                allocated[identity] = to
                next[physicalBank]++
                maps[lane.source][slot] = to
                retained += bank to slot
                val kinds = kindsBySource.getOrPut(bank to slot) { linkedSetOf() }
                if (kinds.isNotEmpty() && kind !in kinds) issues += ConversionIssue(ConversionImpact.CHANGE, where,
                    "Editable source slot split into separate target voices because $kind is incompatible with ${kinds.joinToString()}.")
                kinds += kind
                val targetName = target.instrumentSummary(blank.copy(sound = sound), targetBank, to) ?: kind
                issues += ConversionIssue(ConversionImpact.LOSS, where,
                    "'$name' replaced by $kind preset in target bank $targetBank slot $to ($targetName). Source timbre, envelopes, modulation, fine tuning and instrument-owned sample/wave programs are not preserved.")
                if (name.length > 16) issues += ConversionIssue(ConversionImpact.CHANGE, where, "Target instrument name shortened to '${name.take(16)}'.")
            }
        }
        if (sound is SidSound) sound = SidTables.place(null, sound)
        for (bank in source.system.banks.indices) {
            val dropped = (1..source.system.banks[bank].slots).filter { slot ->
                bank to slot !in retained && source.system.instrumentName(source, bank, slot) != null
            }
            if (dropped.isNotEmpty()) issues += ConversionIssue(ConversionImpact.LOSS, "${source.system.banks[bank].name} bank $bank",
                "Unused or unmapped source instrument slots dropped: " + dropped.joinToString { "$it '${source.system.instrumentName(source, bank, it)}'" } + ".")
        }
        issues += ConversionIssue(ConversionImpact.LOSS, "Sound resources", resourceDescription(source.sound) +
            " Source chip-wide routing, shared controls and tuning/envelope behavior are replaced by destination defaults; target preset resources are replacement audio, not converted source assets.")
        if (target.channels.indices.any { reserved(blank.sound, it) }) issues += ConversionIssue(ConversionImpact.CHANGE, "Target lanes",
            "Sample-only and external-firmware rhythm lanes remain blank: " + target.channels.indices.filter { reserved(blank.sound, it) }.joinToString { target.channels[it].name } +
                ". No unavailable rhythm ROM or empty sample bank is used as a playable replacement.")
        return ConversionVoicePlan(blank.copy(sound = sound), maps)
    }

    private fun voiceKind(sound: SystemSound, lane: Int): String = when (sound) {
        is NesSound -> "NES ${if (NesRules.isMmc5(sound.expansion, lane)) NesChip.APU else NesRules.chip(sound.expansion, lane)} ${sound.system.channels[lane].kind}"
        is SmsSound -> "SMS ${sound.layout.instrumentKind(lane)}"
        is OpnSound -> "OPN ${sound.chip.part(lane)}"
        else -> "${sound.system.id} ${sound.system.channels[lane].kind}"
    }

    /** Copies an actual self-contained audible starter, never merely the system's default slot number. */
    private fun install(sound: SystemSound, presets: SystemSound, lane: Int, slot: Int, name: String, noise: Boolean): SystemSound = when (sound) {
        is GbSound -> {
            val bank = sound.system.channels[lane].bank
            when (GbBank.entries[bank]) {
                GbBank.PULSE -> sound.copy(pulse = sound.pulse.replacing(slot, GbInstrument(name = name, kind = GbBank.PULSE)))
                GbBank.WAVE -> sound.copy(wave = sound.wave.replacing(slot, GbInstrument(name = name, kind = GbBank.WAVE)))
                GbBank.NOISE -> sound.copy(noise = sound.noise.replacing(slot, GbInstrument(name = name, kind = GbBank.NOISE)))
            }
        }
        is NesSound -> sound.copy(instruments = sound.instruments.replacing(slot,
            NesInstrument(name = name, chip = if (NesRules.isMmc5(sound.expansion, lane)) NesChip.APU else NesRules.chip(sound.expansion, lane))))
        is SidSound -> sound.copy(instruments = sound.instruments.replacing(slot, SidPresets.ALL.first().build(lane).copy(name = name, gateTimer = 1)))
        is GbaSound -> sound.copy(instruments = sound.instruments.replacing(slot,
            (presets as GbaSound).instruments[if (noise) 4 else 0].copy(name = name)))
        is AySound -> sound.withInstrument(slot, (presets as AySound).instrument(1)!!.copy(name = name))
        is CbmSound -> sound.withInstrument(slot, (presets as CbmSound).instrument(1)!!.copy(name = name))
        is Opl3Sound -> sound.withInstrument(slot, (presets as Opl3Sound).instrument(1)!!.copy(name = name))
        is OpmSound -> sound.withInstrument(slot, (presets as OpmSound).instrument(1)!!.copy(name = name))
        is OpnSound -> sound.withInstrument(slot, OpnPresets.default(sound.chip, sound.chip.part(lane)).named(name))
        is PceSound -> sound.withInstrument(slot, (presets as PceSound).instrument(1)!!.copy(name = name, kind = if (noise) PceKind.NOISE else PceKind.TONE))
        is PokeySound -> sound.withInstrument(slot, (presets as PokeySound).instrument(1)!!.copy(name = name))
        is SaaSound -> sound.withInstrument(slot, (presets as SaaSound).instrument(1)!!.copy(name = name))
        is SccSound -> sound.withInstrument(slot, (presets as SccSound).instrument(1)!!.copy(name = name))
        is SmsSound -> sound.withInstrument(slot, (presets as SmsSound).instruments.filterNotNull().first { it.kind == sound.layout.instrumentKind(lane) }.named(name))
        is TiaSound -> sound.withInstrument(slot, (presets as TiaSound).instrument(1)!!.copy(name = name))
        is VsuSound -> sound.withInstrument(slot, (presets as VsuSound).instrument(1)!!.copy(name = name))
        is WsSound -> sound.withInstrument(slot, (presets as WsSound).instrument(1)!!.copy(name = name, kind = if (noise) WsKind.NOISE else WsKind.TONE))
        is YmSound -> sound.withInstrument(slot, (presets as YmSound).instruments.filterNotNull().first {
            if (YmRules.isFm(lane)) it is YmFmInstrument else it is YmPsgInstrument
        }.named(name))
        else -> error("Unsupported sound model ${sound.system.id}")
    }

    private fun <T> List<T>.replacing(slot: Int, item: T): List<T> = toMutableList().also { it[slot - 1] = item }

    private fun resourceDescription(sound: SystemSound): String = when (sound) {
        is GbSound -> "Dropped ${sound.waves.size} source 4-bit wave tables."
        is GbaSound -> "Dropped ${sound.samples.size} PCM samples, instrument key maps, sample loops and XM envelopes."
        is NesSound -> "Dropped ${sound.samples.size} DPCM samples, ${sound.pcmSamples.size} PCM samples, DPCM/PCM key maps and expansion wave/FM/envelope programs."
        is SidSound -> "Dropped source wave, pulse, filter and speed table programs."
        is Opl3Sound -> "Dropped source FM operators and chord table."
        is YmSound -> "Dropped source FM/PSG programs, PCM sources and key maps."
        is OpnSound -> "Dropped ${sound.samples.size} ADPCM samples and source FM/SSG/rhythm programs."
        is PceSound -> "Dropped ${sound.waves.size} wave tables and ${sound.samples.size} DDA samples."
        is PokeySound -> "Dropped ${sound.samples.size} PCM samples and joined/filter timer programs."
        is CbmSound -> "Dropped ${sound.samples.size} DAC samples and source waveform programs."
        is WsSound -> "Dropped ${sound.waves.size} wave tables and ${sound.samples.size} PCM samples, including DMA/loop settings."
        is VsuSound -> "Dropped ${sound.waves.size} wave tables and the shared modulation table."
        is SccSound -> "Dropped ${sound.waves.size} wave tables and wave-sequence programs."
        is AySound -> "Dropped source tone/noise/envelope programs."
        is SaaSound -> "Dropped source tone/noise/hardware-envelope programs."
        is SmsSound -> "Dropped source PSG/FM/custom-patch/rhythm-kit programs."
        is TiaSound -> "Dropped source AUDC waveform/rate and envelope programs."
        else -> "Dropped source instrument resources."
    }

    fun note(source: Song, destination: Song, lane: ConversionLane, note: Int, instrument: Int,
             location: String, issues: MutableList<ConversionIssue>, sourceInstrument: Int = 0): Int {
        if (note == Cell.EMPTY || note == Cell.CUT) return note
        if (note < 0) {
            if (note == source.system.keyOn && destination.system.keyOn == note) return note
            issues += ConversionIssue(ConversionImpact.LOSS, location, "Key-on without retrigger is unsupported; event removed (not replaced by a new note or OFF).")
            return Cell.EMPTY
        }
        if (preserves(source.sound, destination.sound)) return note
        var pitch = note
        val src = source.sound
        val dst = destination.sound
        if (src is NesSound && lane.source == NesRules.TRIANGLE) {
            pitch -= 12
            issues += ConversionIssue(ConversionImpact.CHANGE, location, "NES triangle sounds one octave below its stored key: $note -> $pitch before target fitting.")
        }
        if (src is NesSound && NesRules.isN163(src.expansion, lane.source)) {
            val ins = src.instruments.getOrNull(sourceInstrument - 1)
            if (ins == null) issues += ConversionIssue(ConversionImpact.BLOCKER, location, "Cannot resolve inherited N163 wave length for musical pitch conversion.")
            else {
                val shift = NesRules.n163Transpose(ins.wave.size)
                pitch += shift.roundToInt()
                if (shift != 0.0) issues += ConversionIssue(ConversionImpact.CHANGE, location,
                    "N163 ${ins.wave.size}-sample wave transposes by $shift semitones; key $note -> $pitch (fractional detuning cannot be retained in a note alone).")
            }
        }
        val sourceRate = rateKey(source, lane.source, sourceInstrument)
        val targetRate = rateKey(destination, lane.target, instrument)
        if (sourceRate || targetRate) issues += ConversionIssue(ConversionImpact.LOSS, location,
            "${if (sourceRate) "Source noise/sample/raw-rate key $note is not a musical pitch" else "Musical pitch $note becomes a noise/raw-rate key"}; target key is an approximation, not a frequency/sample identity match.")
        if (dst is NesSound && lane.target == NesRules.TRIANGLE) {
            pitch += 12
            issues += ConversionIssue(ConversionImpact.CHANGE, location, "Target NES triangle key raised one octave to compensate its octave-lower hardware pitch.")
        }
        var range = destination.system.noteRange
        if (dst is NesSound) range = NesRules.lowestNote(dst.expansion, lane.target)..NesRules.highestNote(dst.expansion, lane.target)
        if (dst is CbmSound) range = CbmRules.laneRange(dst.chip, dst.video, lane.target)
        if (dst is OpnSound) range = OpnRules.lowest(dst.chip, lane.target)..range.last
        if (dst is VsuSound) range = VsuRules.noteRange(lane.target)
        if (dst is YmSound && !YmRules.isFm(lane.target) && lane.target != YmRules.NOISE) range = YmRules.PSG_LOWEST..range.last
        if (dst is SmsSound) range = when (dst.layout.kind(lane.target)) {
            SmsChannelKind.SQUARE -> SmsRules.PSG_LOWEST..range.last
            SmsChannelKind.FM, SmsChannelKind.DRUM -> range.first..SmsInstruments.FM_TOP
            SmsChannelKind.NOISE -> range
        }
        if (dst is TiaSound) dst.instrument(instrument)?.let { ins -> TiaRules.reach(dst.region, ins.keyOnWaveform())?.let { range = it } }
        val mappedKeys = destination.system.mappedNotes(destination, lane.target, instrument)
        val selected = if (mappedKeys != null) {
            if (mappedKeys.isEmpty()) {
                issues += ConversionIssue(ConversionImpact.BLOCKER, location, "Target sample instrument has no playable mapped keys.")
                return Cell.EMPTY
            }
            mappedKeys.minBy { abs(it - pitch) }
        } else pitch.coerceIn(range)
        if (selected != pitch) issues += ConversionIssue(ConversionImpact.CHANGE, location,
            "${if (mappedKeys != null) "Sample key remapped" else "Pitch clamped to target lane range ${range.first}..${range.last}"}: $pitch -> $selected.")
        if (dst is TiaSound) dst.instrument(instrument)?.let { ins ->
            TiaRules.played(dst.region, ins.keyOnWaveform(), selected)?.let { played ->
                if (abs(played.cents) > 1.0) issues += ConversionIssue(ConversionImpact.CHANGE, location,
                    "TIA divider quantization detunes target key $selected by ${TiaRules.centsText(played.cents)}.")
            }
        }
        // Audition validation supplies lane-specific restrictions absent from a system-wide note range.
        destination.system.engine.auditionProblem(destination, lane.target, selected, instrument, 0)?.let { problem ->
            issues += ConversionIssue(ConversionImpact.BLOCKER, location, "Target voice cannot play this note: $problem")
        }
        return selected
    }

    private fun rateKey(song: Song, lane: Int, slot: Int): Boolean {
        if (!song.system.channels[lane].tonal) return true
        return when (val sound = song.sound) {
            is PceSound -> sound.instrument(slot)?.kind?.let { it != PceKind.TONE } == true
            is WsSound -> sound.instrument(slot)?.kind?.let { it != WsKind.TONE } == true
            is TiaSound -> sound.instrument(slot)?.raw == true
            is CbmSound -> sound.instrument(slot)?.kind == CbmKind.SAMPLE
            is PokeySound -> sound.instrument(slot)?.kind == PokeyKind.SAMPLE
            is OpnSound -> sound.chip.part(lane) == OpnPart.ADPCM
            else -> false
        }
    }
}
