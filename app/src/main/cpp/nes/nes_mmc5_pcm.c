/* Thor Tracker's MMC5 PCM lane: extension validation and the software player (see nes_mmc5_pcm.h for the layout, the
 * timing and what this is not). */
#include "nes_mmc5_pcm.h"

#include <string.h>

static uint16_t rd16(const uint8_t* p) { return (uint16_t)(p[0] | p[1] << 8); }
static uint32_t rd32(const uint8_t* p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }

static const uint8_t* order_entry(const uint8_t* e, int o) { return e + NES_PCM_ORDERS_AT + NES_PCM_ORDER_SIZE * o; }
static const uint8_t* sample_entry(const uint8_t* e, int s) { return e + NES_PCM_SAMPLES_AT + NES_PCM_SAMPLE_SIZE * s; }
static const uint8_t* mapping_entry(const uint8_t* e, int m) { return e + NES_PCM_MAPPINGS_AT + NES_PCM_MAPPING_SIZE * m; }
static const uint8_t* event_entry(const uint8_t* e, int i) { return e + NES_PCM_EVENTS_AT + NES_PCM_EVENT_SIZE * i; }

static int ev_volume(const uint8_t* ev) { return rd16(ev + 4) & 15; }
static int ev_delay(const uint8_t* ev) { return rd16(ev + 4) >> 4 & 31; }
static int ev_cut(const uint8_t* ev) { return rd16(ev + 4) >> 9 & 31; }

/* ---- validation --------------------------------------------------------------------------- */

static int fail(NesPcmImage* image, const char* why, uint32_t at) {
    image->info.problem = why;
    image->info.problem_at = at;
    return -1;
}

static int zero(const uint8_t* p, uint32_t n) {
    for (uint32_t i = 0; i < n; ++i) {
        if (p[i]) return 0;
    }
    return 1;
}

