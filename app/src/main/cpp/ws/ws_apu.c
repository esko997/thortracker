/* The WonderSwan sound unit (ws_apu.h). A C port of the ares WonderSwan APU, ares/ws/apu/{apu,io,dma,channel1-5}.cpp
 * at github.com/ares-emulator/ares commit 038bcb91c230d33fc8911e2bc21b53c66d70a2b0 (2025-09-14), its cycle-accurate
 * ("accurate") mode: Copyright (c) 2004-2025 ares team, Near et al, ISC licence (third_party/ares-ws-apu/LICENSE;
 * the notice is kept in app/src/main/assets/NOTICE.txt). Ported, not vendored: ares is nall-based C++.
 *
 * Where ares disagrees with the WSdev wiki (ws.nesdev.org: Sound rev. 658, Hyper Voice rev. 613, DMA rev. 562) this
 * port follows the wiki (docs/AUDIO_ENGINE.md, "WonderSwan: Sources and hardware decisions"):
 *  1. $94 voice volume bits: bit 0 right 100 %, bit 1 right 50 %, bit 2 left 100 %, bit 3 left 50 % (ares swaps the
 *     50 % and 100 % bits).
 *  2. Writing $8D resets the sweep step counter to the new time; the 375 Hz prescaler is not reset.
 *  3. The sweep acts only while channel 3 is enabled ($90 bit 2) and its sweep bit is set (ares ignores the enable).
 *  4. $6B bit 4 resets the Hyper Voice stereo flip-flop (the next sample goes left) (ares misses it).
 *  5. A Sound DMA enable ($52 bit 7) with the length counter at 0 fails: the enable bit stays 0.
 *  6. Every Hyper Voice output update re-converts both latched samples with the current shift and scaling (ares
 *     converts only a side whose sample changed), by the wiki's conversion table.
 *  7. The headphone adder wraps to 16 bits (ares adds Hyper Voice unwrapped).
 * Not modelled: $95 (the hardware test register: stored only), $9E (the Color's speaker master volume: stored only),
 * the readable output ports $96-$9B and the CPU cycles Sound DMA steals. The output frame is produced after cycle 127
 * of its block (ares outputs at cycle 0 of the next block from the same sums). Hyper Voice DMA input in channel modes
 * 1-3 writes the latches directly without touching the flip-flop (the wiki's description).
 *
 * The per-cycle loop (ws_apu_block_reference) is the specification; ws_apu_block advances the counters in closed form
 * between the block's events (DMA at cycle 122, Hyper Voice at 123, outputs at 124..127, at most one sweep step). */
#include "ws_apu.h"

#include <string.h>

_Static_assert((-3 >> 1) == -2, "Hyper Voice conversion uses an arithmetic right shift");

static const uint8_t NOISE_TAP[8] = {14, 10, 13, 4, 8, 6, 9, 11};
static const uint8_t DMA_DIVISOR[4] = {6, 4, 2, 1};
static const uint8_t HV_DIVISOR[8] = {1, 2, 3, 4, 5, 6, 8, 12};

void ws_apu_reset(WsApu* apu, int model) {
    const int speaker = apu->speaker;
    const unsigned mute = apu->mute;
    const uint8_t* rom = apu->rom;
    const uint32_t rom_base = apu->rom_base, rom_size = apu->rom_size;
    const WsApuDmaHook hook = apu->hook;
    void* ctx = apu->hook_ctx;
    memset(apu, 0, sizeof *apu);
    apu->model = model;
    apu->speaker = speaker;
    apu->mute = mute;
    apu->rom = rom;
    apu->rom_base = rom_base;
    apu->rom_size = rom_size;
    apu->hook = hook;
    apu->hook_ctx = ctx;
}

void ws_apu_map_rom(WsApu* apu, uint32_t base, const uint8_t* bytes, uint32_t size) {
    apu->rom = bytes;
    apu->rom_base = base;
    apu->rom_size = bytes ? size : 0;
}

void ws_apu_set_hook(WsApu* apu, WsApuDmaHook hook, void* ctx) {
    apu->hook = hook;
    apu->hook_ctx = ctx;
}

void ws_apu_set_output(WsApu* apu, int speaker) { apu->speaker = speaker ? 1 : 0; }

void ws_apu_set_mute(WsApu* apu, unsigned mask) { apu->mute = mask & 0x1F; }

/* ---- writes ---------------------------------------------------------------------------------------------------- */

