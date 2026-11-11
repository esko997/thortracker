/* hUGEDriver player machine on binjgb. See huge_machine.h for the memory map.
 *
 * This translation unit includes binjgb's emulator.c so it can drive the CPU directly
 * (call a driver entry point with a sentinel return address), skip idle time while seeking
 * and snapshot/restore the power-on state without allocation. binjgb itself is unmodified
 * apart from the documented float audio buffer and envelope ceiling patches. */
#ifndef NDEBUG
#define NDEBUG 1 /* binjgb asserts must never abort the app */
#endif
#include "emulator.c"

#include <math.h>

#include "generated/huge_player_image.h"
#include "huge_machine.h"

#define HM_ROM_SIZE 0x8000u
#define HM_AUDIO_FRAMES 4096u
#define HM_CALL_BUDGET HM_TICK_CYCLES /* a driver call longer than a frame is a fault */
#define HM_OUTPUT_GAIN 0.9f

struct HugeMachine {
    Emulator* e;
    u8* rom; /* == e->cart_info->data, HM_ROM_SIZE bytes, host-writable */
    EmulatorState* pristine;
    u8 shadow[0x10000]; /* last song bytes written into the non-ROM regions */
    uint32_t image_version;
    u8 base_tempo;
    u8 restore_tempo; /* ticks_per_row temporarily ends a row shortened by a speed edit */
    u8 faulted;
    uint64_t driver_cycles;
    double hpf_factor;
    double cap_l, cap_r;
};

static u16 rd16(const u8* mem, u32 addr) { return (u16)(mem[addr & 0xFFFF] | (mem[(addr + 1) & 0xFFFF] << 8)); }

static int in_song(u32 addr, u32 len) {
    u32 end = addr + len;
    return (addr >= HM_REGION_A_BEGIN && end <= HM_REGION_A_END) ||
           (addr >= HM_REGION_B_BEGIN && end <= HM_REGION_B_END);
}

static void poke(HugeMachine* m, u16 addr, u8 value) {
    if (addr < HM_ROM_SIZE) {
        m->rom[addr] = value;
    } else {
        write_u8_raw(m->e, addr, value);
    }
}

uint8_t hm_peek(HugeMachine* m, uint16_t addr) {
    if (addr < HM_ROM_SIZE) return m->rom[addr];
    return read_u8_raw(m->e, addr);
}

static void poke16(HugeMachine* m, u16 addr, u16 value) {
    poke(m, addr, (u8)(value & 0xFF));
    poke(m, (u16)(addr + 1), (u8)(value >> 8));
}

static void build_rom(u8* rom) {
    memset(rom, 0, HM_ROM_SIZE);
    /* Sentinel: halt; jr -3. The engine stops before executing it. */
    rom[HM_SENTINEL + 0] = 0x76;
    rom[HM_SENTINEL + 1] = 0x18;
    rom[HM_SENTINEL + 2] = 0xFD;
    /* Entry point: nop; jp sentinel (never run: the engine sets PC itself). */
    rom[0x100] = 0x00;
    rom[0x101] = 0xC3;
    rom[0x102] = HM_SENTINEL & 0xFF;
    rom[0x103] = HM_SENTINEL >> 8;
    memcpy(rom + 0x134, "THORTRACKER", 11);
    rom[0x143] = 0x80; /* CGB supported: CGB-mode APU, like gbc-puzzler's target */
    rom[0x147] = 0x1A; /* MBC5+RAM */
    rom[0x148] = 0x00; /* 32 KB */
    rom[0x149] = 0x02; /* 8 KB cart RAM */
    u8 checksum = 0;
    for (int i = 0x134; i <= 0x14C; ++i) checksum = (u8)(checksum - rom[i] - 1);
    rom[0x14D] = checksum;
    memcpy(rom + HUGE_CODE_BASE, huge_player_code, HUGE_CODE_SIZE);
}

