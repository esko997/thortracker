/* Thor Tracker's Virtual Boy VSU player (vsu_player.h). Specification: docs/AUDIO_ENGINE.md, "Virtual Boy VSU:
 * Player" and "Effects". Every rule here has a counterpart in tools/audio/vsu/vsu_ref.py; change both and the spec
 * together. */
#include "vsu_player.h"

#include <string.h>

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");

/* VSU_PERIOD_Q[f] = round(65536 * 156250 / 440 * 2^((3648 - f) / 768)), f = 0..767: octave 0 of 156250 / frequency
 * (156250 = 5 MHz / 32 samples; 3648 = A-4's pitch) in 1/65536 units, computed with 60-digit decimal arithmetic
 * (tools/audio/vsu/vsu_ref.py --check-tables recomputes it). Defined at the end of this file. */
static const uint32_t VSU_PERIOD_Q[768];

/* Vibrato: floor(127 sin(2 pi i / 64) + 0.5), OPL3's O3_SINE (generated/opl3_tables.h) copied. */
static const int8_t SINE[64] = {
       0,   12,   25,   37,   49,   60,   71,   81,   90,   98,  106,  112,  117,  122,  125,  126,
     127,  126,  125,  122,  117,  112,  106,   98,   90,   81,   71,   60,   49,   37,   25,   12,
       0,  -12,  -25,  -37,  -49,  -60,  -71,  -81,  -90,  -98, -106, -112, -117, -122, -125, -126,
    -127, -126, -125, -122, -117, -112, -106,  -98,  -90,  -81,  -71,  -60,  -49,  -37,  -25,  -12,
};

/* Locks of the row being run (-1 = none; the last of the row wins): the note keyed on that row uses them, a row
 * without a key-on writes C, N, W, M at once. They never outlive their row. */
typedef struct Locks {
    int level, length, swp, tap, wave;
} Locks;

static const Locks NO_LOCKS = {-1, -1, -1, -1, -1};

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

static int pitch_min(int lane) { return lane == VB_LANE_NOISE ? 0 : VBP_WAVE_PITCH_MIN; }

static void emit(VbPlayer* p, int reg, int val) {
    p->shadow[reg] = (uint8_t)val;
    if (p->writes < VBP_MAX_WRITES) p->write[p->writes++] = (VbWrite){(uint16_t)reg, (uint8_t)val};
}

/* A diffed write. */
static void wr(VbPlayer* p, int reg, int val) {
    if (p->shadow[reg] != (uint8_t)val) emit(p, reg, val);
}

uint32_t vbp_period_q(int f) { return VSU_PERIOD_Q[f]; }

int vbp_frequency(int lane, int pitch) {
    pitch = clamp(pitch, pitch_min(lane), VBP_PITCH_MAX);
    const int o = pitch / 768, f = pitch % 768;
    const uint32_t q = VSU_PERIOD_Q[f];
    if (lane == VB_LANE_NOISE) {
        const uint32_t unit = 1u << (16 + o);
        return 2048 - (int)((q + 5u * unit) / (10u * unit));
    }
    return 2048 - (int)((q + (1u << (15 + o))) >> (16 + o));
}

/* SxFQL then SxFQH: at key-on always (they also set the current frequency); otherwise diffed, except on Wave 5,
 * where a change rewrites both (the hardware sweep/modulation may have moved the current value). */
static void write_frequency(VbPlayer* p, int c, int pitch, int keyon) {
    const int f = vbp_frequency(c, pitch);
    const int lo = VSU_REG(c, VSU_FQL), hi = VSU_REG(c, VSU_FQH);
    if (keyon || (c == VB_LANE_WAVE5 && (p->shadow[lo] != (f & 0xFF) || p->shadow[hi] != (f >> 8)))) {
        emit(p, lo, f & 0xFF);
        emit(p, hi, f >> 8);
    } else {
        wr(p, lo, f & 0xFF);
        wr(p, hi, f >> 8);
    }
}