/* A byte for Hyper Voice: channel mode 0 (stereo) as a manual write, 1 left, 2 right, 3 both. */
static void hv_manual(WsApuHyper* hv, uint8_t v) {
    if (hv->flip) hv->latch_r = v;
    else hv->latch_l = v;
    hv->flip ^= 1;
}

static void hv_dma_input(WsApuHyper* hv, uint8_t v) {
    switch (hv->chan_mode) {
    case 0: hv_manual(hv, v); break;
    case 1: hv->latch_l = v; break;
    case 2: hv->latch_r = v; break;
    default: hv->latch_l = hv->latch_r = v; break;
    }
}

static uint32_t set_byte(uint32_t v, int i, uint8_t b) {
    return (v & ~(0xFFu << (8 * i)) & 0xFFFFFu) | ((uint32_t)b << (8 * i) & 0xFFFFFu);
}

void ws_apu_write_port(WsApu* apu, int port, uint8_t val) {
    const int color = apu->model == WS_MODEL_COLOR;
    if (port >= 0x80 && port <= 0x87) {
        WsApuChannel* ch = &apu->ch[(port - 0x80) >> 1];
        if (port & 1) ch->pitch = (uint16_t)((ch->pitch & 0xFF) | (val & 7) << 8);
        else ch->pitch = (uint16_t)((ch->pitch & 0x700) | val);
        return;
    }
    if (port >= 0x88 && port <= 0x8B) {
        WsApuChannel* ch = &apu->ch[port - 0x88];
        ch->vol_l = val >> 4;
        ch->vol_r = val & 15;
        return;
    }
    switch (port) {
    case 0x8C: apu->sweep_value = (int8_t)val; break;
    case 0x8D:
        apu->sweep_time = val & 31;
        apu->sweep_counter = apu->sweep_time; /* deviation 2 */
        break;
    case 0x8E:
        apu->noise_mode = val & 7;
        apu->noise_update = val >> 4 & 1;
        if (val & 8) apu->lfsr = 0;
        break;
    case 0x8F: apu->wave_base = val; break;
    case 0x90:
        for (int c = 0; c < 4; ++c) apu->ch[c].enable = val >> c & 1;
        apu->voice = val >> 5 & 1;
        apu->sweep = val >> 6 & 1;
        apu->noise = val >> 7 & 1;
        break;
    case 0x91:
        apu->speaker_enable = val & 1;
        apu->speaker_shift = val >> 1 & 3;
        apu->headphone_enable = val >> 3 & 1;
        break;
    case 0x92: apu->lfsr = (uint16_t)((apu->lfsr & 0x7F00) | val); break;
    case 0x93: apu->lfsr = (uint16_t)((apu->lfsr & 0x00FF) | (val & 0x7F) << 8); break;
    case 0x94: /* deviation 1 */
        apu->voice_right_full = val & 1;
        apu->voice_right_half = val >> 1 & 1;
        apu->voice_left_full = val >> 2 & 1;
        apu->voice_left_half = val >> 3 & 1;
        break;
    case 0x95: apu->reg95 = val; break;
    case 0x9E: apu->reg9e = val; break;
    default:
        if (!color) break;
        if (port >= 0x4A && port <= 0x4C) {
            apu->dma.source = set_byte(apu->dma.source, port - 0x4A, val);
            apu->dma.shadow_source = set_byte(apu->dma.shadow_source, port - 0x4A, val);
        } else if (port >= 0x4E && port <= 0x50) {
            apu->dma.length = set_byte(apu->dma.length, port - 0x4E, val);
            apu->dma.shadow_length = set_byte(apu->dma.shadow_length, port - 0x4E, val);
        } else if (port == 0x52) {
            apu->dma.rate = val & 3;
            apu->dma.hold = val >> 2 & 1;
            apu->dma.loop = val >> 3 & 1;
            apu->dma.target = val >> 4 & 1;
            apu->dma.direction = val >> 6 & 1;
            apu->dma.enable = (val >> 7 & 1) && apu->dma.length != 0; /* deviation 5 */
        } else if (port == 0x64) {
            apu->hv.out_l = (int16_t)(uint16_t)(((uint16_t)apu->hv.out_l & 0xFF00) | val);
        } else if (port == 0x65) {
            apu->hv.out_l = (int16_t)(uint16_t)(((uint16_t)apu->hv.out_l & 0x00FF) | val << 8);
        } else if (port == 0x66) {
            apu->hv.out_r = (int16_t)(uint16_t)(((uint16_t)apu->hv.out_r & 0xFF00) | val);
        } else if (port == 0x67) {
            apu->hv.out_r = (int16_t)(uint16_t)(((uint16_t)apu->hv.out_r & 0x00FF) | val << 8);
        } else if (port == 0x69) {
            hv_manual(&apu->hv, val); /* the channel mode is ignored */
        } else if (port == 0x6A) {
            apu->hv.shift = val & 3;
            apu->hv.scaling = val >> 2 & 3;
            apu->hv.speed = val >> 4 & 7;
            apu->hv.enable = val >> 7 & 1;
        } else if (port == 0x6B) {
            apu->hv.chan_mode = val >> 5 & 3;
            if (val & 0x10) apu->hv.flip = 0; /* deviation 4 */
        }
        break;
    }
}

