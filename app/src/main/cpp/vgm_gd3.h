/* GD3 1.00 tags for the VGM exports (OPL3, YM2612; also the YM2612's .xgm): 11 UTF-16LE strings — track, track
 * (JP), game, game (JP), system, system (JP), author, author (JP), date, converter, notes — of which Thor Tracker
 * fills track, system, author, date and converter. UTF-8 in (NULL = ""; malformed bytes become U+FFFD). */
#ifndef THORTRACKER_VGM_GD3_H_
#define THORTRACKER_VGM_GD3_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* The tag ("Gd3 ", version 0x100, length, strings) into out, or only its size when out is NULL. */
size_t vgm_gd3(const char* title, const char* system, const char* author, const char* date, const char* converter,
               uint8_t* out);
/* `date`, or today ("yyyy/mm/dd", local time) when it is NULL or empty; `today` holds it (16 bytes). */
const char* vgm_date(const char* date, char* today);
void vgm_put32(uint8_t* p, uint32_t v);

#ifdef __cplusplus
}
#endif

#endif /* THORTRACKER_VGM_GD3_H_ */
