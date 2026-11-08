/* Thor Tracker's WonderSwan player (ws_player.h). Specification: docs/AUDIO_ENGINE.md, "WonderSwan: Player".
 * Every rule here has a counterpart in tools/audio/ws/ws_ref.py; change both and the spec together. */
#include "ws_player.h"

#include <string.h>

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");

/* round(96000 / (440 * 2^((i / 64 - 57) / 12)) * 65536), i = 0..767: N = 2048 - d of C-0's octave in 16.16 fixed
 * point (96000 = 3072000 / 32 steps; ws_ref.py computes it with this formula; prepare_ws.py fails on any difference). */
const uint32_t WSP_PERIOD_Q16[768] = {
    384760931u, 384413827u, 384067036u, 383720559u, 383374394u, 383028541u, 382683000u, 382337771u,
    381992853u, 381648246u, 381303951u, 380959966u, 380616291u, 380272926u, 379929871u, 379587126u,
    379244690u, 378902562u, 378560744u, 378219233u, 377878031u, 377537137u, 377196550u, 376856270u,
    376516297u, 376176631u, 375837272u, 375498218u, 375159471u, 374821029u, 374482892u, 374145061u,
    373807534u, 373470312u, 373133393u, 372796779u, 372460469u, 372124462u, 371788758u, 371453357u,
    371118258u, 370783462u, 370448968u, 370114775u, 369780884u, 369447294u, 369114005u, 368781017u,
    368448330u, 368115942u, 367783854u, 367452066u, 367120577u, 366789387u, 366458496u, 366127904u,
    365797609u, 365467613u, 365137914u, 364808513u, 364479409u, 364150602u, 363822091u, 363493877u,
    363165959u, 362838337u, 362511010u, 362183979u, 361857242u, 361530801u, 361204653u, 360878801u,
    360553242u, 360227976u, 359903005u, 359578326u, 359253940u, 358929847u, 358606046u, 358282538u,
    357959321u, 357636396u, 357313762u, 356991419u, 356669367u, 356347605u, 356026134u, 355704953u,
    355384061u, 355063459u, 354743147u, 354423123u, 354103388u, 353783941u, 353464783u, 353145912u,
    352827329u, 352509034u, 352191025u, 351873304u, 351555869u, 351238720u, 350921858u, 350605282u,
    350288991u, 349972985u, 349657265u, 349341829u, 349026678u, 348711811u, 348397228u, 348082929u,
    347768914u, 347455182u, 347141733u, 346828566u, 346515682u, 346203081u, 345890761u, 345578723u,
    345266967u, 344955492u, 344644298u, 344333385u, 344022752u, 343712399u, 343402326u, 343092533u,
    342783020u, 342473786u, 342164830u, 341856154u, 341547756u, 341239636u, 340931794u, 340624230u,
    340316943u, 340009934u, 339703201u, 339396745u, 339090566u, 338784663u, 338479035u, 338173684u,
    337868608u, 337563807u, 337259281u, 336955030u, 336651053u, 336347351u, 336043923u, 335740768u,
    335437887u, 335135279u, 334832944u, 334530881u, 334229092u, 333927574u, 333626329u, 333325355u,
    333024653u, 332724222u, 332424062u, 332124173u, 331824554u, 331525206u, 331226128u, 330927319u,
    330628780u, 330330511u, 330032510u, 329734779u, 329437316u, 329140121u, 328843194u, 328546536u,
    328250144u, 327954021u, 327658164u, 327362574u, 327067251u, 326772195u, 326477404u, 326182880u,
    325888621u, 325594628u, 325300900u, 325007437u, 324714238u, 324421304u, 324128635u, 323836229u,
    323544087u, 323252209u, 322960594u, 322669242u, 322378153u, 322087327u, 321796763u, 321506461u,
    321216421u, 320926642u, 320637125u, 320347870u, 320058875u, 319770141u, 319481667u, 319193453u,
    318905500u, 318617806u, 318330372u, 318043197u, 317756282u, 317469625u, 317183226u, 316897087u,
    316611205u, 316325581u, 316040215u, 315755106u, 315470254u, 315185660u, 314901322u, 314617240u,
    314333415u, 314049846u, 313766533u, 313483475u, 313200673u, 312918126u, 312635834u, 312353796u,
    312072013u, 311790484u, 311509209u, 311228188u, 310947420u, 310666905u, 310386644u, 310106635u,
    309826880u, 309547376u, 309268125u, 308989125u, 308710377u, 308431881u, 308153636u, 307875642u,
    307597898u, 307320406u, 307043163u, 306766171u, 306489428u, 306212936u, 305936692u, 305660698u,
    305384953u, 305109457u, 304834209u, 304559209u, 304284458u, 304009954u, 303735698u, 303461690u,
    303187928u, 302914414u, 302641146u, 302368125u, 302095350u, 301822822u, 301550539u, 301278502u,
    301006710u, 300735163u, 300463861u, 300192804u, 299921992u, 299651424u, 299381100u, 299111020u,
    298841183u, 298571590u, 298302240u, 298033133u, 297764269u, 297495648u, 297227269u, 296959131u,
    296691236u, 296423583u, 296156171u, 295889000u, 295622070u, 295355381u, 295088933u, 294822724u,
    294556757u, 294291029u, 294025540u, 293760292u, 293495282u, 293230512u, 292965980u, 292701687u,
    292437633u, 292173817u, 291910238u, 291646898u, 291383795u, 291120930u, 290858301u, 290595910u,
    290333755u, 290071837u, 289810155u, 289548709u, 289287499u, 289026524u, 288765785u, 288505281u,
    288245013u, 287984979u, 287725179u, 287465614u, 287206283u, 286947187u, 286688323u, 286429694u,
    286171297u, 285913134u, 285655204u, 285397506u, 285140041u, 284882808u, 284625808u, 284369039u,
    284112501u, 283856195u, 283600121u, 283344277u, 283088664u, 282833282u, 282578130u, 282323208u,
    282068516u, 281814054u, 281559822u, 281305819u, 281052045u, 280798500u, 280545184u, 280292096u,
    280039237u, 279786605u, 279534202u, 279282026u, 279030078u, 278778357u, 278526863u, 278275596u,
    278024556u, 277773742u, 277523155u, 277272793u, 277022658u, 276772748u, 276523063u, 276273604u,
    276024370u, 275775360u, 275526576u, 275278015u, 275029679u, 274781567u, 274533679u, 274286014u,
    274038573u, 273791355u, 273544360u, 273297588u, 273051039u, 272804712u, 272558607u, 272312724u,
    272067063u, 271821624u, 271576406u, 271331409u, 271086633u, 270842079u, 270597744u, 270353630u,
    270109737u, 269866063u, 269622609u, 269379375u, 269136360u, 268893565u, 268650988u, 268408631u,
    268166492u, 267924571u, 267682869u, 267441385u, 267200118u, 266959069u, 266718238u, 266477624u,
    266237227u, 265997047u, 265757084u, 265517337u, 265277806u, 265038491u, 264799393u, 264560510u,
    264321842u, 264083390u, 263845153u, 263607131u, 263369323u, 263131730u, 262894352u, 262657187u,
    262420237u, 262183500u, 261946977u, 261710667u, 261474571u, 261238687u, 261003016u, 260767558u,
    260532312u, 260297279u, 260062457u, 259827848u, 259593450u, 259359263u, 259125288u, 258891523u,
    258657970u, 258424627u, 258191495u, 257958573u, 257725862u, 257493360u, 257261068u, 257028985u,
    256797112u, 256565448u, 256333993u, 256102747u, 255871710u, 255640881u, 255410260u, 255179847u,
    254949642u, 254719645u, 254489855u, 254260273u, 254030897u, 253801729u, 253572767u, 253344012u,
    253115463u, 252887121u, 252658984u, 252431053u, 252203328u, 251975808u, 251748494u, 251521384u,
    251294480u, 251067780u, 250841285u, 250614994u, 250388907u, 250163024u, 249937345u, 249711869u,
    249486597u, 249261528u, 249036662u, 248811999u, 248587539u, 248363281u, 248139226u, 247915372u,
    247691721u, 247468271u, 247245023u, 247021976u, 246799131u, 246576486u, 246354043u, 246131800u,
    245909757u, 245687915u, 245466273u, 245244831u, 245023589u, 244802546u, 244581703u, 244361059u,
    244140614u, 243920368u, 243700320u, 243480471u, 243260821u, 243041368u, 242822114u, 242603057u,
    242384198u, 242165536u, 241947072u, 241728805u, 241510734u, 241292861u, 241075183u, 240857703u,
    240640418u, 240423330u, 240206437u, 239989740u, 239773239u, 239556932u, 239340821u, 239124905u,
    238909184u, 238693657u, 238478325u, 238263187u, 238048243u, 237833493u, 237618936u, 237404574u,
    237190404u, 236976428u, 236762645u, 236549055u, 236335657u, 236122452u, 235909439u, 235696619u,
    235483990u, 235271554u, 235059308u, 234847255u, 234635392u, 234423721u, 234212241u, 234000951u,
    233789853u, 233578944u, 233368226u, 233157698u, 232947360u, 232737211u, 232527253u, 232317483u,
    232107903u, 231898512u, 231689310u, 231480296u, 231271471u, 231062835u, 230854387u, 230646126u,
    230438054u, 230230169u, 230022472u, 229814962u, 229607640u, 229400504u, 229193556u, 228986794u,
    228780218u, 228573829u, 228367626u, 228161609u, 227955778u, 227750133u, 227544673u, 227339399u,
    227134309u, 226929405u, 226724686u, 226520151u, 226315801u, 226111635u, 225907653u, 225703855u,
    225500242u, 225296812u, 225093565u, 224890502u, 224687622u, 224484925u, 224282410u, 224080079u,
    223877930u, 223675963u, 223474179u, 223272577u, 223071156u, 222869917u, 222668860u, 222467984u,
    222267289u, 222066776u, 221866443u, 221666291u, 221466320u, 221266529u, 221066918u, 220867487u,
    220668236u, 220469165u, 220270274u, 220071562u, 219873029u, 219674675u, 219476501u, 219278505u,
    219080687u, 218883049u, 218685588u, 218488306u, 218291201u, 218094274u, 217897525u, 217700954u,
    217504560u, 217308343u, 217112303u, 216916440u, 216720753u, 216525243u, 216329910u, 216134752u,
    215939771u, 215744966u, 215550336u, 215355882u, 215161604u, 214967500u, 214773572u, 214579819u,
    214386240u, 214192836u, 213999607u, 213806552u, 213613671u, 213420964u, 213228431u, 213036071u,
    212843886u, 212651873u, 212460034u, 212268368u, 212076874u, 211885554u, 211694406u, 211503430u,
    211312627u, 211121996u, 210931537u, 210741250u, 210551134u, 210361190u, 210171417u, 209981816u,
    209792385u, 209603125u, 209414037u, 209225118u, 209036370u, 208847793u, 208659385u, 208471148u,
    208283080u, 208095182u, 207907453u, 207719894u, 207532504u, 207345283u, 207158231u, 206971348u,
    206784633u, 206598087u, 206411709u, 206225499u, 206039457u, 205853583u, 205667877u, 205482338u,
    205296967u, 205111763u, 204926726u, 204741855u, 204557152u, 204372615u, 204188245u, 204004041u,
    203820003u, 203636131u, 203452425u, 203268885u, 203085511u, 202902301u, 202719258u, 202536379u,
    202353665u, 202171116u, 201988732u, 201806512u, 201624457u, 201442566u, 201260839u, 201079276u,
    200897876u, 200716641u, 200535568u, 200354660u, 200173914u, 199993331u, 199812912u, 199632655u,
    199452561u, 199272629u, 199092859u, 198913252u, 198733807u, 198554524u, 198375402u, 198196442u,
    198017643u, 197839006u, 197660530u, 197482215u, 197304060u, 197126067u, 196948234u, 196770561u,
    196593049u, 196415697u, 196238505u, 196061472u, 195884600u, 195707887u, 195531333u, 195354939u,
    195178704u, 195002627u, 194826710u, 194650951u, 194475351u, 194299910u, 194124626u, 193949501u,
    193774534u, 193599724u, 193425072u, 193250578u, 193076241u, 192902062u, 192728039u, 192554174u,
};