/* ---- notes ------------------------------------------------------------------------------------------------- */

static void key_off(VbPlayer* p, int c) {
    VbLane* l = &p->lane[c];
    if (!l->keyed) return;
    emit(p, VSU_REG(c, VSU_INT), 0);
    l->keyed = 0;
}

void vbp_key_off(VbPlayer* p, int lane) {
    p->writes = 0;
    if (lane >= 0 && lane < VB_LANES) key_off(p, lane);
}

/* Key on `note` with the lane's instrument and the row's locks. Returns 0 (nothing written) if the lane's instrument
 * is not defined, else 1. */
static int key_on(VbPlayer* p, const VsuImage* image, int c, int note, const Locks* k) {
    VbLane* l = &p->lane[c];
    const uint8_t* rec = vb_instrument(image, l->instrument);
    if (!rec) return 0;
    l->base = l->target = note * 64;
    l->slide = 0;
    l->arp_step = 0;
    l->vib_phase = 0;
    const int speed = rec[VB_I_SWEEP_SPEED];
    l->sweep = speed ? (int8_t)rec[VB_I_SWEEP_START] * 64 : 0;
    l->sweep_end = speed ? (int8_t)rec[VB_I_SWEEP_END] * 64 : 0;
    l->sweep_step = speed * 4;
    const int flags = rec[VB_I_FLAGS];
    /* 1. stereo */
    wr(p, VSU_REG(c, VSU_LRV), l->stereo);
    /* 2. frequency (also the current frequency the sweep/modulation runs from) */
    write_frequency(p, c, l->base + l->fine + l->sweep, 1);
    /* 3. envelope start (reloads the level) */
    const int level = k->level >= 0 ? k->level : rec[VB_I_LEVEL];
    emit(p, VSU_REG(c, VSU_EV0), level << 4 | ((flags & VB_FLAG_GROW) ? 8 : 0) | rec[VB_I_INTERVAL]);
    /* 4. envelope control, Wave 5's frequency modification, Noise's tap */
    int ev1 = ((flags & VB_FLAG_REPEAT) ? 2 : 0) | ((flags & VB_FLAG_ENVELOPE) ? 1 : 0);
    if (c == VB_LANE_WAVE5) {
        const int fm = rec[VB_I_FREQMOD];
        ev1 |= ((fm & VB_FM_ON) ? 0x40 : 0) | ((fm & VB_FM_REPEAT) ? 0x20 : 0) | ((fm & VB_FM_MODULATION) ? 0x10 : 0);
    }
    if (c == VB_LANE_NOISE) ev1 |= (k->tap >= 0 ? k->tap : rec[VB_I_TAP]) << 4;
    wr(p, VSU_REG(c, VSU_EV1), ev1);
    /* 5. wave */
    if (c != VB_LANE_NOISE) wr(p, VSU_REG(c, VSU_RAM), k->wave >= 0 ? k->wave : rec[VB_I_WAVE]);
    /* 6. sweep/modulation control */
    if (c == VB_LANE_WAVE5) wr(p, VSU_REG(c, VSU_SWP), k->swp >= 0 ? k->swp : rec[VB_I_SWP]);
    /* 7. start (restarts the channel's positions, dividers and noise register) */
    int autolen, length;
    if (k->length >= 0) {
        autolen = k->length > 0;
        length = autolen ? k->length - 1 : 0;
    } else {
        autolen = (flags & VB_FLAG_AUTO) != 0;
        length = rec[VB_I_LENGTH];
    }
    emit(p, VSU_REG(c, VSU_INT), 0x80 | (autolen ? 0x20 | length : 0));
    l->keyed = 1;
    l->active = 1;
    return 1;
}

