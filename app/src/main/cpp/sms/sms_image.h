/* Images of the Master System / Game Gear engine ("sms-banjo"): a song in the Banjo sound driver's own data format
 * (joffb/banjo 7aaf837, third_party/banjo) as a game links it, at the Z80 address it is played from, plus a small
 * header with what the engine needs to report positions and seek. Produced by the Kotlin lowering
 * (core/.../backend/SmsLowering.kt, SmsImageLayout.kt mirrors these constants); docs/AUDIO_ENGINE.md, "Master System:
 * image layout", is the contract.
 *
 *   bytes[0 .. SMS_META_SIZE)              meta: magic "TTSB", version, layout, channel and order counts, loop order,
 *                                          the driver's pattern length, data size, instrument count, then per order
 *                                          its rows and the two speeds straight play enters it with
 *   bytes[SMS_META_SIZE .. SMS_IMAGE_SIZE) the region: Z80 $4000-$BFFF. At $4000 Banjo's song header (music_state, 34
 *                                          bytes, copied by banjo_play_song) whose five call fields (init, update,
 *                                          channel mute table, song mute, stop) are 0: the machine fills them with the
 *                                          driver's routines for the layout when it loads the region. Then the order
 *                                          pointer table, the order rows (one track pointer per channel), the
 *                                          instrument table (12-byte records), envelopes, FM patches, tracks.
 *
 * sms_load_image validates everything the driver will read (pointers inside the data, command sizes, row counts,
 * instrument and order references, envelope headers), so a loaded image cannot send the Z80 outside its tables. */
#ifndef THORTRACKER_SMS_IMAGE_H_
#define THORTRACKER_SMS_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SMS_IMAGE_VERSION 1
#define SMS_META_SIZE 0x400
#define SMS_REGION_BASE 0x4000
#define SMS_REGION_SIZE 0x8000
#define SMS_IMAGE_SIZE (SMS_META_SIZE + SMS_REGION_SIZE)
#define SMS_MAX_ORDERS 255
#define SMS_MAX_ROWS 64
#define SMS_MAX_CHANNELS 15
#define SMS_MAX_INSTRUMENTS 255
#define SMS_LOADED 0x534D5342u /* info.loaded of a validated image */

/* meta offsets */
#define SMS_META_MAGIC 0          /* "TTSB" */
#define SMS_META_VERSION 4
#define SMS_META_LAYOUT 5
#define SMS_META_CHANNELS 6
#define SMS_META_ORDERS 7
#define SMS_META_LOOP 8
#define SMS_META_PATTERN_LENGTH 9
#define SMS_META_DATA_SIZE 10     /* u16 little endian: bytes of song data from $4000 */
#define SMS_META_INSTRUMENTS 12   /* u16 */
#define SMS_META_ROWS 0x020       /* [255] rows of each order, 1..pattern length */
#define SMS_META_SPEED1 0x120     /* [255] speed_1 at each order's start in straight play */
#define SMS_META_SPEED2 0x220     /* [255] speed_2 */

/* layouts: the chips and channels of a song (Kotlin SmsLayout) */
enum {
    SMS_LAYOUT_PSG = 0,      /* SN76489: Square 1-3, Noise */
    SMS_LAYOUT_GG = 1,       /* the same on a Game Gear (stereo: port $06) */
    SMS_LAYOUT_FM = 2,       /* SN76489 + YM2413: 4 + 9 channels */
    SMS_LAYOUT_FM_DRUMS = 3, /* SN76489 + YM2413 rhythm mode: 4 + 6 + 5 (bass drum, snare, tom, cymbal, hi-hat) */
    SMS_LAYOUTS = 4,
};

/* What a channel is (the driver's update routine for it). */
enum { SMS_KIND_SN = 0, SMS_KIND_OPLL = 1, SMS_KIND_DRUM = 2 };

