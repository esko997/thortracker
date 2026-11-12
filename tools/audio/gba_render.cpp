#include "gba/gba_image.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>

static void le16(FILE* f, unsigned value) { std::fputc(value & 255, f); std::fputc((value >> 8) & 255, f); }
static void le32(FILE* f, unsigned value) { le16(f,value); le16(f,value >> 16); }
int main(int argc, char** argv) {
    if (argc != 5) { std::fprintf(stderr,"usage: gba_render image.gbaimg output.wav seconds rate\n"); return 2; }
    char *end = nullptr; unsigned seconds = unsigned(std::strtoul(argv[3],&end,10));
    if (*end || !seconds || seconds > 600) return 2;
    unsigned rate = unsigned(std::strtoul(argv[4],&end,10));
    if (*end || rate < 8000 || rate > CHIP_MAX_SAMPLE_RATE) return 2;
    std::vector<uint8_t> bytes(GBA_IMAGE_SIZE);
    FILE* input = std::fopen(argv[1],"rb"); if (!input) return 1;
    bool complete = std::fread(bytes.data(),1,bytes.size(),input) == bytes.size();
    complete = complete && std::fgetc(input) == EOF; std::fclose(input);
    if (!complete) return 1;
    auto im = std::make_unique<GbaImage>();
    auto* loaded = reinterpret_cast<ChipImage*>(im.get());
    if (gba_load_image(loaded,bytes.data(),bytes.size()) != CHIP_OK) return 1;
    ChipMachine* m = gba_maxmod_ops.create(rate); if (!m) return 1;
    int mode;
    if (gba_maxmod_ops.start(m,loaded,0,0,0,&mode) != CHIP_OK) { gba_maxmod_ops.destroy(m); return 1; }
    FILE* output = std::fopen(argv[2],"wb");
    if (!output) { gba_maxmod_ops.destroy(m); return 1; }
    unsigned remaining = seconds * rate, data_size = remaining * 4;
    std::fwrite("RIFF",1,4,output); le32(output,36+data_size); std::fwrite("WAVEfmt ",1,8,output);
    le32(output,16); le16(output,1); le16(output,2); le32(output,rate); le32(output,rate*4);
    le16(output,4); le16(output,16); std::fwrite("data",1,4,output); le32(output,data_size);
    float pcm[CHIP_MAX_TICK_FRAMES*2]; int status = 0;
    while (remaining) {
        int count = gba_maxmod_ops.tick(m,pcm);
        if (count <= 0) { status = 1; break; }
        if (unsigned(count) > remaining) count = int(remaining);
        for (int i=0;i<count*2;++i) le16(output,unsigned(int16_t(pcm[i]*32768.0f)));
        remaining -= unsigned(count);
    }
    if (std::ferror(output)) status = 1;
    if (std::fclose(output)) status = 1;
    gba_maxmod_ops.destroy(m);
    return status;
}
