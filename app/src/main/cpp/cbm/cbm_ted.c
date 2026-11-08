
// plus4emu -- portable Commodore Plus/4 emulator
// Copyright (C) 2003-2008 Istvan Varga <istvanv@users.sourceforge.net>
// http://sourceforge.net/projects/plus4emu/
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

/* Adapted for Thor Tracker (the "c16-ted" engine, docs/AUDIO_ENGINE.md "VIC-20 and C16/Plus/4", "Chip units") from
 * plus4emu's src/ted_snd.cpp and the sound parts of ted_write.cpp, ted_init.cpp, ted_main.cpp and ted.hpp,
 * github.com/istvan-v/plus4emu commit 939e65233b227a47e5523eca149de8247176e28f (sha256 ted_snd.cpp
 * be2b86ea8afe3e742f65ae17cf591da7158de638d2a21d2f35230baab469d030, ted_write.cpp
 * e18a6e94173ed5b76ceb6257383d37310f08ce74555c029cd68892980510bd17, ted.hpp
 * ecbd508839243a7941c413a216ce8788cc5ee1a1aa4ba2f65162f4cff0ad7047, ted_init.cpp
 * bf3c7812faa0be882ce66680cc48dc0cd131eaaf8048a4605b69e5390dea4fbf, ted_main.cpp
 * 94b560dc95ee1653a6d8b755d3eba2a8f74fb463e5f167858a831bfd1619f806). Ported unchanged from C++ to C: the volume, mix
 * and distortion tables (copied exactly), write_register_FF0E/FF0F/FF10/FF11/FF12's sound parts,
 * calculateSoundOutput, updateSoundOutput, the reset values of initRegisters and TED7360::run's 4-cycle sound step
 * (cycle_count). Changed, all plumbing: the TED is only its sound part, clocked by the caller in single clocks; each
 * output change goes to blip_buf at its single clock instead of playSample; mute masks (a muted voice is left out of
 * the mix index, a muted DAC lane silences D/A mode) applied to a second copy of the output, so every counter, state,
 * decay, noise register and phase runs as unmuted. Thor Tracker as a whole is GPL-3.0-or-later. */
#include "cbm_ted.h"

#include <string.h>

static const uint8_t soundVolumeTable[16] = {
      0,     6,    16,    26,    36,    46,    56,    66,
     75,    75,    75,    75,    75,    75,    75,    75
};

static const uint8_t soundMixTable[128] = {
  0, 0, 0, 0,  0, 0, 0, 0,  0, 0, 0, 0,  1, 1, 1, 1,
  0, 0, 1, 1,  0, 0, 1, 1,  0, 0, 1, 1,  1, 1, 2, 2,
  1, 0, 1, 0,  1, 0, 1, 0,  1, 0, 1, 0,  2, 1, 2, 1,
  0, 0, 1, 1,  0, 0, 1, 1,  0, 0, 1, 1,  1, 1, 2, 2,
  0, 0, 0, 0,  0, 0, 0, 0,  1, 1, 1, 1,  1, 1, 1, 1,
  1, 1, 1, 1,  1, 1, 1, 1,  2, 2, 2, 2,  2, 2, 2, 2,
  0, 0, 0, 0,  0, 0, 0, 0,  1, 1, 1, 1,  1, 1, 1, 1,
  1, 1, 1, 1,  1, 1, 1, 1,  2, 2, 2, 2,  2, 2, 2, 2
};

static const int16_t soundDistortionTable[301] = {
      0,    66,   135,   205,   276,   347,   419,   491,
    563,   635,   708,   781,   854,   927,  1000,  1073,
   1146,  1219,  1293,  1366,  1440,  1513,  1587,  1660,
   1734,  1808,  1882,  1955,  2029,  2103,  2177,  2251,
   2325,  2399,  2473,  2547,  2621,  2695,  2770,  2844,
   2918,  2992,  3067,  3141,  3215,  3290,  3364,  3438,
   3513,  3587,  3662,  3737,  3811,  3886,  3961,  4035,
   4110,  4185,  4260,  4334,  4409,  4484,  4559,  4634,
   4709,  4784,  4860,  4935,  5010,  5085,  5160,  5236,
   5311,  5387,  5462,  5537,  5613,  5689,  5764,  5840,
   5916,  5991,  6067,  6143,  6219,  6295,  6371,  6447,
   6523,  6599,  6675,  6752,  6828,  6904,  6981,  7057,
   7134,  7210,  7287,  7364,  7440,  7517,  7594,  7671,
   7748,  7825,  7902,  7979,  8056,  8134,  8211,  8288,
   8366,  8443,  8521,  8599,  8676,  8754,  8832,  8910,
   8988,  9066,  9144,  9222,  9300,  9379,  9457,  9536,
   9614,  9693,  9771,  9850,  9929, 10008, 10087, 10166,
  10245, 10324, 10403, 10482, 10562, 10641, 10721, 10801,
  10880, 10960, 11040, 11120, 11200, 11280, 11360, 11440,
  11521, 11601, 11682, 11762, 11843, 11924, 12005, 12085,
  12166, 12248, 12329, 12410, 12491, 12573, 12654, 12736,
  12818, 12900, 12981, 13063, 13146, 13228, 13310, 13392,
  13475, 13557, 13640, 13723, 13805, 13888, 13971, 14055,
  14138, 14221, 14304, 14388, 14472, 14555, 14639, 14723,
  14807, 14891, 14975, 15060, 15144, 15228, 15313, 15398,
  15483, 15568, 15653, 15738, 15823, 15908, 15994, 16079,
  16165, 16251, 16337, 16423, 16509, 16595, 16681, 16768,
  16854, 16941, 17028, 17115, 17202, 17289, 17376, 17464,
  17551, 17639, 17726, 17814, 17902, 17990, 18078, 18167,
  18255, 18344, 18432, 18521, 18610, 18699, 18788, 18877,
  18967, 19056, 19146, 19236, 19326, 19416, 19506, 19596,
  19687, 19777, 19868, 19959, 20049, 20141, 20232, 20323,
  20414, 20506, 20598, 20690, 20782, 20874, 20966, 21058,
  21151, 21244, 21336, 21429, 21522, 21615, 21709, 21802,
  21896, 21990, 22084, 22178, 22272, 22366, 22461, 22555,
  22650, 22745, 22840, 22935, 23030, 23126, 23222, 23317,
  23413, 23509, 23606, 23702, 23798, 23895, 23992, 24089,
  24186, 24283, 24381, 24478, 24576
};

