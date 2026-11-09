/* Thor Tracker's PC Engine player (pce_player.h). Specification: docs/AUDIO_ENGINE.md, "PC Engine: Player".
 * Every rule here has a counterpart in tools/audio/pce/pce_ref.py; change both and the spec together. */
#include "pce_player.h"

#include <string.h>

_Static_assert((-1 >> 1) == -1, "the vibrato offset uses an arithmetic (floor) right shift");

/* round(39375000 / 11 / (32 * 440 * 2^((i / 64 - 57) / 12)) * 65536), i = 0..767: the 12-bit period of C-0's octave in
 * 16.16 fixed point (pce_ref.py computes it with this formula; prepare_pce.py fails on any difference). */
const uint32_t PCP_PERIOD_Q16[768] = {
    448329831u, 447925380u, 447521294u, 447117572u, 446714215u, 446311221u, 445908591u, 445506325u,
    445104421u, 444702880u, 444301701u, 443900883u, 443500428u, 443100334u, 442700600u, 442301228u,
    441902215u, 441503563u, 441105270u, 440707336u, 440309762u, 439912546u, 439515688u, 439119189u,
    438723047u, 438327263u, 437931835u, 437536765u, 437142050u, 436747692u, 436353690u, 435960043u,
    435566751u, 435173814u, 434781231u, 434389003u, 433997128u, 433605607u, 433214439u, 432823624u,
    432433162u, 432043052u, 431653294u, 431263887u, 430874832u, 430486127u, 430097774u, 429709770u,
    429322117u, 428934813u, 428547859u, 428161254u, 427774998u, 427389090u, 427003530u, 426618318u,
    426233454u, 425848936u, 425464766u, 425080942u, 424697465u, 424314333u, 423931547u, 423549107u,
    423167011u, 422785260u, 422403854u, 422022791u, 421642072u, 421261697u, 420881665u, 420501976u,
    420122629u, 419743625u, 419364962u, 418986641u, 418608662u, 418231023u, 417853725u, 417476767u,
    417100150u, 416723872u, 416347933u, 415972334u, 415597074u, 415222152u, 414847568u, 414473323u,
    414099415u, 413725844u, 413352610u, 412979713u, 412607152u, 412234928u, 411863039u, 411491486u,
    411120268u, 410749384u, 410378836u, 410008621u, 409638741u, 409269194u, 408899981u, 408531101u,
    408162554u, 407794339u, 407426456u, 407058905u, 406691686u, 406324797u, 405958240u, 405592014u,
    405226118u, 404860552u, 404495316u, 404130409u, 403765832u, 403401583u, 403037663u, 402674072u,
    402310808u, 401947872u, 401585264u, 401222982u, 400861028u, 400499400u, 400138098u, 399777122u,
    399416472u, 399056147u, 398696147u, 398336472u, 397977121u, 397618095u, 397259392u, 396901014u,
    396542958u, 396185225u, 395827815u, 395470728u, 395113963u, 394757519u, 394401397u, 394045597u,
    393690117u, 393334958u, 392980120u, 392625601u, 392271402u, 391917523u, 391563963u, 391210723u,
    390857800u, 390505196u, 390152911u, 389800943u, 389449292u, 389097959u, 388746943u, 388396243u,
    388045860u, 387695793u, 387346042u, 386996606u, 386647485u, 386298680u, 385950189u, 385602012u,
    385254150u, 384906601u, 384559366u, 384212444u, 383865835u, 383519539u, 383173555u, 382827883u,
    382482524u, 382137475u, 381792738u, 381448312u, 381104197u, 380760392u, 380416897u, 380073713u,
    379730837u, 379388272u, 379046015u, 378704067u, 378362427u, 378021096u, 377680072u, 377339356u,
    376998948u, 376658846u, 376319052u, 375979564u, 375640382u, 375301506u, 374962936u, 374624671u,
    374286712u, 373949057u, 373611707u, 373274662u, 372937920u, 372601482u, 372265348u, 371929517u,
    371593989u, 371258763u, 370923840u, 370589219u, 370254900u, 369920883u, 369587167u, 369253752u,
    368920638u, 368587824u, 368255310u, 367923097u, 367591183u, 367259569u, 366928253u, 366597237u,
    366266519u, 365936100u, 365605979u, 365276155u, 364946629u, 364617401u, 364288469u, 363959834u,
    363631496u, 363303453u, 362975707u, 362648257u, 362321101u, 361994241u, 361667676u, 361341405u,
    361015429u, 360689747u, 360364358u, 360039264u, 359714462u, 359389953u, 359065738u, 358741814u,
    358418183u, 358094844u, 357771797u, 357449041u, 357126576u, 356804402u, 356482518u, 356160925u,
    355839623u, 355518610u, 355197886u, 354877452u, 354557307u, 354237451u, 353917884u, 353598604u,
    353279613u, 352960909u, 352642493u, 352324365u, 352006523u, 351688968u, 351371699u, 351054717u,
    350738021u, 350421610u, 350105485u, 349789645u, 349474090u, 349158819u, 348843833u, 348529131u,
    348214713u, 347900579u, 347586728u, 347273160u, 346959875u, 346646873u, 346334153u, 346021715u,
    345709559u, 345397685u, 345086092u, 344774780u, 344463749u, 344152999u, 343842528u, 343532338u,
    343222428u, 342912797u, 342603446u, 342294374u, 341985580u, 341677066u, 341368829u, 341060871u,
    340753190u, 340445787u, 340138661u, 339831813u, 339525241u, 339218945u, 338912926u, 338607183u,
    338301716u, 337996525u, 337691609u, 337386967u, 337082601u, 336778509u, 336474692u, 336171149u,
    335867879u, 335564883u, 335262161u, 334959711u, 334657535u, 334355631u, 334053999u, 333752639u,
    333451552u, 333150736u, 332850191u, 332549918u, 332249915u, 331950183u, 331650721u, 331351530u,
    331052608u, 330753956u, 330455574u, 330157460u, 329859616u, 329562040u, 329264733u, 328967694u,
    328670923u, 328374420u, 328078184u, 327782215u, 327486514u, 327191079u, 326895911u, 326601009u,
    326306373u, 326012002u, 325717898u, 325424059u, 325130484u, 324837175u, 324544130u, 324251350u,
    323958833u, 323666581u, 323374592u, 323082867u, 322791405u, 322500205u, 322209269u, 321918595u,
    321628183u, 321338033u, 321048145u, 320758518u, 320469153u, 320180049u, 319891205u, 319602623u,
    319314300u, 319026238u, 318738435u, 318450892u, 318163608u, 317876584u, 317589819u, 317303312u,
    317017064u, 316731074u, 316445342u, 316159867u, 315874651u, 315589691u, 315304989u, 315020543u,
    314736354u, 314452422u, 314168745u, 313885325u, 313602160u, 313319251u, 313036597u, 312754197u,
    312472053u, 312190163u, 311908528u, 311627146u, 311346018u, 311065144u, 310784524u, 310504156u,
    310224042u, 309944180u, 309664570u, 309385213u, 309106108u, 308827255u, 308548653u, 308270303u,
    307992203u, 307714355u, 307436757u, 307159410u, 306882312u, 306605465u, 306328868u, 306052520u,
    305776421u, 305500572u, 305224971u, 304949619u, 304674515u, 304399660u, 304125052u, 303850692u,
    303576580u, 303302715u, 303029097u, 302755726u, 302482602u, 302209724u, 301937092u, 301664706u,
    301392565u, 301120671u, 300849021u, 300577617u, 300306457u, 300035542u, 299764872u, 299494445u,
    299224263u, 298954324u, 298684629u, 298415177u, 298145969u, 297877003u, 297608280u, 297339799u,
    297071560u, 296803563u, 296535809u, 296268295u, 296001023u, 295733992u, 295467202u, 295200653u,
    294934344u, 294668276u, 294402447u, 294136858u, 293871509u, 293606399u, 293341529u, 293076897u,
    292812504u, 292548350u, 292284434u, 292020756u, 291757315u, 291494113u, 291231148u, 290968420u,
    290705929u, 290443675u, 290181658u, 289919877u, 289658332u, 289397023u, 289135950u, 288875112u,
    288614509u, 288354142u, 288094010u, 287834112u, 287574449u, 287315020u, 287055825u, 286796864u,
    286538136u, 286279642u, 286021381u, 285763353u, 285505558u, 285247995u, 284990665u, 284733567u,
    284476701u, 284220066u, 283963663u, 283707491u, 283451551u, 283195841u, 282940362u, 282685114u,
    282430096u, 282175307u, 281920749u, 281666420u, 281412321u, 281158451u, 280904810u, 280651398u,
    280398215u, 280145259u, 279892532u, 279640034u, 279387762u, 279135719u, 278883903u, 278632314u,
    278380951u, 278129816u, 277878907u, 277628225u, 277377769u, 277127538u, 276877534u, 276627755u,
    276378201u, 276128873u, 275879769u, 275630890u, 275382236u, 275133805u, 274885599u, 274637617u,
    274389859u, 274142324u, 273895013u, 273647924u, 273401059u, 273154416u, 272907996u, 272661798u,
    272415822u, 272170068u, 271924535u, 271679225u, 271434135u, 271189267u, 270944619u, 270700192u,
    270455986u, 270212000u, 269968234u, 269724688u, 269481362u, 269238255u, 268995368u, 268752700u,
    268510250u, 268268020u, 268026007u, 267784214u, 267542638u, 267301280u, 267060140u, 266819218u,
    266578512u, 266338024u, 266097753u, 265857699u, 265617861u, 265378240u, 265138835u, 264899646u,
    264660672u, 264421914u, 264183372u, 263945044u, 263706932u, 263469035u, 263231352u, 262993883u,
    262756629u, 262519589u, 262282763u, 262046150u, 261809751u, 261573565u, 261337592u, 261101832u,
    260866285u, 260630950u, 260395827u, 260160917u, 259926218u, 259691731u, 259457456u, 259223392u,
    258989540u, 258755898u, 258522467u, 258289246u, 258056236u, 257823437u, 257590847u, 257358467u,
    257126296u, 256894336u, 256662584u, 256431041u, 256199708u, 255968583u, 255737666u, 255506958u,
    255276458u, 255046166u, 254816082u, 254586205u, 254356535u, 254127073u, 253897818u, 253668770u,
    253439928u, 253211293u, 252982863u, 252754640u, 252526623u, 252298812u, 252071206u, 251843806u,
    251616610u, 251389620u, 251162834u, 250936253u, 250709876u, 250483704u, 250257735u, 250031971u,
    249806410u, 249581053u, 249355898u, 249130947u, 248906199u, 248681654u, 248457311u, 248233171u,
    248009233u, 247785497u, 247561962u, 247338630u, 247115499u, 246892569u, 246669840u, 246447312u,
    246224985u, 246002859u, 245780932u, 245559207u, 245337681u, 245116355u, 244895228u, 244674301u,
    244453574u, 244233045u, 244012716u, 243792585u, 243572653u, 243352919u, 243133383u, 242914046u,
    242694906u, 242475964u, 242257220u, 242038673u, 241820323u, 241602170u, 241384214u, 241166454u,
    240948891u, 240731525u, 240514354u, 240297379u, 240080600u, 239864017u, 239647628u, 239431436u,
    239215438u, 238999635u, 238784026u, 238568613u, 238353393u, 238138368u, 237923536u, 237708899u,
    237494455u, 237280205u, 237066147u, 236852283u, 236638612u, 236425134u, 236211848u, 235998755u,
    235785853u, 235573144u, 235360627u, 235148302u, 234936168u, 234724225u, 234512474u, 234300914u,
    234089544u, 233878365u, 233667377u, 233456579u, 233245971u, 233035554u, 232825326u, 232615287u,
    232405439u, 232195779u, 231986309u, 231777027u, 231567935u, 231359031u, 231150315u, 230941788u,
    230733449u, 230525298u, 230317334u, 230109559u, 229901970u, 229694569u, 229487355u, 229280328u,
    229073488u, 228866834u, 228660367u, 228454086u, 228247991u, 228042082u, 227836359u, 227630821u,
    227425469u, 227220302u, 227015320u, 226810524u, 226605911u, 226401484u, 226197241u, 225993182u,
    225789307u, 225585616u, 225382109u, 225178785u, 224975645u, 224772688u, 224569914u, 224367324u,
};