int nes_pcm_load(NesPcmImage* image, const NesImageInfo* base) {
    NesPcmInfo* info = &image->info;
    const uint8_t* e = image->bytes;
    memset(info, 0, sizeof *info);
    if (rd32(e) != NES_PCM_MAGIC) return fail(image, "not a TTPC PCM extension", 0);
    if (e[4] != NES_PCM_VERSION) return fail(image, "PCM extension version is not 1", 4);
    if (e[15]) return fail(image, "PCM extension header byte 15 not zero", 15);
    info->samples = e[5];
    info->mappings = rd16(e + 6);
    info->data = rd32(e + 8);
    info->events = rd16(e + 12);
    info->orders = e[14];
    if (info->samples > NES_PCM_MAX_SAMPLES) return fail(image, "more than 64 PCM samples", 5);
    if (info->mappings > NES_PCM_MAX_MAPPINGS) return fail(image, "more than 6144 PCM mappings", 6);
    if (info->data > NES_PCM_MAX_DATA) return fail(image, "more than 256 KiB of PCM sample data", 8);
    if (info->events > NES_PCM_MAX_EVENTS) return fail(image, "more than 16320 PCM events", 12);
    if (!base || base->loaded != NM_LOADED) return fail(image, "no loaded FamiStudio image", 0);
    if (base->layout != NM_LAYOUT_THOR ? info->orders || info->events : info->orders != base->orders) {
        return fail(image, "PCM order count is not the FamiStudio image's (0 for a raw image)", 14);
    }
    /* Orders: each a range of events, rows ascending and inside the order. */
    for (int o = 0; o < NES_PCM_MAX_ORDERS; ++o) {
        const uint8_t* oe = order_entry(e, o);
        const uint32_t at = (uint32_t)(oe - e);
        const uint32_t first = rd16(oe), count = rd16(oe + 2);
        if (o >= info->orders) {
            if (first || count) return fail(image, "PCM order entry beyond the order count", at);
            continue;
        }
        if (first + count > info->events) return fail(image, "PCM order events outside the event table", at);
        int prev = -1;
        for (uint32_t k = 0; k < count; ++k) {
            const int row = event_entry(e, (int)(first + k))[0];
            if (row <= prev || row >= base->rows[o]) return fail(image, "PCM events of an order not in ascending rows inside it", at);
            prev = row;
        }
    }
    /* Samples inside the data. */
    for (int s = 0; s < NES_PCM_MAX_SAMPLES; ++s) {
        const uint8_t* se = sample_entry(e, s);
        const uint32_t at = (uint32_t)(se - e);
        if (s >= info->samples) {
            if (!zero(se, NES_PCM_SAMPLE_SIZE)) return fail(image, "PCM sample entry beyond the sample count", at);
            continue;
        }
        const uint32_t offset = rd32(se), length = rd32(se + 4), loop = rd32(se + 8);
        if (!length || offset > info->data || length > info->data - offset) return fail(image, "PCM sample outside the data", at);
        if (loop >= length) return fail(image, "PCM loop start not inside the sample", at + 8);
    }
    /* Mappings: an existing sample, the loop flag, its binding (ascending: unique), a period in range. */
    int prev_binding = -1;
    for (int m = 0; m < NES_PCM_MAX_MAPPINGS; ++m) {
        const uint8_t* me = mapping_entry(e, m);
        const uint32_t at = (uint32_t)(me - e);
        if (m >= info->mappings) {
            if (!zero(me, NES_PCM_MAPPING_SIZE)) return fail(image, "PCM mapping entry beyond the mapping count", at);
            continue;
        }
        if (me[0] >= info->samples) return fail(image, "PCM mapping of a missing sample", at);
        if (me[1] & ~1u) return fail(image, "PCM mapping flags other than loop", at + 1);
        const int binding = rd16(me + 2);
        if (binding >= NES_PCM_BINDINGS) return fail(image, "PCM mapping binding outside 64 slots x 96 keys", at + 2);
        if (binding <= prev_binding) return fail(image, "PCM mapping bindings not strictly ascending (unique)", at + 2);
        prev_binding = binding;
        const uint32_t period = rd32(me + 4);
        if (period < NES_PCM_PERIOD_MIN || period > NES_PCM_PERIOD_MAX) return fail(image, "PCM mapping period outside 8..32768 cycles", at + 4);
    }
    /* Events. */
    for (int i = 0; i < NES_PCM_MAX_EVENTS; ++i) {
        const uint8_t* ev = event_entry(e, i);
        const uint32_t at = (uint32_t)(ev - e);
        if (i >= info->events) {
            if (!zero(ev, NES_PCM_EVENT_SIZE)) return fail(image, "PCM event beyond the event count", at);
            continue;
        }
        const uint8_t flags = ev[1];
        const uint16_t mapping = rd16(ev + 2), packed = rd16(ev + 4);
        const int note = flags & NES_PCM_EV_NOTE, stop = flags & NES_PCM_EV_STOP;
        if (!flags || (flags & ~0x1Fu) || (note && stop)) return fail(image, "PCM event flags invalid", at + 1);
        if (note ? mapping >= info->mappings : mapping != 0) return fail(image, "PCM event mapping invalid", at + 2);
        if (packed & 0xC000u) return fail(image, "PCM event reserved bits set", at + 4);
        if (!(flags & NES_PCM_EV_VOLUME) && ev_volume(ev)) return fail(image, "PCM event volume without the volume flag", at + 4);
        if (flags & NES_PCM_EV_DELAY ? !ev_delay(ev) || !(note || stop) : ev_delay(ev) != 0) {
            return fail(image, "PCM event delay not 1..31 with a note or stop", at + 4);
        }
        if (!(flags & NES_PCM_EV_CUT) && ev_cut(ev)) return fail(image, "PCM event cut without the cut flag", at + 4);
        if ((flags & NES_PCM_EV_CUT) && (flags & NES_PCM_EV_DELAY)) return fail(image, "PCM event with both a delay and a cut", at + 1);
    }
    /* Data: 1..255 (a $00 would hold the DAC and trip the IRQ), zero after it. */
    const uint8_t* d = e + NES_PCM_DATA_AT;
    for (uint32_t i = 0; i < info->data; ++i) {
        if (!d[i]) return fail(image, "PCM sample byte $00 (the MMC5 DAC ignores it)", NES_PCM_DATA_AT + i);
    }
    if (!zero(d + info->data, NES_PCM_MAX_DATA - info->data)) return fail(image, "PCM data not zero after its end", NES_PCM_DATA_AT + info->data);
    info->loaded = NM_LOADED;
    return 0;
}

