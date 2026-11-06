package org.thortracker.backend

/** Capacity limits of the GB/hUGE backend shared by validation, preview and export. */
object HugeLimits {
    /** hUGEDriver's `order_cnt` is one byte holding orders * 2. */
    const val MAX_ORDERS = 127

    /**
     * Distinct lowered channel tracks (byte-identical tracks count once) referenced by the order list,
     * including loop-variant tracks. Bounded by the preview player's flat (unbanked) song-data area.
     */
    const val PREVIEW_TRACK_SLOTS = 272

    /** One GBDK ROM bank; uge2source -b output must fit in it for a banked game build (warning only). */
    const val ROM_BANK_BYTES = 16384
}
