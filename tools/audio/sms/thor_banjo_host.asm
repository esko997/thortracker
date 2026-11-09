; Thor Tracker's host for the Banjo sound driver (third_party/banjo: joffb/banjo 7aaf837, MIT).
;
; tools/audio/prepare_sms.py assembles this file with WLA-DX v10.7 (wla-z80 -D BANJO_SYS=1, wlalink -S -r) next to a
; copy of third_party/banjo/music_driver and writes the 16 KiB bank and the labels the "sms-banjo" engine needs into
; app/src/main/cpp/generated/banjo_driver_image.h. The driver files are included unmodified, in the order of upstream's
; examples/wladx/main.asm (banjo core, SN76489, OPLL, OPLL rhythm), for the Master System (BANJO_SYS 1: SN76489 on
; port $7F, YM2413 on $F0/$F1, audio control $F2, Game Gear stereo $06).
;
; What is Thor Tracker's own here is what a game supplies around the driver: the channel array (15 channels, enough
; for the largest layout: SN76489 + OPLL rhythm mode) and the routines json2sms writes into a song file for songs on
; more than one chip (one init, update and song-mute routine calling each chip's, and the per-channel mute tables).
; The engine points a song's music_state call fields at these when it loads an image; exported songs carry the same
; routines themselves (docs/AUDIO_ENGINE.md, "Master System: image layout").

.include "music_driver/banjo_defines_wladx.inc"
; Publish WLA's actual structure sizes so the generator can check its image ABI against the assembled driver.
.EXPORT _sizeof_channel, _sizeof_music_state, _sizeof_instrument

.define THOR_CHANNELS CHAN_COUNT_SN + CHAN_COUNT_OPLL_DRUMS

.MEMORYMAP
	SLOTSIZE $4000
	DEFAULTSLOT 0
	SLOT 0 $0000			; the driver (the engine's ROM)
	SLOT 1 $4000			; song data (the image region, $4000-$BFFF)
	SLOT 2 $8000
	SLOT 3 $C000			; RAM
.ENDME

.ROMBANKMAP
	BANKSTOTAL 1
	BANKSIZE $4000
	BANKS 1
.ENDRO

.EMPTYFILL $FF

.RAMSECTION "THOR_HOST_RAM" bank 0 slot 3

	song_channels: INSTANCEOF channel (THOR_CHANNELS)

.ENDS

.BANK 0 SLOT 0

.SECTION "THOR_GLUE" FREE

	; SN76489 + OPLL (13 channels)
	thor_init_sn_opll:
		call banjo_init_sn
		call banjo_init_opll
		ret

	thor_update_sn_opll:
		call banjo_update_channels_sn
		call banjo_update_channels_opll
		ret

	thor_mute_all_sn_opll:
		call banjo_mute_all_sn
		call banjo_mute_all_opll
		ret

	; SN76489 + OPLL in rhythm mode (15 channels)
	thor_init_sn_drums:
		call banjo_init_sn
		call banjo_init_opll_drums
		ret

	thor_update_sn_drums:
		call banjo_update_channels_sn
		call banjo_update_channels_opll_drums
		ret

	thor_mute_all_sn_drums:
		call banjo_mute_all_sn
		call banjo_mute_all_opll_drums
		ret

	; channel_mute_calls tables (one routine per song channel)
	thor_mute_calls_sn:
		.dw banjo_mute_channel_sn, banjo_mute_channel_sn, banjo_mute_channel_sn, banjo_mute_channel_sn

	thor_mute_calls_sn_opll:
		.dw banjo_mute_channel_sn, banjo_mute_channel_sn, banjo_mute_channel_sn, banjo_mute_channel_sn
		.dw banjo_mute_channel_opll, banjo_mute_channel_opll, banjo_mute_channel_opll
		.dw banjo_mute_channel_opll, banjo_mute_channel_opll, banjo_mute_channel_opll
		.dw banjo_mute_channel_opll, banjo_mute_channel_opll, banjo_mute_channel_opll

	thor_mute_calls_sn_drums:
		.dw banjo_mute_channel_sn, banjo_mute_channel_sn, banjo_mute_channel_sn, banjo_mute_channel_sn
		.dw banjo_mute_channel_opll, banjo_mute_channel_opll, banjo_mute_channel_opll
		.dw banjo_mute_channel_opll, banjo_mute_channel_opll, banjo_mute_channel_opll
		.dw banjo_mute_channel_opll_drums, banjo_mute_channel_opll_drums, banjo_mute_channel_opll_drums
		.dw banjo_mute_channel_opll_drums, banjo_mute_channel_opll_drums

.ENDS

.incdir "music_driver/banjo"
.include "banjo.asm"

.incdir "music_driver/opll"
.include "banjo_opll.asm"

.incdir "music_driver/opll_drums"
.include "banjo_opll_drums.asm"

.incdir "music_driver/sn"
.include "banjo_sn.asm"
