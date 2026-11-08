/* The WonderSwan sound unit: a C port of the ares WonderSwan APU (ISC; third_party/ares-ws-apu/LICENSE) in its
 * cycle-accurate mode, with the corrections docs/AUDIO_ENGINE.md ("WonderSwan: The sound unit") lists. Both models:
 * the original WonderSwan (four channels, 16 KiB RAM) and the WonderSwan Color (plus Sound DMA and Hyper Voice).
 *
 * Time advances in 128-cycle blocks (one 24 kHz output frame each) at 3072000 Hz; port and RAM writes are applied
 * between blocks. ws_apu_block advances one block in closed form; ws_apu_block_reference runs the specification's
 * per-cycle loop (the host test requires both to agree bit for bit). Sound DMA reads the mapped ROM (the image's
 * data region at WS_DMA_BASE); a transfer hook reports every transfer (the VGM export logs CH2 transfers as $89
 * writes). The mute mask (bit c = lane c: CH1..CH4, Hyper Voice) discards a lane's output after computing it: no state
 * depends on it. No allocation; one thread at a time. */
#ifndef THORTRACKER_WS_APU_H_
#define THORTRACKER_WS_APU_H_

#include <stdint.h>

#include "ws_image.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Called for every Sound DMA transfer: `cycle` = its cycle (block start + 122, counted from the last reset), target
 * 0 = CH2's volume byte ($89), 1 = Hyper Voice; data as transferred (0 while held). */
typedef void (*WsApuDmaHook)(void* ctx, uint64_t cycle, int target, uint8_t data);

typedef struct WsApuChannel {
    uint16_t pitch;   /* 11-bit frequency register d */
    uint16_t counter; /* 11-bit down counter */
    uint8_t vol_l, vol_r;
    uint8_t enable;
    uint8_t offset;   /* 5-bit wave position */
} WsApuChannel;

typedef struct WsApuDma {
    uint32_t source, length;               /* 20 bits each */
    uint32_t shadow_source, shadow_length; /* reloaded by a loop */
    uint8_t rate, hold, loop, target, direction, enable;
    uint32_t clock;
} WsApuDma;

typedef struct WsApuHyper {
    uint8_t shift, scaling, speed, enable, chan_mode;
    uint8_t flip;            /* the next manual / stereo byte goes right */
    uint8_t latch_l, latch_r;
    int16_t out_l, out_r;
    uint32_t clock;
} WsApuHyper;

typedef struct WsApu {
    int model;                         /* WS_MODEL_* */
    WsApuChannel ch[4];
    /* channel 2 voice */
    uint8_t voice, voice_left_full, voice_left_half, voice_right_full, voice_right_half;
    /* channel 3 sweep */
    int8_t sweep_value;
    uint8_t sweep_time, sweep;
    int32_t sweep_counter;
    /* channel 4 noise */
    uint8_t noise_mode, noise_update, noise;
    uint16_t lfsr;                     /* 15 bits */
    uint8_t noise_output;
    WsApuHyper hv;
    WsApuDma dma;
    /* global */
    uint8_t wave_base, speaker_enable, speaker_shift, headphone_enable;
    uint16_t sweep_clock;              /* 13 bits */
    uint8_t reg95, reg9e;              /* stored, not modelled */
    uint64_t cycle;                    /* cycles since the reset (block starts) */
    uint8_t ram[65536];
    /* wiring (kept across ws_apu_reset) */
    int speaker;                       /* output path: 0 headphones, 1 speaker */
    unsigned mute;                     /* bit c: lane c's output discarded */
    const uint8_t* rom;                /* DMA memory: rom[a - rom_base] for rom_base <= a < rom_base + rom_size */
    uint32_t rom_base, rom_size;
    WsApuDmaHook hook;
    void* hook_ctx;
} WsApu;

/* Every chip field to zero for `model` (the RAM too); wiring (output path, mute, ROM mapping, hook) is kept. */
void ws_apu_reset(WsApu* apu, int model);
/* Wiring. */
void ws_apu_map_rom(WsApu* apu, uint32_t base, const uint8_t* bytes, uint32_t size);
void ws_apu_set_hook(WsApu* apu, WsApuDmaHook hook, void* ctx);
void ws_apu_set_output(WsApu* apu, int speaker);
void ws_apu_set_mute(WsApu* apu, unsigned mask);

/* A port write ($4A..$9F; Color ports are ignored on the original model) or a RAM write. */
void ws_apu_write_port(WsApu* apu, int port, uint8_t val);
void ws_apu_write_ram(WsApu* apu, uint16_t addr, uint8_t val);
void ws_apu_write(WsApu* apu, const WsWrite* w);

/* One 128-cycle block; out[0], out[1] = the block's frame (left, right) as the output path renders it. */
void ws_apu_block(WsApu* apu, int16_t out[2]);
/* The same block by the specification's per-cycle loop (test reference; slow). */
void ws_apu_block_reference(WsApu* apu, int16_t out[2]);
/* One block of Sound DMA only (the VGM export: nothing else runs; transfers still reach CH2 / Hyper Voice state). */
void ws_apu_dma_block(WsApu* apu);

/* A live edit moved a byte-identical DMA sample: source and shadow source += delta (20 bits). */
void ws_apu_dma_rebase(WsApu* apu, int32_t delta);

/* Every chip field of a and b is equal (wiring and the RAM included; the mute mask excluded). */
int ws_apu_same_state(const WsApu* a, const WsApu* b);
/* The Sound DMA units of a and b are equal (every field, the clock included). */
int ws_apu_same_dma(const WsApuDma* a, const WsApuDma* b);
/* Hyper Voice conversion of a latched byte with the unit's current shift and scaling (the wiki's table). */
int16_t ws_apu_hv_convert(const WsApu* apu, uint8_t v);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_WS_APU_H_ */