void nes_pcm_build_empty(uint8_t* ext, int orders) {
    memset(ext, 0, NES_PCM_EXT_SIZE);
    ext[0] = 'T';
    ext[1] = 'T';
    ext[2] = 'P';
    ext[3] = 'C';
    ext[4] = NES_PCM_VERSION;
    ext[14] = (uint8_t)orders;
}

/* ---- the voice ---------------------------------------------------------------------------- */

static uint8_t value_of(const NesPcm* p, int sample) {
    return (uint8_t)(NES_MMC5_DAC_CENTRE + (sample - NES_MMC5_DAC_CENTRE) * (int)p->volume / 15);
}

int nes_pcm_value(const NesPcm* p) {
    if (!p->playing) return NES_MMC5_DAC_CENTRE;
    return value_of(p, p->ext[NES_PCM_DATA_AT + p->offset + p->pos]);
}

/* A $5011 write at `t` (the unit's time if that is later), skipped when the DAC already holds the value (no change, no
 * delta). The values are 1..255, so a write never trips the IRQ. */
static void dac(NesMmc5* unit, uint32_t t, uint8_t v) {
    if (unit->dac != v) nes_mmc5_write(unit, t < unit->time ? unit->time : t, 0x5011, v);
}

static void stop(NesPcm* p, NesMmc5* unit, uint32_t t) {
    if (!p->playing) return;
    p->playing = 0;
    dac(unit, t, NES_MMC5_DAC_CENTRE);
}

/* The voice takes mapping `m` (sample, period, loop) without restarting it. */
static void take_mapping(NesPcm* p, int m) {
    const uint8_t* me = mapping_entry(p->ext, m);
    const uint8_t* se = sample_entry(p->ext, me[0]);
    p->mapping = (uint16_t)m;
    p->sample = me[0];
    p->loop = me[1] & 1;
    p->period = rd32(me + 4);
    p->offset = rd32(se);
    p->length = rd32(se + 4);
    p->loop_start = rd32(se + 8);
}

static void start(NesPcm* p, NesMmc5* unit, int m, uint32_t t) {
    take_mapping(p, m);
    p->playing = 1;
    p->pos = 0;
    p->next = ((int64_t)t << 16) + p->period;
    dac(unit, t, (uint8_t)nes_pcm_value(p));
}

int nes_pcm_note(NesPcm* p, NesMmc5* unit, int mapping) {
    if (mapping < 0 || mapping >= p->info.mappings) return -1;
    start(p, unit, mapping, unit->time);
    return 0;
}

/* `k` >= 1 sample steps from the current position. Returns 0 when the voice ends at step `*end` (1..k) instead. */
static int advance(NesPcm* p, uint64_t k, uint64_t* end) {
    const uint64_t total = p->pos + k;
    if (total < p->length) {
        p->pos = (uint32_t)total;
        return 1;
    }
    if (p->loop) {
        const uint64_t span = p->length - p->loop_start;
        p->pos = (uint32_t)(p->loop_start + (total - p->length) % span);
        return 1;
    }
    *end = p->length - p->pos;
    return 0;
}

