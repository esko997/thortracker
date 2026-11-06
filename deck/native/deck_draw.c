// Drawing for the Deck frontend (see deck_draw.h). Shapes are SDL_RenderGeometry triangles (no anti-aliasing).
// Text: glyphs are rasterised once per (font, size, glyph) by stb_truetype into a 2048x2048 atlas (white + coverage
// alpha) and each string is drawn as one geometry batch tinted by vertex colour. A full atlas starts over.
#include "deck_draw.h"
#include <SDL3/SDL.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define STB_TRUETYPE_IMPLEMENTATION
#define STBTT_STATIC
#include "third_party/stb_truetype.h"

static SDL_Renderer* R;

// ---- colour / geometry ---------------------------------------------------------------------------------------

static SDL_FColor fcolor(uint32_t argb) {
    SDL_FColor c = { ((argb >> 16) & 0xFF) / 255.0f, ((argb >> 8) & 0xFF) / 255.0f, (argb & 0xFF) / 255.0f, ((argb >> 24) & 0xFF) / 255.0f };
    return c;
}

#define MAX_VERTS 4096
static SDL_Vertex verts[MAX_VERTS];
static int indices[MAX_VERTS * 3];
static int nv, ni;

static void flush(SDL_Texture* tex) {
    if (ni > 0) SDL_RenderGeometry(R, tex, verts, nv, indices, ni);
    nv = ni = 0;
}

static int vert(float x, float y, SDL_FColor c, float u, float v) {
    verts[nv].position.x = x; verts[nv].position.y = y;
    verts[nv].color = c;
    verts[nv].tex_coord.x = u; verts[nv].tex_coord.y = v;
    return nv++;
}

static void tri(int a, int b, int c) { indices[ni++] = a; indices[ni++] = b; indices[ni++] = c; }

/* Ensures room for [v] more vertices (and their triangles) in an untextured batch. */
static void room(int v) { if (nv + v > MAX_VERTS || ni + v * 3 > MAX_VERTS * 3) flush(NULL); }

static void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, SDL_FColor c) {
    room(4);
    int a = vert(x0, y0, c, 0, 0), b = vert(x1, y1, c, 0, 0), d = vert(x2, y2, c, 0, 0), e = vert(x3, y3, c, 0, 0);
    tri(a, b, d); tri(a, d, e);
}

void dd_fill_rect(float l, float t, float r, float b, uint32_t argb) {
    if (r <= l || b <= t) return;
    quad(l, t, r, t, r, b, l, b, fcolor(argb));
    flush(NULL);
}

#define CORNER_STEPS 6
#define OUTLINE_POINTS (4 * (CORNER_STEPS + 1))

/* The outline of a rounded rectangle, clockwise from the top-left corner, OUTLINE_POINTS points. */
static void rounded_outline(float l, float t, float r, float b, float radius, float* xs, float* ys) {
    float maxr = fminf((r - l) / 2, (b - t) / 2);
    if (radius > maxr) radius = maxr;
    if (radius < 0) radius = 0;
    const float cx[4] = { l + radius, r - radius, r - radius, l + radius };
    const float cy[4] = { t + radius, t + radius, b - radius, b - radius };
    int n = 0;
    for (int k = 0; k < 4; k++) {
        float a0 = (float)M_PI * (1.0f + 0.5f * k);
        for (int s = 0; s <= CORNER_STEPS; s++) {
            float a = a0 + (float)M_PI * 0.5f * s / CORNER_STEPS;
            xs[n] = cx[k] + radius * cosf(a);
            ys[n] = cy[k] + radius * sinf(a);
            n++;
        }
    }
}

void dd_fill_round(float l, float t, float r, float b, float radius, uint32_t argb) {
    if (r <= l || b <= t) return;
    if (radius <= 0.5f) { dd_fill_rect(l, t, r, b, argb); return; }
    float xs[OUTLINE_POINTS], ys[OUTLINE_POINTS];
    rounded_outline(l, t, r, b, radius, xs, ys);
    SDL_FColor c = fcolor(argb);
    room(OUTLINE_POINTS + 1);
    int center = vert((l + r) / 2, (t + b) / 2, c, 0, 0);
    int first = nv;
    for (int i = 0; i < OUTLINE_POINTS; i++) vert(xs[i], ys[i], c, 0, 0);
    for (int i = 0; i < OUTLINE_POINTS; i++) tri(center, first + i, first + (i + 1) % OUTLINE_POINTS);
    flush(NULL);
}