void ws_apu_write_ram(WsApu* apu, uint16_t addr, uint8_t val) {
    apu->ram[apu->model == WS_MODEL_COLOR ? addr : addr & 0x3FFF] = val;
}

void ws_apu_write(WsApu* apu, const WsWrite* w) {
    if (w->space == WS_SPACE_RAM) ws_apu_write_ram(apu, w->addr, w->val);
    else ws_apu_write_port(apu, w->addr, w->val);
}

/* ---- the block's events ---------------------------------------------------------------------------------------- */

int16_t ws_apu_hv_convert(const WsApu* apu, uint8_t v) {
    const int shift = apu->hv.shift;
    int32_t x;
    switch (apu->hv.scaling) {
    case 0: x = ((int32_t)v << 8) >> shift; break;
    case 1: x = (((int32_t)v << 8) - 65536) >> shift; break;
    case 2: x = ((int32_t)(int8_t)v * 256) >> shift; break;
    default: x = (int32_t)v << 8; break;
    }
    return (int16_t)(uint16_t)(x & 0xFFFF);
}

static uint8_t dma_read(const WsApu* apu, uint32_t address) {
    const uint32_t at = address - apu->rom_base;
    return address >= apu->rom_base && at < apu->rom_size ? apu->rom[at] : 0;
}

/* Cycle 122: Sound DMA. */
static void dma_run(WsApu* apu) {
    WsApuDma* d = &apu->dma;
    if (!d->enable) return;
    if (++d->clock < DMA_DIVISOR[d->rate]) return;
    d->clock = 0;
    uint8_t data = 0;
    if (!d->hold) {
        data = dma_read(apu, d->source);
        d->source = (d->direction ? d->source - 1 : d->source + 1) & 0xFFFFF;
    }
    if (d->target == 0) {
        apu->ch[1].vol_l = data >> 4;
        apu->ch[1].vol_r = data & 15;
    } else {
        hv_dma_input(&apu->hv, data);
    }
    if (apu->hook) apu->hook(apu->hook_ctx, apu->cycle + WS_DMA_TRANSFER_CYCLE, d->target, data);
    if (d->hold) return;
    d->length = (d->length - 1) & 0xFFFFF;
    if (d->length) return;
    if (d->loop) {
        d->source = d->shadow_source;
        d->length = d->shadow_length;
    } else {
        d->enable = 0;
    }
}

/* Cycle 123: Hyper Voice output update (deviation 6: both sides re-converted). */
static void hv_run(WsApu* apu) {
    WsApuHyper* hv = &apu->hv;
    if (!hv->enable) return;
    if (++hv->clock < HV_DIVISOR[hv->speed]) return;
    hv->clock = 0;
    hv->out_l = ws_apu_hv_convert(apu, hv->latch_l);
    hv->out_r = ws_apu_hv_convert(apu, hv->latch_r);
}

static int wave_sample(const WsApu* apu, int c, int index) {
    uint32_t addr = ((uint32_t)apu->wave_base << 6) + 16u * (uint32_t)c + (uint32_t)(index >> 1);
    addr &= apu->model == WS_MODEL_COLOR ? 0xFFFFu : 0x3FFFu;
    const uint8_t b = apu->ram[addr];
    return (index & 1) ? b >> 4 : b & 15;
}