HugeMachine* hm_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > HM_MAX_SAMPLE_RATE) return NULL;
    HugeMachine* m = (HugeMachine*)calloc(1, sizeof(HugeMachine));
    if (!m) return NULL;
    m->pristine = (EmulatorState*)malloc(sizeof(EmulatorState));
    u8* rom = (u8*)malloc(HM_ROM_SIZE);
    if (!m->pristine || !rom) {
        free(rom);
        free(m->pristine);
        free(m);
        return NULL;
    }
    build_rom(rom);

    EmulatorInit init;
    memset(&init, 0, sizeof(init));
    init.rom.data = rom; /* ownership passes to binjgb (freed by emulator_delete) */
    init.rom.size = HM_ROM_SIZE;
    init.audio_frequency = (int)sample_rate;
    init.audio_frames = HM_AUDIO_FRAMES;
    init.random_seed = 0x7470;
    Emulator* e = emulator_new(&init);
    if (!e) {
        /* emulator_new frees the ROM through emulator_delete on its error path. */
        free(m->pristine);
        free(m);
        return NULL;
    }
    m->e = e;
    m->rom = e->cart_info->data;

    write_u8_raw(e, 0x0000, 0x0A); /* MBC5: enable cart RAM */
    write_u8_raw(e, 0xFF40, 0x00); /* LCD off: VRAM always accessible, no PPU work */
    write_u8_raw(e, 0xFFFF, 0x00); /* IE = 0: no interrupts */
    /* Power-cycle the APU (clears the boot-sound state binjgb leaves on CH1), then the
     * standard hUGEDriver setup: NR52=$80, NR51=$FF, NR50=$77. */
    write_u8_raw(e, 0xFF26, 0x00);
    write_u8_raw(e, 0xFF26, 0x80);
    write_u8_raw(e, 0xFF25, 0xFF);
    write_u8_raw(e, 0xFF24, 0x77);
    /* Deterministic RAM: zero driver variables, scratch and the non-ROM song regions. */
    for (u32 a = 0x8000; a < 0xE000; ++a) write_u8_raw(e, (Address)a, 0);
    for (u32 a = 0xFF80; a < 0xFFFF; ++a) write_u8_raw(e, (Address)a, 0);
    apu_synchronize(e);
    e->audio_buffer.position = e->audio_buffer.data;
    e->apu_log.write_count = 0;
    memcpy(m->pristine, &e->state, sizeof(EmulatorState));

    /* 1-pole DC blocker, ~20 Hz corner (the GB's output capacitor is similar). */
    m->hpf_factor = exp(-2.0 * 3.14159265358979 * 20.0 / (double)sample_rate);
    return m;
}

void hm_destroy(HugeMachine* m) {
    if (!m) return;
    emulator_delete(m->e);
    free(m->pristine);
    free(m);
}

static void reset(HugeMachine* m) {
    Emulator* e = m->e;
    memcpy(&e->state, m->pristine, sizeof(EmulatorState));
    e->audio_buffer.position = e->audio_buffer.data;
    e->audio_buffer.freq_counter = 0;
    e->audio_buffer.divisor = 0;
    e->apu_log.write_count = 0;
    memset(m->shadow, 0, sizeof(m->shadow));
    memset(m->rom + HM_REGION_A_BEGIN, 0, HM_ROM_SIZE - HM_REGION_A_BEGIN);
    m->faulted = 0;
    m->restore_tempo = 0;
    m->driver_cycles = 0;
    m->cap_l = m->cap_r = 0.0;
}

/* Calls a driver entry point and runs until it returns to the sentinel. */
static int call(HugeMachine* m, u16 addr, u8 a, u16 bc, u16 de, u16 hl) {
    Emulator* e = m->e;
    if (m->faulted) return HM_ERR_FAULT;
    INTR.state = CPU_STATE_NORMAL;
    INTR.ime = FALSE;
    REG.SP = 0xFFFE;
    REG.SP -= 2;
    write_u8_raw(e, REG.SP, HM_SENTINEL & 0xFF);
    write_u8_raw(e, (Address)(REG.SP + 1), HM_SENTINEL >> 8);
    REG.PC = addr;
    REG.A = a;
    REG.BC = bc;
    REG.DE = de;
    REG.HL = hl;
    e->state.event = 0;
    Ticks start = TICKS;
    Ticks limit = start + HM_CALL_BUDGET;
    while (REG.PC != HM_SENTINEL) {
        emulator_step_internal(e);
        if (UNLIKELY((e->state.event & EMULATOR_EVENT_INVALID_OPCODE) || TICKS > limit)) {
            m->faulted = 1;
            return HM_ERR_FAULT;
        }
    }
    m->driver_cycles += TICKS - start;
    return HM_OK;
}