/* A band between two closed outlines of [n] corresponding points. */
static void ring(const float* ox, const float* oy, const float* ix, const float* iy, int n, SDL_FColor c) {
    room(n * 2);
    int o0 = nv;
    for (int i = 0; i < n; i++) vert(ox[i], oy[i], c, 0, 0);
    int i0 = nv;
    for (int i = 0; i < n; i++) vert(ix[i], iy[i], c, 0, 0);
    for (int i = 0; i < n; i++) {
        int j = (i + 1) % n;
        tri(o0 + i, o0 + j, i0 + j);
        tri(o0 + i, i0 + j, i0 + i);
    }
    flush(NULL);
}

void dd_stroke_rect(float l, float t, float r, float b, uint32_t argb, float width, float radius) {
    float h = width / 2;
    float ox[OUTLINE_POINTS], oy[OUTLINE_POINTS], ix[OUTLINE_POINTS], iy[OUTLINE_POINTS];
    rounded_outline(l - h, t - h, r + h, b + h, radius > 0 ? radius + h : 0, ox, oy);
    rounded_outline(l + h, t + h, r - h, b - h, radius > h ? radius - h : 0, ix, iy);
    ring(ox, oy, ix, iy, OUTLINE_POINTS, fcolor(argb));
}

void dd_line(float x0, float y0, float x1, float y1, uint32_t argb, float width) {
    float dx = x1 - x0, dy = y1 - y0, len = sqrtf(dx * dx + dy * dy);
    if (len <= 0) return;
    float nx = -dy / len * width / 2, ny = dx / len * width / 2;
    quad(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, fcolor(argb));
    flush(NULL);
}

#define CIRCLE_STEPS 40

void dd_fill_circle(float cx, float cy, float radius, uint32_t argb) {
    SDL_FColor c = fcolor(argb);
    room(CIRCLE_STEPS + 1);
    int center = vert(cx, cy, c, 0, 0);
    int first = nv;
    for (int i = 0; i < CIRCLE_STEPS; i++) {
        float a = 2 * (float)M_PI * i / CIRCLE_STEPS;
        vert(cx + radius * cosf(a), cy + radius * sinf(a), c, 0, 0);
    }
    for (int i = 0; i < CIRCLE_STEPS; i++) tri(center, first + i, first + (i + 1) % CIRCLE_STEPS);
    flush(NULL);
}

void dd_stroke_circle(float cx, float cy, float radius, uint32_t argb, float width) {
    float ox[CIRCLE_STEPS], oy[CIRCLE_STEPS], ix[CIRCLE_STEPS], iy[CIRCLE_STEPS];
    for (int i = 0; i < CIRCLE_STEPS; i++) {
        float a = 2 * (float)M_PI * i / CIRCLE_STEPS, ca = cosf(a), sa = sinf(a);
        ox[i] = cx + (radius + width / 2) * ca; oy[i] = cy + (radius + width / 2) * sa;
        ix[i] = cx + (radius - width / 2) * ca; iy[i] = cy + (radius - width / 2) * sa;
    }
    ring(ox, oy, ix, iy, CIRCLE_STEPS, fcolor(argb));
}

void dd_polyline(const float* xs, const float* ys, int count, uint32_t argb, float width) {
    SDL_FColor c = fcolor(argb);
    for (int i = 0; i + 1 < count; i++) {
        float dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i], len = sqrtf(dx * dx + dy * dy);
        if (len <= 0) continue;
        float nx = -dy / len * width / 2, ny = dx / len * width / 2;
        quad(xs[i] + nx, ys[i] + ny, xs[i + 1] + nx, ys[i + 1] + ny, xs[i + 1] - nx, ys[i + 1] - ny, xs[i] - nx, ys[i] - ny, c);
    }
    flush(NULL);
}

// ---- fonts and glyph atlas ------------------------------------------------------------------------------------

