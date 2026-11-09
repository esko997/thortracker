package org.thortracker.backend

import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesRules

/**
 * One build of the NES preview player (FamiStudio sound engine 4.5.3, FamiTracker tempo): the plain 2A03 build
 * ([NesPlayerImage]) or one assembled with an expansion chip ([NesVrc6PlayerImage], [NesVrc7PlayerImage], [NesFdsPlayerImage],
 * the eight N163 builds [NesN163PlayerImage1]..[NesN163PlayerImage8], [NesS5bPlayerImage], [NesMmc5PlayerImage],
 * [NesEpsmPlayerImage]). Each has its own native engine ([engineId]), player bytes and song region (the region starts on
 * the page after the player, so a larger player leaves less room); the image layout numbers all share stay in
 * [NesPlayerImage].
 */
class NesPlayer private constructor(
    /** The native `ChipEngineOps` id that plays this build's images. */
    val engineId: String,
    val load: Int,
    val init: Int,
    val play: Int,
    /** Song data from here upward; the region ends at [regionEnd] (exclusive; the vectors follow). */
    val songBase: Int,
    val regionEnd: Int,
    val regionSize: Int,
    /** Image header bytes 4..7: an image only plays on the build it was lowered for. */
    val playerId: Int,
    val imageSize: Int,
    /** Channel streams in the song header: the 2A03's five plus the expansion's. */
    val channels: Int,
    /**
     * NSF header byte `$7B`: the expansion chips the player drives (bit 0 = VRC6, bit 1 = VRC7, bit 2 = FDS, bit 3 = MMC5,
     * bit 4 = Namco 163, bit 5 = Sunsoft 5B, bit 7 = EPSM; FamiStudio's `NsfFile`).
     */
    val nsfChips: Int,
    /**
     * The song header holds FamiStudio's expansion instrument pointer (`instruments_exp`, after `instruments`) and the
     * expansion's channels load their instruments from that table (FDS, N163, VRC7, EPSM and S5B builds).
     */
    val expInstruments: Boolean,
    /**
     * The first envelope directory index of effect envelopes (`0xy` arpeggios, vibratos); the instruments' own envelopes
     * sit below it, one stable index each (slot × 4 + kind; the N163 and FDS builds add each slot's wave-index envelope
     * at 256 + slot, so their effect envelopes start at 320; the 5B and EPSM builds add their SSG mixer and noise envelopes
     * at 256 + slot × 2 + 0/1, so their effect envelopes start at 384).
     */
    val effectEnvelopes: Int,
    private val code: () -> ByteArray,
) {
    /** The player bytes at [load]; a fresh copy per call. */
    val bytes: ByteArray get() = code()

    companion object {
        val APU = NesPlayer(
            "nes-famistudio", NesPlayerImage.LOAD, NesPlayerImage.INIT, NesPlayerImage.PLAY, NesPlayerImage.SONG_BASE,
            NesPlayerImage.REGION_END, NesPlayerImage.REGION_SIZE, NesPlayerImage.PLAYER_ID, NesPlayerImage.IMAGE_SIZE,
            channels = 5, nsfChips = 0, expInstruments = false, effectEnvelopes = EFFECT_ENVELOPES,
        ) { NesPlayerImage.BYTES }

        val VRC6 = NesPlayer(
            "nes-famistudio-vrc6", NesVrc6PlayerImage.LOAD, NesVrc6PlayerImage.INIT, NesVrc6PlayerImage.PLAY,
            NesVrc6PlayerImage.SONG_BASE, NesVrc6PlayerImage.REGION_END, NesVrc6PlayerImage.REGION_SIZE,
            NesVrc6PlayerImage.PLAYER_ID, NesVrc6PlayerImage.IMAGE_SIZE, NesVrc6PlayerImage.CHANNELS, nsfChips = 0x01,
            expInstruments = false, effectEnvelopes = EFFECT_ENVELOPES,
        ) { NesVrc6PlayerImage.BYTES }

        val VRC7 = NesPlayer(
            "nes-famistudio-vrc7", NesVrc7PlayerImage.LOAD, NesVrc7PlayerImage.INIT, NesVrc7PlayerImage.PLAY,
            NesVrc7PlayerImage.SONG_BASE, NesVrc7PlayerImage.REGION_END, NesVrc7PlayerImage.REGION_SIZE,
            NesVrc7PlayerImage.PLAYER_ID, NesVrc7PlayerImage.IMAGE_SIZE, NesVrc7PlayerImage.CHANNELS, nsfChips = 0x02,
            expInstruments = true, effectEnvelopes = EFFECT_ENVELOPES,
        ) { NesVrc7PlayerImage.BYTES }

        /**
         * The FDS build (`FAMISTUDIO_EXP_FDS` with `FAMISTUDIO_USE_FDS_AUTOMOD`, as FamiStudio's own NSF kernel `nsf.s`):
         * the 2A03's five channels and the FDS as channel 5.
         */
        val FDS = NesPlayer(
            "nes-famistudio-fds", NesFdsPlayerImage.LOAD, NesFdsPlayerImage.INIT, NesFdsPlayerImage.PLAY,
            NesFdsPlayerImage.SONG_BASE, NesFdsPlayerImage.REGION_END, NesFdsPlayerImage.REGION_SIZE,
            NesFdsPlayerImage.PLAYER_ID, NesFdsPlayerImage.IMAGE_SIZE, NesFdsPlayerImage.CHANNELS, nsfChips = 0x04,
            expInstruments = true, effectEnvelopes = FamiStudioLowering.WAVE_EFFECT_ENVELOPES_START,
        ) { NesFdsPlayerImage.BYTES }

        /** The N163 builds, index n − 1 for n enabled channels (`FAMISTUDIO_EXP_N163_CHN_CNT` = n). */
        val N163: List<NesPlayer> = listOf(
            n163(NesN163PlayerImage1.LOAD, NesN163PlayerImage1.INIT, NesN163PlayerImage1.PLAY, NesN163PlayerImage1.SONG_BASE,
                NesN163PlayerImage1.REGION_END, NesN163PlayerImage1.REGION_SIZE, NesN163PlayerImage1.PLAYER_ID,
                NesN163PlayerImage1.IMAGE_SIZE, NesN163PlayerImage1.CHANNELS, NesN163PlayerImage1.N163_CHANNELS) { NesN163PlayerImage1.BYTES },
            n163(NesN163PlayerImage2.LOAD, NesN163PlayerImage2.INIT, NesN163PlayerImage2.PLAY, NesN163PlayerImage2.SONG_BASE,
                NesN163PlayerImage2.REGION_END, NesN163PlayerImage2.REGION_SIZE, NesN163PlayerImage2.PLAYER_ID,
                NesN163PlayerImage2.IMAGE_SIZE, NesN163PlayerImage2.CHANNELS, NesN163PlayerImage2.N163_CHANNELS) { NesN163PlayerImage2.BYTES },
            n163(NesN163PlayerImage3.LOAD, NesN163PlayerImage3.INIT, NesN163PlayerImage3.PLAY, NesN163PlayerImage3.SONG_BASE,
                NesN163PlayerImage3.REGION_END, NesN163PlayerImage3.REGION_SIZE, NesN163PlayerImage3.PLAYER_ID,
                NesN163PlayerImage3.IMAGE_SIZE, NesN163PlayerImage3.CHANNELS, NesN163PlayerImage3.N163_CHANNELS) { NesN163PlayerImage3.BYTES },
            n163(NesN163PlayerImage4.LOAD, NesN163PlayerImage4.INIT, NesN163PlayerImage4.PLAY, NesN163PlayerImage4.SONG_BASE,
                NesN163PlayerImage4.REGION_END, NesN163PlayerImage4.REGION_SIZE, NesN163PlayerImage4.PLAYER_ID,
                NesN163PlayerImage4.IMAGE_SIZE, NesN163PlayerImage4.CHANNELS, NesN163PlayerImage4.N163_CHANNELS) { NesN163PlayerImage4.BYTES },
            n163(NesN163PlayerImage5.LOAD, NesN163PlayerImage5.INIT, NesN163PlayerImage5.PLAY, NesN163PlayerImage5.SONG_BASE,
                NesN163PlayerImage5.REGION_END, NesN163PlayerImage5.REGION_SIZE, NesN163PlayerImage5.PLAYER_ID,
                NesN163PlayerImage5.IMAGE_SIZE, NesN163PlayerImage5.CHANNELS, NesN163PlayerImage5.N163_CHANNELS) { NesN163PlayerImage5.BYTES },
            n163(NesN163PlayerImage6.LOAD, NesN163PlayerImage6.INIT, NesN163PlayerImage6.PLAY, NesN163PlayerImage6.SONG_BASE,
                NesN163PlayerImage6.REGION_END, NesN163PlayerImage6.REGION_SIZE, NesN163PlayerImage6.PLAYER_ID,
                NesN163PlayerImage6.IMAGE_SIZE, NesN163PlayerImage6.CHANNELS, NesN163PlayerImage6.N163_CHANNELS) { NesN163PlayerImage6.BYTES },
            n163(NesN163PlayerImage7.LOAD, NesN163PlayerImage7.INIT, NesN163PlayerImage7.PLAY, NesN163PlayerImage7.SONG_BASE,
                NesN163PlayerImage7.REGION_END, NesN163PlayerImage7.REGION_SIZE, NesN163PlayerImage7.PLAYER_ID,
                NesN163PlayerImage7.IMAGE_SIZE, NesN163PlayerImage7.CHANNELS, NesN163PlayerImage7.N163_CHANNELS) { NesN163PlayerImage7.BYTES },
            n163(NesN163PlayerImage8.LOAD, NesN163PlayerImage8.INIT, NesN163PlayerImage8.PLAY, NesN163PlayerImage8.SONG_BASE,
                NesN163PlayerImage8.REGION_END, NesN163PlayerImage8.REGION_SIZE, NesN163PlayerImage8.PLAYER_ID,
                NesN163PlayerImage8.IMAGE_SIZE, NesN163PlayerImage8.CHANNELS, NesN163PlayerImage8.N163_CHANNELS) { NesN163PlayerImage8.BYTES },
        )

        private fun n163(load: Int, init: Int, play: Int, songBase: Int, regionEnd: Int, regionSize: Int, playerId: Int,
                         imageSize: Int, channels: Int, n163Channels: Int, code: () -> ByteArray): NesPlayer {
            check(channels == NesRules.APU_CHANNELS + n163Channels) { "N163 build with $n163Channels channels has $channels streams" }
            return NesPlayer("nes-famistudio-n163-$n163Channels", load, init, play, songBase, regionEnd, regionSize, playerId, imageSize,
                channels, nsfChips = 0x10, expInstruments = true, effectEnvelopes = FamiStudioLowering.WAVE_EFFECT_ENVELOPES_START,
                code = code)
        }

        val S5B = NesPlayer(
            "nes-famistudio-s5b", NesS5bPlayerImage.LOAD, NesS5bPlayerImage.INIT, NesS5bPlayerImage.PLAY,
            NesS5bPlayerImage.SONG_BASE, NesS5bPlayerImage.REGION_END, NesS5bPlayerImage.REGION_SIZE,
            NesS5bPlayerImage.PLAYER_ID, NesS5bPlayerImage.IMAGE_SIZE, NesS5bPlayerImage.CHANNELS, nsfChips = 0x20,
            expInstruments = true, effectEnvelopes = NesS5bPlayerImage.EFFECT_ENVELOPES,
        ) { NesS5bPlayerImage.BYTES }

        /**
         * The MMC5 build (`FAMISTUDIO_EXP_MMC5`): the 2A03's five channels, then MMC5 Pulse 1 and 2 as channels 5 and 6,
         * which load 2A03 instruments from the 2A03 instrument table (no expansion instrument table).
         */
        val MMC5 = NesPlayer(
            "nes-famistudio-mmc5", NesMmc5PlayerImage.LOAD, NesMmc5PlayerImage.INIT, NesMmc5PlayerImage.PLAY,
            NesMmc5PlayerImage.SONG_BASE, NesMmc5PlayerImage.REGION_END, NesMmc5PlayerImage.REGION_SIZE,
            NesMmc5PlayerImage.PLAYER_ID, NesMmc5PlayerImage.IMAGE_SIZE, NesMmc5PlayerImage.CHANNELS, nsfChips = 0x08,
            expInstruments = false, effectEnvelopes = EFFECT_ENVELOPES,
        ) { NesMmc5PlayerImage.BYTES }

        /**
         * The EPSM build (`FAMISTUDIO_EXP_EPSM` with all 3 SSG, 6 FM and 6 rhythm channels, and Thor Tracker's EPSM engine
         * patch): the 2A03's five channels, then SSG 1-3, FM 1-6 and the six rhythm drums (20 streams).
         */
        val EPSM = NesPlayer(
            "nes-famistudio-epsm", NesEpsmPlayerImage.LOAD, NesEpsmPlayerImage.INIT, NesEpsmPlayerImage.PLAY,
            NesEpsmPlayerImage.SONG_BASE, NesEpsmPlayerImage.REGION_END, NesEpsmPlayerImage.REGION_SIZE,
            NesEpsmPlayerImage.PLAYER_ID, NesEpsmPlayerImage.IMAGE_SIZE, NesEpsmPlayerImage.CHANNELS, nsfChips = 0x80,
            expInstruments = true, effectEnvelopes = NesEpsmPlayerImage.EFFECT_ENVELOPES,
        ) { NesEpsmPlayerImage.BYTES }

        /** Effect envelopes of the builds without extra instrument envelopes: after the 64 slots × 4 kinds. */
        private const val EFFECT_ENVELOPES = 256
    }
}

/** The player build that plays songs with this expansion. */
val NesExpansion.player: NesPlayer
    get() = when (this) {
        NesExpansion.NONE -> NesPlayer.APU
        NesExpansion.VRC6 -> NesPlayer.VRC6
        NesExpansion.VRC7 -> NesPlayer.VRC7
        NesExpansion.FDS -> NesPlayer.FDS
        NesExpansion.S5B -> NesPlayer.S5B
        NesExpansion.MMC5 -> NesPlayer.MMC5
        NesExpansion.EPSM -> NesPlayer.EPSM
        else -> NesPlayer.N163[n163Channels - 1]
    }