/* floor(127 sin(2 pi i / 64) + 0.5): the player's own vibrato (the OPL3's table). */
const int8_t WSP_SINE[64] = {
    0,   12,  25,  37,  49,  60,  71,  81,  90,  98,  106,  112,  117,  122,  125,  126,
    127, 126, 125, 122, 117, 112, 106, 98,  90,  81,  71,   60,   49,   37,   25,   12,
    0,   -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
    -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12,
};

/* Hyper Voice update divisor matching each DMA rate (6, 4, 2, 1 blocks): speed 5, 3, 1, 0. */
static const uint8_t HV_SPEED_FOR_RATE[4] = {5, 3, 1, 0};

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

/* ---- writes ---------------------------------------------------------------------------------------------------- */

static int shadow_index(int port) {
    if (port >= 0x80 && port <= 0x8C) return port - 0x80;
    switch (port) {
    case 0x8E: return 13;
    case 0x90: return 14;
    case 0x91: return 15;
    case 0x94: return 16;
    case 0x6A: return 17;
    case 0x6B: return 18;
    default: return -1;
    }
}

int wsp_shadow(const WsPlayer* p, int port) {
    const int i = shadow_index(port);
    return i < 0 ? -1 : p->shadow[i];
}

static void emit(WsPlayer* p, int space, int addr, int val) {
    if (p->writes < WSP_MAX_WRITES) {
        WsWrite* w = &p->write[p->writes++];
        w->space = (uint8_t)space;
        w->addr = (uint16_t)addr;
        w->val = (uint8_t)val;
    }
}

