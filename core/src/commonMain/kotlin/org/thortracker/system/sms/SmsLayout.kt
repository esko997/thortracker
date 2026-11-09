package org.thortracker.system.sms

/** What a Master System channel is: one of the SN76489's three squares or its noise, a YM2413 melodic channel or a rhythm drum. */
enum class SmsChannelKind { SQUARE, NOISE, FM, DRUM }

/** The kinds of sound a Master System instrument slot holds, one bank each: PSG sounds, FM voices, rhythm kits. */
enum class SmsInstrumentKind(val bankName: String) { PSG("PSG sounds"), FM("FM voices"), KIT("Drum kits") }

/**
 * The fixed channel layouts the Banjo driver plays, one Thor Tracker system each ([SmsSystem]): the chips a song is for
 * and its channels in Banjo's order (the SN76489's four first, as json2sms lays out `CHIP_SN76489_OPLL`). [code] is the
 * image's layout byte (`sms_image.h`, `SMS_LAYOUT_*`).
 */
enum class SmsLayout(val id: String, val code: Int, val displayName: String, val fmChannels: Int, val drums: Boolean, val stereo: Boolean) {
    /** The SN76489 alone: Square 1-3 and Noise (Banjo `CHIP_SN76489`). */
    PSG("sms-psg", 0, "Master System (SN76489, Banjo)", 0, false, false),

    /** The SN76489 of a Game Gear, with its stereo register $06 (Banjo's `GAME_GEAR_PAN`). */
    GG("gg-psg", 1, "Game Gear (stereo SN76489, Banjo)", 0, false, true),

    /** SN76489 + the FM Sound Unit's YM2413 with nine melodic channels (Banjo `CHIP_SN76489_OPLL`). */
    FM("sms-fm", 2, "Master System + FM Unit (SN76489 + YM2413, Banjo)", 9, false, false),

    /** SN76489 + YM2413 in rhythm mode: six melodic channels and the five rhythm drums (`CHIP_SN76489` + `CHIP_OPLL_DRUMS`). */
    FM_DRUMS("sms-fm-drums", 3, "Master System + FM Unit, rhythm mode (Banjo)", 6, true, false);

    val fm: Boolean get() = fmChannels > 0

    val channelCount: Int get() = 4 + fmChannels + (if (drums) DRUM_NAMES.size else 0)

    /** What channel [channel] is. */
    fun kind(channel: Int): SmsChannelKind = when {
        channel < 3 -> SmsChannelKind.SQUARE
        channel == NOISE -> SmsChannelKind.NOISE
        channel < 4 + fmChannels -> SmsChannelKind.FM
        else -> SmsChannelKind.DRUM
    }

    /** The rhythm drum [channel] plays (0 bass drum, 1 snare, 2 tom, 3 top cymbal, 4 hi-hat). */
    fun drum(channel: Int): Int = channel - 4 - fmChannels

    val channelNames: List<String>
        get() = listOf("Square 1", "Square 2", "Square 3", "Noise") + (1..fmChannels).map { "FM $it" } + (if (drums) DRUM_NAMES else emptyList())

    /** Banjo's `has_chips` for the song header: BANJO_HAS_SN (1), plus BANJO_HAS_OPLL (2) with the FM unit. */
    val hasChips: Int get() = if (fm) 0x03 else 0x01

    /** The instrument kinds (banks) the layout's channels play, in bank order. */
    val kinds: List<SmsInstrumentKind>
        get() = listOfNotNull(SmsInstrumentKind.PSG, if (fm) SmsInstrumentKind.FM else null, if (drums) SmsInstrumentKind.KIT else null)

    /** The instrument kind [channel] plays. */
    fun instrumentKind(channel: Int): SmsInstrumentKind = when (kind(channel)) {
        SmsChannelKind.SQUARE, SmsChannelKind.NOISE -> SmsInstrumentKind.PSG
        SmsChannelKind.FM -> SmsInstrumentKind.FM
        SmsChannelKind.DRUM -> SmsInstrumentKind.KIT
    }

    /** The bank of [channel] (its instrument kind's index in [kinds]). */
    fun bank(channel: Int): Int = kinds.indexOf(instrumentKind(channel))

    companion object {
        const val NOISE = 3
        const val SQUARE_3 = 2
        const val FIRST_FM = 4
        val DRUM_NAMES = listOf("Bass drum", "Snare", "Tom", "Cymbal", "Hi-hat")

        fun of(code: Int): SmsLayout? = entries.firstOrNull { it.code == code }
    }
}
