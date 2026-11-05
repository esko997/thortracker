package org.thortracker.model

/** Factories and backend-aware queries for [Song]. The only backend is GB/hUGE ([BACKEND]). */
object Songs {
    const val BACKEND = "gb-huge"
    const val ROWS = 64
    const val NOTE_MAX = 71
    const val INSTRUMENT_SLOTS = 15
    const val WAVE_COUNT = 16
    const val WAVE_SAMPLES = 32
    const val WAVE_MAX = 15

    private val NOTE_NAMES = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-")

    /** hUGETracker 1.0.11 `DefaultWaves` (hugedatatypes.pas), then five additional fixed tables. */
    val DEFAULT_WAVES: List<List<Int>> = listOf(
        "0000FFFFFFFFFFFFFFFFFFFFFFFFFFFF",
        "00000000FFFFFFFFFFFFFFFFFFFFFFFF",
        "0000000000000000FFFFFFFFFFFFFFFF",
        "000000000000000000000000FFFFFFFF",
        "000112233445566778899AABBCCDDEEF",
        "FEDCBA9876543210123456789ABCDEFF",
        "7ACDDB75211368BDDC974101479CDDB8",
        "0F0F0F0F0F0F0F0F0F0F0F0F0F0F0F0F",
        "FEFCFAF8F6F4F2F0F2F4F6F8FAFCFEFF",
        "FEDDCCBBAA9988778ABDF124578ABDEE",
        "841161ED57475AADCEA31779DD200347",
        // Extra tables (not hUGETracker defaults): soft square, 6.25% pulse, falling saw, organ, half sine.
        "BBBBBBBBBBBBBBBB4444444444444444",
        "FF000000000000000000000000000000",
        "FFEEDDCCBBAA99887766554433221100",
        "89BDEEEDCBA998888777665432111246",
        "89BCDEEFFFEEDCB98888888888888888",
    ).map { row -> row.map { it.digitToInt(16) } }

    val DEFAULT_WAVE_NAMES = listOf(
        "Square 12.5%", "Square 25%", "Square 50%", "Square 75%", "Sawtooth", "Triangle", "Sine", "Toothy",
        "Triangle toothy", "Pointy", "Strange", "Soft square", "Pulse 6.25%", "Falling saw", "Organ", "Half sine",
    )

    fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(HugeRules.CHANNEL_KINDS.size, ROWS, "Pattern 00")),
        orders = listOf(0),
        pulseInstruments = defaultPulseBank(),
        waveInstruments = List(INSTRUMENT_SLOTS) { i ->
            Instrument(name = DEFAULT_WAVE_NAMES[i], kind = InstrumentKind.WAVE, wave = i)
        },
        noiseInstruments = List(INSTRUMENT_SLOTS) { i ->
            Instrument(name = "Noise ${i + 1}", kind = InstrumentKind.NOISE, volume = 15, envelopePace = if (i == 0) 0 else 1)
        },
        waves = DEFAULT_WAVES,
    )

    /** A complete looping four-channel arrangement exercising instruments, waves, cuts and effects. */
    fun demo(): Song = DemoSong.build()

    fun channelNames(song: Song): List<String> = when (song.backend) {
        BACKEND -> HugeRules.CHANNEL_NAMES
        else -> throw IllegalArgumentException("Unsupported backend \"${song.backend}\"")
    }

    fun channelKind(song: Song, channel: Int): InstrumentKind {
        channelNames(song)
        return HugeRules.channelKind(channel)
    }

    /** The 15-slot bank used by [channel]; slot n is element n-1. */
    fun instruments(song: Song, channel: Int): List<Instrument> = HugeRules.bank(song, channelKind(song, channel))

    fun blankPattern(song: Song, name: String = "Pattern"): Pattern = Pattern.blank(channelNames(song).size, ROWS, name)

    /** "C-3".."B-8", "---" for no note, "OFF" for an explicit note cut ([Cell.CUT], exported as E00). */
    fun noteName(note: Int): String = when {
        note == Cell.EMPTY -> "---"
        note == Cell.CUT -> "OFF"
        note in 0..NOTE_MAX -> NOTE_NAMES[note % 12] + (3 + note / 12)
        else -> "???"
    }

    /** Effect metadata for codes 0..F of the song's backend. */
    fun effects(song: Song): List<EffectInfo> {
        channelNames(song)
        return HugeRules.EFFECTS
    }

    /**
     * Effect codes usable on [channel]: supported effects minus those the channel can never take
     * (pitch effects 0-4, including arpeggio, on Noise; Axy on Wave). 000 ("no effect") is not an entry: the
     * editor offers it separately. [cellProblem] performs the parameter checks.
     */
    fun effectsFor(song: Song, channel: Int): List<EffectInfo> {
        val kind = channelKind(song, channel)
        return HugeRules.EFFECTS.filter { info ->
            info.supported && when (kind) {
                InstrumentKind.NOISE -> info.code !in HugeRules.NOISE_UNSAFE_PITCH_EFFECTS
                InstrumentKind.WAVE -> info.code != HugeRules.FX_VOLUME_SLIDE
                InstrumentKind.PULSE -> true
            }
        }
    }

    /** Contextual, human-readable meaning of a raw effect on [channel]. */
    fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String {
        channelNames(song)
        return HugeRules.describeEffect(song, channel, effect, param)
    }

    /**
     * Why [cell] on [channel] cannot be exported or previewed, or null if it can. Same rules as
     * [SongValidator.errors]; out-of-range values are reported too.
     */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String? {
        channelNames(song)
        SongValidator.cellRangeProblem(cell)?.let { return it }
        return HugeRules.cellProblem(song, channel, cell)
    }

    fun patternLabel(song: Song, index: Int): String {
        val name = song.patterns.getOrNull(index)?.name
        return if (name.isNullOrBlank()) "pattern $index" else "pattern $index \"$name\""
    }

    private fun defaultPulseBank(): List<Instrument> {
        val duties = listOf("12.5%", "25%", "50%", "75%")
        return List(INSTRUMENT_SLOTS) { i ->
            when {
                i < 4 -> Instrument(name = "Duty ${duties[i]}", duty = i, volume = 15)
                i < 8 -> Instrument(name = "Duty ${duties[i - 4]} plink", duty = i - 4, volume = 15, envelopePace = 1)
                else -> Instrument(name = "Pulse ${i + 1}", duty = 2, volume = 12)
            }
        }
    }
}