/* A shadowed port: written only when the value differs from the shadow. */
static void through(WsPlayer* p, int port, int val) {
    int* s = &p->shadow[shadow_index(port)];
    if (*s == val) return;
    emit(p, WS_SPACE_PORT, port, val);
    *s = val;
}

/* A shadowed port, always written; $8E's shadow drops bit 3 (LFSR reset), $6B's bit 4 (flip-flop reset). */
static void forced(WsPlayer* p, int port, int val) {
    emit(p, WS_SPACE_PORT, port, val);
    p->shadow[shadow_index(port)] = port == 0x8E ? (val & ~0x08) : port == 0x6B ? (val & ~0x10) : val;
}

/* A port without a shadow ($8D, DMA, $69) or a voice frame to $89 (its shadow becomes unknown). */
static void raw(WsPlayer* p, int port, int val) {
    emit(p, WS_SPACE_PORT, port, val);
    if (port == 0x8D) p->time_written = val;
    if (port == 0x89) p->shadow[0x89 - 0x80] = -1;
}

static void ram(WsPlayer* p, int addr, int val) { emit(p, WS_SPACE_RAM, addr, val); }

/* ---- image access ---------------------------------------------------------------------------------------------- */

/* The instrument record of a slot as the image holds it (sounding notes read it raw every tick: live edits). */
static const uint8_t* raw_record(const WsImage* image, int slot) {
    return image->bytes + WS_OFF_INSTRUMENTS + WS_INSTRUMENT_SIZE * (clamp(slot, 1, WS_MAX_INSTRUMENTS) - 1);
}

/* A defined instrument slot of the image, else NULL. */
static const uint8_t* instrument(const WsImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = raw_record(image, slot);
    return (rec[WS_I_FLAGS] & WS_FLAG_DEFINED) ? rec : NULL;
}

static const uint8_t* sample_entry(const WsImage* image, int index) {
    return image->bytes + WS_OFF_SAMPLES + WS_SAMPLE_ENTRY_SIZE * (index & (WS_MAX_SAMPLES - 1));
}

static const uint8_t* data(const WsImage* image) { return image->bytes + WS_OFF_DATA; }

/* ---- pitch ----------------------------------------------------------------------------------------------------- */

int wsp_period(int pitch) {
    const int p = clamp(pitch, WSP_PITCH_MIN, WSP_PITCH_MAX), o = p / 768;
    return (int)((WSP_PERIOD_Q16[p % 768] + (1u << (15 + o))) >> (16 + o));
}

uint32_t wsp_sample_step(int pitch, int root_note) {
    const int p = clamp(pitch, WSP_PITCH_MIN, WSP_PITCH_MAX), rp = root_note * 64;
    const int d = p / 768 - rp / 768;
    const uint64_t tr = WSP_PERIOD_Q16[rp % 768], tp = WSP_PERIOD_Q16[p % 768];
    if (d >= 0) return (uint32_t)((tr << (16 + d)) / tp);
    return (uint32_t)(((tr << 16) / tp) >> -d);
}

int wsp_timer_cycles(const WsPlayer* p) { return WS_LINE_CYCLES * p->timer; }

/* ---- envelopes ------------------------------------------------------------------------------------------------- */

typedef struct Envelope {
    int offset, length, loop, release;
} Envelope;

static void envelope(const uint8_t* rec, int k, Envelope* e) {
    const uint8_t* at = rec + WS_I_ENVELOPES + WS_ENVELOPE_BYTES * k;
    e->offset = ws_u16(at);
    e->length = at[2];
    e->loop = at[3] == WS_ENV_NONE ? -1 : at[3];
    e->release = at[4] == WS_ENV_NONE ? -1 : at[4];
}

/* The byte at the lane's index (clamped to the length: live edits). */
static int env_byte(const WsImage* image, const Envelope* e, int idx) {
    return data(image)[e->offset + (idx < e->length ? idx : e->length - 1)];
}

static int env_next(const Envelope* e, int idx, int released) {
    if (idx > e->length - 1) idx = e->length - 1;
    const int next = idx + 1;
    if (released && e->release >= 0) return next < e->length ? next : e->length - 1;
    const int held = e->release >= 0 ? e->release : e->length;
    if (next < held) return next;
    return e->loop >= 0 ? e->loop : held - 1;
}

/* ---- notes ----------------------------------------------------------------------------------------------------- */

int wsp_control(const WsPlayer* p) {
    int v = 0;
    for (int c = 0; c < 4; ++c) {
        const WsLane* ln = &p->lane[c];
        if (ln->sounding && (ln->kind == WS_KIND_TONE || (c == WS_NOISE_CHANNEL && ln->kind == WS_KIND_NOISE))) v |= 1 << c;
    }
    const WsLane *l1 = &p->lane[WS_VOICE_CHANNEL], *l2 = &p->lane[WS_SWEEP_CHANNEL], *l3 = &p->lane[WS_NOISE_CHANNEL];
    if (l1->sounding && l1->kind == WS_KIND_SAMPLE) v |= 0x20;
    if (l2->sounding && l2->kind == WS_KIND_TONE && l2->sweep_s != 0) v |= 0x40;
    if (l3->sounding && l3->kind == WS_KIND_NOISE) v |= 0x80;
    return v;
}

/* Whether a record can play on lane c: tone on lanes 0-3, noise on lane 3, samples on lane 1 (mono) and lane 4,
 * DMA samples only on the Color. */