/* floor(127 sin(2 pi i / 64) + 0.5): the player's own vibrato (the OPL3's table). */
const int8_t PCP_SINE[64] = {
    0,   12,  25,  37,  49,  60,  71,  81,  90,  98,  106,  112,  117,  122,  125,  126,
    127, 126, 125, 122, 117, 112, 106, 98,  90,  81,  71,   60,   49,   37,   25,   12,
    0,   -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
    -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12,
};

#define R_SELECT 0
#define R_MASTER 1
#define R_FREQ_LO 2
#define R_FREQ_HI 3
#define R_CONTROL 4
#define R_BALANCE 5
#define R_DATA 6
#define R_NOISE 7
#define R_LFO_FREQ 8
#define R_LFO_CTRL 9

static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

static void append(PcPlayer* p, int reg, int val) {
    if (p->writes < PCP_MAX_WRITES) {
        p->write[p->writes].reg = (uint8_t)reg;
        p->write[p->writes].val = (uint8_t)val;
        ++p->writes;
    }
}

static void select_channel(PcPlayer* p, int c) {
    if (p->select != c) {
        append(p, R_SELECT, c);
        p->select = c;
    }
}

static uint8_t* channel_shadow(PcChannel* ch, int reg) {
    switch (reg) {
    case R_FREQ_LO: return &ch->r2;
    case R_FREQ_HI: return &ch->r3;
    case R_CONTROL: return &ch->r4;
    case R_BALANCE: return &ch->r5;
    default: return &ch->r7;
    }
}