/* The CPU cycle a step at 16.16 time `at` lands on (rounded up: never before it). */
static uint32_t cycle_of(int64_t at) { return (uint32_t)((at + 0xFFFF) >> 16); }

void nes_pcm_run(NesPcm* p, NesMmc5* unit, uint32_t t) {
    const int64_t limit = (int64_t)t << 16;
    if (!p->playing || p->next > limit) return;
    if (!unit->out) { /* seeking: count the steps, write the value they end on */
        const uint64_t k = 1 + (uint64_t)(limit - p->next) / p->period;
        uint64_t end = 0;
        if (advance(p, k, &end)) {
            const int64_t last = p->next + (int64_t)(k - 1) * p->period;
            p->next += (int64_t)k * p->period;
            dac(unit, cycle_of(last), (uint8_t)nes_pcm_value(p));
        } else {
            stop(p, unit, cycle_of(p->next + (int64_t)(end - 1) * p->period));
        }
        return;
    }
    while (p->playing && p->next <= limit) {
        const uint32_t at = cycle_of(p->next);
        uint64_t end = 0;
        if (!advance(p, 1, &end)) {
            stop(p, unit, at);
            break;
        }
        dac(unit, at, (uint8_t)nes_pcm_value(p));
        p->next += p->period;
    }
}

void nes_pcm_end_frame(NesPcm* p, NesMmc5* unit, uint32_t frame_cycles) {
    nes_pcm_run(p, unit, frame_cycles);
    if (p->playing) p->next -= (int64_t)frame_cycles << 16;
}

void nes_pcm_set_image(NesPcm* p, const NesPcmImage* image) {
    if (image && image->info.loaded == NM_LOADED) {
        memcpy(p->ext, image->bytes, NES_PCM_DATA_AT + image->info.data);
        p->info = image->info;
    } else {
        memset(&p->info, 0, sizeof p->info);
    }
    p->playing = 0;
}

void nes_pcm_reset(NesPcm* p, NesMmc5* unit) {
    p->playing = 0;
    p->volume = 15;
    p->pending_flags = 0;
    p->pending_frames = 0;
    p->cut_frames = 0;
    dac(unit, unit->time, NES_MMC5_DAC_CENTRE); /* always: the output is detached while the machine resets */
}

/* A note or OFF happens now (immediate, or a delayed one firing): it cancels a cut set before it (FamiStudio's
 * @cancel_delayed_cut: every note byte of a channel does). */
static void note_now(NesPcm* p, NesMmc5* unit, int flags, int mapping, uint32_t t) {
    p->cut_frames = 0;
    if (flags & NES_PCM_EV_NOTE) start(p, unit, mapping, t);
    else stop(p, unit, t);
}

static void fire_pending(NesPcm* p, NesMmc5* unit, uint32_t t) {
    const uint8_t flags = p->pending_flags;
    p->pending_flags = 0;
    p->pending_frames = 0;
    note_now(p, unit, flags, p->pending_mapping, t);
}

/* Event of (order, row), or NULL. */
static const uint8_t* find_event(const NesPcm* p, int order, int row) {
    if (order < 0 || order >= p->info.orders) return NULL;
    const uint8_t* oe = order_entry(p->ext, order);
    const int first = rd16(oe), count = rd16(oe + 2);
    for (int k = 0; k < count; ++k) {
        const uint8_t* ev = event_entry(p->ext, first + k);
        if (ev[0] == row) return ev;
        if (ev[0] > row) break;
    }
    return NULL;
}

