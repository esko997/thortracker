/* Konami 051649 (SCC) / 052539 (SCC+ mode) sound core (scc_k051649.h).
 *
 * license:BSD-3-Clause
 * Derived from MAME src/devices/sound/k051649.cpp (copyright-holders: Bryan McPhail), commit
 * 20d4667cdf485066a113f1ef24bae0887cd7134c. Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD-3-Clause licence are met (full text in
 * app/src/main/assets/NOTICE.txt). Test-register bits 0-1 follow openMSX's documented model of Manuel Pazos's
 * measurements (behaviour only, no openMSX code).
 *
 * MAME's sound_stream_update runs, every clock and channel with frequency > 8:
 *     if (++clock > frequency) { counter = (counter + 1) & 0x1f; clock = 0; }
 *     if (clock == 0) sample = (key ? waveram[counter] : 0) * volume;
 * and outputs the sum of sample >> 4. Between register writes key, volume and wave RAM are constant, so a channel's
 * latch changes only at its reloads: the first one 1 clock after a frequency write (clock -1 -> 0, counter kept) or
 * E + 1 - clock clocks after the last step, then one every E + 1 clocks, each advancing the counter. sck_advance jumps
 * over them arithmetically; sck_render visits them, emitting the level changes. Both end in the same state. */
#include "scc_k051649.h"

#include <string.h>

void sck_reset(SccCore* core, int plus) {
    memset(core, 0, sizeof *core);
    core->plus = plus ? 1 : 0;
    for (int c = 0; c < SCK_CHANNELS; ++c) core->ch[c].volume = 15;
}

static void write_wave(SccCore* core, int reg, int val) {
    /* MAME k051649_waveform_w / k052539_waveform_w: bit 6 protects every RAM, bit 7 the SCC's shared one */
    if (core->test & SCK_TEST_FREEZE) return;
    if (!core->plus && (core->test & SCK_TEST_FREEZE4) && reg >= 0x60) return;
    core->ram[reg >> 5][reg & 31] = (int8_t)(uint8_t)val;
}

void sck_write(SccCore* core, int port, int reg, int val) {
    val &= 0xFF;
    switch (port) {
    case SCK_PORT_WAVE:
        if (!core->plus && reg >= 0 && reg < 0x80) write_wave(core, reg, val); /* 0x60-0x7F: RAM 3, channels 4 and 5 */
        break;
    case SCK_PORT_WAVE_PLUS:
        if (core->plus && reg >= 0 && reg < 0xA0) write_wave(core, reg, val);
        break;
    case SCK_PORT_PERIOD: {
        if (reg < 0 || reg >= 2 * SCK_CHANNELS) break;
        SccCoreChannel* ch = &core->ch[reg >> 1];
        if (reg & 1) ch->period = (uint16_t)((ch->period & 0x0FF) | (val << 8 & 0xF00));
        else ch->period = (uint16_t)((ch->period & 0xF00) | val);
        /* openMSX's model of the test register's frequency modes, fixed at the write */
        if (core->test & SCK_TEST_FREQ8) ch->effective = ch->period & 0xFF;
        else if (core->test & SCK_TEST_FREQ4) ch->effective = ch->period >> 8;
        else ch->effective = ch->period;
        if (core->test & SCK_TEST_RESTART) ch->pos = 0; /* test register bit 5 resets the internal counter */
        ch->clk = -1;                                    /* sample reload pending */
        break;
    }
    case SCK_PORT_VOLUME:
        if (reg >= 0 && reg < SCK_CHANNELS) core->ch[reg].volume = (uint8_t)(val & 0x0F);
        break;
    case SCK_PORT_KEY:
        core->key = (uint8_t)(val & 0x1F);
        break;
    case SCK_PORT_TEST:
        core->test = (uint8_t)val;
        break;
    default:
        break;
    }
}

/* The latch a reload at waveform position `pos` loads (key, volume and RAM as they are now). */
static int reload_value(const SccCore* core, int c, int pos) {
    if (!(core->key >> c & 1)) return 0;
    return core->ram[sck_ram_of(core, c)][pos] * core->ch[c].volume;
}

static int level_of(int latch) { return latch >= 0 ? latch >> 4 : -((-latch + 15) >> 4); }

/* Clocks until the next reload (1-based) and whether it advances the position; the channel is not halted. */
static uint32_t first_reload(const SccCoreChannel* ch, uint32_t step, int* moves) {
    if (ch->clk < 0) {
        *moves = 0; /* reload pending after a period write: next clock, position kept */
        return 1;
    }
    *moves = 1;
    /* clk <= E always holds (E changes only with a period write, which sets clk = -1); MAME steps at once otherwise */
    return (uint32_t)ch->clk < step ? step - (uint32_t)ch->clk : 1;
}

/* Exact state after n clocks; returns the offset of the first reload + 1, or 0 if none happened. */
static uint32_t advance_channel(SccCore* core, int c, uint32_t n) {
    SccCoreChannel* ch = &core->ch[c];
    if (ch->effective <= SCK_HALT_PERIOD || n == 0) return 0; /* halted: nothing moves, not even clk */
    const uint32_t step = ch->effective + 1u;
    int moves;
    const uint32_t first = first_reload(ch, step, &moves);
    if (n < first) {
        ch->clk += (int32_t)n;
        return 0;
    }
    const uint32_t rest = n - first, k = rest / step;
    ch->pos = (uint8_t)((ch->pos + (uint32_t)moves + k) & 31);
    ch->clk = (int32_t)(rest - k * step);
    ch->latch = (int16_t)reload_value(core, c, ch->pos);
    return first;
}

void sck_advance(SccCore* core, uint32_t clocks) {
    for (int c = 0; c < SCK_CHANNELS; ++c) advance_channel(core, c, clocks);
}

static void render_channel(SccCore* core, int c, uint32_t n, SckEmit emit, void* ctx) {
    SccCoreChannel* ch = &core->ch[c];
    if (ch->effective <= SCK_HALT_PERIOD || n == 0) return;
    const int before = level_of(ch->latch);
    if (!emit || !(core->key >> c & 1) || ch->volume == 0) {
        /* every reload loads 0 (or nobody listens): only the first one can change the level */
        const uint32_t first = advance_channel(core, c, n);
        const int after = level_of(ch->latch);
        if (emit && first && after != before) emit(ctx, first - 1, c, after);
        return;
    }
    const uint32_t step = ch->effective + 1u;
    int moves;
    uint32_t at = first_reload(ch, step, &moves) - 1; /* offset of the next reload */
    if (at >= n) {
        ch->clk += (int32_t)n;
        return;
    }
    const int8_t* wave = core->ram[sck_ram_of(core, c)];
    const int volume = ch->volume;
    int pos = ch->pos, level = before, latch = ch->latch;
    for (;;) {
        pos = (pos + moves) & 31;
        moves = 1;
        latch = wave[pos] * volume;
        const int l = level_of(latch);
        if (l != level) {
            emit(ctx, at, c, l);
            level = l;
        }
        if (n - 1 - at < step) break;
        at += step;
    }
    ch->pos = (uint8_t)pos;
    ch->latch = (int16_t)latch;
    ch->clk = (int32_t)(n - 1 - at);
}

void sck_render(SccCore* core, uint32_t clocks, SckEmit emit, void* ctx) {
    for (int c = 0; c < SCK_CHANNELS; ++c) render_channel(core, c, clocks, emit, ctx);
}
