; Thor Tracker NES preview player: the FamiStudio sound engine 4.5 (third_party/famistudio-4.5.3,
; unmodified) behind a fixed NSF-style entry table. Assembled once by tools/audio/prepare_nes_player.py
; into app/src/main/cpp/generated/nes_player_image.h; nothing is assembled at app build or run time.
;
; CPU memory map (NTSC 2A03, no mapper):
;   $0000-$00FF  engine zero page            $0200-$07FF  engine RAM (BSS)
;   $0100-$01FF  stack                       $4000-$4017  APU
;   $8000-...    this player (code + tables) NES_SONG_BASE-$FFF9  song data (FamiStudio export format)
;   $FFFA-$FFFF  vectors, written by the native machine (not part of the song image)
;
; Entry points (fixed addresses, NSF conventions so the same bytes form an .nsf):
;   $8000 nes_player_init  A = song index. Calls famistudio_init(NTSC, NES_SONG_BASE) and
;                          famistudio_music_play(A).
;   $8003 nes_player_play  one 60 Hz engine frame: famistudio_update.
;   $8006 nes_player_idle  host-called routines return here (the native machine stops at its fetch).
;   $8009 nes_player_trap  BRK/IRQ/NMI target; reaching it is a driver fault.

; NES_SONG_BASE is defined on the ca65 command line by the prepare script.

FAMISTUDIO_CFG_EXTERNAL        = 1
FAMISTUDIO_CFG_NTSC_SUPPORT    = 1
FAMISTUDIO_CFG_SMOOTH_VIBRATO  = 1
FAMISTUDIO_CFG_DPCM_SUPPORT    = 1
FAMISTUDIO_DPCM_OFF            = $c000
; Every 2A03 feature the FamiStudio exporter can ask for, as in FamiStudio's own FamiTracker-tempo NSF
; kernel (FamiStudio/Nsf/nsf.s with FAMISTUDIO_USE_FAMITRACKER_TEMPO), except the DPCM bank-switching /
; extended-range encodings. Extra features only add opcodes. FamiTracker tempo: the engine owns rows
; (speed = frames per row at tempo 150, the $46 opcode = Fxx), note delays ($47) and cuts ($48).
FAMISTUDIO_USE_FAMITRACKER_TEMPO                 = 1
FAMISTUDIO_USE_FAMITRACKER_DELAYED_NOTES_OR_CUTS = 1
FAMISTUDIO_USE_VOLUME_TRACK              = 1
FAMISTUDIO_USE_VOLUME_SLIDES             = 1
FAMISTUDIO_USE_PITCH_TRACK               = 1
FAMISTUDIO_USE_SLIDE_NOTES               = 1
FAMISTUDIO_USE_NOISE_SLIDE_NOTES         = 1
FAMISTUDIO_USE_VIBRATO                   = 1
FAMISTUDIO_USE_ARPEGGIO                  = 1
FAMISTUDIO_USE_DUTYCYCLE_EFFECT          = 1
FAMISTUDIO_USE_DELTA_COUNTER             = 1
FAMISTUDIO_USE_RELEASE_NOTES             = 1
FAMISTUDIO_USE_PHASE_RESET               = 1
FAMISTUDIO_USE_INSTRUMENT_EXTENDED_RANGE = 1

.define FAMISTUDIO_CA65_ZP_SEGMENT   ZEROPAGE
.define FAMISTUDIO_CA65_RAM_SEGMENT  BSS
.define FAMISTUDIO_CA65_CODE_SEGMENT CODE

.segment "CODE"

nes_player_init:
    jmp init_song
nes_player_play:
    jmp famistudio_update
nes_player_idle:
    jmp nes_player_idle
nes_player_trap:
    jmp nes_player_trap

init_song:
    pha
    ldx #<NES_SONG_BASE
    ldy #>NES_SONG_BASE
    lda #1 ; NTSC
    jsr famistudio_init
    pla
    jmp famistudio_music_play

.include "famistudio_ca65.s"

.export nes_player_init, nes_player_play, nes_player_idle, nes_player_trap