/* A row that keys nothing on writes its C, N, W, M locks at once, in that order (L does nothing). */
static void write_locks(VbPlayer* p, const VsuImage* image, int c, const Locks* k) {
    if (k->level >= 0) {
        const uint8_t* rec = vb_instrument(image, p->lane[c].instrument);
        const int low = rec ? ((rec[VB_I_FLAGS] & VB_FLAG_GROW) ? 8 : 0) | rec[VB_I_INTERVAL] : 0;
        emit(p, VSU_REG(c, VSU_EV0), k->level << 4 | low);
    }
    if (k->tap >= 0) {
        const int reg = VSU_REG(c, VSU_EV1);
        wr(p, reg, (p->shadow[reg] & 0x0F) | k->tap << 4);
    }
    if (k->wave >= 0) wr(p, VSU_REG(c, VSU_RAM), k->wave);
    if (k->swp >= 0) wr(p, VSU_REG(c, VSU_SWP), k->swp);
}

static void process_row(VbPlayer* p, const VsuImage* image, int c) {
    VbLane* l = &p->lane[c];
    const VbRow* row = &l->row;
    const int has_note = row->note >= 0 && row->note < VB_NOTES;
    Locks k = NO_LOCKS;
    if (has_note) {
        l->arp = 0;
        l->fine = 0;
    }
    if (row->instrument) l->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e], x = v >> 4, y = v & 15;
        switch (row->code[e]) {
        case VB_FX_ARPEGGIO: l->arp = v; l->arp_step = 0; break;
        case VB_FX_SLIDE_UP: l->slide_mode = v ? 1 : 0; l->slide_speed = v; break;
        case VB_FX_SLIDE_DOWN: l->slide_mode = v ? 2 : 0; l->slide_speed = v; break;
        case VB_FX_PORTAMENTO: l->slide_mode = v ? 3 : 0; l->slide_speed = v; break;
        case VB_FX_VIBRATO: l->vib_speed = x; l->vib_depth = y; break;
        case VB_FX_STEREO:
            l->stereo = v;
            if (l->active) wr(p, VSU_REG(c, VSU_LRV), v);
            break;
        case VB_FX_LEVEL: k.level = v; break;
        case VB_FX_LENGTH: k.length = v; break;
        case VB_FX_SWEEPMOD: k.swp = v; break;
        case VB_FX_TAP: k.tap = v; break;
        case VB_FX_WAVE: k.wave = v - 1; break;
        case VB_FX_FINE: l->fine = v - VB_FINE_CENTRE; break;
        case VB_FX_CUT: l->cut = v; break;
        default: break; /* B, D, F, G act at the row start */
        }
    }
    int keyed_on = 0;
    if (has_note) {
        if (l->slide_mode == 3 && l->keyed) l->target = row->note * 64;
        else keyed_on = key_on(p, image, c, row->note, &k);
    }
    if (!keyed_on) write_locks(p, image, c, &k); /* before an OFF's key-off */
    if (row->note == VB_NOTE_OFF) key_off(p, c);
}

/* ---- per tick ---------------------------------------------------------------------------------------------- */

static int lane_pitch(const VbLane* l, int c) {
    int arp = 0;
    if (l->arp && l->arp_step) arp = l->arp_step == 1 ? l->arp >> 4 : l->arp & 15;
    const int vib = l->vib_depth ? (SINE[l->vib_phase] * l->vib_depth) >> 4 : 0;
    return clamp(l->base + arp * 64 + l->fine + l->slide + vib + l->sweep, pitch_min(c), VBP_PITCH_MAX);
}

static void update(VbPlayer* p, int c) {
    VbLane* l = &p->lane[c];
    if (!l->active) return;
    write_frequency(p, c, lane_pitch(l, c), 0);
    if (l->arp) l->arp_step = (l->arp_step + 1) % 3;
    if (l->slide_mode == 1) l->slide = clamp(l->slide + l->slide_speed, -6144, 6144);
    if (l->slide_mode == 2) l->slide = clamp(l->slide - l->slide_speed, -6144, 6144);
    if (l->slide_mode == 3) {
        if (l->base < l->target) l->base = clamp(l->base + l->slide_speed, 0, l->target);
        else if (l->base > l->target) l->base = clamp(l->base - l->slide_speed, l->target, VBP_PITCH_MAX);
    }
    if (l->vib_depth && l->vib_speed) l->vib_phase = (l->vib_phase + l->vib_speed) & 63;
    if (l->sweep_step) {
        if (l->sweep < l->sweep_end) l->sweep = clamp(l->sweep + l->sweep_step, l->sweep, l->sweep_end);
        else if (l->sweep > l->sweep_end) l->sweep = clamp(l->sweep - l->sweep_step, l->sweep_end, l->sweep);
    }
}

