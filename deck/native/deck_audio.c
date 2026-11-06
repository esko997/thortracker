// SDL3 audio output for the hUGEDriver machine (huge_machine.h) on desktop Linux / Steam Deck.
// One SDL audio stream pulls frames on SDL's audio thread; each request runs whole driver ticks.
// Control calls (main thread) lock the stream, so the callback never sees a half-applied change.
// Spike scope: one song machine plus one audition machine, mixed; no triple buffering as on Android.
#include "deck_audio.h"
#include <SDL3/SDL.h>
#include <string.h>
#include "huge_machine.h"

#define RATE 48000

static SDL_AudioStream* stream;
static HugeMachine* song_machine;
static HugeMachine* audition_machine;
static HugeImage image;
static int has_image;
static int playing;
static int auditioning;
static int audition_ticks_left;
static float song_buf[HM_MAX_TICK_FRAMES * 2];
static float audition_buf[HM_MAX_TICK_FRAMES * 2];
static volatile int pos_order, pos_row;
static volatile float peak;   /* loudest rendered sample since the last da_peak (diagnostics) */

static void SDLCALL feed(void* user, SDL_AudioStream* s, int additional, int total) {
    (void)user; (void)total;
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
            hm_position(song_machine, &order, &row, &tick);
            pos_order = order;
            pos_row = row;
            frames = hm_tick(song_machine, song_buf);
            if (frames < 0) { playing = 0; frames = 0; }
        }
        if (auditioning) {
            int n = hm_tick(audition_machine, audition_buf);
            if (n < 0 || --audition_ticks_left <= 0) auditioning = 0;
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
}

int da_open(void) {
    song_machine = hm_create(RATE);
    audition_machine = hm_create(RATE);
    if (!song_machine || !audition_machine) return -3;
    SDL_AudioSpec spec = { SDL_AUDIO_F32, 2, RATE };
    stream = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, feed, NULL);
    if (!stream) return -4;
    SDL_ResumeAudioStreamDevice(stream);
    return 0;
}

int da_set_image(const unsigned char* bytes, int size) {
    if (size != 0x10000) return -1;
    static HugeImage next;
    memcpy(next.mem, bytes, 0x10000);
    next.version = image.version + 1;
    if (hm_validate(&next) != HM_OK) return -2;
    SDL_LockAudioStream(stream);
    image = next;
    has_image = 1;
    if (playing) hm_apply_image(song_machine, &image);
    SDL_UnlockAudioStream(stream);
    return 0;
}

int da_play(int order, int row) {
    if (!has_image) return -1;
    int seek = 0;
    SDL_LockAudioStream(stream);
    int r = hm_start_song(song_machine, &image, order, row, 60 * 60 * 10, &seek);
    playing = r == HM_OK;
    if (playing) { pos_order = order; pos_row = row; }
    SDL_UnlockAudioStream(stream);
    return r;
}

void da_stop(void) {
    SDL_LockAudioStream(stream);
    playing = 0;
    SDL_UnlockAudioStream(stream);
}

int da_audition(int channel, int note, int instrument, int detune, int ticks) {
    if (!has_image) return -1;
    SDL_LockAudioStream(stream);
    int r = hm_start_audition(audition_machine, &image, channel, note, instrument, detune);
    auditioning = r == HM_OK;
    audition_ticks_left = ticks;
    SDL_UnlockAudioStream(stream);
    return r;
}

void da_release_audition(void) {
    SDL_LockAudioStream(stream);
    auditioning = 0;
    SDL_UnlockAudioStream(stream);
}

int da_playing(void) { return playing; }
float da_peak(void) { float p = peak; peak = 0; return p; }
int da_order(void) { return pos_order; }
int da_row(void) { return pos_row; }

void da_close(void) {
    if (stream) SDL_DestroyAudioStream(stream);
    stream = NULL;
    if (song_machine) hm_destroy(song_machine);
    if (audition_machine) hm_destroy(audition_machine);
    song_machine = audition_machine = NULL;
}
