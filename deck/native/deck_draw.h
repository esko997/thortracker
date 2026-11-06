#ifndef THORTRACKER_DECK_DRAW_H_
#define THORTRACKER_DECK_DRAW_H_
/* Drawing for the Deck frontend's Surface: shapes as SDL triangle geometry, text from TrueType fonts rendered by
 * stb_truetype into one glyph atlas. Colours are ARGB (0xAARRGGBB). Main thread only. */
#include <stdint.h>

#define DD_SANS 0
#define DD_MONO 1

int dd_init(void* sdl_renderer, const char* font_dir);   /* 0, or < 0 when a font is missing */
void dd_fill_rect(float l, float t, float r, float b, uint32_t argb);
void dd_fill_round(float l, float t, float r, float b, float radius, uint32_t argb);
void dd_stroke_rect(float l, float t, float r, float b, uint32_t argb, float width, float radius);
void dd_line(float x0, float y0, float x1, float y1, uint32_t argb, float width);
void dd_fill_circle(float cx, float cy, float radius, uint32_t argb);
void dd_stroke_circle(float cx, float cy, float radius, uint32_t argb, float width);
void dd_polyline(const float* xs, const float* ys, int count, uint32_t argb, float width);

/* Text in UTF-8. dd_text draws with its left edge at x and baseline at y. dd_text_center gives (ascent + descent) / 2
 * of the face, so a row [top, top+h] has its baseline at top + h / 2 + dd_text_center(...). */
float dd_text_width(int face, int bold, float size, const char* utf8);
float dd_text_center(int face, int bold, float size);
void dd_text(int face, int bold, float size, const char* utf8, float x, float baseline, uint32_t argb);
void dd_close(void);
#endif