enum { F_SANS, F_SANS_BOLD, F_MONO, F_MONO_BOLD, F_SYMBOLS, F_MATH, F_COUNT };
static const char* FONT_FILES[F_COUNT] = {
    "NotoSans-Condensed.ttf", "NotoSans-CondensedBold.ttf", "NotoSansMono-Regular.ttf", "NotoSansMono-Bold.ttf",
    "NotoSansSymbols2-Regular.ttf", "NotoSansMath-Regular.ttf",
};
static unsigned char* font_data[F_COUNT];
static stbtt_fontinfo fonts[F_COUNT];

#define ATLAS 2048
static SDL_Texture* atlas;
static int shelf_x, shelf_y, shelf_h;

typedef struct { uint32_t key_font_size; uint32_t glyph; int used; float u0, v0, u1, v1; int w, h, xoff, yoff; } Glyph;
#define CACHE 16384
static Glyph cache[CACHE];

static unsigned char* read_file(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char* data = malloc((size_t)n);
    if (data && fread(data, 1, (size_t)n, f) != (size_t)n) { free(data); data = NULL; }
    fclose(f);
    return data;
}

int dd_init(void* sdl_renderer, const char* font_dir) {
    R = (SDL_Renderer*)sdl_renderer;
    SDL_SetRenderDrawBlendMode(R, SDL_BLENDMODE_BLEND);
    char path[1024];
    for (int i = 0; i < F_COUNT; i++) {
        snprintf(path, sizeof path, "%s/%s", font_dir, FONT_FILES[i]);
        font_data[i] = read_file(path);
        if (!font_data[i] || !stbtt_InitFont(&fonts[i], font_data[i], stbtt_GetFontOffsetForIndex(font_data[i], 0))) {
            fprintf(stderr, "deck_draw: cannot load font %s\n", path);
            return -1;
        }
    }
    atlas = SDL_CreateTexture(R, SDL_PIXELFORMAT_RGBA32, SDL_TEXTUREACCESS_STATIC, ATLAS, ATLAS);
    if (!atlas) return -2;
    SDL_SetTextureBlendMode(atlas, SDL_BLENDMODE_BLEND);
    SDL_SetTextureScaleMode(atlas, SDL_SCALEMODE_NEAREST);
    return 0;
}

void dd_close(void) {
    if (atlas) SDL_DestroyTexture(atlas);
    atlas = NULL;
    for (int i = 0; i < F_COUNT; i++) { free(font_data[i]); font_data[i] = NULL; }
}

static int primary(int face, int bold) { return face == DD_MONO ? (bold ? F_MONO_BOLD : F_MONO) : (bold ? F_SANS_BOLD : F_SANS); }

/* The font holding [cp] (the face, then mono, symbols and math as fallbacks) and its glyph index. */
static int find_glyph(int font, int cp, int* glyph) {
    const int chain[4] = { font, F_MONO, F_SYMBOLS, F_MATH };
    for (int i = 0; i < 4; i++) {
        int g = stbtt_FindGlyphIndex(&fonts[chain[i]], cp);
        if (g) { *glyph = g; return chain[i]; }
    }
    *glyph = 0;
    return font;
}

static float scale_of(int font, float size) { return stbtt_ScaleForMappingEmToPixels(&fonts[font], size); }

static void atlas_reset(void) {
    memset(cache, 0, sizeof cache);
    shelf_x = shelf_y = shelf_h = 0;
}