static int fits(const WsImage* image, int c, const uint8_t* rec) {
    const int kind = rec[WS_I_FLAGS] & WS_KIND_MASK;
    if (kind == WS_KIND_TONE) return c <= WS_NOISE_CHANNEL;
    if (kind == WS_KIND_NOISE) return c == WS_NOISE_CHANNEL;
    if (kind != WS_KIND_SAMPLE) return 0;
    if ((rec[WS_I_FLAGS] & WS_FLAG_DMA) && image->info.model != WS_MODEL_COLOR) return 0;
    const int stereo = sample_entry(image, rec[WS_I_SOURCE])[WS_S_FLAGS] & WS_SAMPLE_STEREO;
    return c == WS_HYPER_VOICE || (c == WS_VOICE_CHANNEL && !stereo);
}

static void stop(WsPlayer* p, int c) {
    WsLane* ln = &p->lane[c];
    ln->sounding = 0;
    ln->feeding = 0;
    if (p->owner == c) {
        p->owner = -1;
        raw(p, 0x52, 0);
    }
    if (c == WS_HYPER_VOICE) through(p, 0x6A, 0);
}

static void off(WsPlayer* p, const WsImage* image, int c) {
    WsLane* ln = &p->lane[c];
    if (!ln->sounding) return;
    const uint8_t* rec = raw_record(image, ln->slot);
    Envelope vol;
    envelope(rec, WS_ENV_VOLUME, &vol);
    if (vol.length && vol.release >= 0) {
        ln->released = 1;
        for (int k = 0; k < WS_ENVELOPE_COUNT; ++k) {
            Envelope e;
            envelope(rec, k, &e);
            if (e.length && e.release >= 0) ln->env[k] = e.release;
        }
    } else {
        stop(p, c);
    }
}

void wsp_off(WsPlayer* p, const WsImage* image, int lane) {
    p->writes = 0;
    if (lane >= 0 && lane < p->lanes) off(p, image, lane);
    through(p, 0x90, wsp_control(p));
}

static void key_on(WsPlayer* p, const WsImage* image, int c, int note) {
    WsLane* ln = &p->lane[c];
    if (p->owner == c) {
        p->owner = -1;
        raw(p, 0x52, 0);
    }
    const uint8_t* rec = instrument(image, ln->instrument);
    if (!rec || !fits(image, c, rec)) return; /* only reachable through a live edit */
    ln->slot = ln->instrument;
    ln->kind = rec[WS_I_FLAGS] & WS_KIND_MASK;
    ln->dma = ln->kind == WS_KIND_SAMPLE && (rec[WS_I_FLAGS] & WS_FLAG_DMA) != 0;
    for (int k = 0; k < WS_ENVELOPE_COUNT; ++k) ln->env[k] = 0;
    ln->released = 0;
    ln->rel_pitch = 0;
    ln->base = ln->target = note * 64;
    ln->slide = 0;
    ln->arp_step = 0;
    ln->vib_phase = 0;
    ln->sounding = 1;
    ln->fresh = 1;
    ln->feeding = ln->kind == WS_KIND_SAMPLE && !ln->dma;
    if (ln->feeding) ln->pos = 0x10000;
}

static void run_row(WsPlayer* p, const WsImage* image, int c) {
    WsLane* ln = &p->lane[c];
    const WsRow* row = &ln->row;
    const int has_note = row->note >= WS_NOTE_MIN && row->note <= WS_NOTE_MAX;
    if (has_note) {
        ln->arp = 0;
        ln->fine = 0;
        ln->wave_ov = ln->tap_ov = ln->time_ov = -1;
        ln->sweep_ov = WSP_NONE;
        ln->hold = 0;
    }
    if (row->instrument) ln->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e];
        switch (row->code[e]) {
        case WS_FX_ARPEGGIO: ln->arp = v; ln->arp_step = 0; break;
        case WS_FX_SLIDE_UP: ln->slide_mode = v ? 1 : 0; ln->slide_speed = v; break;
        case WS_FX_SLIDE_DOWN: ln->slide_mode = v ? 2 : 0; ln->slide_speed = v; break;
        case WS_FX_PORTAMENTO: ln->slide_mode = v ? 3 : 0; ln->slide_speed = v; break;
        case WS_FX_VIBRATO: ln->vib_speed = v >> 4; ln->vib_depth = v & 15; break;
        case WS_FX_PAN: ln->pan = v; break;
        case WS_FX_VOLUME_SLIDE: ln->volslide = v; break;
        case WS_FX_VOLUME: ln->vol8 = v * 8; break;
        case WS_FX_HOLD: ln->hold = v; break;
        case WS_FX_SHIFT:
            p->shift = v;
            through(p, 0x91, 0x09 | p->shift << 1);
            break;
        case WS_FX_TAP: ln->tap_ov = v; break;
        case WS_FX_FINE: ln->fine = v - 0x80; break;
        case WS_FX_CUT: ln->cut = v; break;
        case WS_FX_TIMER: p->timer = v; break;
        case WS_FX_SWEEP: ln->sweep_ov = (int8_t)v; break;
        case WS_FX_SWEEP_TIME: ln->time_ov = v; break;
        case WS_FX_WAVE: ln->wave_ov = v; break;
        }
    }
    if (row->note == WS_NOTE_OFF) {
        off(p, image, c);
    } else if (has_note) {
        if (ln->slide_mode == 3 && ln->sounding && (ln->kind == WS_KIND_TONE || (ln->kind == WS_KIND_SAMPLE && !ln->dma)))
            ln->target = row->note * 64;
        else
            key_on(p, image, c, row->note);
    }
}

/* ---- per tick -------------------------------------------------------------------------------------------------- */

static int voice_bits(int l, int r) {
    return (r >= 12 ? 1 : r >= 1 ? 2 : 0) | (l >= 12 ? 4 : l >= 1 ? 8 : 0);
}

static int hv_control(int v, int l, int r, int speed) {
    if (v == 0 || (l == 0 && r == 0)) return 0;
    const int shift = v >= 12 ? 0 : v >= 6 ? 1 : v >= 3 ? 2 : 3;
    return 0x80 | speed << 4 | 2 << 2 | shift;
}

/* Two $69 writes of frame f (unsigned bytes XOR $80: Hyper Voice's signed scaling), 0 for a side that is off. */
static void hv_frame(WsPlayer* p, const WsImage* image, const WsLane* ln, uint32_t offset, uint32_t f, int stereo) {
    const uint8_t* d = data(image);
    const int left = stereo ? d[offset + 2 * f] : d[offset + f];
    const int right = stereo ? d[offset + 2 * f + 1] : d[offset + f];
    raw(p, 0x69, ln->side_l ? left ^ 0x80 : 0);
    raw(p, 0x69, ln->side_r ? right ^ 0x80 : 0);
}

