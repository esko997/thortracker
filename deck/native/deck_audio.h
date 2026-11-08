#ifndef THORTRACKER_DECK_AUDIO_H_
#define THORTRACKER_DECK_AUDIO_H_
/* SDL3 audio output for one sound system's chip machines (chip_engine.h). All calls from the main thread, between a
 * successful da_open and da_close; da_close (also after a failed da_open) allows da_open with another system. */
int da_open(const char* system_id);                        /* 0, -2 unknown system, or < 0 */
int da_set_image(const unsigned char* bytes, int size);    /* lowered image of the system; applied live while playing */
int da_play(int order, int row, int max_ticks);            /* order = played-order position; max_ticks = seek budget */
void da_stop(void);
int da_audition(int channel, int note, int instrument, int fine, int ticks); /* sounds until ticks run out or release */
void da_release_audition(void);
int da_playing(void);
int da_order(void);                                        /* played-order position of the row heard */
int da_row(void);
#define DA_STATS 6
/* Diagnostics since launch: sample rate, device buffer frames, callbacks, late callbacks while sounding (likely
 * underruns), slowest callback (ns), driver faults; writes min(count, DA_STATS) values. Any thread. */
void da_stats(long long* out, int count);
float da_peak(void);                                       /* loudest sample since the last call (diagnostics) */
void da_close(void);
#endif