/* A channel register (2, 3, 4, 5, 7) through its shadow, R0 first when the select differs. */
static void wr(PcPlayer* p, int c, int reg, int val) {
    uint8_t* s = channel_shadow(&p->ch[c], reg);
    if (*s == (uint8_t)val) return;
    select_channel(p, c);
    append(p, reg, val);
    *s = (uint8_t)val;
}

/* R6 (never shadowed). */
static void wr_data(PcPlayer* p, int c, int val) {
    select_channel(p, c);
    append(p, R_DATA, val);
}

static void wr_global(PcPlayer* p, uint8_t* shadow, int reg, int val) {
    if (*shadow == (uint8_t)val) return;
    append(p, reg, val);
    *shadow = (uint8_t)val;
}

/* The chip's shared state, in the specified order, each through its shadow. */
static void globals(PcPlayer* p) {
    wr_global(p, &p->shadow_r1, R_MASTER, p->master);
    wr_global(p, &p->shadow_r8, R_LFO_FREQ, p->lfo_freq);
    wr_global(p, &p->shadow_r9, R_LFO_CTRL, p->lfo_ctrl);
}

/* The instrument record of a slot as the image holds it (sounding notes read it raw every tick: live edits). */
static const uint8_t* raw_record(const PceImage* image, int slot) {
    return image->bytes + PC_OFF_INSTRUMENTS + PC_INSTRUMENT_SIZE * (clamp(slot, 1, PC_MAX_INSTRUMENTS) - 1);
}