/* Banjo's music_state (banjo_defines_wladx.inc) */
#define BJ_MAGIC 0
#define BJ_BANK 1
#define BJ_CHANNEL_COUNT 2
#define BJ_FLAGS 3
#define BJ_MASTER_VOLUME 4
#define BJ_MASTER_VOLUME_FADE 5
#define BJ_HAS_CHIPS 6
#define BJ_SFX_CHANNEL 7
#define BJ_SFX_SUBCHANNEL 8
#define BJ_TIME_BASE 9
#define BJ_SPEED_1 10
#define BJ_SPEED_2 11
#define BJ_PATTERN_LENGTH 12
#define BJ_ORDERS_LENGTH 13
#define BJ_INSTRUMENT_PTRS 14
#define BJ_ORDER_PTRS 16
#define BJ_TIC 18
#define BJ_LINE 19
#define BJ_ORDER 20
#define BJ_ORDER_JUMP 21
#define BJ_NOISE_MODE 22
#define BJ_PANNING 23
#define BJ_CHANNEL_INIT_CALL 24
#define BJ_CHANNEL_UPDATE_CALL 26
#define BJ_CHANNEL_MUTE_CALLS 28
#define BJ_SONG_MUTE 30
#define BJ_SONG_STOP 32
#define BJ_HEADER_SIZE 34
#define BJ_MAGIC_BYTE 0xBA

#define BJ_STATE_PROCESS_NEW_LINE 0x01
#define BJ_STATE_LOOP 0x02
#define BJ_STATE_ORDER_JUMP 0x08

/* Banjo's channel struct (32 bytes) */
#define BJC_FLAGS 0
#define BJC_SUBCHANNEL 1
#define BJC_PORT 2
#define BJC_EVENTS 3
#define BJC_FREQ 4
#define BJC_TARGET_FREQ 6
#define BJC_VOLUME 7
#define BJC_MIDI_NOTE 8
#define BJC_INSTRUMENT_NUM 9
#define BJC_EFFECT1 10
#define BJC_EFFECT2 11
#define BJC_EFFECT3 12
#define BJC_PATTERN_PTR 13
#define BJC_WAIT 15
#define BJC_VOLUME_MACRO_VOL 16
#define BJC_VOLUME_MACRO_POS 17
#define BJC_VOLUME_MACRO_PTR 18
#define BJC_ARP_MACRO_VAL 20
#define BJC_ARP_MACRO_POS 21
#define BJC_ARP_MACRO_PTR 22
#define BJC_EX_MACRO_VAL 24
#define BJC_EX_MACRO_POS 25
#define BJC_EX_MACRO_TYPE 26
#define BJC_EX_MACRO_PTR 27
#define BJC_GP1 29
#define BJC_GP2 30
#define BJC_GP3 31
#define BJC_SIZE 32

#define BJ_CHAN_FLAG_MUTED 0x01
#define BJ_CHAN_FLAG_NOTE_ON 0x02
#define BJ_CHAN_FLAG_ARP_MACRO 0x04
#define BJ_CHAN_FLAG_VOLUME_MACRO 0x08
#define BJ_CHAN_FLAG_EX_MACRO 0x80
#define BJ_EVENT_TIC_WAIT 0x40
#define BJ_EVENT_MACRO_RELEASE 0x80

/* Banjo's instrument record (12 bytes) */
#define BJI_FLAGS 0
#define BJI_VOLUME_MACRO 1
#define BJI_ARP_MACRO 3
#define BJI_EX_MACRO_TYPE 5
#define BJI_EX_MACRO 6
#define BJI_FM_PRESET 8
#define BJI_FM_PATCH 9
#define BJI_SIZE 12
#define BJ_EX_PITCH 0x01
#define BJ_EX_DUTY 0x20
#define BJ_FM_CUSTOM 0x00      /* fm_preset of a custom OPLL patch (8 register bytes at fm_patch) */
#define BJ_FM_DRUMS_FIXED 0xFF /* fm_preset of a fixed-pitch rhythm kit (6 bytes at fm_patch) */

