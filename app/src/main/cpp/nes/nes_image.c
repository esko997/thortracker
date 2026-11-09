/* NES song image validation and walkers (see nes_image.h). */
#include "nes_image.h"

#include <string.h>

#define DEFAULT_SPEED 6 /* famistudio_music_play's speed until the first $46 */
#define VRC6_SAW 7      /* the VRC6 build's saw channel */

static uint32_t rd32(const uint8_t* p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }

static int fail(NesImage* image, const char* why, uint32_t at) {
    image->info.problem = why;
    image->info.problem_at = at;
    return -1;
}

/* ---- envelopes ---------------------------------------------------------------------------- */

/* Marks instruction starts of a well-formed envelope in `starts` (bitmap of 256); returns NULL or why it is
 * malformed. Instructions: value (>= $80), hold count ($01-$7F), loop ($00 index). The envelope ends with its
 * final loop; loops go to a value or hold of the same envelope. Duty values 0..duty_max; wave indexes 0..63
 * ($C0..$FF); 5B mixer values are FamiStudio's processed bits (tone off in bit 0, noise off in bit 3: $C0, $C1, $C8,
 * $C9), noise periods 0..31. */
static const char* envelope_shape(const uint8_t* e, int len, int kind, int duty_max, uint8_t* starts) {
    memset(starts, 0, 32);
    const int start = nm_envelope_start(kind);
    if (len < start + 2) return "envelope too short";
    int p = start, ended = 0;
    while (p < len) {
        starts[p >> 3] |= (uint8_t)(1 << (p & 7));
        const uint8_t b = e[p];
        if (b >= 0x80) {
            if (kind == NM_KIND_VOLUME && (b < 0xC0 || b > 0xCF)) return "volume envelope value outside 0..15";
            if (kind == NM_KIND_WAVE && b < 0xC0) return "wave index envelope value outside 0..63";
            if (kind == NM_KIND_DUTY && (b < 0xC0 || b > 0xC0 + duty_max)) {
                return duty_max == 3 ? "duty envelope value outside 0..3" : "duty envelope value outside 0..7";
            }
            if (kind == NM_KIND_MIXER && b != 0xC0 && b != 0xC1 && b != 0xC8 && b != 0xC9) {
                return "mixer envelope value not $C0, $C1, $C8 or $C9";
            }
            if (kind == NM_KIND_NOISE && (b < 0xC0 || b > 0xDF)) return "noise envelope value outside 0..31";
            ++p;
        } else if (b) {
            ++p;
        } else {
            if (p + 1 >= len) return "envelope loop without index";
            p += 2;
            if (p == len) ended = 1;
        }
    }
    if (!ended) return "envelope does not end with a loop";
#define IS_START(i) ((i) >= start && (i) < len && (starts[(i) >> 3] >> ((i) & 7) & 1) && e[i] != 0)
    for (p = start; p < len; ++p) {
        if ((starts[p >> 3] >> (p & 7) & 1) && e[p] == 0 && !IS_START(e[p + 1])) return "envelope loops to a non-instruction";
    }
    if (kind == NM_KIND_VOLUME && e[0] && !IS_START(e[0])) return "volume release index is not an instruction";
    if (kind == NM_KIND_WAVE && e[0] && !IS_START(e[0])) return "wave index release index is not an instruction";
    if (kind == NM_KIND_PITCH && e[0] != 0x00 && e[0] != 0x80) return "pitch envelope flag is not $00/$80";
#undef IS_START
    return NULL;
}

int nm_envelope_instruction_at(const uint8_t* env, int len, int kind, int index) {
    uint8_t starts[32];
    /* Images are validated when loaded; only the instruction starts matter here (any build's duty range). */
    if (index < 0 || index >= len || envelope_shape(env, len, kind, 7, starts)) return 0;
    return starts[index >> 3] >> (index & 7) & 1;
}