static void update(WsPlayer* p, const WsImage* image, int c) {
    WsLane* ln = &p->lane[c];
    if (!ln->sounding) return;
    const uint8_t* rec = raw_record(image, ln->slot);
    const int flags = rec[WS_I_FLAGS];
    Envelope env[WS_ENVELOPE_COUNT];
    for (int k = 0; k < WS_ENVELOPE_COUNT; ++k) envelope(rec, k, &env[k]);
    const int e_vol = env[WS_ENV_VOLUME].length ? env_byte(image, &env[WS_ENV_VOLUME], ln->env[WS_ENV_VOLUME]) : 15;
    const int arp_env =
        env[WS_ENV_ARPEGGIO].length ? (int8_t)env_byte(image, &env[WS_ENV_ARPEGGIO], ln->env[WS_ENV_ARPEGGIO]) : 0;
    int pitch_env = 0;
    if (env[WS_ENV_PITCH].length) {
        const int v = (int8_t)env_byte(image, &env[WS_ENV_PITCH], ln->env[WS_ENV_PITCH]);
        if (flags & WS_FLAG_RELATIVE) {
            ln->rel_pitch = clamp(ln->rel_pitch + v, -WSP_SLIDE_LIMIT, WSP_SLIDE_LIMIT);
            pitch_env = ln->rel_pitch;
        } else {
            pitch_env = v;
        }
    }
    const int vol = (e_vol * (ln->vol8 >> 3) + 7) / 15;
    const int l = (vol * (ln->pan >> 4) + 7) / 15, r = (vol * (ln->pan & 15) + 7) / 15;
    const int arp = ln->arp_step == 1 ? ln->arp >> 4 : ln->arp_step == 2 ? ln->arp & 15 : 0;
    const int semitones = arp + arp_env;
    const int vib = (WSP_SINE[ln->vib_phase] * ln->vib_depth) >> 4;
    const int pitch = clamp(ln->base + 64 * semitones + ln->fine + ln->slide + vib + pitch_env, WSP_PITCH_MIN, WSP_PITCH_MAX);
    const int d = 2048 - wsp_period(pitch);
    if (ln->kind == WS_KIND_TONE) {
        const int w = env[WS_ENV_WAVE].length ? env_byte(image, &env[WS_ENV_WAVE], ln->env[WS_ENV_WAVE])
                    : ln->wave_ov >= 0 ? ln->wave_ov : rec[WS_I_SOURCE];
        if (ln->loaded != w) {
            const uint8_t* wave = image->bytes + WS_OFF_WAVES + WS_WAVE_SIZE * (w & (WS_MAX_WAVES - 1));
            for (int k = 0; k < 16; ++k) ram(p, WS_WAVE_RAM + 16 * c + k, wave[2 * k] | wave[2 * k + 1] << 4);
            ln->loaded = w;
        }
        if (c == WS_SWEEP_CHANNEL) {
            const int s = ln->sweep_ov != WSP_NONE ? ln->sweep_ov : (int8_t)rec[WS_I_SWEEP];
            const int t = ln->time_ov >= 0 ? ln->time_ov : rec[WS_I_SWEEP_TIME];
            ln->sweep_s = s;
            through(p, 0x8C, s & 0xFF);
            if (s != 0 && (ln->fresh || t != p->time_written)) raw(p, 0x8D, t);
            if (s != 0 || ln->was_sweep) {
                if (ln->fresh || d != ln->last_d || (s != 0) != ln->was_sweep) {
                    forced(p, 0x84, d & 0xFF);
                    forced(p, 0x85, d >> 8);
                }
            } else {
                through(p, 0x84, d & 0xFF);
                through(p, 0x85, d >> 8);
            }
            ln->was_sweep = s != 0;
            ln->last_d = d;
        } else {
            through(p, 0x80 + 2 * c, d & 0xFF);
            through(p, 0x81 + 2 * c, d >> 8);
        }
        through(p, 0x88 + c, l << 4 | r);
    } else if (ln->kind == WS_KIND_NOISE) {
        int t = env[WS_ENV_WAVE].length ? env_byte(image, &env[WS_ENV_WAVE], ln->env[WS_ENV_WAVE])
              : ln->tap_ov >= 0 ? ln->tap_ov : rec[WS_I_SOURCE];
        t &= 7;
        if (ln->fresh && (flags & WS_FLAG_LFSR_RESET)) forced(p, 0x8E, 0x18 | t);
        else through(p, 0x8E, 0x10 | t);
        through(p, 0x86, d & 0xFF);
        through(p, 0x87, d >> 8);
        through(p, 0x8B, l << 4 | r);
    } else {
        const uint8_t* e = sample_entry(image, rec[WS_I_SOURCE]);
        const uint32_t offset = ws_u32(e + WS_S_OFFSET), frames = ws_u32(e + WS_S_FRAMES);
        const uint32_t signed_offset = ws_u32(e + WS_S_SIGNED_OFFSET);
        const int stereo = (e[WS_S_FLAGS] & WS_SAMPLE_STEREO) != 0;
        ln->side_l = l > 0;
        ln->side_r = r > 0;
        if (!ln->dma) {
            ln->step = wsp_sample_step(pitch, rec[WS_I_ROOT]);
            if (c == WS_VOICE_CHANNEL) {
                through(p, 0x94, voice_bits(l, r));
                if (ln->fresh) raw(p, 0x89, data(image)[offset]);
            } else {
                if (ln->fresh) forced(p, 0x6B, 0x10);
                else through(p, 0x6B, 0);
                through(p, 0x6A, hv_control(vol, l, r, 0));
                if (ln->fresh) hv_frame(p, image, ln, offset, 0, stereo);
            }
        } else {
            const int rate = rec[WS_I_ROOT] & 3;
            const int loop = rec[WS_I_LOOP] ? 1 : 0, reverse = (flags & WS_FLAG_REVERSE) ? 1 : 0;
            const int mode = stereo ? 0 : (l > 0 ? 1 : 0) | (r > 0 ? 2 : 0);
            const int control = 0x80 | rate | loop << 3 | (c == WS_HYPER_VOICE ? 1 : 0) << 4 | reverse << 6;
            if (ln->fresh) {
                if (p->owner >= 0 && p->owner != c) stop(p, p->owner);
                raw(p, 0x52, 0);
                uint32_t a = WS_DMA_BASE + (c == WS_VOICE_CHANNEL ? offset : signed_offset);
                const uint32_t bytes = frames * (stereo ? 2u : 1u);
                if (reverse) a += bytes - 1;
                raw(p, 0x4A, a & 0xFF);
                raw(p, 0x4B, a >> 8 & 0xFF);
                raw(p, 0x4C, a >> 16 & 0xFF);
                raw(p, 0x4E, bytes & 0xFF);
                raw(p, 0x4F, bytes >> 8 & 0xFF);
                raw(p, 0x50, bytes >> 16 & 0xFF);
                if (c == WS_VOICE_CHANNEL) {
                    p->shadow[0x89 - 0x80] = -1; /* the DMA streams into $89 */
                    through(p, 0x94, voice_bits(l, r));
                } else {
                    forced(p, 0x6B, 0x10 | mode << 5);
                    through(p, 0x6A, hv_control(vol, l, r, HV_SPEED_FOR_RATE[rate]));
                }
                raw(p, 0x52, control | ln->hold << 2);
                p->owner = c;
                ln->hold_written = ln->hold;
            } else {
                if (c == WS_VOICE_CHANNEL) {
                    through(p, 0x94, voice_bits(l, r));
                } else {
                    through(p, 0x6B, mode << 5);
                    through(p, 0x6A, hv_control(vol, l, r, HV_SPEED_FOR_RATE[rate]));
                }
                if (p->owner == c && ln->hold != ln->hold_written) {
                    raw(p, 0x52, control | ln->hold << 2);
                    ln->hold_written = ln->hold;
                }
            }
        }
    }
    ln->fresh = 0;
    for (int k = 0; k < WS_ENVELOPE_COUNT; ++k)
        if (env[k].length) ln->env[k] = env_next(&env[k], ln->env[k], ln->released);
    if (ln->arp) ln->arp_step = (ln->arp_step + 1) % 3;
    if (ln->slide_mode == 1) ln->slide = clamp(ln->slide + ln->slide_speed, -WSP_SLIDE_LIMIT, WSP_SLIDE_LIMIT);
    else if (ln->slide_mode == 2) ln->slide = clamp(ln->slide - ln->slide_speed, -WSP_SLIDE_LIMIT, WSP_SLIDE_LIMIT);
    else if (ln->slide_mode == 3) {
        if (ln->base < ln->target) ln->base = ln->base + ln->slide_speed > ln->target ? ln->target : ln->base + ln->slide_speed;
        else if (ln->base > ln->target) ln->base = ln->base - ln->slide_speed < ln->target ? ln->target : ln->base - ln->slide_speed;
    }
    if (ln->vib_depth && ln->vib_speed) ln->vib_phase = (ln->vib_phase + ln->vib_speed) & 63;
    if (ln->volslide) ln->vol8 = clamp(ln->vol8 + (ln->volslide >> 4) - (ln->volslide & 15), 0, WSP_VOL8_MAX);
}

