package org.thortracker.backend

import org.thortracker.system.nes.NesExpansion

/**
 * One build of the NES preview player (FamiStudio sound engine 4.5.3, FamiTracker tempo): the plain 2A03 build
 * ([NesPlayerImage]) or one assembled with an expansion chip ([NesVrc6PlayerImage], [NesEpsmPlayerImage]). Each has its
 * own native engine ([engineId]), player bytes and song region (the region starts on the page after the player, so a
 * larger player leaves less room); the image layout numbers all share stay in [NesPlayerImage].
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
    /** NSF header byte `$7B`: the expansion chips the player drives (bit 0 = VRC6, bit 7 = EPSM). */
    val nsfChips: Int,
    /**
     * The song header holds FamiStudio's expansion instrument pointer (`instruments_exp`, after `instruments`) and the
     * expansion's channels load their instruments from that table (FDS, N163, VRC7, EPSM and S5B builds: the EPSM here).
     */
    val expInstruments: Boolean,
    /**
     * The first envelope directory index of effect envelopes (`0xy` arpeggios, vibratos); the instruments' own envelopes
     * sit below it, one stable index each (slot × 4 + kind; the EPSM build adds its SSG mixer and noise envelopes at
     * 256 + slot × 2 + 0/1, so its effect envelopes start at 384).
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
        NesExpansion.EPSM -> NesPlayer.EPSM
    }