int nm_find_envelope(const NesImageInfo* info, const uint8_t* directory, uint16_t addr) {
    int lo = 0, hi = (int)info->used_count - 1;
    while (lo <= hi) {
        const int mid = (lo + hi) / 2;
        const int idx = info->used[mid];
        const uint16_t a = nm_rd16(directory + 4 * idx);
        if (a == addr) return idx;
        if (a < addr) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}

/* ---- streams ------------------------------------------------------------------------------ */

const char* nm_walk_entry(const NmView* v, uint16_t addr, int channel, NmEntry* out) {
    const NesImageInfo* info = v->info;
    const NesPlayer* player = info->player;
    const uint32_t base = player->song_base;
    const uint8_t* s = v->region;
    const uint32_t limit = base + info->size;
    const int thor = info->layout == NM_LAYOUT_THOR;
    const int tonal = nes_player_tonal(player, channel), pitched = nes_player_pitched(player, channel);
    const int vrc6 = nes_player_vrc6_channel(player, channel), fm = nes_player_vrc7_channel(player, channel);
    const int n163 = nes_player_n163_channel(player, channel), s5b = nes_player_s5b_channel(player, channel);
    const int fds = nes_player_fds_channel(player, channel), mmc5 = nes_player_mmc5_channel(player, channel);
    uint32_t a = addr;
    int delays = 0;
    out->delay_end = 0;
    out->speed = -1;
    out->instrument = -1;
    out->note = 0;
#define NEED(n) \
    if (a < base || a + (n) > limit) return "stream runs past the data"
#define BYTE(k) s[a + (k) - base]
    for (;;) {
        NEED(1);
        const uint8_t b = BYTE(0);
        if (b >= 0x80) {
            if (b & 1) { /* empty run */
                out->rows = 1 + ((b & 0x7F) >> 1);
                out->end = (uint16_t)(a + 1);
                return NULL;
            }
            if (!tonal) return "instrument on the DPCM channel";
            if (thor && ((b & 0x7F) >> 1) >= info->instruments) return "instrument beyond the table";
            out->instrument = (b & 0x7F) >> 1;
            a += 1;
            continue;
        }
        if (!tonal) {
            /* FamiStudio's DPCM rows: [$46 s] [$47 d] [$48 c] [$52 v] [$43] then mapping k as note byte k
             * (engine note k + 12; the engine plays list entry k), `$40 k+12`, `$00` stop or an empty run. */
            if (b < 0x40) {
                if (b && thor && b > info->mappings) return "DPCM note without a DPCM list entry";
                out->rows = 1;
                out->end = (uint16_t)(a + 1);
                out->note = b != 0;
                return NULL;
            }
            if (b == 0x40) {
                NEED(2);
                if (BYTE(1) < 13 || BYTE(1) > 12 + NES_IMAGE_MAX_DPCM_MAPPINGS || (thor && BYTE(1) - 12 > info->mappings)) {
                    return "DPCM note without a DPCM list entry";
                }
                out->rows = 1;
                out->end = (uint16_t)(a + 2);
                out->note = 1;
                return NULL;
            }
            if (b == 0x52) { /* DMC delta counter: $80|v now, else at the next sample start */
                NEED(2);
                a += 2;
                continue;
            }
            if (b != 0x43 && b != 0x46 && b != 0x47 && b != 0x48) return "opcode not allowed on the DPCM channel";
        } else if (b < 0x40 || b == 0x45) { /* note / stop / release */
            out->rows = 1;
            out->end = (uint16_t)(a + 1);
            out->note = b != 0 && b != 0x45;
            return NULL;
        }
        if (b >= 0x70) { /* volume track */
            a += 1;
            continue;
        }
        int size;
        switch (b) {
        case 0x40: /* extended note */
            NEED(2);
            if (BYTE(1) > 96) return "note beyond 96";
            out->rows = 1;
            out->end = (uint16_t)(a + 2);
            out->note = 1;
            return NULL;
        case 0x50: /* slide: step, from, to */
            NEED(4);
            if (channel != 3 && (BYTE(2) < 1 || BYTE(2) > 96 || BYTE(3) < 1 || BYTE(3) > 96)) return "slide note outside 1..96";
            out->rows = 1;
            out->end = (uint16_t)(a + 4);
            out->note = 1;
            return NULL;
        case 0x43: case 0x4C: case 0x4D: size = 0; break;
        case 0x4A: if (!pitched) return "vibrato opcode on a channel without pitch envelope"; size = 0; break;
        case 0x53: /* famistudio_channel_to_phase_reset_mask: the 2A03 pulses, the VRC6 channels, the FDS channel and the
                    * MMC5 pulses (VRC7, 5B: none) */
            if (n163) return "phase reset on an N163 channel is $5B";
            if (channel > 1 && !vrc6 && !fds && !mmc5) {
                return player->expansion == NES_EXPANSION_VRC6   ? "phase reset only on the pulses and the VRC6 channels"
                       : player->expansion == NES_EXPANSION_FDS  ? "phase reset only on the pulses and the FDS channel"
                       : player->expansion == NES_EXPANSION_MMC5 ? "phase reset only on the pulses and the MMC5 pulses"
                                                                 : "phase reset only on the pulses";
            }
            size = 0;
            break;
        case 0x5B: /* N163 phase reset: famistudio_phase_reset_n163 gets the channel's bit */
            if (player->expansion != NES_EXPANSION_N163) return "opcode not allowed in a row";
            if (!n163) return "N163 phase reset off the N163 channels";
            size = 0;
            break;
        case 0x5A: /* N163 release: the volume and wave-index envelopes jump to their release points */
            if (player->expansion != NES_EXPANSION_N163) return "opcode not allowed in a row";
            if (!n163) return "N163 release off the N163 channels";
            out->rows = 1;
            out->end = (uint16_t)(a + 1);
            return NULL;
        case 0x57: /* FDS mod speed word (famistudio_fds_mod_speed, overriding the instrument's until it reloads) */
            if (player->expansion != NES_EXPANSION_FDS) return "opcode not allowed in a row";
            if (!fds) return "FDS mod speed off the FDS channel";
            NEED(3);
            if (BYTE(2) > 0x0F) return "FDS mod speed above $0FFF";
            size = 2;
            break;
        case 0x58: /* FDS mod depth (famistudio_fds_mod_depth, overriding the instrument's until it reloads) */
            if (player->expansion != NES_EXPANSION_FDS) return "opcode not allowed in a row";
            if (!fds) return "FDS mod depth off the FDS channel";
            NEED(2);
            if (BYTE(1) > 0x3F) return "FDS mod depth above 63";
            size = 1;
            break;
        case 0x59: /* FDS release: the volume and wave-index envelopes jump to their release points (ends the row) */
            if (player->expansion != NES_EXPANSION_FDS) return "opcode not allowed in a row";
            if (!fds) return "FDS release off the FDS channel";
            out->rows = 1;
            out->end = (uint16_t)(a + 1);
            return NULL;
        case 0x56: /* VRC7 release: key-off, then the volume envelope's release (famistudio_chn_vrc7_trigger bit 7) */
            if (player->expansion != NES_EXPANSION_VRC7) return "opcode not allowed in a row";
            if (!fm) return "VRC7 release off the FM channels";
            out->rows = 1;
            out->end = (uint16_t)(a + 1);
            return NULL;
        case 0x46:
            NEED(2);
            if (BYTE(1) < 1 || BYTE(1) > 127) return "speed outside 1..127";
            out->speed = BYTE(1);
            size = 1;
            break;
        case 0x47:
            NEED(2);
            if (BYTE(1) > 127) return "note delay above 127";
            if (++delays > 1) return "two note delays in one row";
            out->delay_end = (uint16_t)(a + 2);
            size = 1;
            break;
        case 0x48: NEED(2); if (BYTE(1) > 127) return "cut delay above 127"; size = 1; break;
        case 0x4E: if (!pitched) return "fine pitch on a channel without pitch envelope"; size = 1; break;
        case 0x4F:
            NEED(2);
            if (channel == 2) return "duty effect on the triangle";
            if (fm) return "duty effect on a VRC7 channel"; /* famistudio_channel_to_dutycycle has 5 entries */
            if (n163) return "duty effect on an N163 channel"; /* famistudio_channel_to_dutycycle: 2A03 only */
            if (fds) return "duty effect on the FDS channel";
            if (s5b) return "duty effect on a 5B channel"; /* FamiStudio: duty only on the 2A03 pulses and noise */
            if (channel == VRC6_SAW && vrc6) return "duty effect on the VRC6 saw";
            if (vrc6 ? BYTE(1) > 7 : BYTE(1) > 3) return vrc6 ? "VRC6 duty outside 0..7" : mmc5 ? "MMC5 duty outside 0..3" : "duty outside 0..3";
            size = 1;
            break;
        case 0x51: size = 2; break;
        case 0x54:
            NEED(2);
            if (thor && BYTE(1) >= info->instruments) return "instrument beyond the table";
            out->instrument = BYTE(1);
            size = 1;
            break;
        case 0x55: /* VRC6 saw master volume: 1 - Vrc6SawMasterVolume (Full $01, Half $00, Quarter $FF) */
            if (player->expansion != NES_EXPANSION_VRC6) {
                return player->expansion == NES_EXPANSION_MMC5 ? "VRC6 saw volume opcode in an MMC5 song" : "opcode not allowed in a row";
            }
            if (channel != VRC6_SAW) return "saw master volume off the VRC6 saw";
            NEED(2);
            if (BYTE(1) != 0x00 && BYTE(1) != 0x01 && BYTE(1) != 0xFF) return "saw master volume not $00, $01 or $FF";
            size = 1;
            break;
        case 0x49: case 0x4B:
            NEED(3);
            if (b == 0x49 && !pitched) return "vibrato opcode on a channel without pitch envelope";
            if (thor) {
                const uint8_t* dir = v->directory;
                const int idx = nm_find_envelope(info, dir, (uint16_t)(BYTE(1) | BYTE(2) << 8));
                if (idx < 0) return "envelope operand is not a directory entry";
                if (dir[4 * idx + 3] != (b == 0x49 ? NM_KIND_PITCH : NM_KIND_ARPEGGIO)) return "envelope operand of the wrong kind";
            }
            size = 2;
            break;
        case 0x5E: /* 5B manual envelope period (lo, hi) for every 5B channel with a manual-period envelope */
            if (player->expansion != NES_EXPANSION_S5B) return "opcode not allowed in a row";
            if (!s5b) return "5B envelope period off the 5B channels";
            size = 2;
            break;
        case 0x5F: /* Thor Tracker: load the actual slot while keeping FDS modulation or the 5B shared period. */
            if (player->expansion == NES_EXPANSION_FDS) {
                if (!fds) return "FDS modulation-keeping instrument off the FDS channel";
            } else if (player->expansion == NES_EXPANSION_S5B) {
                if (!s5b) return "5B period-keeping instrument off the 5B channels";
            } else return "opcode not allowed in a row";
            NEED(2);
            if (BYTE(1) >= (thor ? info->instruments : NES_IMAGE_MAX_INSTRUMENTS)) return "instrument beyond the table";
            out->instrument = BYTE(1);
            size = 1;
            break;
        default: return "opcode not allowed in a row";
        }
        NEED(1 + size);
        a += 1 + (uint32_t)size;
    }
#undef BYTE
#undef NEED
}

int nm_speed_at(const NmView* v, int order, int row) {
    const NesImageInfo* info = v->info;
    const uint32_t base = info->player->song_base;
    const uint8_t* s = v->region;
    int speed = info->speed[order], last_row = -1, last_channel = -1;
    for (int c = 0; c < info->player->channels; ++c) {
        const uint8_t* e = s + (info->streams[c] + 4u * (uint32_t)order - base);
        uint16_t a = nm_rd16(e + 2);
        int at = 0;
        for (int k = 0; k < e[1] && at <= row; ++k) {
            NmEntry entry;
            if (nm_walk_entry(v, a, c, &entry)) break;
            /* The engine runs rows in order and channels in index order within a row: the last $46 wins. */
            if (entry.speed >= 0 && (at > last_row || (at == last_row && c > last_channel))) {
                speed = entry.speed;
                last_row = at;
                last_channel = c;
            }
            at += entry.rows;
            a = entry.end;
        }
    }
    return speed;
}

void nm_row_speeds(const NmView* v, int order, uint8_t speeds[NES_IMAGE_MAX_ROWS]) {
    const NesImageInfo* info = v->info;
    const uint32_t base = info->player->song_base;
    int16_t set[NES_IMAGE_MAX_ROWS];
    for (int r = 0; r < NES_IMAGE_MAX_ROWS; ++r) set[r] = -1;
    /* Channels in index order: a later channel's $46 in the same row wins, as the engine runs them. */
    for (int c = 0; c < info->player->channels; ++c) {
        const uint8_t* e = v->region + (info->streams[c] + 4u * (uint32_t)order - base);
        uint16_t a = nm_rd16(e + 2);
        int at = 0;
        for (int k = 0; k < e[1] && at < NES_IMAGE_MAX_ROWS; ++k) {
            NmEntry entry;
            if (nm_walk_entry(v, a, c, &entry)) break;
            if (entry.speed >= 0) set[at] = (int16_t)entry.speed;
            at += entry.rows;
            a = entry.end;
        }
    }
    int speed = info->speed[order];
    for (int r = 0; r < info->rows[order]; ++r) {
        if (set[r] >= 0) speed = set[r];
        speeds[r] = (uint8_t)speed;
    }
}

int nm_n163_waves(const NmView* v, uint16_t entry) {
    const uint32_t base = v->info->player->song_base;
    const int idx = nm_find_envelope(v->info, v->directory, nm_rd16(v->region + (entry - base) + 6));
    if (idx < 0 || v->directory[4 * idx + 3] != NM_KIND_WAVE) return -1;
    const uint8_t* env = v->region + (nm_rd16(v->directory + 4 * idx) - base);
    const int len = v->directory[4 * idx + 2];
    int top = 0;
    for (int p = nm_envelope_start(NM_KIND_WAVE); p < len;) {
        const uint8_t b = env[p];
        if (b >= 0xC0) { /* value: $C0 + wave index */
            if (b - 0xC0 > top) top = b - 0xC0;
            ++p;
        } else if (b) { /* hold count */
            ++p;
        } else { /* loop and its index */
            p += 2;
        }
    }
    return top + 1;
}

/* ---- loading ------------------------------------------------------------------------------ */

/* N163 expansion instrument entry at region offset `at` (its four envelope words already checked): the wave in N163
 * RAM (even position, size a multiple of 4 from 4, below the channel registers: 256 - 16 n samples), a wave table
 * entry for every index its wave-index envelope produces, each wave's size / 2 bytes inside the data, bytes 12-15
 * zero. */
static int n163_instrument(NesImage* image, const NmView* view, uint32_t at) {
    const NesImageInfo* info = &image->info;
    const NesPlayer* player = info->player;
    const uint32_t base = player->song_base, end = base + info->size;
    const uint8_t* s = nm_region(image);
    const uint8_t* e = s + at;
    const uint32_t pos = e[8], wave = e[9], ram = 256u - 16u * player->n163_channels;
    if (pos & 1) return fail(image, "N163 wave position is odd", NM_REGION_OFFSET + at + 8);
    if (wave < 4 || wave % 4) return fail(image, "N163 wave size is not a multiple of 4 from 4", NM_REGION_OFFSET + at + 9);
    if (pos + wave > ram) return fail(image, "N163 wave overlaps the channel registers", NM_REGION_OFFSET + at + 8);
    for (uint32_t k = 12; k < NM_EXP_INSTRUMENT_SIZE; ++k) {
        if (e[k]) return fail(image, "N163 instrument bytes 12-15 not zero", NM_REGION_OFFSET + at + k);
    }
    const int waves = nm_n163_waves(view, (uint16_t)(base + at));
    const uint32_t table = nm_rd16(e + 10);
    if (waves < 1 || table < base || table + 2u * (uint32_t)waves > end) {
        return fail(image, "N163 wave table outside the data", NM_REGION_OFFSET + at + 10);
    }
    for (uint32_t w = 0; w < (uint32_t)waves; ++w) {
        const uint32_t ptr = nm_rd16(s + (table - base) + 2u * w);
        if (ptr < base || ptr + wave / 2u > end) return fail(image, "N163 wave outside the data", NM_REGION_OFFSET + (table - base) + 2u * w);
    }
    return 0;
}

/* FDS expansion instrument entry at region offset `at` (its four envelope words already checked; FamiStudio
 * FamitoneMusicFile.cs writes byte 8 = mod depth << 2 | master volume, any value valid): a wave table entry for every
 * index its wave-index envelope produces, each to 64 samples 0..63 inside the data; the mod table (word 11): 32 entries
 * 0..7 inside the data; bytes 13-14 (nm_fds_manual_speed): a manual mod speed word up to $0FFF with its low byte below
 * $80, Thor Tracker's tagged low, high | $80 for one whose low byte is $80-$FF (high $80-$8F), or auto-mod
 * $80 | numerator, denominator (1..32 each); byte 15: the mod delay, any value. */
static int fds_instrument(NesImage* image, const NmView* view, uint32_t at) {
    const NesImageInfo* info = &image->info;
    const uint32_t base = info->player->song_base, end = base + info->size;
    const uint8_t* s = nm_region(image);
    const uint8_t* e = s + at;
    const int waves = nm_n163_waves(view, (uint16_t)(base + at));
    const uint32_t table = nm_rd16(e + 9);
    if (waves < 1 || table < base || table + 2u * (uint32_t)waves > end) {
        return fail(image, "FDS wave table outside the data", NM_REGION_OFFSET + at + 9);
    }
    for (uint32_t w = 0; w < (uint32_t)waves; ++w) {
        const uint32_t ptr = nm_rd16(s + (table - base) + 2u * w);
        if (ptr < base || ptr + 64u > end) return fail(image, "FDS wave outside the data", NM_REGION_OFFSET + (table - base) + 2u * w);
        for (uint32_t k = 0; k < 64; ++k) {
            if (s[ptr - base + k] > 63) return fail(image, "FDS wave sample above 63", NM_REGION_OFFSET + (ptr - base) + k);
        }
    }
    const uint32_t mod = nm_rd16(e + 11);
    if (mod < base || mod + 32u > end) return fail(image, "FDS mod table outside the data", NM_REGION_OFFSET + at + 11);
    for (uint32_t k = 0; k < 32; ++k) {
        if (s[mod - base + k] > 7) return fail(image, "FDS mod table entry above 7", NM_REGION_OFFSET + (mod - base) + k);
    }
    if (!(e[13] & 0x80)) {
        if (e[14] > 0x0F) return fail(image, "FDS mod speed above $0FFF", NM_REGION_OFFSET + at + 14);
    } else if (e[14] & 0x80) {
        if (e[14] & 0x70) return fail(image, "FDS tagged mod speed above $0FFF", NM_REGION_OFFSET + at + 14);
    } else {
        const int numer = e[13] & 0x7F, denom = e[14];
        if (numer < 1 || numer > 32) return fail(image, "FDS auto-mod numerator outside 1..32", NM_REGION_OFFSET + at + 13);
        if (denom < 1 || denom > 32) return fail(image, "FDS auto-mod denominator outside 1..32", NM_REGION_OFFSET + at + 14);
    }
    return 0;
}

static int load_raw(NesImage* image) {
    NesImageInfo* info = &image->info;
    const NesPlayer* player = info->player;
    const uint32_t base = player->song_base, channels = player->channels;
    const uint8_t* h = image->bytes;
    const uint8_t* s = nm_region(image);
    for (int i = 14; i < NES_IMAGE_HEADER_SIZE; ++i) {
        if ((i >= 18 && i <= 20) || (i >= NES_IMAGE_HEADER_SAMPLE_SIZE && i < NES_IMAGE_HEADER_SAMPLE_START + 2)) continue;
        if (h[i]) return fail(image, "reserved header byte not zero", (uint32_t)i);
    }
    for (int i = 0; i < NES_IMAGE_DIRECTORY_SIZE; ++i) {
        if (h[NES_IMAGE_HEADER_SIZE + i]) return fail(image, "raw image with an envelope directory", NES_IMAGE_HEADER_SIZE + (uint32_t)i);
    }
    /* FamiStudio header: count, instruments, [expansion instruments (VRC7, N163, FDS, 5B),] samples-4, then a channel pointer
     * per engine channel + 4 tempo bytes per song. With 5 channels the engine indexes songs with an 8-bit
     * offset, so at most 17; with more it steps the pointer itself (the count byte's limit). */
    const uint32_t head = (uint32_t)nes_player_head(player);
    const uint32_t stride = 2 * channels + 4, max_songs = channels == 5 ? 17 : 255;
    const uint32_t songs = s[0], index = h[12], size = info->size;
    if (size < head + stride || songs == 0 || songs > max_songs || index >= songs || head + stride * songs > size) {
        return fail(image, "bad FamiStudio song list", NM_REGION_OFFSET);
    }
#define INSIDE(ptr) ((ptr) >= base && (ptr) < base + size)
    if (!INSIDE(nm_rd16(s + 1))) return fail(image, "instrument pointer outside the data", NM_REGION_OFFSET + 1);
    if (player->exp_instruments && !INSIDE(nm_rd16(s + 3))) {
        return fail(image, "expansion instrument pointer outside the data", NM_REGION_OFFSET + 3);
    }
    for (uint32_t k = 0; k < songs; ++k) {
        for (uint32_t ch = 0; ch < channels; ++ch) {
            if (!INSIDE(nm_rd16(s + head + stride * k + 2 * ch))) return fail(image, "channel pointer outside the data", NM_REGION_OFFSET + head + stride * k);
        }
    }
#undef INSIDE
    const int rows = h[18], orders = h[19], loop = h[20];
    if (rows || orders || loop) {
        if (!rows || !orders || (loop != 0xFF && loop >= orders)) return fail(image, "bad raw timing", 18);
    }
    info->orders = (uint8_t)orders;
    info->loop = (uint8_t)loop;
    for (int o = 0; o < orders; ++o) info->rows[o] = (uint8_t)rows;
    info->tempo_ntsc = nm_rd16(s + head + stride * index + 2 * channels + 2);
    return 0;
}

static int load_thor(NesImage* image) {
    NesImageInfo* info = &image->info;
    const NesPlayer* player = info->player;
    const uint32_t base = player->song_base;
    const int channels = player->channels;
    const uint8_t* h = image->bytes;
    const uint8_t* dir = h + NES_IMAGE_HEADER_SIZE;
    const uint8_t* s = nm_region(image);
    const uint32_t size = info->size;
    if (h[12] != 0) return fail(image, "Thor layout: song index must be 0", 12);
    for (int i = 18; i < NES_IMAGE_HEADER_SIZE; ++i) {
        if (i >= NES_IMAGE_HEADER_SAMPLE_SIZE && i < NES_IMAGE_HEADER_SAMPLE_START + 2) continue;
        if (h[i]) return fail(image, "reserved header byte not zero", (uint32_t)i);
    }
    info->mappings = h[NES_IMAGE_HEADER_DPCM_COUNT];
    if (info->mappings > NES_IMAGE_MAX_DPCM_MAPPINGS) return fail(image, "more than 63 DPCM list entries", NES_IMAGE_HEADER_DPCM_COUNT);
    info->instruments = h[14];
    info->envelopes = nm_rd16(h + 16);
    if (info->instruments < 1 || info->instruments > NES_IMAGE_MAX_INSTRUMENTS) return fail(image, "instrument count outside 1..64", 14);
    if (info->envelopes > NES_IMAGE_MAX_ENVELOPES) return fail(image, "envelope count above the directory", 16);
    for (uint32_t i = 4u * info->envelopes; i < NES_IMAGE_DIRECTORY_SIZE; ++i) {
        if (dir[i]) return fail(image, "directory entry beyond the envelope count", NES_IMAGE_HEADER_SIZE + i);
    }
    /* Directory: used entries ascending by address, non-overlapping, well formed. */
    uint32_t prev_end = base;
    uint8_t starts[32];
    info->used_count = 0;
    for (int i = 0; i < info->envelopes; ++i) {
        const uint8_t* d = dir + 4 * i;
        const uint16_t addr = nm_rd16(d);
        const uint32_t at = NES_IMAGE_HEADER_SIZE + 4u * (uint32_t)i;
        if (!addr) {
            if (d[2] || d[3]) return fail(image, "unused directory entry not zero", at);
            continue;
        }
        const int wave_kinds = player->expansion == NES_EXPANSION_N163 || player->expansion == NES_EXPANSION_FDS;
        const int s5b_kinds = player->expansion == NES_EXPANSION_S5B;
        if (!(d[3] <= NM_KIND_PITCH || (wave_kinds && d[3] == NM_KIND_WAVE) ||
              (s5b_kinds && (d[3] == NM_KIND_MIXER || d[3] == NM_KIND_NOISE)))) {
            return fail(image, wave_kinds  ? "envelope kind outside 0..4"
                               : s5b_kinds ? "envelope kind not 0..3, 5 or 6"
                                           : "envelope kind outside 0..3",
                        at + 3);
        }
        if (d[2] < 2) return fail(image, "envelope shorter than 2 bytes", at + 2);
        if (addr < prev_end || addr + (uint32_t)d[2] > base + size) return fail(image, "envelope overlaps, is out of order or outside the data", at);
        const char* why = envelope_shape(s + (addr - base), d[2], d[3], player->duty_max, starts);
        if (why) return fail(image, why, NM_REGION_OFFSET + (addr - base));
        prev_end = addr + d[2];
        info->used[info->used_count++] = (uint16_t)i;
    }
    /* FamiStudio header: one song, the instrument table, [the expansion instrument table,] the DPCM list, a stream
     * per engine channel, tempo words. */
    const uint32_t head = (uint32_t)nes_player_head(player), tempo = head + 2u * (uint32_t)channels;
    if (size < tempo + 4 || s[0] != 1) return fail(image, "Thor layout: exactly one song", NM_REGION_OFFSET);
    const uint16_t table = nm_rd16(s + 1);
    if (table < base || table + 8u * info->instruments > base + size) return fail(image, "instrument table outside the data", NM_REGION_OFFSET + 1);
    for (int i = 0; i < info->instruments; ++i) {
        for (int k = 0; k < 4; ++k) {
            const uint32_t at = table - base + 8u * (uint32_t)i + 2u * (uint32_t)k;
            const int idx = nm_find_envelope(info, dir, nm_rd16(s + at));
            if (idx < 0) return fail(image, "instrument envelope is not a directory entry", NM_REGION_OFFSET + at);
            if (dir[4 * idx + 3] != k) return fail(image, "instrument envelope of the wrong kind", NM_REGION_OFFSET + at);
        }
    }
    /* VRC7, N163, FDS, 5B: FamiStudio's expansion instruments, as many as the 2A03 table (instrument byte i addresses
     * entry i on the expansion channels). VRC7: volume, arpeggio and pitch envelopes, the release ($20) / stop ($80)
     * override byte, the patch number << 4 (0: the custom patch) and the custom patch's 8 registers (any value). N163:
     * volume, arpeggio, pitch and wave-index envelopes, then the wave (n163_instrument). FDS: volume, arpeggio, pitch and
     * wave-index envelopes, then the waves, mod table and mod parameters (fds_instrument). 5B
     * (famistudio_set_s5b_instrument): volume, arpeggio, pitch, mixer and noise envelopes, the hardware envelope shape
     * (0 = none, else $08-$0F), the auto-period octave (-8..8) or $80 (manual), the manual period (any value), two zero
     * bytes. */
    if (player->exp_instruments) {
        const uint16_t exp = nm_rd16(s + 3);
        if (exp < base || exp + (uint32_t)NM_EXP_INSTRUMENT_SIZE * info->instruments > base + size) {
            return fail(image, "expansion instrument table outside the data", NM_REGION_OFFSET + 3);
        }
        static const uint8_t vrc7_kinds[3] = {NM_KIND_VOLUME, NM_KIND_ARPEGGIO, NM_KIND_PITCH};
        static const uint8_t wave_kinds[4] = {NM_KIND_VOLUME, NM_KIND_ARPEGGIO, NM_KIND_PITCH, NM_KIND_WAVE};
        static const uint8_t s5b_kinds[5] = {NM_KIND_VOLUME, NM_KIND_ARPEGGIO, NM_KIND_PITCH, NM_KIND_MIXER, NM_KIND_NOISE};
        const int n163 = player->expansion == NES_EXPANSION_N163, s5b = player->expansion == NES_EXPANSION_S5B;
        const int fds = player->expansion == NES_EXPANSION_FDS;
        const uint8_t* kinds = n163 || fds ? wave_kinds : s5b ? s5b_kinds : vrc7_kinds;
        const int words = n163 || fds ? 4 : s5b ? 5 : 3;
        const NmView view = nm_view(image);
        for (int i = 0; i < info->instruments; ++i) {
            const uint32_t at = exp - base + (uint32_t)NM_EXP_INSTRUMENT_SIZE * (uint32_t)i;
            for (int k = 0; k < words; ++k) {
                const int idx = nm_find_envelope(info, dir, nm_rd16(s + at + 2u * (uint32_t)k));
                if (idx < 0) return fail(image, "expansion instrument envelope is not a directory entry", NM_REGION_OFFSET + at + 2u * (uint32_t)k);
                if (dir[4 * idx + 3] != kinds[k]) return fail(image, "expansion instrument envelope of the wrong kind", NM_REGION_OFFSET + at + 2u * (uint32_t)k);
            }
            if (n163) {
                if (n163_instrument(image, &view, at)) return -1;
            } else if (fds) {
                if (fds_instrument(image, &view, at)) return -1;
            } else if (s5b) {
                const uint8_t shape = s[at + 10];
                const int octave = (int8_t)s[at + 11];
                if (shape && (shape < 0x08 || shape > 0x0F)) return fail(image, "5B envelope shape not 0 or $08-$0F", NM_REGION_OFFSET + at + 10);
                if (s[at + 11] != 0x80 && (octave < -8 || octave > 8)) {
                    return fail(image, "5B auto-period octave outside -8..8 and not $80", NM_REGION_OFFSET + at + 11);
                }
                if (s[at + 14] || s[at + 15]) return fail(image, "5B expansion instrument bytes 14-15 not zero", NM_REGION_OFFSET + at + 14);
            } else {
                if (s[at + 6] & ~0xA0) return fail(image, "VRC7 override byte not a combination of $20 and $80", NM_REGION_OFFSET + at + 6);
                if (s[at + 7] & 0x0F) return fail(image, "VRC7 patch byte not patch << 4", NM_REGION_OFFSET + at + 7);
            }
        }
        info->exp_instruments = exp;
    }
    info->tempo_ntsc = nm_rd16(s + tempo + 2);
    /* DPCM list: `mappings` FamiStudio entries {$4012, $4013, $4010, $4011} in the data (the header word
     * points 4 bytes before entry 1), each sample inside the used sample bytes. */
    if (info->mappings) {
        const uint32_t list = (uint32_t)nm_rd16(s + head - 2) + 4u;
        if (list < base || list + 4u * info->mappings > base + size) {
            return fail(image, "DPCM list outside the data", NM_REGION_OFFSET + head - 2);
        }
        info->dpcm_list = (uint16_t)list;
        for (uint32_t k = 0; k < info->mappings; ++k) {
            const uint32_t at = list - base + 4u * k;
            const uint8_t* e = s + at;
            if (e[2] & 0xB0) return fail(image, "DPCM entry with IRQ or unused $4010 bits", NM_REGION_OFFSET + at + 2);
            if (e[3] > 127) return fail(image, "DPCM entry initial level above 127", NM_REGION_OFFSET + at + 3);
            const uint32_t from = NES_SAMPLE_BASE + 64u * e[0], to = from + 16u * e[1] + 1u;
            if (from < info->sample_start || to > (uint32_t)info->sample_start + info->samples) {
                return fail(image, "DPCM sample outside the sample block", NM_REGION_OFFSET + at);
            }
        }
    }
    /* Order streams: `$41 n pattern` per order, then `$42` to the loop order's entry. */
    int orders = -1, loop = -1;
    for (int c = 0; c < channels; ++c) {
        const uint16_t first = nm_rd16(s + head + 2 * c);
        info->streams[c] = first;
        int o = 0;
        for (;; ++o) {
            const uint32_t a = first + 4u * (uint32_t)o;
            if (a < base || a + 3 > base + size) return fail(image, "order stream outside the data", NM_REGION_OFFSET + head + 2 * c);
            const uint8_t* e = s + (a - base);
            if (e[0] == 0x42) {
                const uint16_t target = nm_rd16(e + 1);
                if (target < first || (target - first) % 4 || (target - first) / 4 >= (uint32_t)o) return fail(image, "order stream loop is not an order entry", a - base + NM_REGION_OFFSET);
                if (o == 0) return fail(image, "no orders", a - base + NM_REGION_OFFSET);
                if (orders >= 0 && (o != orders || (int)(target - first) / 4 != loop)) return fail(image, "channels disagree on orders or loop", a - base + NM_REGION_OFFSET);
                orders = o;
                loop = (int)(target - first) / 4;
                break;
            }
            if (e[0] != 0x41 || a + 4 > base + size) return fail(image, "order stream entry is not $41 n address", a - base + NM_REGION_OFFSET);
            if (o >= NES_IMAGE_MAX_ORDERS) return fail(image, "more than 255 orders", a - base + NM_REGION_OFFSET);
        }
    }
    info->orders = (uint8_t)orders;
    info->loop = (uint8_t)loop;
    /* Patterns: n entries each, the same row count on every channel; speed at each order start. */
    const NmView view = nm_view(image);
    uint8_t speed = DEFAULT_SPEED;
    for (int o = 0; o < orders; ++o) {
        info->speed[o] = speed;
        int order_rows = -1;
        for (int c = 0; c < channels; ++c) {
            const uint8_t* e = s + (info->streams[c] + 4u * (uint32_t)o - base);
            const int n = e[1];
            uint16_t a = nm_rd16(e + 2);
            if (!n) return fail(image, "pattern with zero entries", (uint32_t)(e - h) + 1);
            int rows = 0;
            for (int k = 0; k < n; ++k) {
                NmEntry entry;
                const char* why = nm_walk_entry(&view, a, c, &entry);
                if (why) return fail(image, why, a >= base ? NM_REGION_OFFSET + (uint32_t)(a - base) : (uint32_t)(e - h) + 2);
                rows += entry.rows;
                a = entry.end;
            }
            if (rows > NES_IMAGE_MAX_ROWS) return fail(image, "order longer than 255 rows", (uint32_t)(e - h));
            if (order_rows >= 0 && rows != order_rows) return fail(image, "channels disagree on the order's rows", (uint32_t)(e - h));
            order_rows = rows;
        }
        info->rows[o] = (uint8_t)order_rows;
        speed = (uint8_t)nm_speed_at(&view, o, order_rows - 1);
    }
    return 0;
}

int nm_load_image(NesImage* image, const NesPlayer* player) {
    NesImageInfo* info = &image->info;
    memset(info, 0, sizeof *info);
    info->player = player;
    const uint32_t base = player->song_base;
    const uint8_t* h = image->bytes;
    if (rd32(h) != NM_MAGIC) return fail(image, "not a TTNS image", 0);
    if (rd32(h + 4) != player->id) return fail(image, "lowered for another player build", 4);
    if (nm_rd16(h + 8) != base) return fail(image, "song base is not the player's NES_SONG_BASE", 8);
    /* One region: song data from the song base up, the sample block (if any) above it; zero elsewhere. */
    info->size = nm_rd16(h + 10);
    if (info->size > player->region_size) return fail(image, "data larger than the region", 10);
    info->samples = nm_rd16(h + NES_IMAGE_HEADER_SAMPLE_SIZE);
    info->sample_start = nm_rd16(h + NES_IMAGE_HEADER_SAMPLE_START);
    const uint32_t start = info->sample_start, end = start + info->samples;
    if (!info->samples != !start) return fail(image, "sample block size and start must both be 0 or both set", NES_IMAGE_HEADER_SAMPLE_SIZE);
    if (start && (start < NES_SAMPLE_BASE || start % NES_IMAGE_SAMPLE_ALIGN || end > NES_REGION_END)) {
        return fail(image, "sample block not 64-aligned within $C000..the vectors", NES_IMAGE_HEADER_SAMPLE_START);
    }
    if (start && base + (uint32_t)info->size > start) return fail(image, "song data overlaps the samples", 10);
    const uint8_t* s = nm_region(image);
    for (uint32_t a = base + info->size; a < NES_REGION_END; ++a) {
        if (a == start) a = end;
        if (a < NES_REGION_END && s[a - base]) {
            return fail(image, "region not zero outside the song data and the samples", NM_REGION_OFFSET + a - base);
        }
    }
    info->layout = h[13];
    int r;
    if (info->layout == NM_LAYOUT_THOR) {
        r = load_thor(image);
    } else if (info->layout == NM_LAYOUT_RAW) {
        r = load_raw(image);
    } else {
        r = fail(image, "unknown layout", 13);
    }
    if (r) return r;
    info->loaded = NM_LOADED;
    return 0;
}

void nm_build_raw_image(NesImage* image, const NesPlayer* player, const uint8_t* song, uint32_t size, const uint8_t* samples,
                        uint32_t sample_size, int song_index, int rows_per_order, int order_count, int loop_order) {
    uint8_t* h = image->bytes;
    const uint32_t base = player->song_base;
    memset(image, 0, sizeof *image);
    const uint32_t words[2] = {NM_MAGIC, player->id};
    for (int w = 0; w < 2; ++w) {
        for (int i = 0; i < 4; ++i) h[4 * w + i] = (uint8_t)(words[w] >> (8 * i));
    }
    h[8] = (uint8_t)base;
    h[9] = (uint8_t)(base >> 8);
    if (size > player->region_size) size = player->region_size;
    h[10] = (uint8_t)size;
    h[11] = (uint8_t)(size >> 8);
    h[12] = (uint8_t)song_index;
    h[13] = NM_LAYOUT_RAW;
    h[18] = (uint8_t)rows_per_order;
    h[19] = (uint8_t)order_count;
    h[20] = (uint8_t)loop_order;
    memcpy(h + NM_REGION_OFFSET, song, size);
    if (sample_size > NES_REGION_END - NES_SAMPLE_BASE) sample_size = NES_REGION_END - NES_SAMPLE_BASE;
    if (sample_size) {
        h[NES_IMAGE_HEADER_SAMPLE_SIZE] = (uint8_t)sample_size;
        h[NES_IMAGE_HEADER_SAMPLE_SIZE + 1] = (uint8_t)(sample_size >> 8);
        h[NES_IMAGE_HEADER_SAMPLE_START] = (uint8_t)NES_SAMPLE_BASE;
        h[NES_IMAGE_HEADER_SAMPLE_START + 1] = (uint8_t)(NES_SAMPLE_BASE >> 8);
        memcpy(h + NM_REGION_OFFSET + (NES_SAMPLE_BASE - base), samples, sample_size);
    }
}