static Glyph* glyph_of(int font, float size, int glyph) {
    uint32_t sq = (uint32_t)lroundf(size * 4);
    uint32_t key = ((uint32_t)font << 24) | (sq & 0xFFFFFF);
    uint32_t h = (key * 2654435761u) ^ ((uint32_t)glyph * 40503u);
    for (int probe = 0; probe < CACHE; probe++) {
        Glyph* e = &cache[(h + probe) & (CACHE - 1)];
        if (e->used && e->key_font_size == key && e->glyph == (uint32_t)glyph) return e;
        if (!e->used) {
            float sc = scale_of(font, sq / 4.0f);
            int w, gh, xo, yo;
            unsigned char* bmp = stbtt_GetGlyphBitmap(&fonts[font], sc, sc, glyph, &w, &gh, &xo, &yo);
            if (shelf_x + w + 1 > ATLAS) { shelf_x = 0; shelf_y += shelf_h + 1; shelf_h = 0; }
            if (shelf_y + gh + 1 > ATLAS) { atlas_reset(); stbtt_FreeBitmap(bmp, NULL); return glyph_of(font, size, glyph); }
            if (w > 0 && gh > 0) {
                uint32_t* px = malloc((size_t)w * gh * 4);
                for (int i = 0; i < w * gh; i++) px[i] = 0x00FFFFFFu | ((uint32_t)bmp[i] << 24);  // RGBA32 little-endian: A in the top byte
                SDL_Rect dst = { shelf_x, shelf_y, w, gh };
                SDL_UpdateTexture(atlas, &dst, px, w * 4);
                free(px);
            }
            stbtt_FreeBitmap(bmp, NULL);
            e->used = 1; e->key_font_size = key; e->glyph = (uint32_t)glyph;
            e->w = w; e->h = gh; e->xoff = xo; e->yoff = yo;
            e->u0 = (float)shelf_x / ATLAS; e->v0 = (float)shelf_y / ATLAS;
            e->u1 = (float)(shelf_x + w) / ATLAS; e->v1 = (float)(shelf_y + gh) / ATLAS;
            shelf_x += w + 1;
            if (gh > shelf_h) shelf_h = gh;
            return e;
        }
    }
    atlas_reset();
    return glyph_of(font, size, glyph);
}

static int utf8_next(const char** s) {
    const unsigned char* p = (const unsigned char*)*s;
    int c = p[0], n = 1;
    if (c >= 0xF0) { c = ((c & 0x07) << 18) | ((p[1] & 0x3F) << 12) | ((p[2] & 0x3F) << 6) | (p[3] & 0x3F); n = 4; }
    else if (c >= 0xE0) { c = ((c & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F); n = 3; }
    else if (c >= 0xC0) { c = ((c & 0x1F) << 6) | (p[1] & 0x3F); n = 2; }
    *s += n;
    return c;
}

/* Walks [utf8] laying out glyphs; draws them when [draw] is set. Returns the advance width. */
static float layout(int face, int bold, float size, const char* utf8, float x, float baseline, uint32_t argb, int draw) {
    int base = primary(face, bold);
    float pen = 0;
    int prev_font = -1, prev_glyph = 0;
    SDL_FColor c = fcolor(argb);
    const char* s = utf8;
    while (*s) {
        int cp = utf8_next(&s), glyph;
        int font = find_glyph(base, cp, &glyph);
        float sc = scale_of(font, size);
        if (font == prev_font && prev_glyph) pen += sc * stbtt_GetGlyphKernAdvance(&fonts[font], prev_glyph, glyph);
        int adv, lsb;
        stbtt_GetGlyphHMetrics(&fonts[font], glyph, &adv, &lsb);
        if (draw) {
            Glyph* g = glyph_of(font, size, glyph);
            if (g->w > 0 && g->h > 0) {
                if (nv + 4 > MAX_VERTS || ni + 6 > MAX_VERTS * 3) flush(atlas);
                float gx = roundf(x + pen) + g->xoff, gy = roundf(baseline) + g->yoff;
                int a = vert(gx, gy, c, g->u0, g->v0), b = vert(gx + g->w, gy, c, g->u1, g->v0);
                int d = vert(gx + g->w, gy + g->h, c, g->u1, g->v1), e = vert(gx, gy + g->h, c, g->u0, g->v1);
                tri(a, b, d); tri(a, d, e);
            }
        }
        pen += sc * adv;
        prev_font = font;
        prev_glyph = glyph;
    }
    if (draw) flush(atlas);
    return pen;
}

float dd_text_width(int face, int bold, float size, const char* utf8) { return layout(face, bold, size, utf8, 0, 0, 0, 0); }

float dd_text_center(int face, int bold, float size) {
    int font = primary(face, bold), asc, desc, gap;
    stbtt_GetFontVMetrics(&fonts[font], &asc, &desc, &gap);
    return scale_of(font, size) * (asc + desc) / 2.0f;
}

void dd_text(int face, int bold, float size, const char* utf8, float x, float baseline, uint32_t argb) {
    layout(face, bold, size, utf8, x, baseline, argb, 1);
}