static void read_rows(VbPlayer* p, const VsuImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < VB_LANES; ++c) {
            VbCursor cur = {vb_pattern(image->bytes, p->order, c), 0};
            VbRow skip;
            for (int r = 0; r < p->row; ++r) vb_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < VB_LANES; ++c) vb_next_row(image->bytes, &p->cursor[c], &p->lane[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(VbPlayer* p, const VsuImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < VB_LANES; ++c) {
        const VbRow* row = &p->lane[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == VB_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == VB_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const VbRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == VB_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(VbPlayer* p, const VsuImage* image) {
    read_rows(p, image);
    for (int c = 0; c < VB_LANES; ++c) {
        const VbRow* row = &p->lane[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == VB_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < VB_LANES; ++c) {
        VbLane* l = &p->lane[c];
        l->cut = -1;
        l->pending = 0;
        const int g = row_delay(&l->row);
        if (g) {
            l->pending = 1;
            l->delay = g;
        } else {
            process_row(p, image, c);
        }
    }
}

/* Next row. Every arrival at the loop order's row 0 starts it at the speed sequential straight play reaches it
 * with (the loop speed rule, as the OPL3 player's). */
static void advance(VbPlayer* p, const VsuImage* image) {
    if (p->jump) {
        p->order = p->jump_order;
        p->row = p->jump_row < image->info.rows[p->order] ? p->jump_row : image->info.rows[p->order] - 1;
        p->jump = 0;
    } else if (++p->row >= image->info.rows[p->order]) {
        p->row = 0;
        if (++p->order >= image->info.orders) p->order = image->info.loop;
    }
    if (p->order == image->info.loop && p->row == 0) p->speed = image->info.start_speed[p->order];
}

void vbp_tick(VbPlayer* p, const VsuImage* image) {
    p->writes = 0;
    if (!p->audition) {
        if (p->tick == 0) {
            row_start(p, image);
        } else {
            for (int c = 0; c < VB_LANES; ++c) {
                if (p->lane[c].pending && p->lane[c].delay == p->tick) {
                    p->lane[c].pending = 0;
                    process_row(p, image, c);
                }
            }
        }
        for (int c = 0; c < VB_LANES; ++c)
            if (p->lane[c].cut == p->tick) key_off(p, c);
    }
    for (int c = 0; c < VB_LANES; ++c) update(p, c);
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
}

void vbp_reset(VbPlayer* p, const VsuImage* image) {
    memset(p, 0, sizeof *p);
    for (int c = 0; c < VB_LANES; ++c) {
        p->lane[c].cut = -1;
        p->lane[c].stereo = 0xFF;
    }
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    emit(p, VSU_SSTOP, 1);
    for (int i = 0; i < VSU_WAVES * VSU_WAVE_SAMPLES; ++i) wr(p, i, image->bytes[VB_OFF_WAVES + i]);
    for (int i = 0; i < VSU_MOD_VALUES; ++i) wr(p, VSU_MOD(i), image->bytes[VB_OFF_MOD + i]);
}

void vbp_place(VbPlayer* p, const VsuImage* image, int order) {
    vbp_reset(p, image);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

void vbp_adopt_ram(VbPlayer* p, const VsuImage* image) {
    for (int i = 0; i < VSU_WAVES * VSU_WAVE_SAMPLES; ++i) p->shadow[i] = image->bytes[VB_OFF_WAVES + i];
    for (int i = 0; i < VSU_MOD_VALUES; ++i) p->shadow[VSU_MOD(i)] = image->bytes[VB_OFF_MOD + i];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it (and
 * of the row itself once it has started). */
static int speed_at(const VsuImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    VbCursor cur[VB_LANES];
    for (int c = 0; c < VB_LANES; ++c) cur[c] = (VbCursor){vb_pattern(image->bytes, order, c), 0};
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < VB_LANES; ++c) {
            VbRow x;
            vb_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == VB_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

void vbp_relocate(VbPlayer* p, const VsuImage* old, const VsuImage* next) {
    if (p->audition) return;
    const VbInfo* info = &next->info;
    const int started = p->tick > 0;
    const int old_speed = speed_at(old, p->order, p->row, started);
    int clamped = 0;
    if (p->order >= info->orders) {
        p->order = info->orders - 1;
        p->row = info->rows[p->order] - 1;
        clamped = 1;
    } else if (p->row >= info->rows[p->order]) {
        p->row = info->rows[p->order] - 1;
        clamped = 1;
    }
    p->cursor_order = p->cursor_row = -1;
    if (clamped) {
        p->jump = 0;
        for (int c = 0; c < VB_LANES; ++c) p->lane[c].pending = 0;
        p->speed = speed_at(next, p->order, p->row, 1);
        if (!started) advance(p, next); /* the clamped row counts as played: the next row follows it */
        return;
    }
    const int new_speed = speed_at(next, p->order, p->row, started);
    if (new_speed != old_speed) p->speed = new_speed;
    if (!started) return;
    /* The row has started: its jump and any still-delayed rows come from the new data. */
    for (int c = 0; c < VB_LANES; ++c) {
        VbRow row;
        vb_read_row(next, p->order, c, p->row, &row, NULL);
        VbLane* l = &p->lane[c];
        l->row = row;
        if (l->pending) {
            const int g = row_delay(&row);
            l->pending = g >= p->tick;
            l->delay = g;
        }
    }
    derive_jump(p, next);
}

int vbp_audition(VbPlayer* p, const VsuImage* image, int lane, int note, int slot, int fine) {
    if (lane < 0 || lane >= VB_LANES || fine < -128 || fine > 127) return -1;
    if (note < (lane == VB_LANE_NOISE ? 0 : VB_WAVE_NOTE_MIN) || note >= VB_NOTES) return -1;
    if (!vb_instrument(image, slot)) return -1;
    vbp_reset(p, image);
    p->audition = 1;
    p->lane[lane].instrument = slot;
    p->lane[lane].fine = fine;
    key_on(p, image, lane, note, &NO_LOCKS);
    return 0;
}

static int lane_equal(const VbLane* a, const VbLane* b) {
    return a->instrument == b->instrument && a->active == b->active && a->keyed == b->keyed && a->base == b->base &&
           a->target == b->target && a->fine == b->fine && a->arp == b->arp && a->arp_step == b->arp_step &&
           a->slide_mode == b->slide_mode && a->slide_speed == b->slide_speed && a->slide == b->slide &&
           a->vib_speed == b->vib_speed && a->vib_depth == b->vib_depth && a->vib_phase == b->vib_phase &&
           a->sweep == b->sweep && a->sweep_end == b->sweep_end && a->sweep_step == b->sweep_step &&
           a->stereo == b->stereo;
}

int vbp_state_equal(const VbPlayer* a, const VbPlayer* b) {
    if (a->order != b->order || a->row != b->row || a->tick != b->tick || a->speed != b->speed ||
        a->audition != b->audition || memcmp(a->shadow, b->shadow, sizeof a->shadow))
        return 0;
    for (int c = 0; c < VB_LANES; ++c)
        if (!lane_equal(&a->lane[c], &b->lane[c])) return 0;
    return 1;
}

static const uint32_t VSU_PERIOD_Q[768] = {
    626238494u, 625673547u, 625109109u, 624545180u, 623981760u, 623418849u, 622856445u, 622294549u,
    621733159u, 621172276u, 620611899u, 620052028u, 619492661u, 618933799u, 618375442u, 617817588u,
    617260237u, 616703389u, 616147044u, 615591200u, 615035858u, 614481017u, 613926676u, 613372835u,
    612819494u, 612266653u, 611714310u, 611162465u, 610611118u, 610060268u, 609509916u, 608960060u,
    608410700u, 607861835u, 607313466u, 606765591u, 606218211u, 605671324u, 605124931u, 604579031u,
    604033623u, 603488707u, 602944283u, 602400350u, 601856908u, 601313956u, 600771493u, 600229520u,
    599688036u, 599147041u, 598606533u, 598066514u, 597526981u, 596987935u, 596449375u, 595911301u,
    595373713u, 594836610u, 594299991u, 593763856u, 593228205u, 592693037u, 592158352u, 591624149u,
    591090428u, 590557189u, 590024430u, 589492153u, 588960355u, 588429037u, 587898199u, 587367839u,
    586837958u, 586308555u, 585779630u, 585251181u, 584723210u, 584195714u, 583668695u, 583142151u,
    582616082u, 582090488u, 581565367u, 581040721u, 580516548u, 579992847u, 579469619u, 578946863u,
    578424579u, 577902766u, 577381424u, 576860551u, 576340149u, 575820217u, 575300753u, 574781758u,
    574263231u, 573745172u, 573227580u, 572710455u, 572193797u, 571677605u, 571161878u, 570646617u,
    570131821u, 569617489u, 569103621u, 568590216u, 568077275u, 567564796u, 567052780u, 566541226u,
    566030133u, 565519501u, 565009330u, 564499619u, 563990368u, 563481577u, 562973244u, 562465370u,
    561957954u, 561450996u, 560944495u, 560438451u, 559932864u, 559427733u, 558923057u, 558418837u,
    557915072u, 557411761u, 556908904u, 556406500u, 555904550u, 555403053u, 554902009u, 554401416u,
    553901275u, 553401585u, 552902345u, 552403557u, 551905218u, 551407328u, 550909888u, 550412897u,
    549916354u, 549420259u, 548924611u, 548429411u, 547934657u, 547440350u, 546946489u, 546453073u,
    545960102u, 545467576u, 544975494u, 544483857u, 543992662u, 543501911u, 543011603u, 542521737u,
    542032312u, 541543330u, 541054788u, 540566687u, 540079027u, 539591806u, 539105025u, 538618684u,
    538132780u, 537647316u, 537162289u, 536677700u, 536193548u, 535709832u, 535226553u, 534743710u,
    534261303u, 533779331u, 533297793u, 532816690u, 532336021u, 531855786u, 531375984u, 530896615u,
    530417678u, 529939173u, 529461100u, 528983458u, 528506247u, 528029467u, 527553116u, 527077196u,
    526601705u, 526126642u, 525652009u, 525177803u, 524704025u, 524230675u, 523757752u, 523285255u,
    522813185u, 522341540u, 521870321u, 521399527u, 520929158u, 520459213u, 519989692u, 519520595u,
    519051921u, 518583669u, 518115840u, 517648433u, 517181448u, 516714884u, 516248741u, 515783018u,
    515317716u, 514852833u, 514388370u, 513924326u, 513460700u, 512997493u, 512534703u, 512072331u,
    511610376u, 511148838u, 510687716u, 510227011u, 509766720u, 509306845u, 508847385u, 508388340u,
    507929708u, 507471491u, 507013686u, 506556295u, 506099316u, 505642750u, 505186595u, 504730852u,
    504275520u, 503820599u, 503366088u, 502911987u, 502458296u, 502005014u, 501552141u, 501099677u,
    500647621u, 500195973u, 499744732u, 499293898u, 498843471u, 498393450u, 497943835u, 497494626u,
    497045822u, 496597423u, 496149428u, 495701838u, 495254651u, 494807868u, 494361488u, 493915511u,
    493469935u, 493024762u, 492579991u, 492135620u, 491691651u, 491248082u, 490804913u, 490362144u,
    489919775u, 489477804u, 489036233u, 488595059u, 488154284u, 487713906u, 487273926u, 486834342u,
    486395155u, 485956364u, 485517969u, 485079970u, 484642365u, 484205156u, 483768341u, 483331920u,
    482895892u, 482460258u, 482025017u, 481590169u, 481155713u, 480721649u, 480287976u, 479854695u,
    479421804u, 478989304u, 478557195u, 478125475u, 477694144u, 477263203u, 476832650u, 476402486u,
    475972710u, 475543321u, 475114320u, 474685706u, 474257479u, 473829638u, 473402183u, 472975113u,
    472548429u, 472122130u, 471696215u, 471270685u, 470845538u, 470420775u, 469996395u, 469572398u,
    469148784u, 468725551u, 468302701u, 467880232u, 467458144u, 467036437u, 466615110u, 466194163u,
    465773596u, 465353409u, 464933600u, 464514171u, 464095119u, 463676446u, 463258150u, 462840232u,
    462422691u, 462005526u, 461588738u, 461172326u, 460756289u, 460340628u, 459925342u, 459510430u,
    459095893u, 458681729u, 458267940u, 457854523u, 457441480u, 457028809u, 456616510u, 456204584u,
    455793028u, 455381845u, 454971032u, 454560590u, 454150518u, 453740816u, 453331483u, 452922520u,
    452513926u, 452105700u, 451697843u, 451290354u, 450883232u, 450476478u, 450070090u, 449664069u,
    449258414u, 448853126u, 448448203u, 448043645u, 447639452u, 447235624u, 446832160u, 446429060u,
    446026324u, 445623951u, 445221941u, 444820294u, 444419009u, 444018086u, 443617524u, 443217325u,
    442817486u, 442418008u, 442018890u, 441620132u, 441221734u, 440823696u, 440426016u, 440028695u,
    439631733u, 439235129u, 438838882u, 438442993u, 438047462u, 437652287u, 437257468u, 436863006u,
    436468899u, 436075148u, 435681753u, 435288712u, 434896026u, 434503694u, 434111716u, 433720091u,
    433328820u, 432937902u, 432547336u, 432157123u, 431767262u, 431377753u, 430988595u, 430599788u,
    430211331u, 429823226u, 429435470u, 429048064u, 428661008u, 428274301u, 427887942u, 427501932u,
    427116271u, 426730957u, 426345991u, 425961372u, 425577101u, 425193176u, 424809597u, 424426364u,
    424043477u, 423660935u, 423278739u, 422896887u, 422515380u, 422134217u, 421753398u, 421372922u,
    420992790u, 420613000u, 420233553u, 419854449u, 419475686u, 419097265u, 418719186u, 418341448u,
    417964050u, 417586993u, 417210276u, 416833899u, 416457861u, 416082163u, 415706803u, 415331782u,
    414957100u, 414582755u, 414208748u, 413835079u, 413461747u, 413088751u, 412716092u, 412343769u,
    411971782u, 411600131u, 411228815u, 410857834u, 410487187u, 410116875u, 409746897u, 409377253u,
    409007942u, 408638965u, 408270320u, 407902008u, 407534028u, 407166380u, 406799064u, 406432079u,
    406065425u, 405699102u, 405333109u, 404967447u, 404602114u, 404237111u, 403872437u, 403508093u,
    403144077u, 402780389u, 402417029u, 402053998u, 401691293u, 401328916u, 400966866u, 400605143u,
    400243745u, 399882674u, 399521929u, 399161509u, 398801414u, 398441644u, 398082199u, 397723077u,
    397364280u, 397005807u, 396647656u, 396289829u, 395932325u, 395575143u, 395218284u, 394861746u,
    394505530u, 394149636u, 393794062u, 393438810u, 393083877u, 392729265u, 392374973u, 392021001u,
    391667347u, 391314013u, 390960998u, 390608301u, 390255922u, 389903861u, 389552118u, 389200692u,
    388849583u, 388498791u, 388148315u, 387798156u, 387448312u, 387098784u, 386749571u, 386400673u,
    386052090u, 385703822u, 385355868u, 385008227u, 384660900u, 384313887u, 383967187u, 383620799u,
    383274724u, 382928961u, 382583510u, 382238370u, 381893542u, 381549025u, 381204819u, 380860924u,
    380517338u, 380174063u, 379831097u, 379488441u, 379146093u, 378804055u, 378462325u, 378120904u,
    377779790u, 377438984u, 377098486u, 376758295u, 376418411u, 376078833u, 375739562u, 375400596u,
    375061937u, 374723583u, 374385534u, 374047790u, 373710351u, 373373217u, 373036386u, 372699859u,
    372363636u, 372027717u, 371692100u, 371356786u, 371021775u, 370687065u, 370352658u, 370018552u,
    369684748u, 369351245u, 369018043u, 368685141u, 368352540u, 368020239u, 367688238u, 367356536u,
    367025133u, 366694029u, 366363224u, 366032718u, 365702509u, 365372598u, 365042986u, 364713670u,
    364384651u, 364055930u, 363727505u, 363399376u, 363071543u, 362744006u, 362416764u, 362089818u,
    361763166u, 361436810u, 361110747u, 360784979u, 360459505u, 360134324u, 359809437u, 359484843u,
    359160541u, 358836532u, 358512816u, 358189391u, 357866258u, 357543417u, 357220867u, 356898608u,
    356576640u, 356254962u, 355933574u, 355612477u, 355291669u, 354971150u, 354650920u, 354330980u,
    354011328u, 353691964u, 353372889u, 353054101u, 352735601u, 352417388u, 352099463u, 351781824u,
    351464471u, 351147405u, 350830625u, 350514131u, 350197922u, 349881999u, 349566361u, 349251007u,
    348935938u, 348621153u, 348306652u, 347992434u, 347678501u, 347364850u, 347051482u, 346738398u,
    346425595u, 346113075u, 345800836u, 345488880u, 345177204u, 344865810u, 344554697u, 344243865u,
    343933312u, 343623041u, 343313048u, 343003336u, 342693903u, 342384749u, 342075874u, 341767278u,
    341458960u, 341150920u, 340843158u, 340535674u, 340228467u, 339921538u, 339614885u, 339308509u,
    339002409u, 338696585u, 338391037u, 338085765u, 337780769u, 337476047u, 337171600u, 336867428u,
    336563531u, 336259907u, 335956558u, 335653482u, 335350679u, 335048150u, 334745894u, 334443910u,
    334142199u, 333840760u, 333539592u, 333238697u, 332938073u, 332637720u, 332337638u, 332037827u,
    331738286u, 331439016u, 331140015u, 330841285u, 330542823u, 330244631u, 329946708u, 329649054u,
    329351668u, 329054551u, 328757702u, 328461120u, 328164806u, 327868759u, 327572979u, 327277467u,
    326982220u, 326687241u, 326392527u, 326098079u, 325803897u, 325509980u, 325216328u, 324922941u,
    324629819u, 324336961u, 324044368u, 323752038u, 323459972u, 323168170u, 322876631u, 322585355u,
    322294341u, 322003590u, 321713102u, 321422875u, 321132911u, 320843208u, 320553766u, 320264585u,
    319975666u, 319687007u, 319398608u, 319110469u, 318822591u, 318534972u, 318247613u, 317960512u,
    317673671u, 317387089u, 317100765u, 316814700u, 316528892u, 316243342u, 315958050u, 315673016u,
    315388238u, 315103717u, 314819454u, 314535446u, 314251695u, 313968199u, 313684960u, 313401976u,
};
