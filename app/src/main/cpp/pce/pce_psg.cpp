// The C interface of Mednafen's HuC6280 PSG (pce_psg.h). Mednafen's pce_psg.cpp (unmodified) is compiled here, once,
// against the minimal shim in third_party/mednafen-pce-psg/shim (include it on the path: <mednafen/mednafen.h>,
// <trio/trio.h>). Its own "pce_psg.h" resolves next to it, never to this directory's header of the same name.
#include "../third_party/mednafen-pce-psg/pce_psg.cpp"

#include "pce_psg.h"

#include <new>

#include "blip_buf.h"

using Mednafen::PCE_PSG;

struct PcePsg {
    PCE_PSG* psg;
    blip_t* blip[2];
    int32_t level[2];  // integrated deltas (Mednafen's scale x 2048)
    int32_t last[2];   // the value last fed to blip (level >> PCS_LEVEL_SHIFT)
    int32_t lo;        // timestamp of the current tick's start (the previous end & 3)
    int32_t hr[2][PCS_HR_SIZE + PCS_HR_PADDING];
};

static void clear_buffers(PcePsg* s) {
    memset(s->hr, 0, sizeof s->hr);
    for (int k = 0; k < 2; ++k) {
        blip_clear(s->blip[k]);
        s->level[k] = s->last[k] = 0;
    }
    s->lo = 0;
}

extern "C" PcePsg* pcs_create(uint32_t sample_rate, int max_frames) {
    PcePsg* s = static_cast<PcePsg*>(calloc(1, sizeof(PcePsg)));
    if (!s) return nullptr;
    s->psg = new (std::nothrow) PCE_PSG(s->hr[0], s->hr[1], PCE_PSG::REVISION_HUC6280);
    for (int k = 0; k < 2; ++k) {
        s->blip[k] = blip_new(max_frames);
        if (s->blip[k]) blip_set_rates(s->blip[k], PCS_NATIVE_HZ, sample_rate);
    }
    if (!s->psg || !s->blip[0] || !s->blip[1]) {
        pcs_destroy(s);
        return nullptr;
    }
    pcs_reset(s);
    return s;
}

extern "C" void pcs_destroy(PcePsg* s) {
    if (!s) return;
    delete s->psg;
    for (int k = 0; k < 2; ++k)
        if (s->blip[k]) blip_delete(s->blip[k]);
    free(s);
}

extern "C" void pcs_reset(PcePsg* s) {
    // Power() keeps each channel's last filtered output and timestamp ("shouldn't be altered"), which would leave a
    // step against the cleared buffers; a reconstructed object is exactly a fresh chip (construction allocates nothing).
    s->psg->~PCE_PSG();
    new (s->psg) PCE_PSG(s->hr[0], s->hr[1], PCE_PSG::REVISION_HUC6280);
    s->psg->SetVolume(1.0);
    s->psg->Power(0);
    clear_buffers(s);
    s->psg->ResetTS(0);
}

extern "C" void pcs_write(PcePsg* s, int32_t cycle, unsigned reg, unsigned val) {
    s->psg->Write(s->lo + cycle, static_cast<uint8>(reg), static_cast<uint8>(val));
}

extern "C" int pcs_end_tick(PcePsg* s, int32_t length) {
    const int32_t end = s->lo + length;
    s->psg->Update(end);
    const int count = end >> 2;
    for (int k = 0; k < 2; ++k) {
        int32_t* hr = s->hr[k];
        int32_t level = s->level[k], last = s->last[k];
        for (int i = 0; i < count; ++i) {
            level += hr[i];
            hr[i] = 0;
            const int32_t v = level >> PCS_LEVEL_SHIFT;
            if (v != last) {
                blip_add_delta(s->blip[k], static_cast<unsigned>(i), v - last);
                last = v;
            }
        }
        // The filter tail past the end (indices count .. count + 7) becomes the next tick's first indices.
        for (int j = 0; j < PCS_HR_TAIL; ++j) {
            const int32_t t = hr[count + j];
            hr[count + j] = 0;
            hr[j] = t;
        }
        s->level[k] = level;
        s->last[k] = last;
        blip_end_frame(s->blip[k], static_cast<unsigned>(count));
    }
    s->lo = end & 3;
    s->psg->ResetTS(s->lo);
    return count;
}

extern "C" int pcs_read(PcePsg* s, short* out, int max_frames) {
    blip_read_samples(s->blip[0], out, max_frames, 1);
    return blip_read_samples(s->blip[1], out + 1, max_frames, 1);
}

extern "C" uint32_t pcs_register(const PcePsg* s, unsigned id) { return s->psg->GetRegister(id, nullptr, 0); }