/* A defined instrument slot of the image, else NULL. */
static const uint8_t* instrument(const PceImage* image, int slot) {
    if (slot < 1 || slot > image->info.instruments) return NULL;
    const uint8_t* rec = raw_record(image, slot);
    return (rec[PC_I_FLAGS] & PC_FLAG_DEFINED) ? rec : NULL;
}

/* ---- pitch ----------------------------------------------------------------------------------- */

int pcp_period(int pitch) {
    const int p = clamp(pitch, PCP_PITCH_MIN, PCP_PITCH_MAX), o = p / 768;
    return (int)((PCP_PERIOD_Q16[p % 768] + (1u << (15 + o))) >> (16 + o));
}

uint32_t pcp_sample_step(int pitch, int root_note) {
    const int p = clamp(pitch, PCP_PITCH_MIN, PCP_PITCH_MAX), rp = root_note * 64;
    const int d = p / 768 - rp / 768;
    const uint64_t tr = PCP_PERIOD_Q16[rp % 768], tp = PCP_PERIOD_Q16[p % 768];
    if (d >= 0) return (uint32_t)((tr << (16 + d)) / tp);
    return (uint32_t)(((tr << 16) / tp) >> -d);
}

int pcp_timer_cycles(const PcPlayer* p) { return PCP_TIMER_DIVIDER * (p->timer + 1); }

/* ---- envelopes ------------------------------------------------------------------------------- */

typedef struct Envelope {
    int offset, length, loop, release;
} Envelope;

static void envelope(const uint8_t* rec, int k, Envelope* e) {
    const uint8_t* at = rec + PC_I_ENVELOPES + PC_ENVELOPE_BYTES * k;
    e->offset = pc_u16(at);
    e->length = at[2];
    e->loop = at[3] == PC_ENV_NONE ? -1 : at[3];
    e->release = at[4] == PC_ENV_NONE ? -1 : at[4];
}

/* The byte at the channel's index (clamped to the length: live edits). */
static int env_byte(const PceImage* image, const Envelope* e, int idx) {
    return image->bytes[PC_OFF_DATA + e->offset + (idx < e->length ? idx : e->length - 1)];
}

static int env_next(const Envelope* e, int idx, int released) {
    if (idx > e->length - 1) idx = e->length - 1;
    const int next = idx + 1;
    if (released && e->release >= 0) return next < e->length ? next : e->length - 1;
    const int held = e->release >= 0 ? e->release : e->length;
    if (next < held) return next;
    return e->loop >= 0 ? e->loop : held - 1;
}

/* ---- notes ----------------------------------------------------------------------------------- */

/* The channel stops: R4 = $00 (tone, noise) or $40 (sample, staying in DDA mode). */
static void stop(PcPlayer* p, int c) {
    PcChannel* ch = &p->ch[c];
    ch->sounding = 0;
    ch->dda = 0;
    wr(p, c, R_CONTROL, ch->kind == PC_KIND_SAMPLE ? 0x40 : 0x00);
}

static void off(PcPlayer* p, const PceImage* image, int c) {
    PcChannel* ch = &p->ch[c];
    if (!ch->sounding) return;
    const uint8_t* rec = raw_record(image, ch->slot);
    Envelope vol;
    envelope(rec, PC_ENV_VOLUME, &vol);
    if (vol.length && vol.release >= 0) {
        ch->released = 1;
        for (int k = 0; k < PC_ENVELOPE_COUNT; ++k) {
            Envelope e;
            envelope(rec, k, &e);
            if (e.length && e.release >= 0) ch->env[k] = e.release;
        }
    } else {
        stop(p, c);
    }
}

void pcp_off(PcPlayer* p, const PceImage* image, int channel) {
    p->writes = 0;
    if (channel >= 0 && channel < PC_CHANNELS) off(p, image, channel);
}

