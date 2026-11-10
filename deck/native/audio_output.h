#ifndef THOR_AUDIO_OUTPUT_H
#define THOR_AUDIO_OUTPUT_H
#include <stdint.h>
/* Platform output owns the callback thread/lock. Feed emits whole float-stereo ticks via ao_put. */
int ao_open(int rate, void (*feed)(int bytes), int* device_frames);
void ao_put(const float* samples, int bytes);
void ao_lock(void);
void ao_unlock(void);
void ao_close(void);
uint64_t ao_ticks_ns(void);
#endif
