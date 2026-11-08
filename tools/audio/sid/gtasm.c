/* gtasm: command-line front end for the 6502 assembler GoatTracker 2 embeds (Magnus Lind's
 * assembler from Exomizer, GoatTracker's src/asm/), the assembler player.s is written for.
 * Offline developer tool for tools/audio/prepare_sid_player.py; built by
 * tools/audio/fetch_goattracker.sh against the pinned GoatTracker 2.77 sources. Not shipped.
 *
 * Usage: gtasm <source.s> <output.bin>
 * Writes the assembled bytes (from the first .ORG) exactly as GoatTracker's packer does
 * (greloc.c: assemble(&src, &dest)). Exit status 1 on an assembly error. */
#include <stdio.h>
#include <stdlib.h>

#include "membuf.h"
#include "parse.h"

int main(int argc, char** argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: gtasm <source.s> <output.bin>\n");
        return 2;
    }
    FILE* in = fopen(argv[1], "rb");
    if (!in) {
        perror(argv[1]);
        return 2;
    }
    struct membuf src = STATIC_MEMBUF_INIT;
    struct membuf dest = STATIC_MEMBUF_INIT;
    int c;
    while ((c = fgetc(in)) != EOF) membuf_append_char(&src, (char)c);
    fclose(in);
    if (assemble(&src, &dest)) return 1;
    FILE* out = fopen(argv[2], "wb");
    if (!out) {
        perror(argv[2]);
        return 2;
    }
    const int n = membuf_memlen(&dest);
    if (fwrite(membuf_get(&dest), 1, (size_t)n, out) != (size_t)n) return 2;
    fclose(out);
    membuf_free(&src);
    membuf_free(&dest);
    return 0;
}