static void key_on(PcPlayer* p, const PceImage* image, int c, int note) {
    PcChannel* ch = &p->ch[c];
    const uint8_t* rec = instrument(image, ch->instrument);
    if (!rec) return;
    const int kind = rec[PC_I_FLAGS] & PC_KIND_MASK;
    if (kind == PC_KIND_NOISE && c < PC_NOISE_FIRST) return; /* only reachable through a live edit */
    ch->slot = ch->instrument;
    ch->kind = kind;
    for (int k = 0; k < PC_ENVELOPE_COUNT; ++k) ch->env[k] = 0;
    ch->released = 0;
    ch->rel_pitch = 0;
    ch->base = ch->target = note * 64;
    ch->slide = 0;
    ch->arp_step = 0;
    ch->vib_phase = 0;
    ch->sounding = 1;
    ch->fresh = 1;
    ch->dda = kind == PC_KIND_SAMPLE;
    if (ch->dda) ch->pos = 0x10000;
}

static void process_row(PcPlayer* p, const PceImage* image, int c) {
    PcChannel* ch = &p->ch[c];
    const PcRow* row = &ch->row;
    const int has_note = row->note >= PC_NOTE_MIN && row->note <= PC_NOTE_MAX;
    if (has_note) {
        ch->arp = 0;
        ch->fine = 0;
        ch->wave_override = -1;
    }
    if (row->instrument) ch->instrument = row->instrument;
    for (int e = 0; e < row->effects; ++e) {
        const int v = row->param[e];
        switch (row->code[e]) {
        case PC_FX_ARPEGGIO: ch->arp = v; ch->arp_step = 0; break;
        case PC_FX_SLIDE_UP: ch->slide_mode = v ? 1 : 0; ch->slide_speed = v; break;
        case PC_FX_SLIDE_DOWN: ch->slide_mode = v ? 2 : 0; ch->slide_speed = v; break;
        case PC_FX_PORTAMENTO: ch->slide_mode = v ? 3 : 0; ch->slide_speed = v; break;
        case PC_FX_VIBRATO: ch->vib_speed = v >> 4; ch->vib_depth = v & 15; break;
        case PC_FX_PAN: ch->pan = v; break;
        case PC_FX_VOLUME_SLIDE: ch->volslide = v; break;
        case PC_FX_VOLUME: ch->vol8 = v * 8; break;
        case PC_FX_FINE: ch->fine = v - 0x80; break;
        case PC_FX_CUT: ch->cut = v; break;
        case PC_FX_WAVE: ch->wave_override = v; break;
        case PC_FX_LFO_FREQ: p->lfo_freq = v; globals(p); break;
        case PC_FX_LFO_CTRL: p->lfo_ctrl = v; globals(p); break;
        case PC_FX_MASTER: p->master = v; globals(p); break;
        case PC_FX_TIMER: p->timer = v; break;
        }
    }
    if (row->note == PC_NOTE_OFF) {
        off(p, image, c);
    } else if (has_note) {
        if (ch->slide_mode == 3 && ch->sounding && !ch->released && ch->kind != PC_KIND_NOISE) ch->target = row->note * 64;
        else key_on(p, image, c, row->note);
    }
}

/* ---- per tick -------------------------------------------------------------------------------- */

/* Wave load: R4 = $40, R4 = $00 (bit 6 falling resets the write index), 32 x R6. */
static void load_wave(PcPlayer* p, const PceImage* image, int c, int w) {
    PcChannel* ch = &p->ch[c];
    const uint8_t* wave = image->bytes + PC_OFF_WAVES + PC_WAVE_SIZE * (w & (PC_MAX_WAVES - 1));
    select_channel(p, c);
    append(p, R_CONTROL, 0x40);
    append(p, R_CONTROL, 0x00);
    for (int i = 0; i < PC_WAVE_SIZE; ++i) append(p, R_DATA, wave[i]);
    ch->r4 = 0x00;
    ch->loaded = w;
}

static const uint8_t* sample_entry(const PceImage* image, const uint8_t* rec) {
    return image->bytes + PC_OFF_SAMPLES + PC_SAMPLE_ENTRY_SIZE * (rec[PC_I_SAMPLE] & (PC_MAX_SAMPLES - 1));
}