/* pattern commands (process_new_line.inc and the command jump tables) */
#define BJ_NOTE_OFF 0x01
#define BJ_INSTRUMENT_CHANGE 0x02
#define BJ_FM_DRUM 0x04
#define BJ_SN_NOISE_MODE 0x05
#define BJ_SLIDE_UP 0x06
#define BJ_SLIDE_DOWN 0x07
#define BJ_SLIDE_PORTA 0x08
#define BJ_SLIDE_OFF 0x09
#define BJ_ARPEGGIO 0x0A
#define BJ_ARPEGGIO_OFF 0x0B
#define BJ_VIBRATO 0x0C
#define BJ_VIBRATO_OFF 0x0D
#define BJ_LEGATO_ON 0x0E
#define BJ_LEGATO_OFF 0x0F
#define BJ_GAME_GEAR_PAN 0x10
#define BJ_ORDER_JUMP 0x19
#define BJ_SET_SPEED_1 0x1A
#define BJ_SET_SPEED_2 0x1B
#define BJ_ORDER_NEXT 0x1C
#define BJ_NOTE_DELAY 0x1D
#define BJ_NOTE_RELEASE 0x1E
#define BJ_VOLUME 0x20   /* 0x20 | volume 0-15 */
#define BJ_END_LINE 0x40 /* 0x40 | further empty rows 0-63 */
#define BJ_NOTE_ON 0x80  /* 0x80 | note 0-127 */

typedef struct SmsImageInfo {
    uint32_t loaded; /* SMS_LOADED once validated */
    int layout, channels, orders, loop, pattern_length, instruments;
    uint16_t data_size;
    uint16_t instrument_table, order_table; /* Z80 addresses */
    uint8_t rows[SMS_MAX_ORDERS];
    uint8_t speed1[SMS_MAX_ORDERS], speed2[SMS_MAX_ORDERS];
    uint8_t kind[SMS_MAX_CHANNELS]; /* SMS_KIND_* per channel */
} SmsImageInfo;

typedef struct SmsImage {
    uint8_t bytes[SMS_IMAGE_SIZE];
    SmsImageInfo info;
} SmsImage;

/* Validates `image->bytes` and fills its info. Returns 0, or a problem code for sms_image_problem. */
int sms_load_image(SmsImage* image);
/* Why the loader rejected an image (code > 0): a static string. */
const char* sms_image_problem(int code);

/* Channels and kinds of a layout. */
int sms_layout_channels(int layout);
int sms_channel_kind(int layout, int channel);

static inline uint16_t sms_rd16(const uint8_t* p) { return (uint16_t)(p[0] | p[1] << 8); }

/* The region of an image: Z80 address a is region[a - SMS_REGION_BASE]. */
static inline const uint8_t* sms_region(const SmsImage* image) { return image->bytes + SMS_META_SIZE; }

/* Bytes of a pattern command (the opcode included), 0 for bytes the driver must not read as a command. */
int sms_command_size(uint8_t op);

/* Z80 address of the track of (order, channel) in a loaded region (order table → order row → pointer). */
uint16_t sms_track(const uint8_t* region, const SmsImageInfo* info, int order, int channel);

/* Where a channel's pointer and line wait stand once rows 0..consumed-1 of the track at `track` have been read
 * (consumed 0: its start): the next command group and the empty rows the driver still skips before it. */
typedef struct SmsTrackPos {
    uint16_t ptr;
    uint8_t wait;
} SmsTrackPos;
SmsTrackPos sms_track_after(const uint8_t* region, uint16_t track, int consumed);

/* The address of row `row`'s command group when the row starts one (0 when the row lies in a run of empty rows). */
uint16_t sms_track_row(const uint8_t* region, uint16_t track, int row);

/* NOTE_DELAY commands of row `row`'s group before address `until`. */
int sms_track_delays_before(const uint8_t* region, uint16_t track, int row, uint16_t until);

/* The address after the k-th (k >= 1) NOTE_DELAY of row `row`'s group, or 0 when it has fewer. */
uint16_t sms_track_after_delay(const uint8_t* region, uint16_t track, int row, int k);

/* speed_1/speed_2 straight play has once rows 0..rows-1 of `order` have been processed (every channel, in order). */
void sms_speeds_after(const uint8_t* region, const SmsImageInfo* info, int order, int rows, int* speed1, int* speed2);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_SMS_IMAGE_H_ */