/* Cycle 124 + c: channel c adds its output to the sums (computed even when muted, then discarded). */
static void channel_output(const WsApu* apu, int c, int* sum_l, int* sum_r) {
    const WsApuChannel* ch = &apu->ch[c];
    int l, r;
    if (c == 1 && apu->voice) {
        const int b = ch->vol_l << 4 | ch->vol_r;
        l = apu->voice_left_full ? b : apu->voice_left_half ? b >> 1 : 0;
        r = apu->voice_right_full ? b : apu->voice_right_half ? b >> 1 : 0;
    } else if (ch->enable) {
        const int s = (c == 3 && apu->noise) ? (apu->noise_output ? 15 : 0) : wave_sample(apu, c, ch->offset);
        l = s * ch->vol_l;
        r = s * ch->vol_r;
    } else {
        return;
    }
    if (apu->mute >> c & 1) return;
    *sum_l += l;
    *sum_r += r;
}

static void lfsr_step(WsApu* apu) {
    const int t = NOISE_TAP[apu->noise_mode];
    const int out = 1 ^ (apu->lfsr >> 7 & 1) ^ (apu->lfsr >> t & 1);
    apu->noise_output = (uint8_t)out;
    apu->lfsr = (uint16_t)(((apu->lfsr << 1) | out) & 0x7FFF);
}

static void sweep_step(WsApu* apu) {
    if (!apu->ch[2].enable || !apu->sweep) return; /* deviation 3 */
    if (--apu->sweep_counter < 0) {
        apu->sweep_counter = apu->sweep_time;
        apu->ch[2].pitch = (uint16_t)((apu->ch[2].pitch + apu->sweep_value) & 2047);
    }
}

/* The frame after cycle 127. */
static void frame(const WsApu* apu, int sum_l, int sum_r, int16_t out[2]) {
    if (apu->speaker) {
        const int v = apu->speaker_enable ? (((sum_l + sum_r) >> apu->speaker_shift) & 0xFF) << 7 : 0;
        out[0] = out[1] = (int16_t)v;
        return;
    }
    if (!apu->headphone_enable) {
        out[0] = out[1] = 0;
        return;
    }
    const int hv = apu->hv.enable && !(apu->mute >> 4 & 1);
    out[0] = (int16_t)(uint16_t)(((sum_l << 5) + (hv ? apu->hv.out_l : 0)) & 0xFFFF);
    out[1] = (int16_t)(uint16_t)(((sum_r << 5) + (hv ? apu->hv.out_r : 0)) & 0xFFFF);
}

/* ---- the per-cycle specification -------------------------------------------------------------------------------- */

static void tick_reference(WsApu* apu, int c) {
    WsApuChannel* ch = &apu->ch[c];
    if (!ch->enable) return;
    ch->counter = (uint16_t)((ch->counter - 1) & 2047);
    if (ch->counter != ch->pitch) return;
    ch->counter = 0;
    ch->offset = (uint8_t)((ch->offset + 1) & 31);
    if (c == 3 && apu->noise_update) lfsr_step(apu);
}

void ws_apu_block_reference(WsApu* apu, int16_t out[2]) {
    int sum_l = 0, sum_r = 0;
    for (int j = 0; j < WS_BLOCK_CYCLES; ++j) {
        tick_reference(apu, 0);
        tick_reference(apu, 1);
        tick_reference(apu, 2);
        apu->sweep_clock = (uint16_t)((apu->sweep_clock + 1) & 8191);
        if (apu->sweep_clock == 0) sweep_step(apu);
        tick_reference(apu, 3);
        if (j == WS_DMA_TRANSFER_CYCLE) {
            dma_run(apu);
            sum_l = sum_r = 0;
        } else if (j == WS_DMA_TRANSFER_CYCLE + 1) {
            hv_run(apu);
        } else if (j >= 124) {
            channel_output(apu, j - 124, &sum_l, &sum_r);
        }
    }
    frame(apu, sum_l, sum_r, out);
    apu->cycle += WS_BLOCK_CYCLES;
}

/* ---- closed form ------------------------------------------------------------------------------------------------- */

/* n cycles of channel c: the counter steps when it reaches the pitch, every 2048 - pitch cycles (2048 at pitch 0). */
static void advance(WsApu* apu, int c, int n) {
    WsApuChannel* ch = &apu->ch[c];
    if (!ch->enable || n <= 0) return;
    int k = (ch->counter - ch->pitch) & 2047; /* cycles to the next step */
    if (!k) k = 2048;
    if (n < k) {
        ch->counter = (uint16_t)((ch->counter - n) & 2047);
        return;
    }
    const int period = ch->pitch ? 2048 - ch->pitch : 2048;
    const int rest = n - k, steps = 1 + rest / period;
    ch->counter = (uint16_t)((-(rest % period)) & 2047);
    ch->offset = (uint8_t)((ch->offset + steps) & 31);
    if (c == 3 && apu->noise_update)
        for (int i = 0; i < steps; ++i) lfsr_step(apu);
}

