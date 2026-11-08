// SDL3 audio output for one sound system's chip machines (chip_engine.h) on desktop Linux / Steam Deck.
// One SDL audio stream pulls frames on SDL's audio thread; each request runs whole driver ticks.
// Control calls (main thread) lock the stream only to swap pointers or apply a live edit, so the callback
// never sees a half-applied change and never waits for a seek: start() and audition() (the costly calls:
// a seek replays the song silently) run on spare machines outside the lock, which are then swapped in at a
// tick boundary. Machines are created in da_open (reSID's first one builds its tables), never in the
// callback. As on Android, an audition borrows its channel: the song machine's channel is muted while the
// audition sounds.
#include "deck_audio.h"
#include <SDL3/SDL.h>
#include <stdlib.h>
#include <string.h>
#include "chip_engine.h"

#define RATE 48000

static const ChipEngineOps* ops;
static SDL_AudioStream* stream;
static ChipMachine* song_machine;
static ChipMachine* audition_machine;
static ChipMachine* song_spare;      /* seeks run here outside the lock, then swap with song_machine */
static ChipMachine* audition_spare;  /* likewise for auditions */
static ChipImage* image;   /* the image the machines play */
static ChipImage* spare;   /* loaded and validated outside the lock, then swapped with image */
static int has_image;
static int playing;
static int auditioning;
static int audition_ticks_left;
static int audition_channel;
static int borrowed_channel = -1;   /* channel muted on the song machine */
static float song_buf[CHIP_MAX_TICK_FRAMES * 2];
static float audition_buf[CHIP_MAX_TICK_FRAMES * 2];
static volatile int pos_order, pos_row;
static volatile float peak;   /* loudest rendered sample since the last da_peak (diagnostics) */
/* da_stats (diagnostics): device buffer, callbacks, late ones while sounding, the slowest, driver faults. */
static int device_frames;
static volatile long long callbacks, late_callbacks, worst_callback_ns, faults;
static Uint64 last_callback_ns;

static void SDLCALL feed(void* user, SDL_AudioStream* s, int additional, int total) {
    (void)user; (void)total;
    const Uint64 started = SDL_GetTicksNS();
    ++callbacks;
    /* A callback more than two device buffers after the previous one while sounding probably let the device run dry. */
    if ((playing || auditioning) && last_callback_ns && device_frames > 0 &&
        started - last_callback_ns > 2 * (Uint64)device_frames * SDL_NS_PER_SECOND / RATE)
        ++late_callbacks;
    last_callback_ns = started;
    while (additional > 0) {
        if (!playing && !auditioning) {
            static const float silence[512 * 2];
            int n = additional < (int)sizeof silence ? additional : (int)sizeof silence;
            SDL_PutAudioStreamData(s, silence, n);
            additional -= n;
            continue;
        }
        int frames = 0;
        if (playing) {
            int order, row, tick;
            /* Borrowing changes at a tick boundary, as audio_engine.cpp does. */
            const int desired = auditioning ? audition_channel : -1;
            if (desired != borrowed_channel) {
                if (borrowed_channel >= 0) ops->mute(song_machine, borrowed_channel, 0);
                if (desired >= 0) ops->mute(song_machine, desired, 1);
                borrowed_channel = desired;
            }
            ops->position(song_machine, &order, &row, &tick);
            pos_order = order;
            pos_row = row;
            frames = ops->tick(song_machine, song_buf);
            if (frames <= 0) { if (frames < 0) ++faults; playing = 0; frames = 0; }
        }
        if (auditioning) {
            int n = ops->tick(audition_machine, audition_buf);
            /* n == 0: a released note has ended. */
            if (n <= 0 || --audition_ticks_left <= 0) auditioning = 0;
            if (n > 0) {
                if (frames == 0) { memset(song_buf, 0, sizeof(float) * 2 * (size_t)n); frames = n; }
                int m = n < frames ? n : frames;
                for (int i = 0; i < m * 2; i++) song_buf[i] += audition_buf[i];
            }
        }
        if (frames == 0) continue;
        for (int i = 0; i < frames * 2; i++) { float a = song_buf[i] < 0 ? -song_buf[i] : song_buf[i]; if (a > peak) peak = a; }
        int bytes = frames * 2 * (int)sizeof(float);
        SDL_PutAudioStreamData(s, song_buf, bytes);
        additional -= bytes;
    }
    const long long took = (long long)(SDL_GetTicksNS() - started);
    if (took > worst_callback_ns) worst_callback_ns = took;
}