void nes_pcm_frame(NesPcm* p, NesMmc5* unit, int row_started, int order, int row) {
    const uint32_t t = unit->time;
    if (row_started) {
        if (p->pending_flags) fire_pending(p, unit, t); /* a delay longer than its row: the next row forces it */
        const uint8_t* ev = find_event(p, order, row);
        if (ev) {
            const uint8_t flags = ev[1];
            if (flags & NES_PCM_EV_VOLUME) {
                p->volume = (uint8_t)ev_volume(ev);
                if (p->playing) dac(unit, t, (uint8_t)nes_pcm_value(p));
            }
            if (flags & NES_PCM_EV_DELAY) {
                p->pending_flags = flags & (NES_PCM_EV_NOTE | NES_PCM_EV_STOP);
                p->pending_mapping = rd16(ev + 2);
                p->pending_frames = (uint8_t)ev_delay(ev);
            } else if (flags & (NES_PCM_EV_NOTE | NES_PCM_EV_STOP)) {
                note_now(p, unit, flags, rd16(ev + 2), t);
            }
            /* Set after the row's note (which cancels only earlier cuts); counted from this frame, S00 fires on it. */
            if (flags & NES_PCM_EV_CUT) p->cut_frames = (uint8_t)(ev_cut(ev) + 1);
        }
    } else if (p->pending_frames && --p->pending_frames == 0) {
        fire_pending(p, unit, t);
    }
    if (p->cut_frames && --p->cut_frames == 0) stop(p, unit, t);
}

/* ---- live edits --------------------------------------------------------------------------- */

static int binding_of(const uint8_t* e, int m) { return rd16(mapping_entry(e, m) + 2); }