/* Channel 3 over cycles [from, to) of the block, the sweep step after its tick of cycle `js` (-1: none). */
static void advance_ch3(WsApu* apu, int from, int to, int js) {
    if (js >= from && js < to) {
        advance(apu, 2, js + 1 - from);
        sweep_step(apu);
        advance(apu, 2, to - js - 1);
    } else {
        advance(apu, 2, to - from);
    }
}

void ws_apu_block(WsApu* apu, int16_t out[2]) {
    const int js = (8191 - apu->sweep_clock) & 8191; /* the cycle whose prescaler increment wraps to 0 */
    const int sweep_at = js < WS_BLOCK_CYCLES ? js : -1;
    apu->sweep_clock = (uint16_t)((apu->sweep_clock + WS_BLOCK_CYCLES) & 8191);
    /* DMA (122) and Hyper Voice (123) depend on no channel counter; channel 2's output (125) reads the DMA's byte. */
    dma_run(apu);
    hv_run(apu);
    int sum_l = 0, sum_r = 0;
    advance(apu, 0, 125);
    channel_output(apu, 0, &sum_l, &sum_r);
    advance(apu, 0, 3);
    advance(apu, 1, 126);
    channel_output(apu, 1, &sum_l, &sum_r);
    advance(apu, 1, 2);
    advance_ch3(apu, 0, 127, sweep_at);
    channel_output(apu, 2, &sum_l, &sum_r);
    advance_ch3(apu, 127, 128, sweep_at);
    advance(apu, 3, 128);
    channel_output(apu, 3, &sum_l, &sum_r);
    frame(apu, sum_l, sum_r, out);
    apu->cycle += WS_BLOCK_CYCLES;
}

void ws_apu_dma_block(WsApu* apu) {
    dma_run(apu);
    apu->cycle += WS_BLOCK_CYCLES;
}

void ws_apu_dma_rebase(WsApu* apu, int32_t delta) {
    apu->dma.source = (uint32_t)((int64_t)apu->dma.source + delta) & 0xFFFFF;
    apu->dma.shadow_source = (uint32_t)((int64_t)apu->dma.shadow_source + delta) & 0xFFFFF;
}

/* ---- comparison --------------------------------------------------------------------------------------------------- */

#define SAME(f) (a->f == b->f)

int ws_apu_same_dma(const WsApuDma* a, const WsApuDma* b) {
    return SAME(source) && SAME(length) && SAME(shadow_source) && SAME(shadow_length) && SAME(rate) && SAME(hold) &&
           SAME(loop) && SAME(target) && SAME(direction) && SAME(enable) && SAME(clock);
}

static int same_channel(const WsApuChannel* a, const WsApuChannel* b) {
    return SAME(pitch) && SAME(counter) && SAME(vol_l) && SAME(vol_r) && SAME(enable) && SAME(offset);
}

static int same_hyper(const WsApuHyper* a, const WsApuHyper* b) {
    return SAME(shift) && SAME(scaling) && SAME(speed) && SAME(enable) && SAME(chan_mode) && SAME(flip) && SAME(latch_l) &&
           SAME(latch_r) && SAME(out_l) && SAME(out_r) && SAME(clock);
}

int ws_apu_same_state(const WsApu* a, const WsApu* b) {
    for (int c = 0; c < 4; ++c)
        if (!same_channel(&a->ch[c], &b->ch[c])) return 0;
    return SAME(model) && SAME(voice) && SAME(voice_left_full) && SAME(voice_left_half) && SAME(voice_right_full) &&
           SAME(voice_right_half) && SAME(sweep_value) && SAME(sweep_time) && SAME(sweep) && SAME(sweep_counter) &&
           SAME(noise_mode) && SAME(noise_update) && SAME(noise) && SAME(lfsr) && SAME(noise_output) &&
           same_hyper(&a->hv, &b->hv) && ws_apu_same_dma(&a->dma, &b->dma) && SAME(wave_base) && SAME(speaker_enable) &&
           SAME(speaker_shift) && SAME(headphone_enable) && SAME(sweep_clock) && SAME(reg95) && SAME(reg9e) && SAME(cycle) &&
           SAME(speaker) && SAME(rom) && SAME(rom_base) && SAME(rom_size) && !memcmp(a->ram, b->ram, sizeof a->ram);
}

#undef SAME
