#include "gba_maxmod.h"
#include <new>

namespace {
constexpr uint32_t VALID = 0x47424131;
struct Machine {
    GbaImage image;
    GbaMaxmod player;
    uint32_t rate, phase, serial;
    unsigned native_frames, native_at, repeats;
    int order, row, tick;
    uint8_t muted;
    bool ready, auditioning, released, note_pending, off_pending;
    int note, instrument, channel;
    int8_t pcm[1232 * 2];
    uint8_t audition_row[8];
    float repeat_l, repeat_r;
};
Machine* machine(ChipMachine* m) { return reinterpret_cast<Machine*>(m); }
const Machine* machine(const ChipMachine* m) { return reinterpret_cast<const Machine*>(m); }
const GbaImage* image(const ChipImage* i) { return reinterpret_cast<const GbaImage*>(i); }
ChipMachine* create(uint32_t rate) {
    if (rate < 8000 || rate > CHIP_MAX_SAMPLE_RATE) return nullptr;
    auto* m = new (std::nothrow) Machine{};
    if (m) m->rate = rate;
    return reinterpret_cast<ChipMachine*>(m);
}
void destroy(ChipMachine* m) { delete machine(m); }
void silence_channel(Machine& m, unsigned channel) {
    auto& p = m.player;
    for (unsigned i = 0; i < 8; ++i) {
        if (p.active_channels[i].type && p.active_channels[i].parent == channel) {
            p.active_channels[i].type = ACHN_DISABLED;
            p.mixing_channels[i].active = false;
        }
    }
    p.module_channels[channel].alloc = NO_CHANNEL_AVAILABLE;
}
bool position_valid(const GbaImage& im, int order, int row) {
    if (order < 0 || order >= im.orders || row < 0) return false;
    unsigned pattern = im.bytes[im.module + 8 + 76 + order];
    return row < im.row_count[pattern];
}
void report_position(Machine& m) {
    auto& l = m.player.mmLayerMain;
    m.order = l.position; m.row = l.row; m.tick = l.tick;
}
void reset(Machine& m, const GbaImage& im) {
    std::memcpy(&m.image, &im, sizeof(im));
    m.player.init(&m.image);
    m.phase = m.serial = m.native_frames = m.native_at = m.repeats = 0;
    m.muted = 0;
    m.ready = true;
    m.auditioning = m.released = m.note_pending = m.off_pending = false;
    report_position(m);
}
bool audible(const Machine& m) {
    for (const auto& c : m.player.mixing_channels) if (c.active && c.vol) return true;
    return false;
}
// Process exactly one Maxmod tick, then retain its PCM until all host-rate
// samples have been returned. Slow BPMs may span more than one host buffer.
bool driver_tick(Machine& m) {
    auto& p = m.player;
    auto& l = p.mmLayerMain;
    if (m.auditioning) {
        l.position = l.row = l.tick = l.pattdelay = l.fpattdelay = 0;
        l.speed = 1;
        unsigned n = 0;
        if (m.note_pending) {
            m.audition_row[n++] = 0x80 | (m.channel + 1);
            m.audition_row[n++] = 0x33; // note + instrument + START + DVOL
            m.audition_row[n++] = uint8_t(m.note + 12);
            m.audition_row[n++] = uint8_t(m.instrument);
            m.note_pending = false;
        } else if (m.off_pending) {
            m.audition_row[n++] = 0x80 | (m.channel + 1);
            m.audition_row[n++] = 1;
            m.audition_row[n++] = 255;
            m.off_pending = false;
        }
        m.audition_row[n] = 0;
        l.pattread = m.audition_row;
        p.audition_begin = m.audition_row;
        p.audition_end = m.audition_row + n + 1;
        // Prevent the synthetic audition row from advancing into the song.
        l.nrows = 255;
    }
    p.mppProcessTickMain();
    if (!l.isplaying || l.samples_per_tick < 2 || l.samples_per_tick > 1232) return false;
    for (unsigned ch = 0; ch < 8; ++ch) if (m.muted & (1u << ch)) silence_channel(m, ch);
    m.native_frames = l.samples_per_tick;
    m.native_at = 0;
    p.mix(m.pcm, m.native_frames);
    return true;
}
int tick(ChipMachine* opaque, float* out) {
    if (!opaque || !out) return CHIP_ERR_FAULT;
    auto& m = *machine(opaque);
    if (!m.ready) return CHIP_ERR_FAULT;
    if (m.native_at == m.native_frames && !m.repeats) {
        if (m.auditioning && m.released && !m.off_pending && !audible(m)) return 0;
        if (!driver_tick(m)) { m.ready = false; return CHIP_ERR_FAULT; }
    }
    unsigned frames = 0;
    while (frames < CHIP_MAX_TICK_FRAMES) {
        if (!m.repeats) {
            if (m.native_at == m.native_frames) break;
            m.repeat_l = m.pcm[m.native_at * 2] / 128.0f;
            m.repeat_r = m.pcm[m.native_at * 2 + 1] / 128.0f;
            ++m.native_at;
            m.phase += m.rate;
            m.repeats = m.phase / GBA_NATIVE_RATE;
            m.phase %= GBA_NATIVE_RATE;
            if (!m.repeats) continue;
        }
        out[frames * 2] = m.repeat_l;
        out[frames * 2 + 1] = m.repeat_r;
        --m.repeats;
        ++frames;
    }
    if (m.native_at == m.native_frames && !m.repeats) {
        if (!m.auditioning) {
            report_position(m);
            if (!m.tick && !m.player.mmLayerMain.pattdelay) ++m.serial;
        }
    }
    return int(frames);
}
int start(ChipMachine* opaque, const ChipImage* loaded, int order, int row, int max_ticks, int* mode) {
    if (!opaque || !loaded || !mode || max_ticks < 0 || image(loaded)->valid != VALID ||
        !position_valid(*image(loaded), order, row)) return CHIP_ERR_IMAGE;
    auto& m = *machine(opaque);
    reset(m, *image(loaded));
    *mode = CHIP_SEEK_FROM_START;
    int ticks = 0;
    while (m.order != order || m.row != row || m.tick) {
        if (ticks >= max_ticks / 2) {
            reset(m, *image(loaded));
            m.player.mmSetPositionEx(unsigned(order), 0);
            report_position(m);
            *mode = CHIP_SEEK_FROM_ORDER;
            // Reconstruct this order's preceding rows, not just pattern bytes.
            int budget = max_ticks - ticks;
            while (m.row != row || m.tick) {
                if (budget-- <= 0 || m.order != order || !driver_tick(m)) {
                    m.ready = false;
                    return CHIP_ERR_IMAGE;
                }
                report_position(m);
            }
            break;
        }
        ++ticks;
        if (!driver_tick(m)) return CHIP_ERR_FAULT;
        report_position(m);
    }
    m.native_frames = m.native_at = m.repeats = m.phase = m.serial = 0;
    return CHIP_OK;
}
int audition(ChipMachine* opaque, const ChipImage* loaded, int channel, int note, int instrument, int fine) {
    if (!opaque || !loaded || image(loaded)->valid != VALID || channel < 0 || channel >= 8 ||
        note < 0 || note >= 96 || instrument < 1 || instrument > image(loaded)->instruments || fine) return CHIP_ERR_IMAGE;
    const auto& im = *image(loaded);
    const uint8_t* inst = im.bytes + im.instrument[instrument - 1];
    unsigned map = inst[8] | unsigned(inst[9]) << 8;
    unsigned sample = map & 0x8000 ? map & 255 : inst[map + (note + 12) * 2 + 1];
    if (!sample) return CHIP_ERR_IMAGE;
    auto& m = *machine(opaque);
    reset(m, im);
    m.auditioning = m.note_pending = true;
    m.note = note; m.instrument = instrument; m.channel = channel;
    return CHIP_OK;
}
int release(ChipMachine* opaque) {
    if (!opaque) return 0;
    auto& m = *machine(opaque);
    if (!m.ready || !m.auditioning || m.released) return m.ready && m.released && audible(m);
    m.released = true;
    m.note_pending = false;
    m.off_pending = true;
    const auto* inst = m.image.bytes + m.image.instrument[m.instrument - 1];
    // XM OFF without an enabled volume envelope is an immediate cut.
    if (!(inst[5] & MAS_INSTR_FLAG_VOL_ENV_ENABLED)) {
        silence_channel(m, unsigned(m.channel));
        m.native_frames = m.native_at = m.repeats = 0;
        m.off_pending = false;
        return 0;
    }
    return 1;
}
int mute(ChipMachine* opaque, int channel, int muted) {
    if (!opaque || channel < 0 || channel >= 8) return CHIP_ERR_IMAGE;
    auto& m = *machine(opaque);
    if (muted) { m.muted |= uint8_t(1u << channel); silence_channel(m, unsigned(channel)); }
    else m.muted &= uint8_t(~(1u << channel));
    return CHIP_OK;
}
void position(const ChipMachine* opaque, int* order, int* row, int* tick_value) {
    const auto* m = machine(opaque);
    if (order) *order = m ? m->order : 0;
    if (row) *row = m ? m->row : 0;
    if (tick_value) *tick_value = m ? m->tick : 0;
}
uint32_t row_serial(const ChipMachine* m) { return m ? machine(m)->serial : 0; }

void apply_image(ChipMachine* opaque, const ChipImage* loaded) {
    if (!opaque || !loaded || image(loaded)->valid != VALID) return;
    auto& m = *machine(opaque);
    if (!m.ready) return;
    auto& p = m.player;
    auto& l = p.mmLayerMain;
    const auto& im = *image(loaded);
    if (!m.auditioning && !position_valid(im, l.position, l.row)) return;
    if (!m.auditioning && l.pattjump != 255 && l.pattjump > im.orders) return;
    if (m.auditioning && m.instrument > im.instruments) return;
    const unsigned old_bpm = l.songadr->initial_tempo;
    const unsigned old_speed = l.songadr->initial_speed;
    const unsigned old_volume = l.songadr->global_volume;
    bool envelope_changed[8] = {};
    auto env_size = [](const uint8_t* inst) {
        unsigned size = 12;
        if (inst[5] & 1) size += inst[size];
        if (inst[5] & 2) size += inst[size];
        return size;
    };
    for (unsigned i = 0; i < 8; ++i) {
        unsigned n = p.active_channels[i].inst;
        if (!n || n > im.instruments) continue;
        const auto* before = m.image.bytes + m.image.instrument[n - 1];
        const auto* after = im.bytes + im.instrument[n - 1];
        unsigned size = env_size(before);
        envelope_changed[i] = size != env_size(after) || std::memcmp(before, after, size);
    }
    std::memcpy(&m.image, &im, sizeof(im));
    p.bank = m.image.bytes + 64;
    auto* h = reinterpret_cast<mm_mas_head*>(m.image.bytes + m.image.module + 8);
    l.songadr = h;
    l.insttable = h->tables;
    l.samptable = h->tables + h->instr_count;
    l.patttable = l.samptable + h->sampl_count;
    if (old_bpm != h->initial_tempo) p.mpp_setbpm(&l, h->initial_tempo);
    if (old_speed != h->initial_speed) l.speed = h->initial_speed;
    if (old_volume != h->global_volume) l.global_volume = h->global_volume;
    if (!m.auditioning) {
        unsigned pat = h->sequence[l.position];
        l.nrows = uint8_t(m.image.row_count[pat] - 1);
        unsigned next = l.row + (l.tick || l.pattdelay ? 1u : 0u);
        if (next > m.image.row_count[pat]) next = m.image.row_count[pat];
        // Rebuild only packed-data caches; retain sounding note/envelope/FX state.
        const uint8_t* ptr = m.image.bytes + m.image.rows[pat][0];
        for (unsigned r = 0; r < next; ++r) {
            while (*ptr) {
                unsigned c = *ptr++;
                auto& ch = p.module_channels[(c & 127) - 1];
                if (c & 128) ch.cflags = *ptr++;
                if (ch.cflags & 1) { unsigned n = *ptr++; if (n < 120) ch.pnoter = uint8_t(n); }
                if (ch.cflags & 2) ch.inst = *ptr++;
                if (ch.cflags & 4) ch.volcmd = *ptr++;
                if (ch.cflags & 8) { ch.effect = *ptr++; ch.param = *ptr++; }
            }
            ++ptr;
        }
        l.pattread = m.image.bytes + m.image.rows[pat][next];
        if (l.ploop_row > l.nrows) l.ploop_row = l.ploop_times = l.ploop_jump = 0;
        l.ploop_adr = m.image.bytes + m.image.rows[pat][l.ploop_row];
    } else if (!m.released) m.note_pending = true;
    for (unsigned i = 0; i < 8; ++i) {
        if (p.module_channels[i].inst > im.instruments) p.module_channels[i].inst = 0;
        auto& a = p.active_channels[i];
        auto& c = p.mixing_channels[i];
        if (a.inst > im.instruments || a.sample > im.samples) { silence_channel(m, a.parent); continue; }
        if (!a.type || !a.sample) continue;
        auto* s = mpp_SamplePointer(&l, a.sample);
        auto* sample = p.mmGetSampleAddress(s->msl_id);
        c.src = uintptr_t(sample->data);
        if ((c.read >> 12) >= sample->length) {
            if (sample->loop_length == 0xffffffffu) c.active = false;
            else c.read = (sample->length - sample->loop_length) << 12;
        }
        // Changed envelope topology cannot retain stale node indexes.
        if (envelope_changed[i]) {
            a.envc_vol = a.envc_pan = a.envc_pic = 0;
            a.envn_vol = a.envn_pan = a.envn_pic = 0;
        }
    }
}
}
extern "C" const ChipEngineOps gba_maxmod_ops = {
    "gba-maxmod", GBA_IMAGE_SIZE, sizeof(GbaImage), create, destroy, gba_load_image,
    start, apply_image, audition, release, mute, position, tick, row_serial
};