static void update(PcPlayer* p, const PceImage* image, int c) {
    PcChannel* ch = &p->ch[c];
    if (!ch->sounding) return;
    const uint8_t* rec = raw_record(image, ch->slot);
    Envelope env[PC_ENVELOPE_COUNT];
    for (int k = 0; k < PC_ENVELOPE_COUNT; ++k) envelope(rec, k, &env[k]);
    const int volume = env[PC_ENV_VOLUME].length ? env_byte(image, &env[PC_ENV_VOLUME], ch->env[PC_ENV_VOLUME]) : 31;
    /* End only on the final zero itself, not when advancing toward it: keep the preceding tail tick intact. */
    if (ch->released && env[PC_ENV_VOLUME].length && volume == 0 &&
        ch->env[PC_ENV_VOLUME] >= env[PC_ENV_VOLUME].length - 1) {
        stop(p, c);
        return;
    }
    const int arp_env = env[PC_ENV_ARPEGGIO].length ? (int8_t)env_byte(image, &env[PC_ENV_ARPEGGIO], ch->env[PC_ENV_ARPEGGIO]) : 0;
    int pitch_env = 0;
    if (env[PC_ENV_PITCH].length) {
        const int v = (int8_t)env_byte(image, &env[PC_ENV_PITCH], ch->env[PC_ENV_PITCH]);
        if (rec[PC_I_FLAGS] & PC_FLAG_RELATIVE) {
            ch->rel_pitch = clamp(ch->rel_pitch + v, -PCP_SLIDE_LIMIT, PCP_SLIDE_LIMIT);
            pitch_env = ch->rel_pitch;
        } else {
            pitch_env = v;
        }
    }
    const int vol = clamp(volume + (ch->vol8 >> 3) - 31, 0, 31);
    const int arp = ch->arp_step == 1 ? ch->arp >> 4 : ch->arp_step == 2 ? ch->arp & 15 : 0;
    const int semitones = arp + arp_env;
    const int vib = (PCP_SINE[ch->vib_phase] * ch->vib_depth) >> 4;
    const int pitch = clamp(ch->base + 64 * semitones + ch->fine + ch->slide + vib + pitch_env, PCP_PITCH_MIN, PCP_PITCH_MAX);
    switch (ch->kind) {
    case PC_KIND_TONE: {
        const int w = env[PC_ENV_WAVE].length ? env_byte(image, &env[PC_ENV_WAVE], ch->env[PC_ENV_WAVE])
                    : ch->wave_override >= 0 ? ch->wave_override : rec[PC_I_WAVE];
        if (ch->loaded != w) load_wave(p, image, c, w);
        const int n = pcp_period(pitch);
        wr(p, c, R_FREQ_LO, n & 0xFF);
        wr(p, c, R_FREQ_HI, n >> 8);
        if (c >= PC_NOISE_FIRST) wr(p, c, R_NOISE, 0x00);
        wr(p, c, R_BALANCE, ch->pan);
        wr(p, c, R_CONTROL, 0x80 | vol);
        break;
    }
    case PC_KIND_NOISE:
        wr(p, c, R_NOISE, 0x80 | (((ch->base >> 6) + semitones) & 31));
        wr(p, c, R_BALANCE, ch->pan);
        wr(p, c, R_CONTROL, 0x80 | vol);
        break;
    default: /* sample */
        ch->step = pcp_sample_step(pitch, rec[PC_I_ROOT]);
        if (c >= PC_NOISE_FIRST) wr(p, c, R_NOISE, 0x00);
        wr(p, c, R_BALANCE, ch->pan);
        wr(p, c, R_CONTROL, 0xC0 | vol);
        if (ch->fresh) wr_data(p, c, image->bytes[PC_OFF_DATA + pc_u32(sample_entry(image, rec))]);
        break;
    }
    ch->fresh = 0;
    for (int k = 0; k < PC_ENVELOPE_COUNT; ++k)
        if (env[k].length) ch->env[k] = env_next(&env[k], ch->env[k], ch->released);
    if (ch->arp) ch->arp_step = (ch->arp_step + 1) % 3;
    if (ch->slide_mode == 1) ch->slide = clamp(ch->slide + ch->slide_speed, -PCP_SLIDE_LIMIT, PCP_SLIDE_LIMIT);
    if (ch->slide_mode == 2) ch->slide = clamp(ch->slide - ch->slide_speed, -PCP_SLIDE_LIMIT, PCP_SLIDE_LIMIT);
    if (ch->slide_mode == 3) {
        if (ch->base < ch->target) ch->base = ch->base + ch->slide_speed > ch->target ? ch->target : ch->base + ch->slide_speed;
        else if (ch->base > ch->target) ch->base = ch->base - ch->slide_speed < ch->target ? ch->target : ch->base - ch->slide_speed;
    }
    if (ch->vib_depth && ch->vib_speed) ch->vib_phase = (ch->vib_phase + ch->vib_speed) & 63;
    if (ch->volslide) ch->vol8 = clamp(ch->vol8 + (ch->volslide >> 4) - (ch->volslide & 15), 0, 248);
}

void pcp_timer(PcPlayer* p, const PceImage* image) {
    p->writes = 0;
    for (int c = 0; c < PC_CHANNELS; ++c) {
        PcChannel* ch = &p->ch[c];
        if (!ch->dda) continue;
        const uint8_t* rec = raw_record(image, ch->slot);
        if ((rec[PC_I_FLAGS] & (PC_FLAG_DEFINED | PC_KIND_MASK)) != (PC_FLAG_DEFINED | PC_KIND_SAMPLE)) {
            stop(p, c); /* the instrument is no longer a sample (live edit): as a one-shot's end */
            continue;
        }
        const uint8_t* entry = sample_entry(image, rec);
        const uint32_t offset = pc_u32(entry), length = pc_u32(entry + 4);
        uint64_t f = ch->pos >> 16;
        if (f >= length) {
            const uint32_t start = (uint32_t)pc_u16(rec + PC_I_LOOP_START);
            if (!rec[PC_I_LOOP] || start >= length) {
                stop(p, c);
                continue;
            }
            const uint64_t span = (uint64_t)(length - start) << 16;
            ch->pos = ((uint64_t)start << 16) + (ch->pos - ((uint64_t)length << 16)) % span;
            f = ch->pos >> 16;
        }
        wr_data(p, c, image->bytes[PC_OFF_DATA + offset + (uint32_t)f]);
        ch->pos += ch->step;
    }
}

