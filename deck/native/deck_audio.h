#ifndef THORTRACKER_DECK_AUDIO_H_
#define THORTRACKER_DECK_AUDIO_H_
/* SDL3 audio output for the hUGEDriver machine. All calls from the main thread. */
int da_open(void);                                         /* 0 or < 0 */
int da_set_image(const unsigned char* bytes, int size);    /* 64 KiB HugeImage; applied live while playing */
int da_play(int order, int row);                           /* order = played-order position */
void da_stop(void);
int da_audition(int channel, int note, int instrument, int detune, int ticks); /* sounds until ticks run out or release */
void da_release_audition(void);
int da_playing(void);
int da_order(void);                                        /* played-order position of the row heard */
int da_row(void);
float da_peak(void);                                       /* loudest sample since the last call (diagnostics) */
void da_close(void);
#endif
