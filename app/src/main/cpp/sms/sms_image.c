/* Master System image loader and track walking (sms_image.h). */
#include "sms_image.h"

#include <string.h>

enum {
    P_SIZE = 1, P_MAGIC, P_VERSION, P_LAYOUT, P_CHANNELS, P_ORDERS, P_LOOP, P_PATTERN_LENGTH, P_ROWS, P_SPEEDS,
    P_DATA_SIZE, P_HEADER, P_INSTRUMENTS, P_ENVELOPE, P_PATCH, P_ORDER_TABLE, P_TRACK_PTR, P_COMMAND, P_TRACK_ROWS,
    P_INSTRUMENT_REF, P_JUMP, P_DELAY, P_COUNT,
};

static const char* const PROBLEMS[P_COUNT] = {
    "",
    "image size",
    "not a Master System image (magic)",
    "image version",
    "layout",
    "channel count does not match the layout",
    "order count",
    "loop order",
    "pattern length",
    "order rows",
    "speeds",
    "song data size",
    "song header",
    "instrument table",
    "instrument envelope",
    "FM patch",
    "order table",
    "track pointer",
    "track command",
    "track rows",
    "instrument reference",
    "order jump",
    "note delay",
};

const char* sms_image_problem(int code) { return code > 0 && code < P_COUNT ? PROBLEMS[code] : "unknown problem"; }

/* Bytes of each command below $20 (process_new_line.inc dispatches these through the chip's jump table; $00 is the
 * note-on entry the driver reaches with a note byte, $1F lies past the 31-entry tables). */
static const uint8_t SIZES[32] = {
    0, 1, 2, 2, 2, 2, 2, 2,  /* -, note off, instrument, volume, FM drum, noise mode, slide up, slide down */
    2, 1, 2, 1, 2, 1, 1, 1,  /* portamento, slide off, arpeggio, arpeggio off, vibrato, vibrato off, legato on/off */
    3, 1, 1, 2, 2, 2, 2, 2,  /* Game Gear pan, AY commands (skipped by the SN and OPLL tables) */
    3, 2, 2, 2, 1, 2, 1, 0,  /* AY envelope word, order jump, speed 1, speed 2, order next, note delay, note release */
};

int sms_command_size(uint8_t op) { return op >= 0x20 ? 1 : SIZES[op]; }

static int is_end_line(uint8_t op) { return op >= BJ_END_LINE && op < BJ_NOTE_ON; }

int sms_layout_channels(int layout) {
    switch (layout) {
    case SMS_LAYOUT_PSG:
    case SMS_LAYOUT_GG: return 4;
    case SMS_LAYOUT_FM: return 13;
    case SMS_LAYOUT_FM_DRUMS: return 15;
    default: return 0;
    }
}

int sms_channel_kind(int layout, int channel) {
    if (channel < 4) return SMS_KIND_SN;
    if (layout == SMS_LAYOUT_FM_DRUMS && channel >= 10) return SMS_KIND_DRUM;
    return SMS_KIND_OPLL;
}

/* [addr, addr + len) lies within the song data. */
static int in_data(const SmsImageInfo* info, uint32_t addr, uint32_t len) {
    return addr >= SMS_REGION_BASE && len <= SMS_REGION_SIZE && addr + len <= (uint32_t)SMS_REGION_BASE + info->data_size;
}

#define R(region, a) ((region)[(a) - SMS_REGION_BASE])

uint16_t sms_track(const uint8_t* region, const SmsImageInfo* info, int order, int channel) {
    const uint16_t row = sms_rd16(region + (info->order_table - SMS_REGION_BASE) + 2 * order);
    return sms_rd16(region + (row - SMS_REGION_BASE) + 2 * channel);
}