int hm_validate(const HugeImage* image) {
    const u8* mem = image->mem;
    const u32 d = HM_DESCRIPTOR;
    if (!in_song(d, 21)) return HM_ERR_IMAGE;
    u16 order_cnt_ptr = rd16(mem, d + 1);
    if (!in_song(order_cnt_ptr, 1)) return HM_ERR_IMAGE;
    u8 order_cnt = mem[order_cnt_ptr];
    if (order_cnt < 2 || (order_cnt & 1)) return HM_ERR_IMAGE;
    for (int ch = 0; ch < 4; ++ch) {
        u16 table = rd16(mem, d + 3 + 2 * ch);
        if (!in_song(table, order_cnt)) return HM_ERR_IMAGE;
        for (u32 i = 0; i < order_cnt; i += 2) {
            if (!in_song(rd16(mem, table + i), 192)) return HM_ERR_IMAGE;
        }
    }
    if (!in_song(rd16(mem, d + 11), 15 * 6) || !in_song(rd16(mem, d + 13), 15 * 6) ||
        !in_song(rd16(mem, d + 15), 15 * 6) || !in_song(rd16(mem, d + 19), 16 * 16)) {
        return HM_ERR_IMAGE;
    }
    return HM_OK;
}

/* Copies the song regions. ROM bytes go straight into the cartridge buffer; VRAM, cart
 * RAM and WRAM bytes go through binjgb's bus only where they changed. Returns nonzero if
 * the wave table bytes changed. */
static int copy_song(HugeMachine* m, const HugeImage* image) {
    const u8* src = image->mem;
    u16 waves = rd16(src, HM_DESCRIPTOR + 19);
    u16 old_waves = rd16(m->rom, HM_DESCRIPTOR + 19);
    int waves_changed = waves != old_waves;
    if (!waves_changed) {
        for (u32 i = 0; i < 256 && !waves_changed; ++i) {
            u32 a = (u32)waves + i;
            u8 cur = a < HM_ROM_SIZE ? m->rom[a] : m->shadow[a];
            waves_changed = cur != src[a];
        }
    }
    memcpy(m->rom + HM_REGION_A_BEGIN, src + HM_REGION_A_BEGIN, HM_ROM_SIZE - HM_REGION_A_BEGIN);
    for (u32 a = HM_ROM_SIZE; a < HM_REGION_A_END; ++a) {
        if (m->shadow[a] != src[a]) {
            m->shadow[a] = src[a];
            write_u8_raw(m->e, (Address)a, src[a]);
        }
    }
    for (u32 a = HM_REGION_B_BEGIN; a < HM_REGION_B_END; ++a) {
        if (m->shadow[a] != src[a]) {
            m->shadow[a] = src[a];
            write_u8_raw(m->e, (Address)a, src[a]);
        }
    }
    m->image_version = image->version;
    return waves_changed;
}

static void set_position(HugeMachine* m, const u8* mem, int order, int row) {
    u8 order_cnt = mem[rd16(mem, HM_DESCRIPTOR + 1)];
    u8 cur = (u8)(order * 2);
    if (cur >= order_cnt) cur = (u8)(order_cnt - 2);
    poke(m, HUGE_VAR_CURRENTORDER, cur);
    poke(m, HUGE_VAR_ROW, (u8)(row & 63));
    poke(m, HUGE_VAR_TICK, 0);
    poke(m, HUGE_VAR_ROWBREAK, 0);
    poke(m, HUGE_VAR_NEXTORDER, 0);
    for (int ch = 0; ch < 4; ++ch) {
        u16 table = rd16(mem, HM_DESCRIPTOR + 3 + 2 * ch);
        poke16(m, (u16)(HUGE_VAR_PATTERN1 + 2 * ch), rd16(mem, (u32)table + cur));
    }
}