void wsp_timer(WsPlayer* p, const WsImage* image) {
    p->writes = 0;
    static const int feeds[2] = {WS_VOICE_CHANNEL, WS_HYPER_VOICE};
    for (int i = 0; i < 2; ++i) {
        const int c = feeds[i];
        if (c >= p->lanes) continue;
        WsLane* ln = &p->lane[c];
        if (!ln->feeding) continue;
        const uint8_t* rec = instrument(image, ln->slot);
        if (!rec || (rec[WS_I_FLAGS] & WS_KIND_MASK) != WS_KIND_SAMPLE || (rec[WS_I_FLAGS] & WS_FLAG_DMA)) {
            stop(p, c); /* no longer a CPU-fed sample (live edit): as a one-shot's end */
            continue;
        }
        const uint8_t* e = sample_entry(image, rec[WS_I_SOURCE]);
        const uint32_t offset = ws_u32(e + WS_S_OFFSET), frames = ws_u32(e + WS_S_FRAMES);
        uint64_t f = ln->pos >> 16;
        if (f >= frames) {
            const uint32_t start = (uint32_t)ws_u16(rec + WS_I_LOOP_START);
            if (!rec[WS_I_LOOP] || start >= frames) {
                stop(p, c);
                continue;
            }
            const uint64_t span = (uint64_t)(frames - start) << 16;
            ln->pos = ((uint64_t)start << 16) + (ln->pos - ((uint64_t)frames << 16)) % span;
            f = ln->pos >> 16;
        }
        if (c == WS_VOICE_CHANNEL) raw(p, 0x89, data(image)[offset + (uint32_t)f]);
        else hv_frame(p, image, ln, offset, (uint32_t)f, (e[WS_S_FLAGS] & WS_SAMPLE_STEREO) != 0);
        ln->pos += ln->step;
    }
    through(p, 0x90, wsp_control(p));
}