/* An envelope header and its bytes: volume (4-byte header with sustain and release length/loop) or arpeggio/extra. */
static int check_macro(const uint8_t* region, const SmsImageInfo* info, uint16_t ptr, int volume) {
    if (!in_data(info, ptr, volume ? 4 : 2)) return 0;
    const uint8_t len = R(region, ptr), loop = R(region, ptr + 1);
    if (volume) {
        const uint8_t rlen = R(region, ptr + 2), rloop = R(region, ptr + 3);
        if (len < 5 || rlen < len) return 0;
        if (loop != 0 && (loop < 4 || loop >= len)) return 0;
        if (rloop != 0 && (rloop < 4 || rloop >= rlen)) return 0;
        return in_data(info, ptr, rlen);
    }
    if (len < 3) return 0;
    if (loop != 0 && (loop < 2 || loop >= len)) return 0;
    return in_data(info, ptr, len);
}

static int check_instruments(const uint8_t* region, const SmsImageInfo* info) {
    if (!info->instruments) return 0;
    if (!in_data(info, info->instrument_table, (uint32_t)BJI_SIZE * info->instruments)) return P_INSTRUMENTS;
    for (int i = 0; i < info->instruments; ++i) {
        const uint8_t* rec = region + (info->instrument_table - SMS_REGION_BASE) + BJI_SIZE * i;
        const uint8_t flags = rec[BJI_FLAGS];
        if (flags & ~(BJ_CHAN_FLAG_ARP_MACRO | BJ_CHAN_FLAG_VOLUME_MACRO | BJ_CHAN_FLAG_EX_MACRO)) return P_INSTRUMENTS;
        if ((flags & BJ_CHAN_FLAG_VOLUME_MACRO) && !check_macro(region, info, sms_rd16(rec + BJI_VOLUME_MACRO), 1)) return P_ENVELOPE;
        if ((flags & BJ_CHAN_FLAG_ARP_MACRO) && !check_macro(region, info, sms_rd16(rec + BJI_ARP_MACRO), 0)) return P_ENVELOPE;
        if (flags & BJ_CHAN_FLAG_EX_MACRO) {
            if (rec[BJI_EX_MACRO_TYPE] != BJ_EX_PITCH && rec[BJI_EX_MACRO_TYPE] != BJ_EX_DUTY) return P_ENVELOPE;
            if (!check_macro(region, info, sms_rd16(rec + BJI_EX_MACRO), 0)) return P_ENVELOPE;
        }
    }
    return 0;
}

static const uint8_t* instrument(const uint8_t* region, const SmsImageInfo* info, int index) {
    return region + (info->instrument_table - SMS_REGION_BASE) + BJI_SIZE * index;
}

/* One (order, channel) track: exactly `rows` rows of known commands whose references are valid. */
static int check_track(const uint8_t* region, const SmsImageInfo* info, uint16_t track, int rows, int kind) {
    uint32_t a = track;
    int covered = 0;
    while (covered < rows) {
        if (!in_data(info, a, 1)) return P_TRACK_PTR;
        const uint8_t op = R(region, a);
        if (is_end_line(op)) {
            covered += 1 + (op & 0x3F);
            ++a;
            continue;
        }
        const int n = sms_command_size(op);
        if (!n) return P_COMMAND;
        if (!in_data(info, a, (uint32_t)n)) return P_TRACK_PTR;
        const uint8_t p = n > 1 ? R(region, a + 1) : 0;
        switch (op) {
        case BJ_INSTRUMENT_CHANGE:
            if (p >= info->instruments) return P_INSTRUMENT_REF;
            if (kind == SMS_KIND_OPLL && instrument(region, info, p)[BJI_FM_PRESET] == BJ_FM_CUSTOM &&
                !in_data(info, sms_rd16(instrument(region, info, p) + BJI_FM_PATCH), 8)) {
                return P_PATCH;
            }
            break;
        case BJ_FM_DRUM:
            if (kind != SMS_KIND_DRUM) break; /* skipped by the SN and OPLL tables */
            if (p >= info->instruments) return P_INSTRUMENT_REF;
            if (instrument(region, info, p)[BJI_FM_PRESET] == BJ_FM_DRUMS_FIXED &&
                !in_data(info, sms_rd16(instrument(region, info, p) + BJI_FM_PATCH), 6)) {
                return P_PATCH;
            }
            break;
        case BJ_ORDER_JUMP:
            if (p >= info->orders) return P_JUMP;
            break;
        case BJ_NOTE_DELAY:
            if (p == 0) return P_DELAY; /* the driver would wait 256 ticks */
            break;
        case BJ_SET_SPEED_1:
        case BJ_SET_SPEED_2:
            if (p == 0) return P_SPEEDS;
            break;
        default: break;
        }
        a += (uint32_t)n;
    }
    return covered == rows ? 0 : P_TRACK_ROWS;
}