/* Drops samples synthesized during host-side setup (init, seek): playback starts with the
 * first driver tick instead of a few hundred cycles of setup silence, and every tick then
 * yields one VBlank period of audio. */
static void discard_audio(HugeMachine* m) {
    Emulator* e = m->e;
    apu_synchronize(e);
    e->audio_buffer.position = e->audio_buffer.data;
    e->audio_buffer.divisor = 0;
    for (int j = 0; j < APU_CHANNEL_COUNT; ++j) APU.channel[j].accumulator = 0;
}

static int load_and_init(HugeMachine* m, const HugeImage* image) {
    if (hm_validate(image) != HM_OK) return HM_ERR_IMAGE;
    reset(m);
    copy_song(m, image);
    m->base_tempo = image->mem[HM_DESCRIPTOR];
    return call(m, HUGE_INIT, 0, 0, 0, HM_DESCRIPTOR);
}

int hm_start_song(HugeMachine* m, const HugeImage* image, int order, int row, int max_ticks,
                  int* seek_mode) {
    int r = load_and_init(m, image);
    if (r != HM_OK) return r;
    *seek_mode = HM_SEEK_FROM_START;
    const u8 target_order = (u8)(order * 2);
    const u8 target_row = (u8)(row & 63);
    for (int t = 0; t <= max_ticks; ++t) {
        if (hm_peek(m, HUGE_VAR_TICK) == 0 && hm_peek(m, HUGE_VAR_CURRENTORDER) == target_order &&
            hm_peek(m, HUGE_VAR_ROW) == target_row) {
            discard_audio(m);
            return HM_OK;
        }
        if (t == max_ticks) break;
        r = hm_tick_silent(m);
        if (r < 0) return r;
    }
    /* Not reached (budget or unreachable through jumps): driver-init state at the order
     * start, then the target pattern's earlier rows replayed silently. */
    *seek_mode = HM_SEEK_FROM_ORDER;
    r = load_and_init(m, image);
    if (r != HM_OK) return r;
    set_position(m, image->mem, order, 0);
    for (int guard = 0; guard < 64 * 256; ++guard) {
        if (hm_peek(m, HUGE_VAR_TICK) == 0 && hm_peek(m, HUGE_VAR_ROW) >= target_row) break;
        if (hm_peek(m, HUGE_VAR_CURRENTORDER) != target_order) break; /* jumped away */
        r = hm_tick_silent(m);
        if (r < 0) return r;
    }
    if (hm_peek(m, HUGE_VAR_CURRENTORDER) != target_order || hm_peek(m, HUGE_VAR_ROW) != target_row ||
        hm_peek(m, HUGE_VAR_TICK) != 0) {
        /* A break/jump inside the pattern skips the target row: start exactly there. */
        set_position(m, image->mem, order, row);
    }
    discard_audio(m);
    return HM_OK;
}

int hm_start_audition(HugeMachine* m, const HugeImage* image, int channel, int note,
                      int instrument, int detune) {
    if (channel < 0 || channel > 3 || note < 0 || note > 71 || instrument < 1 || instrument > 15 ||
        detune < 0 || detune > 15) {
        return HM_ERR_IMAGE;
    }
    /* 40y (vibrato speed 0) holds the period y units up from tick 1; noise is never tuned. */
    const u8 fx = detune > 0 && channel != 3 ? 0x4 : 0x0;
    const u8 param = fx ? (u8)detune : 0;
    if (hm_validate(image) != HM_OK) return HM_ERR_IMAGE;
    reset(m);
    copy_song(m, image);
    const u8* mem = image->mem;
    const u16 desc = HM_SCRATCH_BEGIN;          /* 21 bytes */
    const u16 order_cnt = HM_SCRATCH_BEGIN + 0x20;
    const u16 orders = HM_SCRATCH_BEGIN + 0x30; /* 4 one-entry order tables */
    const u16 note_track = HM_SCRATCH_BEGIN + 0x100;
    const u16 empty_track = HM_SCRATCH_BEGIN + 0x200;
    poke(m, desc, 0); /* 0 = 256 ticks per row: the note simply sustains */
    poke16(m, (u16)(desc + 1), order_cnt);
    for (int ch = 0; ch < 4; ++ch) {
        poke16(m, (u16)(desc + 3 + 2 * ch), (u16)(orders + 2 * ch));
        poke16(m, (u16)(orders + 2 * ch), ch == channel ? note_track : empty_track);
    }
    for (int i = 11; i < 21; ++i) poke(m, (u16)(desc + i), mem[HM_DESCRIPTOR + i]);
    poke(m, order_cnt, 2);
    for (int r = 0; r < 64; ++r) {
        u16 at = (u16)(r * 3);
        poke(m, (u16)(empty_track + at), 90);
        poke(m, (u16)(empty_track + at + 1), 0);
        poke(m, (u16)(empty_track + at + 2), 0);
        /* dn(note, instr, 40y or 000) on row 0; rows 1..63 empty. */
        poke(m, (u16)(note_track + at), r == 0 ? (u8)(note | ((instrument & 0x10) << 3)) : 90);
        poke(m, (u16)(note_track + at + 1), r == 0 ? (u8)(((instrument << 4) & 0xF0) | fx) : 0);
        poke(m, (u16)(note_track + at + 2), r == 0 ? param : 0);
    }
    m->base_tempo = 0;
    int r = call(m, HUGE_INIT, 0, 0, 0, desc);
    discard_audio(m);
    return r;
}

