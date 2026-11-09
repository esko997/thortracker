// Realtime playback engine: AAudio output + two chip machines (chip_engine.h) of one sound
// system: the song machine and the audition machine.
//
// Threads:
//  - control thread (ChipAudio's single executor): set_image, play, stop, audition,
//    release_audition, ensure_stream, suspend_if_idle, destroy. Heavy work (image copy,
//    seek fast-forward, audition setup) happens here into spare machines.
//  - AAudio callback thread: consumes published images/machines through lock-free triple
//    buffers at driver-tick boundaries, renders, records row positions. Never allocates,
//    locks or blocks.
//  - any thread: position(), stats().
#pragma once

#include <aaudio/AAudio.h>

#include <atomic>
#include <cstdint>
#include <mutex>

#include "chip_engine.h"

namespace thortracker {

// Lock-free single-producer/single-consumer triple buffer of preallocated objects.
template <class T>
class TripleBuffer {
public:
    T* slots[3] = {nullptr, nullptr, nullptr};
    T* back() { return slots[back_]; }  // producer
    void publish() { back_ = static_cast<uint8_t>(middle_.exchange(static_cast<uint8_t>(back_ | kDirty)) & 3); }
    bool consume() {  // consumer
        if (!(middle_.load(std::memory_order_acquire) & kDirty)) return false;
        front_ = static_cast<uint8_t>(middle_.exchange(front_) & 3);
        return true;
    }
    T* front() { return slots[front_]; }  // consumer

private:
    static constexpr uint8_t kDirty = 0x80;
    uint8_t back_ = 2, front_ = 0;
    std::atomic<uint8_t> middle_{1};
};

// A loaded image (ops->image_storage bytes) and the song version it came from.
struct ImageSlot {
    ChipImage* data = nullptr;
    uint32_t version = 0;
};

struct MachineSlot {
    ChipMachine* machine = nullptr;
    uint32_t seq = 0;
    int channel = -1;            // audition channel
    uint32_t image_version = 0;  // version of the image the machine plays (song machines)
};

class Engine {
public:
    static constexpr int32_t kSampleRate = 48000;
    static constexpr int kStatCount = 14;

    // nullptr if ops is null or allocation fails.
    static Engine* create(const ChipEngineOps* ops);
    ~Engine();

    // Control thread.
    int set_image(const uint8_t* bytes, size_t size, uint32_t version);  // CHIP_OK / CHIP_ERR_IMAGE
    int play(int order, int row, int max_ticks);  // >= 0 seek mode, < 0 error
    void stop();
    int audition(int channel, int note, int instrument, int fine);
    void release_audition();
    int ensure_stream();  // AAUDIO_OK or an AAudio error code
    bool suspend_if_idle();
    bool has_image() const { return has_image_; }

    // Any thread. Packed: bit 40 = playing, bits 16..23 order, bits 0..7 row; -1 = never played.
    int64_t position();
    void stats(int64_t* out, int count);  // see audio_jni.cpp for the field order

    // AAudio callbacks.
    aaudio_data_callback_result_t render(AAudioStream* stream, float* out, int32_t frames);
    void on_error(aaudio_result_t error);

private:
    explicit Engine(const ChipEngineOps* ops) : ops_(ops) {}
    ChipImage* new_image();
    void close_stream_locked();
    void record_row(uint64_t frame, int order, int row);
    int64_t presented_frame_locked();

    const ChipEngineOps* const ops_;

    // Control-thread state.
    ImageSlot control_image_;
    bool has_image_ = false;
    uint32_t seq_ = 0;
    std::mutex stream_mutex_;  // guards stream_ for control/UI threads (never the callback)
    AAudioStream* stream_ = nullptr;
    bool stream_started_ = false;

    // Hand-over to the audio thread.
    TripleBuffer<ImageSlot> images_;
    ImageSlot image_storage_[3];
    TripleBuffer<MachineSlot> play_slots_;
    TripleBuffer<MachineSlot> audition_slots_;
    MachineSlot slot_storage_[6];
    std::atomic<uint32_t> stop_seq_{0};
    std::atomic<uint32_t> release_seq_{0};
    std::atomic<bool> stream_dead_{false};

    // Audio-thread state.
    ImageSlot* front_image_ = nullptr;  // newest image the audio thread holds
    MachineSlot* play_ = nullptr;
    MachineSlot* aud_ = nullptr;
    bool aud_releasing_ = false;  // key-off sent; ticking until the machine reports the end
    int borrowed_channel_ = -1;
    float play_buf_[CHIP_MAX_TICK_FRAMES * 2];
    float aud_buf_[CHIP_MAX_TICK_FRAMES * 2];
    int play_len_ = 0, play_pos_ = 0, aud_len_ = 0, aud_pos_ = 0;
    uint64_t frames_rendered_ = 0;
    int32_t previous_xruns_ = 0;  // callback-owned; reset before starting a newly opened stream

    // Shared (audio thread writes, others read).
    std::atomic<bool> playing_{false};
    std::atomic<bool> auditioning_{false};
    std::atomic<uint32_t> session_{0};
    static constexpr int kRing = 64;
    std::atomic<uint64_t> ring_frame_[kRing];
    std::atomic<uint32_t> ring_pos_[kRing];  // session << 16 | order << 8 | row
    std::atomic<uint32_t> ring_head_{0};
    std::atomic<int64_t> last_position_{-1};
    std::atomic<int64_t> frames_written_{0};
    std::atomic<int64_t> max_callback_ns_{0};
    std::atomic<int64_t> faults_{0};
    std::atomic<int64_t> last_latency_frames_{0};  // latency used for position()
    std::atomic<int64_t> raw_latency_frames_{-1};  // last timestamp measurement, unfiltered
    std::atomic<int64_t> last_seek_ns_{0};
    std::atomic<int64_t> last_audition_ns_{0};
    std::atomic<int64_t> last_apply_ns_{0};
    std::atomic<int64_t> seek_mode_{0};
};

}  // namespace thortracker