static const uint32_t soundDecayCycles = CBT_SOUND_DECAY_CYCLES;

/* The mix index with the mutes applied: outside D/A mode a muted voice 1 clears bit 3 (its enable), a muted voice 2
 * clears bits 4-5; in D/A mode (bit 6) only the DAC lane's mute applies, silencing the output. */
static uint8_t heard_output(const CbTed* t) {
    if (t->mixIndex & 0x40) return (t->mute & 4) ? 0 : (uint8_t)(soundMixTable[t->mixIndex] * t->soundVolume);
    uint8_t idx = t->mixIndex;
    if (t->mute & 1) idx &= (uint8_t)~0x08;
    if (t->mute & 2) idx &= (uint8_t)~0x30;
    return (uint8_t)(soundMixTable[idx] * t->soundVolume);
}

static void updateSoundOutput(CbTed* t) {
    t->mixIndex = (uint8_t)(t->soundFlags | (t->soundChannel1State << 2) | (t->soundChannel2State << 1) |
                            (t->soundChannel2NoiseState & 0x01));
    t->soundOutput = (uint8_t)(soundMixTable[t->mixIndex] * t->soundVolume);
    t->heard = heard_output(t);
}

void cbt_reset(CbTed* t) {
    memset(t, 0, sizeof *t);
    t->cycle_count = 0;
    t->soundChannel1Cnt = 0x03FF;
    t->soundChannel1Reload = 0x03FF;
    t->soundChannel2Cnt = 0x03FF;
    t->soundChannel2Reload = 0x03FF;
    t->prvSoundChannel1Overflow = 0;
    t->prvSoundChannel2Overflow = 0;
    t->soundChannel1Decay = soundDecayCycles;
    t->soundChannel2Decay = soundDecayCycles;
    t->soundChannel1State = 1;
    t->soundChannel2State = 1;
    t->soundChannel2NoiseState = 0xFF;
    t->prvCycleCount = 0;
    t->soundFlags = 0x00;
    t->soundVolume = 0x00;
    t->soundOutput = 0x00;
    t->prvSoundOutput = 0x00;
}

static uint16_t reload_of(int tmp) { return (uint16_t)((((tmp + 1) ^ 0x03FF) & 0x03FF) + 1); }

/* write_register_FF0E .. FF12 (sound parts). A write happens between single clocks: cycle_count is the phase the next
 * single clock sees. */
void cbt_write(CbTed* t, int reg, int val) {
    const uint8_t value = (uint8_t)val;
    switch (reg) {
    case 0x0E:
        t->regs[0x0E] = value;
        t->soundChannel1Reload = reload_of((int)value | ((int)(t->regs[0x12] & 0x03) << 8));
        break;
    case 0x0F:
        t->regs[0x0F] = value;
        t->soundChannel2Reload = reload_of((int)value | ((int)(t->regs[0x10] & 0x03) << 8));
        break;
    case 0x10:
        t->regs[0x10] = value;
        t->soundChannel2Reload = reload_of((int)t->regs[0x0F] | ((int)(value & 0x03) << 8));
        break;
    case 0x11:
        t->soundFlags = (uint8_t)((value >> 1) & 0x78);
        t->soundVolume = soundVolumeTable[value & 0x0F];
        if ((value ^ t->regs[0x11]) & 0x80) {
            if (value & 0x80) {
                t->soundChannel1State = 1;
                t->soundChannel2State = 1;
                t->prvCycleCount = t->cycle_count;
            } else {
                const uint8_t nBits = (uint8_t)((t->prvCycleCount - t->cycle_count) & 3);
                if (nBits > 0) {
                    // shift in '1' bits at single clock frequency
                    t->soundChannel2NoiseState =
                        (uint8_t)((t->soundChannel2NoiseState << nBits) | (uint8_t)(0xFF >> (8 - nBits)));
                }
            }
        }
        t->regs[0x11] = value;
        updateSoundOutput(t);
        break;
    case 0x12:
        t->regs[0x12] = value;
        t->soundChannel1Reload = reload_of((int)t->regs[0x0E] | ((int)(value & 0x03) << 8));
        break;
    default:
        break;
    }
}