int sms_load_image(SmsImage* image) {
    SmsImageInfo* info = &image->info;
    const uint8_t* b = image->bytes;
    const uint8_t* region = sms_region(image);
    memset(info, 0, sizeof *info);
    if (memcmp(b + SMS_META_MAGIC, "TTSB", 4) != 0) return P_MAGIC;
    if (b[SMS_META_VERSION] != SMS_IMAGE_VERSION) return P_VERSION;
    info->layout = b[SMS_META_LAYOUT];
    if (info->layout >= SMS_LAYOUTS) return P_LAYOUT;
    info->channels = b[SMS_META_CHANNELS];
    if (info->channels != sms_layout_channels(info->layout)) return P_CHANNELS;
    for (int c = 0; c < info->channels; ++c) info->kind[c] = (uint8_t)sms_channel_kind(info->layout, c);
    info->orders = b[SMS_META_ORDERS];
    if (info->orders < 1 || info->orders > SMS_MAX_ORDERS) return P_ORDERS;
    info->loop = b[SMS_META_LOOP];
    if (info->loop >= info->orders) return P_LOOP;
    info->pattern_length = b[SMS_META_PATTERN_LENGTH];
    if (info->pattern_length < 1 || info->pattern_length > SMS_MAX_ROWS) return P_PATTERN_LENGTH;
    for (int o = 0; o < info->orders; ++o) {
        info->rows[o] = b[SMS_META_ROWS + o];
        info->speed1[o] = b[SMS_META_SPEED1 + o];
        info->speed2[o] = b[SMS_META_SPEED2 + o];
        if (info->rows[o] < 1 || info->rows[o] > info->pattern_length) return P_ROWS;
        if (!info->speed1[o] || !info->speed2[o]) return P_SPEEDS;
    }
    info->data_size = sms_rd16(b + SMS_META_DATA_SIZE);
    if (info->data_size < BJ_HEADER_SIZE || info->data_size > SMS_REGION_SIZE) return P_DATA_SIZE;
    info->instruments = sms_rd16(b + SMS_META_INSTRUMENTS);
    if (info->instruments > SMS_MAX_INSTRUMENTS) return P_INSTRUMENTS;
    /* Banjo's song header as json2sms writes it, the call fields left to the machine. */
    if (region[BJ_MAGIC] != BJ_MAGIC_BYTE || region[BJ_CHANNEL_COUNT] != info->channels ||
        region[BJ_PATTERN_LENGTH] != info->pattern_length || region[BJ_ORDERS_LENGTH] != info->orders ||
        region[BJ_SPEED_1] != info->speed1[0] || region[BJ_SPEED_2] != info->speed2[0] ||
        region[BJ_LINE] != info->pattern_length || region[BJ_ORDER] != 0) {
        return P_HEADER;
    }
    for (int k = BJ_CHANNEL_INIT_CALL; k < BJ_HEADER_SIZE; ++k) {
        if (region[k]) return P_HEADER;
    }
    info->instrument_table = sms_rd16(region + BJ_INSTRUMENT_PTRS);
    info->order_table = sms_rd16(region + BJ_ORDER_PTRS);
    int p = check_instruments(region, info);
    if (p) return p;
    if (!in_data(info, info->order_table, 2u * (uint32_t)info->orders)) return P_ORDER_TABLE;
    for (int o = 0; o < info->orders; ++o) {
        const uint16_t row = sms_rd16(region + (info->order_table - SMS_REGION_BASE) + 2 * o);
        if (!in_data(info, row, 2u * (uint32_t)info->channels)) return P_ORDER_TABLE;
        for (int c = 0; c < info->channels; ++c) {
            p = check_track(region, info, sms_track(region, info, o, c), info->rows[o], info->kind[c]);
            if (p) return p;
        }
    }
    info->loaded = SMS_LOADED;
    return 0;
}