void hm_apply_image(HugeMachine* m, const HugeImage* image) {
    if (m->faulted || hm_validate(image) != HM_OK) return;
    int waves_changed = copy_song(m, image);
    const u8* mem = image->mem;
    /* hUGE_init copied these from the descriptor; refresh the copies. */
    for (int i = 0; i < 18; ++i) poke(m, (u16)(HUGE_VAR_ORDER1 + i), mem[HM_DESCRIPTOR + 3 + i]);
    u8 order_cnt = mem[rd16(mem, HM_DESCRIPTOR + 1)];
    poke(m, HUGE_VAR_ORDERCNT, order_cnt);
    u8 cur = hm_peek(m, HUGE_VAR_CURRENTORDER);
    if (cur >= order_cnt) {
        cur = (u8)(order_cnt - 2);
        poke(m, HUGE_VAR_CURRENTORDER, cur);
    }
    u8 next = hm_peek(m, HUGE_VAR_NEXTORDER); /* pending Bxx target, 1-based */
    if (next != 0 && (u32)(next - 1) * 2 >= order_cnt) poke(m, HUGE_VAR_NEXTORDER, 1);
    for (int ch = 0; ch < 4; ++ch) {
        u16 table = rd16(mem, HM_DESCRIPTOR + 3 + 2 * ch);
        poke16(m, (u16)(HUGE_VAR_PATTERN1 + 2 * ch), rd16(mem, (u32)table + cur));
    }
    u8 tempo = mem[HM_DESCRIPTOR];
    if (tempo != m->base_tempo) {
        /* The song's base speed was edited: apply it now (overrides an earlier Fxx). */
        m->base_tempo = tempo;
        u8 tick = hm_peek(m, HUGE_VAR_TICK);
        if (tempo != 0 && tick >= tempo) {
            /* The current row is already longer than the new speed. Rewinding the tick
             * would reach tick 0, which replays the row's notes; instead let the driver end
             * the row on the next tick, then install the new speed (restore_tempo). */
            poke(m, HUGE_VAR_TICKSPERROW, (u8)(tick + 1));
            m->restore_tempo = 1;
        } else {
            poke(m, HUGE_VAR_TICKSPERROW, tempo);
            m->restore_tempo = 0;
        }
    }
    if (waves_changed) poke(m, HUGE_VAR_CURRENTWAVEVAR, HM_NO_WAVE);
}

uint32_t hm_image_version(const HugeMachine* m) { return m->image_version; }

int hm_mute_channel(HugeMachine* m, int channel, int mute) {
    if (channel < 0 || channel > 3) return HM_ERR_IMAGE;
    return call(m, HUGE_MUTE_CHANNEL, 0, (u16)((channel << 8) | (mute ? 1 : 0)), 0, 0);
}

