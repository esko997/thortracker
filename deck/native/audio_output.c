#include "audio_output.h"
#include <SDL3/SDL.h>
static SDL_AudioStream* output;
static void (*render)(int);
static void SDLCALL callback(void* user, SDL_AudioStream* stream, int additional, int total) {
    (void)user; (void)stream; (void)total;
    render(additional);
}
int ao_open(int rate, void (*feed)(int), int* frames) {
    render = feed;
    SDL_AudioSpec spec = { SDL_AUDIO_F32, 2, rate };
    output = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, callback, NULL);
    if (!output) return -1;
    SDL_AudioSpec actual;
    if (!SDL_GetAudioDeviceFormat(SDL_GetAudioStreamDevice(output), &actual, frames)) *frames = 0;
    if (!SDL_ResumeAudioStreamDevice(output)) { ao_close(); return -1; }
    return 0;
}
void ao_put(const float* samples, int bytes) { SDL_PutAudioStreamData(output, samples, bytes); }
void ao_lock(void) { if (output) SDL_LockAudioStream(output); }
void ao_unlock(void) { if (output) SDL_UnlockAudioStream(output); }
void ao_close(void) { if (output) SDL_DestroyAudioStream(output); output = NULL; }
uint64_t ao_ticks_ns(void) { return SDL_GetTicksNS(); }
