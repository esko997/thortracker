// Build/run instructions: third_party/maxmod/UPSTREAM (parent verification only).
#include "gba/gba_image.h"
#include <cassert>
#include <cmath>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>

namespace {
void word(std::vector<uint8_t>& b, size_t p, uint32_t n) {
    for (unsigned i = 0; i < 4; ++i) b[p + i] = uint8_t(n >> (i * 8));
}
void half(std::vector<uint8_t>& b, size_t p, unsigned n) { b[p] = uint8_t(n); b[p+1] = uint8_t(n >> 8); }
// A real MASv18 bank: four rows, one looping sample, sustain/release envelope.
// This deliberately has a four-byte loop: high notes used to read out of bounds
// in upstream headless mixer even though the MAS format accepts short loops.
std::vector<uint8_t> fixture(unsigned bpm = 125, bool envelope = true) {
    std::vector<uint8_t> b(GBA_IMAGE_SIZE);
    std::memcpy(b.data(), "GBAM", 4); word(b, 4, 1); word(b, 12, 64);
    half(b, 64, 1); half(b, 66, 1); std::memcpy(b.data()+68, "*maxmod*", 8);
    word(b, 76, 24); word(b, 80, 48); word(b, 84, 0xffffffff);
    const unsigned sample = 88;
    word(b, sample, 16); b[sample+4] = 1; b[sample+5] = 0x18;
    word(b, sample+8, 4); word(b, sample+12, 4); b[sample+17] = 0xba;
    half(b, sample+18, 543); b[sample+20] = 32; b[sample+21] = 224;
    b[sample+22] = 32; b[sample+23] = 224;
    const unsigned module = 112, h = 120, inst = h+288;
    b[module+5] = 0x18;
    b[h] = 200; b[h+1] = b[h+2] = b[h+3] = 1;
    b[h+4] = 0x1e; b[h+5] = 64; b[h+6] = 6; b[h+7] = uint8_t(bpm);
    std::memset(b.data()+h+12, 64, 32); std::memset(b.data()+h+44, 128, 32);
    std::memset(b.data()+h+76, 255, 200); b[h+76] = 0;
    word(b, h+276, inst-h);
    b[inst] = 128; b[inst+1] = 32; b[inst+5] = envelope ? 9 : 0;
    const unsigned envsize = envelope ? 16 : 0, map = 12+envsize;
    half(b, inst+8, map);
    if (envelope) {
        const unsigned e = inst+12;
        b[e] = 16; b[e+1] = b[e+2] = 255; b[e+3] = b[e+4] = 0; b[e+5] = 2;
        half(b, e+8, unsigned(uint16_t(-4096))); half(b, e+10, 64 | (8 << 7));
        half(b, e+12, 0); half(b, e+14, 0);
    }
    for (unsigned n = 0; n < 120; ++n) half(b, inst+map+n*2, n | 256);
    const unsigned info = inst+map+240, pattern = info+12;
    word(b, h+280, info-h); word(b, h+284, pattern-h);
    b[info] = 64; b[info+1] = 192; half(b, info+2, 2091); b[info+7] = 64;
    half(b, info+8, 32768);
    b[pattern] = 3;
    const uint8_t rows[] = {0x81,0x33,60,1,0, 0, 0x81,1,255,0, 0};
    std::memcpy(b.data()+pattern+1, rows, sizeof(rows));
    unsigned end = (pattern+1+sizeof(rows)+3)&~3u;
    word(b, module, end-module-8); word(b, 8, end); word(b, 16, end-64);
    return b;
}
std::unique_ptr<GbaImage> load(const std::vector<uint8_t>& bytes) {
    auto result = std::make_unique<GbaImage>();
    assert(gba_maxmod_ops.load_image(reinterpret_cast<ChipImage*>(result.get()), bytes.data(), bytes.size()) == CHIP_OK);
    return result;
}
const ChipImage* chip(const GbaImage* i) { return reinterpret_cast<const ChipImage*>(i); }
struct Player {
    ChipMachine* p;
    explicit Player(unsigned rate = 48000) : p(gba_maxmod_ops.create(rate)) { assert(p); }
    ~Player() { gba_maxmod_ops.destroy(p); }
    Player(const Player&) = delete;
    void start(const GbaImage* im, int row = 0) {
        int mode = -1; assert(gba_maxmod_ops.start(p, chip(im), 0, row, 100, &mode) == CHIP_OK);
    }
};
float energy(const float* p, int count) {
    float sum = 0; for (int i = 0; i < count * 2; ++i) sum += std::fabs(p[i]); return sum;
}
void isolation(const GbaImage* im) {
    Player a, b, audition;
    a.start(im); b.start(im);
    assert(gba_maxmod_ops.audition(audition.p, chip(im), 5, 95, 1, 0) == CHIP_OK);
    float x[CHIP_MAX_TICK_FRAMES*2], y[CHIP_MAX_TICK_FRAMES*2], scratch[CHIP_MAX_TICK_FRAMES*2];
    for (unsigned t = 0; t < 80; ++t) {
        int n = gba_maxmod_ops.tick(a.p, x);
        gba_maxmod_ops.tick(audition.p, scratch);
        if (t == 7) gba_maxmod_ops.release(audition.p);
        int k = gba_maxmod_ops.tick(b.p, y);
        assert(n > 0 && n == k && !std::memcmp(x, y, n * 2 * sizeof(float)));
    }
}
}
int main() {
    auto bytes = fixture(); auto im = load(bytes);
    isolation(im.get());
    // Two concurrent users must not share even temporary driver/mixer state.
    std::thread t1([&] { isolation(im.get()); });
    std::thread t2([&] { isolation(im.get()); }); t1.join(); t2.join();
    float out[CHIP_MAX_TICK_FRAMES*2];
    {
        Player p;
        assert(gba_maxmod_ops.audition(p.p, chip(im.get()), 0, 48, 1, 0) == CHIP_OK);
        for (int i = 0; i < 20; ++i) { int n = gba_maxmod_ops.tick(p.p, out); assert(n > 0 && energy(out,n) > 0); }
        assert(gba_maxmod_ops.release(p.p) == 1);
        bool ended = false;
        for (int i = 0; i < 128; ++i) { int n = gba_maxmod_ops.tick(p.p,out); assert(n >= 0); if (!n) { ended=true; break; } }
        assert(ended);
    }
    {
        auto noenv = load(fixture(125,false)); Player p;
        assert(gba_maxmod_ops.audition(p.p, chip(noenv.get()), 0, 48, 1, 0) == CHIP_OK);
        assert(gba_maxmod_ops.tick(p.p,out) > 0); assert(gba_maxmod_ops.release(p.p) == 0);
        assert(gba_maxmod_ops.tick(p.p,out) == 0);
    }
    {
        Player p; p.start(im.get());
        assert(gba_maxmod_ops.mute(p.p,0,1) == CHIP_OK);
        int n = gba_maxmod_ops.tick(p.p,out); assert(n > 0 && energy(out,n) == 0);
        assert(gba_maxmod_ops.mute(p.p,0,0) == CHIP_OK);
        n = gba_maxmod_ops.tick(p.p,out); assert(n > 0 && energy(out,n) == 0); // no resumed old note
        for (int i = 0; i < 23; ++i) n = gba_maxmod_ops.tick(p.p,out);
        assert(energy(out,n) > 0); // next loop note sounds
    }
    {
        Player p; p.start(im.get(), 1);
        int order,row,tick; gba_maxmod_ops.position(p.p,&order,&row,&tick); assert(order==0 && row==1 && tick==0);
        int n = gba_maxmod_ops.tick(p.p,out); assert(n>0 && energy(out,n)>0); // prior note reconstructed
        auto changed = bytes; changed[120+7] = 150;
        auto edit = load(changed);
        gba_maxmod_ops.position(p.p,&order,&row,&tick);
        gba_maxmod_ops.apply_image(p.p,chip(edit.get()));
        int a,b,c; gba_maxmod_ops.position(p.p,&a,&b,&c); assert(a==order && b==row && c==tick);
        assert(gba_maxmod_ops.tick(p.p,out)>0);
        auto zero = bytes; std::memset(zero.data()+108,128,4); auto silent = load(zero);
        gba_maxmod_ops.apply_image(p.p,chip(silent.get()));
        n=gba_maxmod_ops.tick(p.p,out); assert(n>0 && energy(out,n)==0);
    }
    {
        auto slow = load(fixture(32)); Player p(96000); p.start(slow.get());
        int order,row,tick=0,total=0,calls=0;
        do {
            int n=gba_maxmod_ops.tick(p.p,out); assert(n>0 && n<=int(CHIP_MAX_TICK_FRAMES)); total+=n; ++calls;
            gba_maxmod_ops.position(p.p,&order,&row,&tick);
        } while (!tick && calls<10);
        assert(calls>1 && tick==1 && total==int(1230u*96000u/15768u));
    }
    // Validation rejects malformed offsets, channels, loops and envelopes before execution.
    for (unsigned target : {12u, 76u, 80u, 96u, im->instrument[0]+12+5, im->pattern[0]+1}) {
        auto bad=bytes; word(bad,target,0xffffffff);
        auto storage=std::make_unique<GbaImage>();
        assert(gba_maxmod_ops.load_image(reinterpret_cast<ChipImage*>(storage.get()),bad.data(),bad.size())==CHIP_ERR_IMAGE);
    }
    assert(!gba_maxmod_ops.create(CHIP_MAX_SAMPLE_RATE+1));
    return 0;
}