/* The table entry of `binding` in extension `e` of `count` mappings (ascending bindings), or -1. */
static int find_binding(const uint8_t* e, int count, int binding) {
    int lo = 0, hi = count - 1;
    while (lo <= hi) {
        const int mid = (lo + hi) / 2, b = binding_of(e, mid);
        if (b == binding) return mid;
        if (b < binding) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}

void nes_pcm_apply(NesPcm* p, NesMmc5* unit, const NesPcmImage* image) {
    if (!image || image->info.loaded != NM_LOADED) return;
    const uint8_t* fresh = image->bytes;
    const NesPcmInfo* ni = &image->info;
    if (p->playing) {
        const int m = find_binding(fresh, ni->mappings, binding_of(p->ext, p->mapping));
        const uint8_t* me = m < 0 ? NULL : mapping_entry(fresh, m);
        const uint8_t* se = me ? sample_entry(fresh, me[0]) : NULL;
        if (!se || rd32(se + 4) != p->length ||
            memcmp(p->ext + NES_PCM_DATA_AT + p->offset, fresh + NES_PCM_DATA_AT + rd32(se), p->length)) {
            stop(p, unit, unit->time); /* the binding is gone or now plays other bytes */
        } else { /* the same bytes, maybe moved: position and phase continue, the binding's edits apply */
            p->mapping = (uint16_t)m;
            p->sample = me[0];
            p->loop = me[1] & 1;
            p->period = rd32(me + 4);
            p->offset = rd32(se);
            p->loop_start = rd32(se + 8);
        }
    }
    if (p->pending_flags & NES_PCM_EV_NOTE) {
        const int m = find_binding(fresh, ni->mappings, binding_of(p->ext, p->pending_mapping));
        if (m < 0) { /* its binding is gone: cancelled, not retargeted */
            p->pending_flags = 0;
            p->pending_frames = 0;
        } else {
            p->pending_mapping = (uint16_t)m;
        }
    }
    memcpy(p->ext, fresh, NES_PCM_DATA_AT + ni->data);
    p->info = *ni;
}

/* ---- the seek fallback -------------------------------------------------------------------- */

/* CPU cycles from the start of frame a to the start of frame b (b >= a) in straight play from the song start: frame f
 * lasts 29780 cycles, 29781 when f is odd (nes_machine.c). */
static uint64_t cycles_between(uint64_t a, uint64_t b) { return 29780u * (b - a) + (b / 2 - a / 2); }

void nes_pcm_carry(NesPcm* p, NesMmc5* unit, const NmView* view, int order) {
    const NesImageInfo* info = view->info;
    int playing = 0, mapping = 0;
    uint64_t started = 0;         /* frame the voice's note started */
    int volume = 15;
    int pending = 0, pending_mapping = 0;
    uint64_t pending_at = 0, cut_at = 0; /* the frames they fire on */
    int cut = 0;
    uint64_t frame = 0;
    uint8_t speeds[NES_IMAGE_MAX_ROWS];
    /* A note or OFF at frame `at` (nes_pcm_frame's note_now): it cancels any earlier cut. */
#define NOTE_AT(flags, m, at)                                                                                          \
    do {                                                                                                               \
        cut = 0;                                                                                                       \
        if ((flags) & NES_PCM_EV_NOTE) playing = 1, mapping = (m), started = (at);                                     \
        else playing = 0;                                                                                              \
    } while (0)
    for (int o = 0; o < order && o < p->info.orders; ++o) {
        nm_row_speeds(view, o, speeds);
        for (int r = 0; r < info->rows[o]; ++r) {
            /* The row's own frame, in nes_pcm_frame's order: a still pending delay is forced, the event applies, then
             * the cut's countdown (an S00 fires here). */
            if (pending) {
                NOTE_AT(pending, pending_mapping, frame);
                pending = 0;
            }
            const uint8_t* ev = find_event(p, o, r);
            if (ev) {
                const uint8_t flags = ev[1];
                if (flags & NES_PCM_EV_VOLUME) volume = ev_volume(ev);
                if (flags & NES_PCM_EV_DELAY) {
                    pending = flags & (NES_PCM_EV_NOTE | NES_PCM_EV_STOP);
                    pending_mapping = rd16(ev + 2);
                    pending_at = frame + (uint64_t)ev_delay(ev);
                } else if (flags & (NES_PCM_EV_NOTE | NES_PCM_EV_STOP)) {
                    NOTE_AT(flags, rd16(ev + 2), frame);
                }
                if (flags & NES_PCM_EV_CUT) {
                    cut = 1;
                    cut_at = frame + (uint64_t)ev_cut(ev);
                }
            }
            if (cut && cut_at == frame) playing = 0, cut = 0;
            /* The row's other frames, up to the next row's: a delay that falls among them fires on its frame (a cut
             * due before it fires first; one due on or after it is cancelled by it); a cut due among them fires. A
             * delay or cut due later carries on to the next row. */
            const uint64_t next = frame + (speeds[r] ? speeds[r] : 1);
            if (pending && pending_at < next) {
                if (cut && cut_at < pending_at) playing = 0, cut = 0;
                NOTE_AT(pending, pending_mapping, pending_at);
                pending = 0;
            }
            if (cut && cut_at < next) playing = 0, cut = 0;
            frame = next;
        }
    }
#undef NOTE_AT
    p->volume = (uint8_t)volume;
    p->pending_flags = (uint8_t)pending;
    p->pending_mapping = (uint16_t)pending_mapping;
    p->pending_frames = (uint8_t)(pending ? pending_at - frame + 1 : 0);
    p->cut_frames = (uint8_t)(cut ? cut_at - frame + 1 : 0);
    p->playing = 0;
    if (!playing || mapping >= p->info.mappings) {
        dac(unit, unit->time, NES_MMC5_DAC_CENTRE);
        return;
    }
    /* The note started at cycle 0 of frame `started` (position 0) and steps every period: the steps at period, 2
     * period, ... up to the order's start have happened. */
    take_mapping(p, mapping);
    p->playing = 1;
    p->pos = 0;
    const uint64_t elapsed = cycles_between(started, frame) << 16;
    const uint64_t k = elapsed / p->period;
    uint64_t end = 0;
    if (k && !advance(p, k, &end)) {
        p->playing = 0;
        dac(unit, unit->time, NES_MMC5_DAC_CENTRE);
        return;
    }
    p->next = (int64_t)((k + 1) * p->period - elapsed) + ((int64_t)unit->time << 16);
    dac(unit, unit->time, (uint8_t)nes_pcm_value(p));
}
