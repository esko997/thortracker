// SPDX-License-Identifier: ISC
// Maxmod player/mixer: Copyright (c) 2008 Mukunda Johnson,
// Copyright (c) 2021-2026 Antonio Niño Díaz. See third_party/maxmod/COPYING.
#ifndef THORTRACKER_GBA_MAXMOD_H
#define THORTRACKER_GBA_MAXMOD_H
// The two upstream player translation units are class members, not process globals.
// All writable state (including former file-static state) is owned by this object.
// Only immutable lookup tables are inline static constexpr. No context switching,
// TLS, mutex, callbacks, allocation, or independent replacement tracker player.
#define __HEADLESS__ 1
#define MM_MMUTIL_DEVKITPRO_COMPAT 1
#include <cassert>
#include <cstdint>
#include <cstring>
#include <maxmod_headless.h>
#include <mm_mas.h>
#include <mm_msl.h>
#include "core/mas.h"
#include "headless/mixer.h"
#include "gba_image.h"

struct GbaMaxmod {
    mm_module_channel module_channels[8];
    mm_active_channel active_channels[8];
    mm_mixer_channel mixing_channels[8];
    mm_mixer_channel* mm_mix_channels;
    mm_word mm_bpmdv;
    uint8_t* bank;
    const GbaImage* image;
    const uint8_t* audition_begin;
    const uint8_t* audition_end;
    const uint8_t* pattern_limit(mpl_layer_information* layer, const uint8_t* ptr) {
        if (audition_begin && uintptr_t(ptr) >= uintptr_t(audition_begin) &&
            uintptr_t(ptr) < uintptr_t(audition_end)) return audition_end;
        if (layer->position >= image->orders) return nullptr;
        unsigned pattern = layer->songadr->sequence[layer->position];
        const auto* begin = image->bytes + image->rows[pattern][0];
        const auto* end = image->bytes + image->rows[pattern][image->row_count[pattern]];
        return uintptr_t(ptr) >= uintptr_t(begin) && uintptr_t(ptr) < uintptr_t(end) ? end : nullptr;
    }
    int16_t mix_buffer[1232 * 2];

    mm_word mmGetModuleCount() { return 1; }
    uintptr_t mmGetModuleAddress(mm_word id) {
        auto* head = reinterpret_cast<msl_head*>(bank);
        return id == 0 ? uintptr_t(bank + head->sampleTable[head->head_data.sampleCount]) : 0;
    }
    mm_mas_gba_sample* mmGetSampleAddress(mm_word id) {
        auto* head = reinterpret_cast<msl_head*>(bank);
        return reinterpret_cast<mm_mas_gba_sample*>(bank + head->sampleTable[id] + 8);
    }
    void mmMixerSetFreq(int ch, mm_word rate) { mm_mix_channels[ch].freq = rate << 2; }
    void mmMixerMulFreq(int ch, mm_word factor) {
        mm_mix_channels[ch].freq = (mm_mix_channels[ch].freq * factor) >> 10;
    }
    void mmMixerStopChannel(int ch) { mm_mix_channels[ch].active = false; }
    void mmMixerSetVolume(int ch, mm_word vol) { mm_mix_channels[ch].vol = vol >> 8; }
    void mmMixerSetPan(int ch, mm_byte pan) { mm_mix_channels[ch].pan = pan; }

#include "core/mas.c"
#include "core/mas_arm.c"

    void init(GbaImage* loaded) {
        std::memset(this, 0, sizeof(*this));
        image = loaded;
        bank = loaded->bytes + 64;
        mm_achannels = active_channels;
        mm_pchannels = module_channels;
        mm_mix_channels = mixing_channels;
        mm_num_mch = mm_num_ach = 8;
        mm_ch_mask = 255;
        mm_bpmdv = 39420; // GBA MM_MIX_16KHZ, not a rounded 16384 Hz target.
        mmSetModuleVolume(1024);
        mmSetJingleVolume(1024);
        mmSetModuleTempo(1024);
        mmSetModulePitch(1024);
        mmSetJingleTempo(1024);
        mmSetJinglePitch(1024);
        mmStart(0, MM_PLAY_LOOP);
    }

    // Adapted from upstream source/gba/mixer.c MM_GBA_MIXER_IN_C, not the
    // headless mixer's different gain/panning rounding. Bounded eight voices.
    // Hardware DMA packing is replaced with interleaved signed bytes.
    void mix(int8_t* output, unsigned frames) {
        std::memset(mix_buffer, 0, frames * 2 * sizeof(*mix_buffer));
        for (unsigned ch = 0; ch < 8; ++ch) {
            auto& c = mixing_channels[ch];
            if (!c.active || !c.freq || !c.vol) continue;
            auto* s = reinterpret_cast<mm_mas_gba_sample*>(c.src - sizeof(mm_mas_gba_sample));
            uint64_t read = c.read;
            const uint64_t end = uint64_t(s->length) << 12;
            const uint64_t loop = uint64_t(s->loop_length) << 12;
            const int left = ((256 - c.pan) * c.vol) >> 8;
            const int right = (c.pan * c.vol) >> 8;
            for (unsigned f = 0; f < frames; ++f) {
                // The original headless mixer reads past short loops at high
                // pitch or a 9xx offset. Wrap before reading, in bounded time.
                if (read >= end) {
                    if (s->loop_length == 0xffffffffu) { c.active = false; break; }
                    read = end - loop + (read - end) % loop;
                }
                const int value = int(s->data[read >> 12]) - 128;
                mix_buffer[f * 2] += (value * left) >> 5;
                mix_buffer[f * 2 + 1] += (value * right) >> 5;
                read += c.freq;
            }
            if (read >= end) {
                if (s->loop_length == 0xffffffffu) c.active = false;
                else read = end - loop + (read - end) % loop;
            }
            c.read = uint32_t(read);
        }
        for (unsigned i = 0; i < frames * 2; ++i) {
            int v = mix_buffer[i] >> 3;
            output[i] = int8_t(v < -128 ? -128 : v > 127 ? 127 : v);
        }
    }
};
#endif