static void read_rows(PcPlayer* p, const PceImage* image) {
    if (p->cursor_order != p->order || p->cursor_row != p->row) {
        for (int c = 0; c < PC_CHANNELS; ++c) {
            PcCursor cur = {pc_u16(image->bytes + PC_OFF_ORDERS + 2 * (PC_CHANNELS * p->order + c)), 0};
            PcRow skip;
            for (int r = 0; r < p->row; ++r) pc_next_row(image->bytes, &cur, &skip);
            p->cursor[c] = cur;
        }
    }
    for (int c = 0; c < PC_CHANNELS; ++c) pc_next_row(image->bytes, &p->cursor[c], &p->ch[c].row);
    p->cursor_order = p->order;
    p->cursor_row = p->row + 1;
}

/* B/D of the current row (rows already read) -> p->jump. */
static void derive_jump(PcPlayer* p, const PceImage* image) {
    int has_b = 0, has_d = 0, b = 0, d = 0;
    for (int c = 0; c < PC_CHANNELS; ++c) {
        const PcRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e) {
            if (row->code[e] == PC_FX_JUMP) has_b = 1, b = row->param[e];
            if (row->code[e] == PC_FX_BREAK) has_d = 1, d = row->param[e];
        }
    }
    p->jump = has_b || has_d;
    p->jump_order = has_b ? b : p->order + 1 < image->info.orders ? p->order + 1 : image->info.loop;
    p->jump_row = has_d ? d : 0;
}

static int row_delay(const PcRow* row) {
    int g = 0;
    for (int e = 0; e < row->effects; ++e)
        if (row->code[e] == PC_FX_DELAY) g = row->param[e];
    return g;
}

static void row_start(PcPlayer* p, const PceImage* image) {
    read_rows(p, image);
    for (int c = 0; c < PC_CHANNELS; ++c) {
        const PcRow* row = &p->ch[c].row;
        for (int e = 0; e < row->effects; ++e)
            if (row->code[e] == PC_FX_SPEED) p->speed = row->param[e];
    }
    derive_jump(p, image);
    for (int c = 0; c < PC_CHANNELS; ++c) {
        PcChannel* ch = &p->ch[c];
        ch->cut = -1;
        ch->pending = 0;
        const int g = row_delay(&ch->row);
        if (g) {
            ch->pending = 1;
            ch->delay = g;
        } else {
            process_row(p, image, c);
        }
    }
}

/* Next row. Every arrival at the loop order's row 0 starts it at the speed sequential straight play reaches it with
 * (a row-0 Fxx still applies on top, at the row start). */