/* calculateSoundOutput without playSample: returns the distorted output of this sound cycle. */
static int16_t calculateSoundOutput(CbTed* t) {
    if (t->regs[0x11] & 0x80) {
        // DAC mode
        t->soundChannel1Cnt = t->soundChannel1Reload;
        t->soundChannel2Cnt = t->soundChannel2Reload;
        t->soundChannel1State = 1;
        t->soundChannel2State = 1;
        // shift in '1' bits at single clock frequency
        t->soundChannel2NoiseState = (uint8_t)((t->soundChannel2NoiseState << (t->prvCycleCount + 1)) |
                                               (uint8_t)(0xFF >> (7 - t->prvCycleCount)));
        t->prvCycleCount = 3;
        if (t->soundChannel1Cnt == 1) {
            if (t->soundChannel1Decay > 0U && t->soundChannel1Decay <= soundDecayCycles) {
                t->soundChannel1Decay--;
                t->soundChannel1State = 0;
            }
            t->prvSoundChannel1Overflow = 1;
        } else {
            t->soundChannel1Decay = soundDecayCycles;
            t->prvSoundChannel1Overflow = 0;
        }
        if (t->soundChannel2Cnt == 1) {
            if (t->soundChannel2Decay > 0U && t->soundChannel2Decay <= soundDecayCycles) {
                t->soundChannel2Decay--;
                t->soundChannel2State = 0;
            }
            t->prvSoundChannel2Overflow = 1;
        } else {
            t->soundChannel2Decay = soundDecayCycles;
            t->prvSoundChannel2Overflow = 0;
        }
    } else {
        // channel 1
        t->soundChannel1Cnt--;
        const int soundChannel1Overflow = !(t->soundChannel1Cnt);
        if (soundChannel1Overflow) {
            t->soundChannel1Cnt = t->soundChannel1Reload;
            if (!t->prvSoundChannel1Overflow) {
                t->soundChannel1Decay = soundDecayCycles;
                t->soundChannel1State = (uint8_t)((~t->soundChannel1State) & 1);
                updateSoundOutput(t);
            }
        }
        t->prvSoundChannel1Overflow = (uint8_t)soundChannel1Overflow;
        t->soundChannel1Decay--;
        if (!t->soundChannel1Decay) {
            t->soundChannel1State = 1;
            updateSoundOutput(t);
        }
        // channel 2
        t->soundChannel2Cnt--;
        const int soundChannel2Overflow = !(t->soundChannel2Cnt);
        if (soundChannel2Overflow) {
            t->soundChannel2Cnt = t->soundChannel2Reload;
            if (!t->prvSoundChannel2Overflow) {
                t->soundChannel2Decay = soundDecayCycles;
                t->soundChannel2State = (uint8_t)((~t->soundChannel2State) & 1);
                // channel 2 noise, 8 bit polycnt (10110010)
                uint8_t tmp = t->soundChannel2NoiseState & 0xB2;
                tmp = tmp ^ (tmp >> 1);
                tmp = tmp ^ (tmp >> 2);
                tmp = tmp ^ (tmp >> 4);
                t->soundChannel2NoiseState = (uint8_t)(t->soundChannel2NoiseState << 1);
                t->soundChannel2NoiseState |= (uint8_t)(tmp & 1);
                updateSoundOutput(t);
            }
        }
        t->prvSoundChannel2Overflow = (uint8_t)soundChannel2Overflow;
        t->soundChannel2Decay--;
        if (!t->soundChannel2Decay) {
            t->soundChannel2State = 1;
            updateSoundOutput(t);
        }
    }
    // mix sound outputs (plus4emu's, and the heard copy that is played)
    t->prvSoundOutput = t->soundOutput;
    const int16_t tmp = soundDistortionTable[(size_t)t->prvHeard + (size_t)t->heard];
    t->prvHeard = t->heard;
    return tmp;
}

/* TED7360::run's sound step: at a single clock where cycle_count is 0 the generators step and cycle_count becomes 4;
 * every single clock decrements it. */
void cbt_run(CbTed* t, blip_t* blip, unsigned from, unsigned to) {
    unsigned now = from;
    while (now < to) {
        if (!t->cycle_count) {
            const int out = calculateSoundOutput(t);
            if (out != t->level) {
                blip_add_delta(blip, now, out - t->level);
                t->level = out;
            }
            t->cycle_count = 4;
        }
        const unsigned skip = t->cycle_count < to - now ? t->cycle_count : to - now;
        t->cycle_count = (uint8_t)(t->cycle_count - skip);
        now += skip;
    }
}

void cbt_mute(CbTed* t, unsigned mask) {
    t->mute = mask & 7;
    t->heard = heard_output(t);
}