/* ---- track walking (loaded images) --------------------------------------------------------------------------- */

/* The command group starting at `a`: returns the address after its END_LINE and the run's extra rows in *w. */
static uint32_t group_end(const uint8_t* region, uint32_t a, int* w) {
    while (a < (uint32_t)SMS_REGION_BASE + SMS_REGION_SIZE) {
        const uint8_t op = R(region, a);
        if (is_end_line(op)) {
            *w = op & 0x3F;
            return a + 1;
        }
        const int n = sms_command_size(op);
        a += (uint32_t)(n ? n : 1);
    }
    *w = 0;
    return a;
}

SmsTrackPos sms_track_after(const uint8_t* region, uint16_t track, int consumed) {
    SmsTrackPos pos = {track, 0};
    if (consumed <= 0) return pos;
    uint32_t a = track;
    int row = 0;
    for (int guard = 0; guard < SMS_MAX_ROWS; ++guard) {
        int w;
        a = group_end(region, a, &w);
        const int last = row + w;
        if (consumed - 1 <= last) {
            pos.ptr = (uint16_t)a;
            pos.wait = (uint8_t)(last - (consumed - 1));
            return pos;
        }
        row = last + 1;
    }
    pos.ptr = (uint16_t)a;
    return pos;
}

uint16_t sms_track_row(const uint8_t* region, uint16_t track, int row) {
    uint32_t a = track;
    int first = 0;
    for (int guard = 0; guard < SMS_MAX_ROWS && first <= row; ++guard) {
        if (first == row) return (uint16_t)a;
        int w;
        a = group_end(region, a, &w);
        first += 1 + w;
    }
    return 0;
}

int sms_track_delays_before(const uint8_t* region, uint16_t track, int row, uint16_t until) {
    uint32_t a = sms_track_row(region, track, row);
    if (!a) return 0;
    int k = 0;
    while (a < until) {
        const uint8_t op = R(region, a);
        if (is_end_line(op)) break;
        if (op == BJ_NOTE_DELAY) ++k;
        const int n = sms_command_size(op);
        a += (uint32_t)(n ? n : 1);
    }
    return k;
}

uint16_t sms_track_after_delay(const uint8_t* region, uint16_t track, int row, int k) {
    uint32_t a = sms_track_row(region, track, row);
    if (!a || k < 1) return 0;
    int seen = 0;
    while (a < (uint32_t)SMS_REGION_BASE + SMS_REGION_SIZE) {
        const uint8_t op = R(region, a);
        if (is_end_line(op)) return 0;
        const int n = sms_command_size(op);
        if (op == BJ_NOTE_DELAY && ++seen == k) return (uint16_t)(a + 2);
        a += (uint32_t)(n ? n : 1);
    }
    return 0;
}

void sms_speeds_after(const uint8_t* region, const SmsImageInfo* info, int order, int rows, int* speed1, int* speed2) {
    int s1 = info->speed1[order], s2 = info->speed2[order];
    uint32_t addr[SMS_MAX_CHANNELS];
    int next[SMS_MAX_CHANNELS];
    for (int c = 0; c < info->channels; ++c) {
        addr[c] = sms_track(region, info, order, c);
        next[c] = 0;
    }
    for (int row = 0; row < rows && row < info->rows[order]; ++row) {
        for (int c = 0; c < info->channels; ++c) {
            if (next[c] != row) continue;
            uint32_t a = addr[c];
            for (;;) {
                const uint8_t op = R(region, a);
                if (is_end_line(op)) break;
                if (op == BJ_SET_SPEED_1) s1 = R(region, a + 1);
                if (op == BJ_SET_SPEED_2) s2 = R(region, a + 1);
                const int n = sms_command_size(op);
                a += (uint32_t)(n ? n : 1);
            }
            addr[c] = a + 1;
            next[c] = row + 1 + (R(region, a) & 0x3F);
        }
    }
    *speed1 = s1;
    *speed2 = s2;
}
