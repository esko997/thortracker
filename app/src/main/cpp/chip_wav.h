/* Offline WAV rendering of a lowered song image through its system's engine (chip_engine.h): the same machine,
 * start() and tick() calls as playback from the song start, so the file sounds exactly like the preview. The hosts
 * call it as Android through JNI (audio_jni.cpp) and the Deck through cinterop (decknative), stepping a job so they
 * can show progress and cancel. Self-contained (no engine types) so cinterop can read it. Never on the audio thread:
 * it allocates and writes a file. A job is used by one thread at a time.
 *
 * The file is spooled to disk as it renders (memory stays bounded by one tick, whatever the length): a canonical
 * 44-byte RIFF/WAVE header, PCM 16-bit little-endian stereo at CHIP_WAV_RATE. The spool path is the host's (a private
 * temporary file); the job creates it, and removes it again unless the file completed: closing a job that is still
 * running, faulted or failed to write leaves nothing behind. A complete file belongs to the host, which moves or
 * copies it to the user's destination and deletes it.
 *
 * Length:
 *  - The song part: from (order 0, row 0) until the loop point is entered for the second time (the loop point is
 *    the first row instance the song enters again, wherever its order list or jumps send it back, including a row
 *    that jumps to itself), then `loops` more arrivals at the loop point (each one more pass of the loop).
 *  - Then, with fade_ms > 0, a fade-out: the song keeps playing (it is looping, nothing is released or keyed off)
 *    while its volume falls linearly to silence over fade_ms. If the faded song is silent before the end, the file
 *    stops one second after its last audible frame (a song that ends by looping an empty pattern). With fade_ms = 0
 *    the file ends at the loop boundary. The intro, chip phase and envelopes can prevent seamless file looping.
 *    No song-wide release tail is requested: the engine interface has no "stop the song with release" operation.
 *  - The limit: a song part that reaches max_seconds (0: no limit but CHIP_WAV_MAX_FRAMES) ends there, then fades
 *    as above; the status reports it (CHIP_WAV_CAPPED) so the host can say the file is not the complete song (or not
 *    all the loop passes asked for). */
#ifndef THORTRACKER_CHIP_WAV_H_
#define THORTRACKER_CHIP_WAV_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CHIP_WAV_RATE 48000u /* the hosts' playback rate */
#define CHIP_WAV_HEADER 44u  /* bytes before the samples */
/* Most frames a file holds: its size stays below 2^31 bytes, inside RIFF's 32-bit lengths (with room to spare) and
 * a 32-bit off_t (about 3 h 6 min). */
#define CHIP_WAV_MAX_FRAMES ((0x7FFFFFFFu - CHIP_WAV_HEADER) / 4u)

/* chip_wav_status fields. */
#define CHIP_WAV_FRAMES 0 /* frames rendered so far */
#define CHIP_WAV_TARGET 1 /* expected frames of the whole file once the loop point is known, else 0 */
#define CHIP_WAV_ORDER 2  /* played-order position of the next tick */
#define CHIP_WAV_LOOPED 3 /* 1 once the loop point is known */
#define CHIP_WAV_SIZE 4   /* file bytes once complete, else 0 */
#define CHIP_WAV_CAPPED 5 /* 1 when the song part stopped at the length limit (before its loop point or passes) */
#define CHIP_WAV_FIELDS 6

/* chip_wav_run results besides 1 (more to do) and 0 (complete). */
#define CHIP_WAV_ERR_FAULT (-2) /* the driver faulted (CHIP_ERR_FAULT) */
#define CHIP_WAV_ERR_WRITE (-3) /* the spool file could not be written (disk full, removed) */

typedef struct ChipWav ChipWav;

/* Starts a job for a lowered image of `system_id` (as chip_engine_ops looks it up; image_size = its image_size),
 * creating (or replacing) the file at `spool_path`. Returns it, or NULL with *problem (a static string): "unknown
 * system", "image" (the loader rejected it, or the driver faulted at the start), "arguments" (fade too long),
 * "out of memory", "spool" (the file could not be created); nothing is left at spool_path then. */
ChipWav* chip_wav_open(const char* system_id, const uint8_t* image, size_t image_size, int loops, uint32_t fade_ms,
                       uint32_t max_seconds, const char* spool_path, const char** problem);

/* Runs at most max_ticks driver ticks. Returns 1 while more remains, 0 once the file is complete and closed (then 0
 * again), or CHIP_WAV_ERR_FAULT / CHIP_WAV_ERR_WRITE (then again; the job can only be closed). */
int chip_wav_run(ChipWav* w, int max_ticks);

/* Writes min(count, CHIP_WAV_FIELDS) status fields (CHIP_WAV_*) to out. */
void chip_wav_status(const ChipWav* w, int64_t* out, int count);

/* Frees the job; removes the spool file unless it is complete. */
void chip_wav_close(ChipWav* w);

/* The whole render in one call (tests, tools) into the file at `path`: 0, or -1 with *problem as chip_wav_open's,
 * or "driver fault" / "write failed" (nothing is left at path then). *capped (may be NULL) receives
 * CHIP_WAV_CAPPED. */
int chip_render_wav(const char* system_id, const uint8_t* image, size_t image_size, int loops, uint32_t fade_ms,
                    uint32_t max_seconds, const char* path, int* capped, const char** problem);

/* One engine sample (float, roughly [-1, 1]) as the file stores it: clamped, scaled by 32767, rounded to nearest. */
int16_t chip_wav_pcm16(float x);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_CHIP_WAV_H_ */