int da_open(const char* system_id) {
    ops = chip_engine_ops(system_id);
    if (!ops) return -2;
    image = calloc(1, ops->image_storage);
    spare = calloc(1, ops->image_storage);
    song_machine = ops->create(RATE);
    audition_machine = ops->create(RATE);
    song_spare = ops->create(RATE);
    audition_spare = ops->create(RATE);
    if (!image || !spare || !song_machine || !audition_machine || !song_spare || !audition_spare) return -3;
    SDL_AudioSpec spec = { SDL_AUDIO_F32, 2, RATE };
    stream = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, feed, NULL);
    if (!stream) return -4;
    SDL_AudioSpec device;
    if (!SDL_GetAudioDeviceFormat(SDL_GetAudioStreamDevice(stream), &device, &device_frames)) device_frames = 0;
    SDL_ResumeAudioStreamDevice(stream);
    return 0;
}

int da_set_image(const unsigned char* bytes, int size) {
    if (size < 0 || (size_t)size != ops->image_size) return -1;
    if (ops->load_image(spare, bytes, (size_t)size) != CHIP_OK) return -2;
    SDL_LockAudioStream(stream);
    ChipImage* t = image;
    image = spare;
    spare = t;
    has_image = 1;
    if (playing) ops->apply_image(song_machine, image);
    SDL_UnlockAudioStream(stream);
    return 0;
}

static void swap(ChipMachine** a, ChipMachine** b) {
    ChipMachine* t = *a;
    *a = *b;
    *b = t;
}

int da_play(int order, int row, int max_ticks) {
    if (!has_image) return -1;
    int seek = 0;
    int r = ops->start(song_spare, image, order, row, max_ticks, &seek);
    SDL_LockAudioStream(stream);
    if (r == CHIP_OK) {
        swap(&song_machine, &song_spare);
        pos_order = order;
        pos_row = row;
    }
    playing = r == CHIP_OK;
    borrowed_channel = -1;   /* start resets the machine: nothing is muted */
    SDL_UnlockAudioStream(stream);
    return r;
}

void da_stop(void) {
    SDL_LockAudioStream(stream);
    playing = 0;
    SDL_UnlockAudioStream(stream);
}

int da_audition(int channel, int note, int instrument, int fine, int ticks) {
    if (!has_image) return -1;
    int r = ops->audition(audition_spare, image, channel, note, instrument, fine);
    SDL_LockAudioStream(stream);
    if (r == CHIP_OK) swap(&audition_machine, &audition_spare);
    auditioning = r == CHIP_OK;
    audition_ticks_left = ticks;
    audition_channel = channel;
    SDL_UnlockAudioStream(stream);
    return r;
}

void da_release_audition(void) {
    SDL_LockAudioStream(stream);
    /* Chips with a release phase keep sounding until their tick reports the end. */
    if (auditioning && !ops->release(audition_machine)) auditioning = 0;
    SDL_UnlockAudioStream(stream);
}

int da_playing(void) { return playing; }
float da_peak(void) { float p = peak; peak = 0; return p; }

void da_stats(long long* out, int count) {
    const long long v[DA_STATS] = {RATE, device_frames, callbacks, late_callbacks, worst_callback_ns, faults};
    for (int i = 0; i < count && i < DA_STATS; ++i) out[i] = v[i];
}

int da_order(void) { return pos_order; }
int da_row(void) { return pos_row; }

void da_close(void) {
    if (stream) SDL_DestroyAudioStream(stream);
    stream = NULL;
    if (song_machine) ops->destroy(song_machine);
    if (audition_machine) ops->destroy(audition_machine);
    if (song_spare) ops->destroy(song_spare);
    if (audition_spare) ops->destroy(audition_spare);
    song_machine = audition_machine = song_spare = audition_spare = NULL;
    free(image);
    free(spare);
    image = spare = NULL;
    /* Closed state, so da_open can start another system's engine. */
    ops = NULL;
    has_image = playing = auditioning = 0;
    borrowed_channel = -1;
}
