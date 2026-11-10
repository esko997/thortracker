#include "audio_output.h"
#include "chip_engine.h"
#include <SDL.h>
#include <string.h>
#include <assert.h>

/* SDL2 requests exact device blocks; keep the tail of a whole driver tick for the next callback.
 * No resampling, allocation or chip work is duplicated here; SDL converts F32 to the ALSA hardware format. */
static SDL_AudioDeviceID device;
static void (*render)(int);
static unsigned char pending[CHIP_MAX_TICK_FRAMES * 2 * sizeof(float)];
static int pending_start, pending_end;
static unsigned char* destination;
static int remaining;
static void SDLCALL callback(void* user, Uint8* buffer, int bytes) {
    (void)user;
    destination = buffer;
    remaining = bytes;
    int available = pending_end - pending_start;
    int copy = available < remaining ? available : remaining;
    if (copy) {
        memcpy(destination, pending + pending_start, (size_t)copy);
        pending_start += copy; destination += copy; remaining -= copy;
    }
    if (pending_start == pending_end) pending_start = pending_end = 0;
    if (remaining) render(remaining);
}
int ao_open(int rate, void (*feed)(int), int* frames) {
    render = feed;
    pending_start = pending_end = 0;
    SDL_AudioSpec wanted, actual;
    SDL_zero(wanted);
    wanted.freq = rate;
    wanted.format = AUDIO_F32SYS;
    wanted.channels = 2;
    wanted.samples = 1024;
    wanted.callback = callback;
    device = SDL_OpenAudioDevice(NULL, 0, &wanted, &actual, 0);
    if (!device) return -1;
    *frames = actual.samples;
    SDL_PauseAudioDevice(device, 0);
    return 0;
}
void ao_put(const float* samples, int bytes) {
    int copy = bytes < remaining ? bytes : remaining;
    memcpy(destination, samples, (size_t)copy);
    destination += copy; remaining -= copy;
    int extra = bytes - copy;
    if (extra) {
        /* feed emits at most one overhanging tick and stops when its request is satisfied. */
        assert(pending_end == 0 && extra <= (int)sizeof pending);
        memcpy(pending, (const unsigned char*)samples + copy, (size_t)extra);
        pending_end = extra;
    }
}
void ao_lock(void) { if (device) SDL_LockAudioDevice(device); }
void ao_unlock(void) { if (device) SDL_UnlockAudioDevice(device); }
void ao_close(void) {
    if (device) SDL_CloseAudioDevice(device);
    device = 0;
    pending_start = pending_end = 0;
}
uint64_t ao_ticks_ns(void) {
    uint64_t count = SDL_GetPerformanceCounter(), frequency = SDL_GetPerformanceFrequency();
    return count / frequency * 1000000000ULL + count % frequency * 1000000000ULL / frequency;
}