static void advance(PcPlayer* p, const PceImage* image) {
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

void pcp_tick(PcPlayer* p, const PceImage* image) {
    p->writes = 0;
    globals(p);
    if (!p->audition) {
        if (p->tick == 0) {
            row_start(p, image);
        } else {
            for (int c = 0; c < PC_CHANNELS; ++c) {
                if (p->ch[c].pending && p->ch[c].delay == p->tick) {
                    p->ch[c].pending = 0;
                    process_row(p, image, c);
                }
            }
        }
        for (int c = 0; c < PC_CHANNELS; ++c)
            if (p->ch[c].cut == p->tick) off(p, image, c);
    }
    for (int c = 0; c < PC_CHANNELS; ++c) update(p, image, c);
    if (!p->audition && ++p->tick >= p->speed) {
        p->tick = 0;
        advance(p, image);
    }
}

static void reset(PcPlayer* p, const PceImage* image, int audition) {
    const uint8_t* b = image->bytes;
    memset(p, 0, sizeof *p);
    for (int c = 0; c < PC_CHANNELS; ++c) {
        PcChannel* ch = &p->ch[c];
        ch->cut = -1;
        ch->vol8 = 248;
        ch->pan = 0xFF;
        ch->loaded = -1;
        ch->wave_override = -1;
    }
    p->select = -1;
    p->cursor_order = p->cursor_row = -1;
    p->speed = image->info.speed;
    p->audition = audition;
    p->master = b[PC_H_MASTER];
    p->lfo_freq = b[PC_H_LFO_FREQ];
    p->lfo_ctrl = b[PC_H_LFO_CTRL];
    p->timer = b[PC_H_TIMER];
    /* The reset batch: every write emitted, setting the shadows. */
    append(p, R_MASTER, p->master);
    append(p, R_LFO_FREQ, p->lfo_freq);
    append(p, R_LFO_CTRL, p->lfo_ctrl);
    p->shadow_r1 = (uint8_t)p->master;
    p->shadow_r8 = (uint8_t)p->lfo_freq;
    p->shadow_r9 = (uint8_t)p->lfo_ctrl;
    for (int c = 0; c < PC_CHANNELS; ++c) {
        PcChannel* ch = &p->ch[c];
        append(p, R_SELECT, c);
        append(p, R_FREQ_LO, 0);
        append(p, R_FREQ_HI, 0);
        append(p, R_CONTROL, 0);
        append(p, R_BALANCE, 0xFF);
        ch->r2 = ch->r3 = ch->r4 = 0;
        ch->r5 = 0xFF;
        if (c >= PC_NOISE_FIRST) append(p, R_NOISE, 0);
        ch->r7 = 0;
    }
    p->select = PC_CHANNELS - 1;
}

void pcp_reset(PcPlayer* p, const PceImage* image) { reset(p, image, 0); }

void pcp_place(PcPlayer* p, const PceImage* image, int order) {
    reset(p, image, 0);
    p->order = order;
    p->speed = image->info.start_speed[order];
}

/* Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before
 * it (and of the row itself once it has started). */
static int speed_at(const PceImage* image, int order, int row, int started) {
    int s = image->info.start_speed[order];
    PcCursor cur[PC_CHANNELS];
    for (int c = 0; c < PC_CHANNELS; ++c) {
        cur[c].offset = pc_u16(image->bytes + PC_OFF_ORDERS + 2 * (PC_CHANNELS * order + c));
        cur[c].run = 0;
    }
    const int last = started ? row : row - 1;
    for (int r = 0; r <= last; ++r) {
        for (int c = 0; c < PC_CHANNELS; ++c) {
            PcRow x;
            pc_next_row(image->bytes, &cur[c], &x);
            for (int e = 0; e < x.effects; ++e)
                if (x.code[e] == PC_FX_SPEED) s = x.param[e];
        }
    }
    return s;
}

/* Shared fields whose song-start (header) value the edit changed take the new value; the next tick's globals write
 * them (the timer reload applies from the next reload). Values set by effects persist when the start values are not
 * edited. Loaded waves whose 32 bytes changed reload at the channel's next update. */
static void relocate_shared(PcPlayer* p, const PceImage* old, const PceImage* next) {
    const uint8_t *a = old->bytes, *b = next->bytes;
    if (a[PC_H_MASTER] != b[PC_H_MASTER]) p->master = b[PC_H_MASTER];
    if (a[PC_H_LFO_FREQ] != b[PC_H_LFO_FREQ]) p->lfo_freq = b[PC_H_LFO_FREQ];
    if (a[PC_H_LFO_CTRL] != b[PC_H_LFO_CTRL]) p->lfo_ctrl = b[PC_H_LFO_CTRL];
    if (a[PC_H_TIMER] != b[PC_H_TIMER]) p->timer = b[PC_H_TIMER];
    for (int c = 0; c < PC_CHANNELS; ++c) {
        PcChannel* ch = &p->ch[c];
        if (ch->loaded < 0) continue;
        const int at = PC_OFF_WAVES + PC_WAVE_SIZE * ch->loaded;
        if (memcmp(a + at, b + at, PC_WAVE_SIZE)) ch->loaded = -1;
    }
}

void pcp_relocate(PcPlayer* p, const PceImage* old, const PceImage* next) {
    if (p->audition) return;
    const PcInfo* info = &next->info;
    relocate_shared(p, old, next);
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
        for (int c = 0; c < PC_CHANNELS; ++c) p->ch[c].pending = 0;
        p->speed = speed_at(next, p->order, p->row, 1);
        if (!started) advance(p, next); /* the clamped row counts as played: the next row follows it */
        return;
    }
    const int new_speed = speed_at(next, p->order, p->row, started);
    if (new_speed != old_speed) p->speed = new_speed;
    if (!started) return;
    /* The row has started: its jump and any still-delayed rows come from the new data. */
    for (int c = 0; c < PC_CHANNELS; ++c) {
        PcRow row;
        pc_read_row(next, p->order, c, p->row, &row, NULL);
        PcChannel* ch = &p->ch[c];
        ch->row = row;
        if (ch->pending) {
            const int g = row_delay(&row);
            ch->pending = g >= p->tick;
            ch->delay = g;
        }
    }
    derive_jump(p, next);
}

int pcp_audition(PcPlayer* p, const PceImage* image, int channel, int note, int slot, int fine) {
    if (channel < 0 || channel >= PC_CHANNELS || note < PC_NOTE_MIN || note > PC_NOTE_MAX || fine < -128 || fine > 127)
        return -1;
    const uint8_t* rec = instrument(image, slot);
    if (!rec || (channel < PC_NOISE_FIRST && (rec[PC_I_FLAGS] & PC_KIND_MASK) == PC_KIND_NOISE)) return -1;
    reset(p, image, 1);
    p->ch[channel].instrument = slot;
    key_on(p, image, channel, note);
    p->ch[channel].fine = fine;
    return 0;
}