static void read_rows(WsPlayer* p, const WsImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < p->lanes; ++c) {
            WsCursor cur = {ws_u16(image->bytes + WS_OFF_ORDERS + 2 * (WS_CHANNELS * p->order + c)), 0};
            WsRow skip;
            for (int r = 0; r < p->row; ++r) ws_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < p->lanes; ++c) ws_next_row(image->bytes, &p->cursor[c], &p->lane[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(WsPlayer* p, const WsImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < p->lanes; ++c) {
        const WsRow* row = &p->lane[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == WS_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == WS_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const WsRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == WS_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(WsPlayer* p, const WsImage* image) {
    read_rows(p, image);
    for (int c = 0; c < p->lanes; ++c) {
        const WsRow* row = &p->lane[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == WS_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < p->lanes; ++c) {
        WsLane* ln = &p->lane[c];
        ln->cut = -1;
        ln->pending = 0;
        const int g = row_delay(&ln->row);
        if (g) {
            ln->pending = 1;
            ln->delay = g;
        } else {
            run_row(p, image, c);
        }
    }
}

/* Next row. Every arrival at the loop order's row 0 starts it at the speed sequential straight play reaches it with
 * (a row-0 Fxx still applies on top, at the row start). */
static void advance(WsPlayer* p, const WsImage* image) {
    if (p->jump) {
        p->order = p->jump_order;
        p->row = p->jump_row < image->info.rows[p->order] ? p->jump_row : image->info.rows[p->order] - 1;
        p->jump = 0;
    } else if (++p->row >= image->info.rows[p->order]) {
        p->row = 0;
        if (++p->order >= image->info.orders) p->order = image->info.loop;
    }
    if (p->order == image->info.loop && p->row == 0) p->speed = image->info.start_speed[p->order];
}

void wsp_tick(WsPlayer* p, const WsImage* image) {
    p->writes = 0;
    if (p->dma_kill) {
        p->dma_kill = 0;
        if (p->owner >= 0) stop(p, p->owner);
    }
    through(p, 0x91, 0x09 | p->shift << 1);
    if (!p->audition) {
        if (p->tick == 0) {
            row_start(p, image);
        } else {
            for (int c = 0; c < p->lanes; ++c) {
                WsLane* ln = &p->lane[c];
                if (ln->pending && ln->delay == p->tick) {
                    ln->pending = 0;
                    run_row(p, image, c);
                }
            }
        }
        for (int c = 0; c < p->lanes; ++c)
            if (p->lane[c].cut == p->tick) off(p, image, c);
    }
    for (int c = 0; c < p->lanes; ++c) update(p, image, c);
    through(p, 0x90, wsp_control(p));
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
}

static void reset(WsPlayer* p, const WsImage* image, int audition) {
    memset(p, 0, sizeof *p);
    p->model = image->info.model;
    p->lanes = image->info.lanes;
    for (int c = 0; c < WS_CHANNELS; ++c) {
        WsLane* ln = &p->lane[c];
        ln->row.note = -1;
        ln->cut = -1;
        ln->vol8 = WSP_VOL8_MAX;
        ln->pan = 0xFF;
        ln->wave_ov = ln->tap_ov = ln->time_ov = -1;
        ln->sweep_ov = WSP_NONE;
        ln->loaded = -1;
    }
    for (int i = 0; i < WSP_SHADOWS; ++i) p->shadow[i] = -1;
    p->owner = -1;
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    p->audition = audition;
    p->timer = image->info.timer;
    p->shift = image->info.shift;
    p->time_written = 0;
    /* The reset batch: every write emitted, setting the shadows ($8F has none). */
    emit(p, WS_SPACE_PORT, 0x8F, 0xFF);
    forced(p, 0x90, 0);
    forced(p, 0x91, 0x09 | p->shift << 1);
    for (int c = 0; c < 4; ++c) {
        forced(p, 0x80 + 2 * c, 0);
        forced(p, 0x81 + 2 * c, 0);
        forced(p, 0x88 + c, 0);
    }
    forced(p, 0x8C, 0);
    raw(p, 0x8D, 0);
    forced(p, 0x8E, 0);
    forced(p, 0x94, 0);
    if (p->model == WS_MODEL_COLOR) {
        forced(p, 0x6A, 0);
        forced(p, 0x6B, 0x10);
        raw(p, 0x52, 0);
    }
}

void wsp_reset(WsPlayer* p, const WsImage* image) { reset(p, image, 0); }

void wsp_place(WsPlayer* p, const WsImage* image, int order) {
    reset(p, image, 0);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it (and of
 * the row itself once it has started). */
static int speed_at(const WsImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    WsCursor cur[WS_CHANNELS];
    const int lanes = image->info.lanes;
    for (int c = 0; c < lanes; ++c) {
        cur[c].offset = ws_u16(image->bytes + WS_OFF_ORDERS + 2 * (WS_CHANNELS * order + c));
        cur[c].run = 0;
    }
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < lanes; ++c) {
            WsRow x;
            ws_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == WS_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

/* The bytes Sound DMA reads for a DMA sample record on lane c: the unsigned bytes on CH2, the signed copy on Hyper
 * Voice. Returns 0 if the record is not a DMA sample. */
static int dma_bytes(const WsImage* image, const uint8_t* rec, int c, uint32_t* offset, uint32_t* bytes, int* stereo) {
    if (!rec || (rec[WS_I_FLAGS] & (WS_FLAG_DEFINED | WS_KIND_MASK | WS_FLAG_DMA)) != (WS_FLAG_DEFINED | WS_KIND_SAMPLE | WS_FLAG_DMA))
        return 0;
    const uint8_t* e = sample_entry(image, rec[WS_I_SOURCE]);
    *stereo = (e[WS_S_FLAGS] & WS_SAMPLE_STEREO) != 0;
    *bytes = ws_u32(e + WS_S_FRAMES) * (*stereo ? 2u : 1u);
    *offset = ws_u32(e + (c == WS_VOICE_CHANNEL ? WS_S_OFFSET : WS_S_SIGNED_OFFSET));
    return 1;
}

/* (5) of the live-edit rules: the DMA owner keeps playing (1, *delta = the move of its bytes) or stops (0). */
static int keep_dma(const WsPlayer* p, const WsImage* old, const WsImage* next, int32_t* delta) {
    const int c = p->owner;
    const WsLane* ln = &p->lane[c];
    const uint8_t* a = raw_record(old, ln->slot);
    const uint8_t* b = instrument(next, ln->slot);
    uint32_t oa, na, ba, bb;
    int sa, sb;
    if (!dma_bytes(old, a, c, &oa, &ba, &sa) || !dma_bytes(next, b, c, &na, &bb, &sb)) return 0;
    if ((a[WS_I_ROOT] & 3) != (b[WS_I_ROOT] & 3) || (a[WS_I_LOOP] != 0) != (b[WS_I_LOOP] != 0) ||
        (a[WS_I_FLAGS] & WS_FLAG_REVERSE) != (b[WS_I_FLAGS] & WS_FLAG_REVERSE) || sa != sb || ba != bb)
        return 0;
    if ((uint64_t)oa + ba > WS_DATA_CAPACITY || (uint64_t)na + bb > WS_DATA_CAPACITY) return 0;
    if (memcmp(data(old) + oa, data(next) + na, ba)) return 0;
    *delta = (int32_t)na - (int32_t)oa;
    return 1;
}

int wsp_relocate(WsPlayer* p, const WsImage* old, const WsImage* next, int32_t* dma_delta) {
    *dma_delta = 0;
    if (p->audition) return 0;
    const WsInfo* info = &next->info;
    /* (4) header fields that changed, waves whose bytes changed */
    if (old->info.timer != info->timer) p->timer = info->timer;
    if (old->info.shift != info->shift) p->shift = info->shift;
    for (int c = 0; c < p->lanes; ++c) {
        WsLane* ln = &p->lane[c];
        if (ln->loaded < 0) continue;
        const int at = WS_OFF_WAVES + WS_WAVE_SIZE * (ln->loaded & (WS_MAX_WAVES - 1));
        if (memcmp(old->bytes + at, next->bytes + at, WS_WAVE_SIZE)) ln->loaded = -1;
    }
    /* (5) the DMA note */
    int rebase = 0;
    if (p->owner >= 0) {
        int32_t delta = 0;
        if (keep_dma(p, old, next, &delta)) {
            *dma_delta = delta;
            rebase = delta != 0;
        } else {
            p->dma_kill = 1;
        }
    }
    /* (1) position, (2) speed, (3) the started row */
    const int started = p->tick > 0;
    const int old_speed = speed_at(old, p->order, p->row, started);
    int clamped = 0;
    if (p->order >= info->orders) {
        p->order = info->orders - 1;
        p->row = info->rows[p->order] - 1;
        clamped = 1;
    } else if (p->row >= info->rows[p->order]) {
        p->row = info->rows[p->order] - 1;
        clamped = 1;
    }
    p->cursor_order = p->cursor_row = -1;
    if (clamped) {
        p->jump = 0;
        for (int c = 0; c < p->lanes; ++c) p->lane[c].pending = 0;
        p->speed = speed_at(next, p->order, p->row, 1);
        if (!started) advance(p, next); /* the clamped row counts as played: the next row follows it */
        return rebase;
    }
    const int new_speed = speed_at(next, p->order, p->row, started);
    if (new_speed != old_speed) p->speed = new_speed;
    if (!started) return rebase;
    for (int c = 0; c < p->lanes; ++c) {
        WsRow row;
        ws_read_row(next, p->order, c, p->row, &row, NULL);
        WsLane* ln = &p->lane[c];
        ln->row = row;
        if (ln->pending) {
            const int g = row_delay(&row);
            ln->pending = g >= p->tick;
            ln->delay = g;
        }
    }
    derive_jump(p, next);
    return rebase;
}

int wsp_audition(WsPlayer* p, const WsImage* image, int lane, int note, int slot, int fine) {
    if (lane < 0 || lane >= image->info.lanes || note < WS_NOTE_MIN || note > WS_NOTE_MAX || fine < -128 || fine > 127)
        return -1;
    const uint8_t* rec = instrument(image, slot);
    if (!rec || !fits(image, lane, rec)) return -1;
    reset(p, image, 1);
    p->lane[lane].instrument = slot;
    key_on(p, image, lane, note);
    p->lane[lane].fine = fine;
    return 0;
}

/* ---- state comparison (the VGM loop) --------------------------------------------------------------------------- */

typedef struct StateVisit {
    int differ;
    uint64_t hash;
} StateVisit;

static void visit(StateVisit* v, const void* a, const void* b, size_t n) {
    if (b) {
        if (!v->differ && memcmp(a, b, n) != 0) v->differ = 1;
        return;
    }
    const uint8_t* q = (const uint8_t*)a;
    for (size_t i = 0; i < n; ++i) v->hash = (v->hash ^ q[i]) * 0x100000001B3ull;
}

#define PAIR(x, y, f) visit(v, &(x)->f, (y) ? (const void*)&(y)->f : NULL, sizeof (x)->f)

static void walk_row(StateVisit* v, const WsRow* a, const WsRow* b) {
    PAIR(a, b, note);
    PAIR(a, b, instrument);
    PAIR(a, b, effects);
    if (b && a->effects != b->effects) return; /* already different */
    visit(v, a->code, b ? b->code : NULL, (size_t)a->effects);
    visit(v, a->param, b ? b->param : NULL, (size_t)a->effects);
}

_Static_assert(sizeof(WsLane) == sizeof(WsRow) + sizeof(uint64_t) + sizeof(uint32_t) + 42 * sizeof(int),
               "WsLane: every field is listed in walk_lane");

static void walk_lane(StateVisit* v, const WsLane* a, const WsLane* b) {
    walk_row(v, &a->row, b ? &b->row : NULL);
    PAIR(a, b, pending); PAIR(a, b, delay); PAIR(a, b, cut);
    PAIR(a, b, instrument); PAIR(a, b, slot); PAIR(a, b, kind); PAIR(a, b, dma);
    PAIR(a, b, sounding); PAIR(a, b, fresh); PAIR(a, b, released); PAIR(a, b, feeding);
    PAIR(a, b, pos); PAIR(a, b, step);
    PAIR(a, b, env); PAIR(a, b, rel_pitch);
    PAIR(a, b, base); PAIR(a, b, target); PAIR(a, b, fine);
    PAIR(a, b, arp); PAIR(a, b, arp_step);
    PAIR(a, b, slide_mode); PAIR(a, b, slide_speed); PAIR(a, b, slide);
    PAIR(a, b, vib_speed); PAIR(a, b, vib_depth); PAIR(a, b, vib_phase);
    PAIR(a, b, vol8); PAIR(a, b, volslide); PAIR(a, b, pan);
    PAIR(a, b, wave_ov); PAIR(a, b, tap_ov); PAIR(a, b, sweep_ov); PAIR(a, b, time_ov);
    PAIR(a, b, hold); PAIR(a, b, hold_written); PAIR(a, b, loaded);
    PAIR(a, b, was_sweep); PAIR(a, b, last_d); PAIR(a, b, sweep_s); PAIR(a, b, side_l); PAIR(a, b, side_r);
}

/* Every field but the batch (writes, write[]) and the row cursor cache. */
static void walk_player(StateVisit* v, const WsPlayer* a, const WsPlayer* b) {
    PAIR(a, b, model); PAIR(a, b, lanes);
    PAIR(a, b, order); PAIR(a, b, row); PAIR(a, b, tick); PAIR(a, b, speed);
    PAIR(a, b, jump); PAIR(a, b, jump_order); PAIR(a, b, jump_row); PAIR(a, b, audition);
    PAIR(a, b, timer); PAIR(a, b, shift); PAIR(a, b, owner); PAIR(a, b, dma_kill); PAIR(a, b, time_written);
    PAIR(a, b, shadow);
    for (int c = 0; c < WS_CHANNELS; ++c) walk_lane(v, &a->lane[c], b ? &b->lane[c] : NULL);
}

#undef PAIR

int wsp_same_state(const WsPlayer* a, const WsPlayer* b) {
    StateVisit v = {0, 0};
    walk_player(&v, a, b);
    return !v.differ;
}

uint64_t wsp_state_hash(const WsPlayer* p) {
    StateVisit v = {0, 0xCBF29CE484222325ull};
    walk_player(&v, p, NULL);
    return v.hash;
}
