package org.thortracker.backend

/**
 * The Master System engine's image ("sms-banjo", `app/src/main/cpp/sms/sms_image.h` mirrors these numbers): a meta
 * block, then the Z80 region $4000-$BFFF holding a song in the Banjo driver's own format (joffb/banjo [BANJO_COMMIT],
 * `third_party/banjo`) exactly as a game links it, its five call fields left 0 for the machine. Contract:
 * docs/AUDIO_ENGINE.md, "Master System: image layout". Banjo's constants are its `banjo_defines_wladx.inc`.
 */
object SmsImageLayout {
    const val ENGINE_ID = "sms-banjo"
    const val BANJO_COMMIT = "7aaf837ebbe67e567e019b5819b5467bf62253ad"
    const val MAGIC = "TTSB"
    const val VERSION = 1
    const val META_SIZE = 0x400
    const val REGION_BASE = 0x4000
    const val REGION_SIZE = 0x8000
    const val IMAGE_SIZE = META_SIZE + REGION_SIZE

    /** One SMS mapper bank: songs larger than this cannot sit in a single swappable ROM slot. */
    const val BANK_SIZE = 0x4000

    const val MAX_ORDERS = 255
    const val MAX_ROWS = 64
    const val MAX_INSTRUMENTS = 255

    // meta offsets
    const val M_MAGIC = 0
    const val M_VERSION = 4
    const val M_LAYOUT = 5
    const val M_CHANNELS = 6
    const val M_ORDERS = 7
    const val M_LOOP = 8
    const val M_PATTERN_LENGTH = 9
    const val M_DATA_SIZE = 10
    const val M_INSTRUMENTS = 12
    const val M_ROWS = 0x020
    const val M_SPEED1 = 0x120
    const val M_SPEED2 = 0x220

    /** NTSC Master System / Game Gear: Z80, SN76489 and YM2413 at 3579545 Hz, frames of 228 × 262 cycles. */
    const val CLOCK = 3579545
    const val CYCLES_PER_TICK = 59736
    val TICK_HZ: Double = CLOCK.toDouble() / CYCLES_PER_TICK

    /** Seek budget (about 3.3 minutes); the last 60 ticks are clocked. */
    const val SEEK_TICKS = 12000

    // Banjo's music_state (34 bytes; the song header json2sms writes)
    const val BJ_HEADER_SIZE = 34
    const val BJ_MAGIC_BYTE = 0xBA
    const val BJ_FLAG_LOOP = 0x02
    const val BJ_CHANNEL_INIT_CALL = 24

    /** Bytes of an instrument record: flags, volume/arpeggio macro pointers, extra macro type and pointer, FM preset, FM patch pointer, padding. */
    const val INSTRUMENT_SIZE = 12

    // instrument macro flags (CHAN_FLAG_*) and extra macro types (MACRO_TYPE_*)
    const val FLAG_ARP_MACRO = 0x04
    const val FLAG_VOLUME_MACRO = 0x08
    const val FLAG_EX_MACRO = 0x80
    const val EX_PITCH = 0x01
    const val EX_DUTY = 0x20
    const val FM_CUSTOM = 0x00
    const val FM_DRUMS_FIXED = 0xFF

    /** The fm_preset of a slot nothing names (never played; any preset, not the custom voice). */
    const val FM_UNUSED = 0x10

    // pattern commands
    const val NOTE_OFF = 0x01
    const val INSTRUMENT_CHANGE = 0x02
    const val FM_DRUM = 0x04
    const val SN_NOISE_MODE = 0x05
    const val SLIDE_UP = 0x06
    const val SLIDE_DOWN = 0x07
    const val SLIDE_PORTA = 0x08
    const val SLIDE_OFF = 0x09
    const val ARPEGGIO = 0x0A
    const val ARPEGGIO_OFF = 0x0B
    const val VIBRATO = 0x0C
    const val VIBRATO_OFF = 0x0D
    const val LEGATO_ON = 0x0E
    const val LEGATO_OFF = 0x0F
    const val GAME_GEAR_PAN = 0x10
    const val ORDER_JUMP = 0x19
    const val SET_SPEED_1 = 0x1A
    const val SET_SPEED_2 = 0x1B
    const val NOTE_DELAY = 0x1D
    const val NOTE_RELEASE = 0x1E
    const val VOLUME = 0x20
    const val END_LINE = 0x40
    const val NOTE_ON = 0x80

    /** Most extra empty rows one END_LINE carries. */
    const val MAX_WAIT = 0x3F

    /** Banjo's YM2413 F-numbers of C..B at the semitone (fnums_fm.inc, first column; block = note / 12). */
    val FM_FNUMS = intArrayOf(0xAC, 0xB7, 0xC2, 0xCD, 0xD9, 0xE6, 0xF4, 0x102, 0x112, 0x122, 0x133, 0x146)

    /** Banjo's SN76489 tone periods from A to G# at the semitone (fnums_sn.inc, first column; halved per octave). */
    val SN_PERIODS = intArrayOf(0x3F9, 0x3C0, 0x38A, 0x357, 0x327, 0x2FA, 0x2CF, 0x2A7, 0x281, 0x25D, 0x23B, 0x21B)

    /** The SN76489 note byte Banjo plays absolute note [note] with: its table starts at A-2 = byte 9 (A-2 is absolute 33). */
    const val SN_NOTE_OFFSET = 24
}