void hm_position(const HugeMachine* m, int* order, int* row, int* tick) {
    HugeMachine* mm = (HugeMachine*)m;
    *order = hm_peek(mm, HUGE_VAR_CURRENTORDER) / 2;
    *row = hm_peek(mm, HUGE_VAR_ROW);
    *tick = hm_peek(mm, HUGE_VAR_TICK);
}

/* After the row that a mid-row speed edit shortened has ended, install the edited speed
 * (before the next row's tick 0, so an Fxx on that row still takes precedence). */
static void finish_row_tempo(HugeMachine* m) {
    if (m->restore_tempo && hm_peek(m, HUGE_VAR_TICK) == 0) {
        poke(m, HUGE_VAR_TICKSPERROW, m->base_tempo);
        m->restore_tempo = 0;
    }
}

int hm_tick(HugeMachine* m, float* out) {
    Emulator* e = m->e;
    Ticks end = TICKS + HM_TICK_CYCLES;
    if (call(m, HUGE_DOSOUND, 0, 0, 0, 0) != HM_OK) return HM_ERR_FAULT;
    finish_row_tempo(m);
    /* The CPU idles until the next VBlank; nothing else on the bus runs (LCD off, no
     * timer/serial/interrupts), so jump straight there and let the APU catch up. */
    if (TICKS < end) TICKS = end;
    apu_synchronize(e);
    AudioBuffer* ab = &e->audio_buffer;
    u32 frames = audio_buffer_get_frames(ab);
    if (frames > HM_MAX_TICK_FRAMES) frames = HM_MAX_TICK_FRAMES;
    const float* src = ab->data;
    const double k = m->hpf_factor;
    for (u32 i = 0; i < frames; ++i) {
        double l = src[2 * i], r = src[2 * i + 1];
        double ol = l - m->cap_l, orr = r - m->cap_r;
        m->cap_l = l - ol * k;
        m->cap_r = r - orr * k;
        out[2 * i] = (float)ol * HM_OUTPUT_GAIN;
        out[2 * i + 1] = (float)orr * HM_OUTPUT_GAIN;
    }
    ab->position = ab->data;
    return (int)frames;
}

int hm_tick_silent(HugeMachine* m) {
    Emulator* e = m->e;
    Ticks end = TICKS + HM_TICK_CYCLES;
    if (call(m, HUGE_DOSOUND, 0, 0, 0, 0) != HM_OK) return HM_ERR_FAULT;
    finish_row_tempo(m);
    apu_synchronize(e);
    if (TICKS < end) {
        /* Advance the APU frame sequencer (lengths, sweep, envelopes) through the idle
         * part of the frame without synthesizing samples (same schedule as apu_update). */
        Ticks idle = end - TICKS;
        if (APU.enabled) {
            Ticks remaining = idle;
            while (remaining) {
                Ticks next_seq_ticks = NEXT_MODULO(APU.sync_ticks, FRAME_SEQUENCER_TICKS);
                if (next_seq_ticks == FRAME_SEQUENCER_TICKS) {
                    APU.frame = (APU.frame + 1) % FRAME_SEQUENCER_COUNT;
                    switch (APU.frame) {
                        case 2: case 6: update_sweep(e); /* Fallthrough. */
                        case 0: case 4: update_lengths(e); break;
                        case 7: update_envelopes(e); break;
                    }
                }
                Ticks ticks = MIN(next_seq_ticks, remaining);
                APU.sync_ticks += ticks;
                remaining -= ticks;
            }
        } else {
            APU.sync_ticks += idle;
        }
        TICKS = end;
    }
    discard_audio(m);
    return 0;
}

uint64_t hm_driver_cycles(const HugeMachine* m) { return m->driver_cycles; }

void hm_set_apu_logging(HugeMachine* m, int enabled) {
    m->e->config.log_apu_writes = enabled ? TRUE : FALSE;
    m->e->apu_log.write_count = 0;
}

int hm_apu_log(HugeMachine* m, uint8_t* pairs, int capacity) {
    ApuLog* log = &m->e->apu_log;
    int n = (int)log->write_count;
    if (n > capacity) n = capacity;
    for (int i = 0; i < n; ++i) {
        pairs[2 * i] = log->writes[i].addr;
        pairs[2 * i + 1] = log->writes[i].value;
    }
    log->write_count = 0;
    return n;
}
