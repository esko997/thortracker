#include "audio_engine.h"

#include <android/log.h>

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <new>

#include <dlfcn.h>

#define LOG_TAG "ThorTrackerAudio"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace thortracker {
namespace {

int64_t now_ns() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

aaudio_data_callback_result_t data_callback(AAudioStream*, void* user, void* audio, int32_t frames) {
    return static_cast<Engine*>(user)->render(static_cast<float*>(audio), frames);
}

void error_callback(AAudioStream*, void* user, aaudio_result_t error) {
    static_cast<Engine*>(user)->on_error(error);
}

// AAudioStreamBuilder_setUsage exists from API 28; minSdk is 26, so resolve it at runtime
// instead of linking it (a direct call would fail to load on API 26/27).
void request_game_usage(AAudioStreamBuilder* builder) {
    using SetUsage = void (*)(AAudioStreamBuilder*, aaudio_usage_t);
    static const auto set_usage =
        reinterpret_cast<SetUsage>(dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setUsage"));
    // GAME instead of the default MEDIA: an interactive instrument, not media playback
    // (media may be routed to a deep-buffer output, e.g. audio.deep_buffer.media=true).
    if (set_usage) set_usage(builder, AAUDIO_USAGE_GAME);
}

bool after(uint32_t a, uint32_t b) { return static_cast<int32_t>(a - b) > 0; }

constexpr int64_t kPlayingBit = int64_t{1} << 40;

int64_t pack_position(bool playing, int order, int row) {
    return (playing ? kPlayingBit : 0) | (static_cast<int64_t>(order & 0xFF) << 16) | (row & 0xFF);
}

}  // namespace

ChipImage* Engine::new_image() { return static_cast<ChipImage*>(calloc(1, ops_->image_storage)); }

Engine* Engine::create(const ChipEngineOps* ops) {
    if (!ops) return nullptr;
    Engine* e = new (std::nothrow) Engine(ops);
    if (!e) return nullptr;
    bool ok = (e->control_image_.data = e->new_image()) != nullptr;
    for (int i = 0; i < 3 && ok; ++i) {
        ok = (e->image_storage_[i].data = e->new_image()) != nullptr;
        e->images_.slots[i] = &e->image_storage_[i];
    }
    for (int i = 0; i < 6 && ok; ++i) {
        e->slot_storage_[i].machine = ops->create(kSampleRate);
        ok = e->slot_storage_[i].machine != nullptr;
    }
    if (!ok) {
        delete e;
        return nullptr;
    }
    for (int i = 0; i < 3; ++i) {
        e->play_slots_.slots[i] = &e->slot_storage_[i];
        e->audition_slots_.slots[i] = &e->slot_storage_[3 + i];
    }
    for (int i = 0; i < kRing; ++i) {
        e->ring_frame_[i].store(0);
        e->ring_pos_[i].store(0xFFFFFFFFu);
    }
    return e;
}

Engine::~Engine() {
    {
        std::lock_guard<std::mutex> lock(stream_mutex_);
        close_stream_locked();
    }
    for (auto& slot : slot_storage_) {
        if (slot.machine) ops_->destroy(slot.machine);
    }
    for (auto& image : image_storage_) free(image.data);
    free(control_image_.data);
}

int Engine::set_image(const uint8_t* bytes, size_t size, uint32_t version) {
    if (ops_->load_image(control_image_.data, bytes, size) != CHIP_OK) {
        has_image_ = false;
        return CHIP_ERR_IMAGE;
    }
    control_image_.version = version;
    ImageSlot* back = images_.back();
    std::memcpy(back->data, control_image_.data, ops_->image_storage);
    back->version = version;
    images_.publish();
    has_image_ = true;
    return CHIP_OK;
}

int Engine::play(int order, int row, int max_ticks) {
    if (!has_image_) return CHIP_ERR_IMAGE;
    const int64_t t0 = now_ns();
    MachineSlot* slot = play_slots_.back();
    int mode = CHIP_SEEK_FROM_START;
    int r = ops_->start(slot->machine, control_image_.data, order, row, max_ticks, &mode);
    if (r != CHIP_OK) return r;
    slot->seq = ++seq_;
    slot->channel = -1;
    slot->image_version = control_image_.version;
    int o = 0, rw = 0, t = 0;
    ops_->position(slot->machine, &o, &rw, &t);
    last_position_.store(pack_position(false, o, rw));
    play_slots_.publish();
    last_seek_ns_.store(now_ns() - t0);
    seek_mode_.store(mode);
    return mode;
}

void Engine::stop() { stop_seq_.store(++seq_, std::memory_order_release); }

int Engine::audition(int channel, int note, int instrument, int fine) {
    if (!has_image_) return CHIP_ERR_IMAGE;
    const int64_t t0 = now_ns();
    MachineSlot* slot = audition_slots_.back();
    int r = ops_->audition(slot->machine, control_image_.data, channel, note, instrument, fine);
    if (r != CHIP_OK) return r;
    slot->seq = ++seq_;
    slot->channel = channel;
    audition_slots_.publish();
    last_audition_ns_.store(now_ns() - t0);
    return CHIP_OK;
}

void Engine::release_audition() { release_seq_.store(++seq_, std::memory_order_release); }

void Engine::close_stream_locked() {
    if (!stream_) return;
    if (stream_started_) {
        AAudioStream_requestStop(stream_);
        aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNINITIALIZED;
        AAudioStream_waitForStateChange(stream_, AAUDIO_STREAM_STATE_STOPPING, &next, 200000000LL);
    }
    AAudioStream_close(stream_);
    stream_ = nullptr;
    stream_started_ = false;
}

int Engine::ensure_stream() {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    if (stream_dead_.exchange(false)) {
        // Device lost (e.g. output route change): transport and audition are stopped, not
        // resumed behind the user's back when the stream is reopened.
        close_stream_locked();
        stop_seq_.store(++seq_, std::memory_order_release);
        release_seq_.store(++seq_, std::memory_order_release);
    }
    if (!stream_) {
        aaudio_result_t result = AAUDIO_ERROR_INTERNAL;
        for (int attempt = 0; attempt < 2 && !stream_; ++attempt) {
            AAudioStreamBuilder* builder = nullptr;
            result = AAudio_createStreamBuilder(&builder);
            if (result != AAUDIO_OK) return result;
            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            request_game_usage(builder);
            AAudioStreamBuilder_setSharingMode(
                builder, attempt == 0 ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
            AAudioStreamBuilder_setChannelCount(builder, 2);
            AAudioStreamBuilder_setSampleRate(builder, kSampleRate);
            AAudioStreamBuilder_setDataCallback(builder, data_callback, this);
            AAudioStreamBuilder_setErrorCallback(builder, error_callback, this);
            AAudioStream* stream = nullptr;
            result = AAudioStreamBuilder_openStream(builder, &stream);
            AAudioStreamBuilder_delete(builder);
            if (result != AAUDIO_OK) continue;
            if (AAudioStream_getSampleRate(stream) != kSampleRate ||
                AAudioStream_getChannelCount(stream) != 2 ||
                AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_FLOAT) {
                AAudioStream_close(stream);
                result = AAUDIO_ERROR_INVALID_FORMAT;
                continue;
            }
            // Two bursts, fixed: the usual low-latency setting. Not adapted on underrun;
            // diagnostics() reports xruns so device evidence decides whether to grow it.
            AAudioStream_setBufferSizeInFrames(stream, AAudioStream_getFramesPerBurst(stream) * 2);
            stream_ = stream;
        }
        if (!stream_) return result;
    }
    if (!stream_started_) {
        aaudio_result_t result = AAudioStream_requestStart(stream_);
        if (result != AAUDIO_OK) {
            close_stream_locked();
            return result;
        }
        stream_started_ = true;
    }
    return AAUDIO_OK;
}

bool Engine::suspend_if_idle() {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    if (!stream_ || !stream_started_ || playing_.load() || auditioning_.load()) return false;
    AAudioStream_requestStop(stream_);
    stream_started_ = false;
    return true;
}

void Engine::on_error(aaudio_result_t error) {
    // Not the data callback: flag only; the control thread reopens on the next command.
    LOGW("AAudio stream error %d (%s)", error, AAudio_convertResultToText(error));
    stream_dead_.store(true);
    playing_.store(false);
    auditioning_.store(false);
}

void Engine::record_row(uint64_t frame, int order, int row) {
    const uint32_t head = ring_head_.load(std::memory_order_relaxed);
    const uint32_t idx = head % kRing;
    ring_frame_[idx].store(frame, std::memory_order_relaxed);
    ring_pos_[idx].store(((session_.load(std::memory_order_relaxed) & 0xFFFF) << 16) |
                             ((order & 0xFF) << 8) | (row & 0xFF),
                         std::memory_order_relaxed);
    ring_head_.store(head + 1, std::memory_order_release);
}

aaudio_data_callback_result_t Engine::render(float* out, int32_t frames) {
    const int64_t t0 = now_ns();
    std::memset(out, 0, sizeof(float) * 2 * static_cast<size_t>(frames));

    if (play_slots_.consume()) {
        play_ = play_slots_.front();
        play_len_ = play_pos_ = 0;
        borrowed_channel_ = -1;
        session_.fetch_add(1, std::memory_order_relaxed);
    }
    if (play_ && !after(play_->seq, stop_seq_.load(std::memory_order_acquire))) play_ = nullptr;
    if (audition_slots_.consume()) {
        aud_ = audition_slots_.front();
        aud_len_ = aud_pos_ = 0;
        aud_releasing_ = false;
    }
    if (aud_ && !aud_releasing_ && !after(aud_->seq, release_seq_.load(std::memory_order_acquire))) {
        // Key-off: chips without a release phase stop at once; others play it out.
        if (ops_->release(aud_->machine)) {
            aud_releasing_ = true;
        } else {
            aud_ = nullptr;
        }
    }
    if (images_.consume()) front_image_ = images_.front();
    const int desired_borrow = aud_ ? aud_->channel : -1;

    int i = 0;
    while (play_ && i < frames) {
        if (play_pos_ >= play_len_) {
            ChipMachine* m = play_->machine;
            if (front_image_ && after(front_image_->version, play_->image_version)) {
                const int64_t a0 = now_ns();
                ops_->apply_image(m, front_image_->data);
                play_->image_version = front_image_->version;
                last_apply_ns_.store(now_ns() - a0, std::memory_order_relaxed);
            }
            if (desired_borrow != borrowed_channel_) {
                if (borrowed_channel_ >= 0) ops_->mute(m, borrowed_channel_, 0);
                if (desired_borrow >= 0) ops_->mute(m, desired_borrow, 1);
                borrowed_channel_ = desired_borrow;
            }
            int order = 0, row = 0, tick = 0;
            ops_->position(m, &order, &row, &tick);
            if (tick == 0) record_row(frames_rendered_ + static_cast<uint64_t>(i), order, row);
            const int k = ops_->tick(m, play_buf_);
            if (k <= 0) {
                faults_.fetch_add(1, std::memory_order_relaxed);
                play_ = nullptr;
                break;
            }
            last_position_.store(pack_position(false, order, row), std::memory_order_relaxed);
            play_len_ = k;
            play_pos_ = 0;
        }
        const int n = std::min(frames - i, play_len_ - play_pos_);
        const float* src = play_buf_ + 2 * play_pos_;
        float* dst = out + 2 * i;
        for (int j = 0; j < 2 * n; ++j) dst[j] += src[j];
        i += n;
        play_pos_ += n;
    }

    i = 0;
    while (aud_ && i < frames) {
        if (aud_pos_ >= aud_len_) {
            const int k = ops_->tick(aud_->machine, aud_buf_);
            if (k <= 0) {
                if (k < 0) faults_.fetch_add(1, std::memory_order_relaxed);
                aud_ = nullptr;  // fault, or a released note has ended
                break;
            }
            aud_len_ = k;
            aud_pos_ = 0;
        }
        const int n = std::min(frames - i, aud_len_ - aud_pos_);
        const float* src = aud_buf_ + 2 * aud_pos_;
        float* dst = out + 2 * i;
        for (int j = 0; j < 2 * n; ++j) dst[j] += src[j];
        i += n;
        aud_pos_ += n;
    }

    for (int j = 0; j < 2 * frames; ++j) out[j] = std::clamp(out[j], -1.0f, 1.0f);

    playing_.store(play_ != nullptr, std::memory_order_relaxed);
    auditioning_.store(aud_ != nullptr, std::memory_order_relaxed);
    frames_rendered_ += static_cast<uint64_t>(frames);
    frames_written_.store(static_cast<int64_t>(frames_rendered_), std::memory_order_release);
    const int64_t spent = now_ns() - t0;
    if (spent > max_callback_ns_.load(std::memory_order_relaxed)) {
        max_callback_ns_.store(spent, std::memory_order_relaxed);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

int64_t Engine::presented_frame_locked() {
    const int64_t written = frames_written_.load(std::memory_order_acquire);
    if (!stream_) return written;
    int64_t frame_pos = 0, time_ns = 0;
    int64_t latency = AAudioStream_getBufferSizeInFrames(stream_);
    if (AAudioStream_getTimestamp(stream_, CLOCK_MONOTONIC, &frame_pos, &time_ns) == AAUDIO_OK) {
        const int64_t presented_now = frame_pos + (now_ns() - time_ns) * kSampleRate / 1000000000LL;
        const int64_t measured = AAudioStream_getFramesWritten(stream_) - presented_now;
        raw_latency_frames_.store(measured, std::memory_order_relaxed);
        if (measured >= 0) latency = measured;
    }
    last_latency_frames_.store(latency, std::memory_order_relaxed);
    return written - latency;
}

int64_t Engine::position() {
    if (!playing_.load()) {
        const int64_t last = last_position_.load();
        return last < 0 ? -1 : (last & ~kPlayingBit);
    }
    int64_t presented;
    {
        // The UI thread must not wait behind a stream open/close on the control thread:
        // if the stream is busy, use the last measured output latency.
        std::unique_lock<std::mutex> lock(stream_mutex_, std::try_to_lock);
        presented = lock.owns_lock()
                        ? presented_frame_locked()
                        : frames_written_.load(std::memory_order_acquire) - last_latency_frames_.load();
    }
    const uint32_t session = session_.load() & 0xFFFF;
    const uint32_t head = ring_head_.load(std::memory_order_acquire);
    int64_t best = -1, earliest = -1;
    for (uint32_t k = 1; k <= static_cast<uint32_t>(kRing) && k <= head; ++k) {
        const uint32_t idx = (head - k) % kRing;
        const uint32_t pos = ring_pos_[idx].load(std::memory_order_relaxed);
        if ((pos >> 16) != session) break;
        earliest = pos;
        if (static_cast<int64_t>(ring_frame_[idx].load(std::memory_order_relaxed)) <= presented) {
            best = pos;
            break;
        }
    }
    const int64_t pos = best >= 0 ? best : earliest;
    if (pos < 0) return last_position_.load() | kPlayingBit;
    return pack_position(true, static_cast<int>((pos >> 8) & 0xFF), static_cast<int>(pos & 0xFF));
}

void Engine::stats(int64_t* out, int count) {
    int64_t v[kStatCount] = {};
    {
        std::lock_guard<std::mutex> lock(stream_mutex_);
        v[0] = kSampleRate;
        if (stream_) {
            v[1] = AAudioStream_getFramesPerBurst(stream_);
            v[2] = AAudioStream_getBufferSizeInFrames(stream_);
            v[3] = AAudioStream_getXRunCount(stream_);
            v[9] = AAudioStream_getSharingMode(stream_) == AAUDIO_SHARING_MODE_EXCLUSIVE ? 1 : 0;
            v[10] = AAudioStream_getPerformanceMode(stream_) == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY ? 1 : 0;
            if (stream_started_) presented_frame_locked();  // refreshes the latency estimate
        }
    }
    v[4] = max_callback_ns_.load();
    v[5] = last_seek_ns_.load();
    v[6] = last_audition_ns_.load();
    v[7] = faults_.load();
    v[8] = seek_mode_.load();
    v[11] = last_apply_ns_.load();
    v[12] = last_latency_frames_.load();
    v[13] = raw_latency_frames_.load();
    for (int i = 0; i < count && i < kStatCount; ++i) out[i] = v[i];
}

}  // namespace thortracker
