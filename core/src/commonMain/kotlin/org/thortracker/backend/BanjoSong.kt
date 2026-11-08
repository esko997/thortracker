package org.thortracker.backend

import org.thortracker.backend.SmsImageLayout.BJ_HEADER_SIZE
import org.thortracker.backend.SmsImageLayout.INSTRUMENT_SIZE
import org.thortracker.backend.SmsImageLayout.REGION_BASE
import org.thortracker.system.sms.SmsLayout

/**
 * One Banjo instrument record (`.STRUCT instrument`, 12 bytes): macro [flags], the volume, arpeggio and extra macros
 * (indices into [BanjoSong.macros], -1 none; [exType] the extra macro's type), the FM [fmPreset] byte and the FM patch
 * ([patch]: index into [BanjoSong.patches], -1 none).
 */
class BanjoInstrument(
    val flags: Int,
    val volume: Int,
    val arpeggio: Int,
    val exType: Int,
    val ex: Int,
    val fmPreset: Int,
    val patch: Int,
)

/**
 * A song in the Banjo driver's data format (joffb/banjo 7aaf837: `music_state` header, order pointer table, one order
 * row of track pointers per order, instrument records, macros, FM patches and the tracks the channels read), built by
 * [SmsLowering]. The same object writes the engine's image region ([region]) and the sources a game assembles with the
 * driver ([asm]): WLA-DX as upstream's examples link songs, SDAS for devkitSMS and GBDK. The bytes are what json2sms
 * 7aaf837 writes for the same musical data, except where its writer and the driver disagree (docs/AUDIO_ENGINE.md,
 * "Master System: song data"): macro loop points as the driver reads them, the first row's tick count and explicit order
 * jumps for shorter orders and the loop.
 */
class BanjoSong(
    val layout: SmsLayout,
    val speed1: Int,
    val speed2: Int,
    val patternLength: Int,
    val instruments: List<BanjoInstrument>,
    val macros: List<IntArray>,
    val patches: List<IntArray>,
    /** Per order: the track index each channel reads. */
    val orders: List<IntArray>,
    val tracks: List<IntArray>,
) {
    /** The first row's tick counter: Banjo counts the start tick itself, so the song's first row needs one more for its speed. */
    val tic: Int get() = (speed1 + 1) and 0xFF

    private class Addresses(val orderTable: Int, val orderRows: Int, val instruments: Int, val macros: IntArray, val patches: IntArray,
                            val tracks: IntArray, val end: Int)

    private fun addresses(base: Int): Addresses {
        val channels = layout.channelCount
        var a = base + BJ_HEADER_SIZE
        val orderTable = a
        a += 2 * orders.size
        val orderRows = a
        a += 2 * channels * orders.size
        val ins = a
        a += INSTRUMENT_SIZE * instruments.size
        val m = IntArray(macros.size)
        for (i in macros.indices) { m[i] = a; a += macros[i].size }
        val p = IntArray(patches.size)
        for (i in patches.indices) { p[i] = a; a += patches[i].size }
        val t = IntArray(tracks.size)
        for (i in tracks.indices) { t[i] = a; a += tracks[i].size }
        return Addresses(orderTable, orderRows, ins, m, p, t, a)
    }

    /** Bytes of song data in the image (the call fields and mute table are the machine's, so not counted). */
    val dataSize: Int get() = addresses(REGION_BASE).end - REGION_BASE

    /** The song data as the image region holds it at [REGION_BASE], call fields 0. */
    fun region(): ByteArray {
        val at = addresses(REGION_BASE)
        val out = ByteArray(at.end - REGION_BASE)
        fun put8(addr: Int, v: Int) { out[addr - REGION_BASE] = v.toByte() }
        fun put16(addr: Int, v: Int) { put8(addr, v and 0xFF); put8(addr + 1, (v shr 8) and 0xFF) }
        header().forEachIndexed { i, v -> put8(REGION_BASE + i, v) }
        put16(REGION_BASE + 14, at.instruments)
        put16(REGION_BASE + 16, at.orderTable)
        val channels = layout.channelCount
        orders.forEachIndexed { o, row ->
            val rowAddr = at.orderRows + 2 * channels * o
            put16(at.orderTable + 2 * o, rowAddr)
            for (c in 0 until channels) put16(rowAddr + 2 * c, at.tracks[row[c]])
        }
        instruments.forEachIndexed { i, ins ->
            val a = at.instruments + INSTRUMENT_SIZE * i
            put8(a, ins.flags)
            put16(a + 1, if (ins.volume >= 0) at.macros[ins.volume] else 0xFFFF)
            put16(a + 3, if (ins.arpeggio >= 0) at.macros[ins.arpeggio] else 0xFFFF)
            put8(a + 5, ins.exType)
            put16(a + 6, if (ins.ex >= 0) at.macros[ins.ex] else 0xFFFF)
            put8(a + 8, ins.fmPreset)
            put16(a + 9, if (ins.patch >= 0) at.patches[ins.patch] else 0xFFFF)
            put8(a + 11, 0xFF)
        }
        macros.forEachIndexed { i, m -> m.forEachIndexed { k, v -> put8(at.macros[i] + k, v) } }
        patches.forEachIndexed { i, p -> p.forEachIndexed { k, v -> put8(at.patches[i] + k, v) } }
        tracks.forEachIndexed { i, t -> t.forEachIndexed { k, v -> put8(at.tracks[i] + k, v) } }
        return out
    }

    /** The header's first 24 bytes (`music_state` up to the call fields); pointers 14-17 are filled by the writers. */
    private fun header(): IntArray = intArrayOf(
        SmsImageLayout.BJ_MAGIC_BYTE, 0, layout.channelCount, SmsImageLayout.BJ_FLAG_LOOP, 0, 0, layout.hasChips, 0xFF, 0xFF,
        1, speed1, speed2, patternLength, orders.size, 0, 0, 0, 0, tic, patternLength, 0, 0xFF, 0, 0xFF,
    )

    /**
     * The song as an assembler source a game includes beside the Banjo driver, json2sms's layout: label [identifier]
     * (SDAS: `_identifier`, with `.module`/`.globl`, for C projects with devkitSMS or GBDK), the header with the chips'
     * init/update/mute routines (call stubs for two chips), the per-channel mute table, then orders, instruments,
     * macros, patches and tracks. Pass the label to `banjo_play_song` (WLA-DX: `SONG_DEF(identifier)` in a song table).
     */
    fun asm(identifier: String, sdas: Boolean, comment: List<String>): String {
        val p = if (sdas) "_$identifier" else identifier
        fun call(name: String) = if (sdas) "_$name" else name
        val channels = layout.channelCount
        val sb = StringBuilder()
        for (line in comment) sb.append("; ").append(line).append('\n')
        if (sdas) sb.append(".module ").append(p).append("\n.globl ").append(p).append("\n\n")
        sb.append(p).append(":\n")
        val names = listOf("magic_byte", "bank", "channel_count", "flags", "master_volume", "master_volume_fade", "has_chips",
            "sfx_channel", "sfx_subchannel", "time_base", "speed_1", "speed_2", "pattern_length", "orders_length")
        val h = header()
        names.forEachIndexed { i, n -> sb.append(p).append('_').append(n).append(":\n.db ").append(h[i]).append('\n') }
        sb.append(p).append("_instrument_ptrs:\n.dw ").append(p).append("_instrument_data\n")
        sb.append(p).append("_order_ptrs:\n.dw ").append(p).append("_order_pointers\n")
        for ((i, n) in listOf(18 to "tic", 19 to "line", 20 to "order", 21 to "order_jump", 22 to "noise_mode", 23 to "panning")) {
            sb.append(p).append('_').append(n).append(":\n.db ").append(h[i]).append('\n')
        }
        val sn = listOf("banjo_init_sn", "banjo_update_channels_sn", "banjo_mute_all_sn")
        val second = when {
            layout.drums -> listOf("banjo_init_opll_drums", "banjo_update_channels_opll_drums", "banjo_mute_all_opll_drums")
            layout.fm -> listOf("banjo_init_opll", "banjo_update_channels_opll", "banjo_mute_all_opll")
            else -> null
        }
        fun field(label: String, index: Int, stub: String) {
            sb.append(p).append('_').append(label).append(":\n.dw ")
            sb.append(if (second == null) call(sn[index]) else "${p}_$stub").append('\n')
        }
        field("channel_init", 0, "channel_init_call")
        field("channel_update", 1, "channel_update_call")
        sb.append(p).append("_channel_mute:\n.dw ").append(p).append("_channel_mute_calls_data\n")
        field("song_mute", 2, "song_mute_call")
        sb.append(p).append("_song_stop:\n.dw ").append(call("banjo_song_stop")).append("\n\n")
        if (second != null) {
            for ((index, stub) in listOf("channel_init_call", "channel_update_call", "song_mute_call").withIndex()) {
                sb.append(p).append('_').append(stub).append(":\n")
                sb.append("\tcall ").append(call(sn[index])).append('\n')
                sb.append("\tcall ").append(call(second[index])).append('\n')
                sb.append("\tret\n\n")
            }
        }
        val mutes = (0 until channels).map { c ->
            when (layout.kind(c)) {
                org.thortracker.system.sms.SmsChannelKind.SQUARE, org.thortracker.system.sms.SmsChannelKind.NOISE -> "banjo_mute_channel_sn"
                org.thortracker.system.sms.SmsChannelKind.FM -> "banjo_mute_channel_opll"
                org.thortracker.system.sms.SmsChannelKind.DRUM -> "banjo_mute_channel_opll_drums"
            }
        }
        sb.append(p).append("_channel_mute_calls_data:\n\t.dw ").append(mutes.joinToString(", ") { call(it) }).append("\n\n")
        sb.append(p).append("_order_pointers:\n")
        for (o in orders.indices) sb.append("\t.dw ").append(p).append("_order_").append(o).append('\n')
        sb.append(p).append("_orders:\n")
        orders.forEachIndexed { o, row ->
            sb.append(p).append("_order_").append(o).append(":\n\t.dw ")
            sb.append(row.joinToString(", ") { "${p}_track_$it" }).append('\n')
        }
        sb.append('\n').append(p).append("_instrument_data:\n")
        instruments.forEachIndexed { i, ins ->
            sb.append(p).append("_instrument_").append(i).append(":\n")
            sb.append("\t.db ").append(hex(ins.flags)).append(" ; flags\n")
            sb.append("\t.dw ").append(if (ins.volume >= 0) "${p}_macro_${ins.volume}" else "0xffff").append(" ; volume macro\n")
            sb.append("\t.dw ").append(if (ins.arpeggio >= 0) "${p}_macro_${ins.arpeggio}" else "0xffff").append(" ; arpeggio macro\n")
            sb.append("\t.db ").append(hex(ins.exType)).append(" ; extra macro type\n")
            sb.append("\t.dw ").append(if (ins.ex >= 0) "${p}_macro_${ins.ex}" else "0xffff").append(" ; extra macro\n")
            sb.append("\t.db ").append(hex(ins.fmPreset)).append(" ; fm preset\n")
            sb.append("\t.dw ").append(if (ins.patch >= 0) "${p}_fm_patch_${ins.patch}" else "0xffff").append(" ; fm patch\n")
            sb.append("\t.db 0xff ; padding\n")
        }
        sb.append('\n').append(p).append("_macros:\n")
        macros.forEachIndexed { i, m -> sb.append(p).append("_macro_").append(i).append(":\n.db ").append(m.joinToString(", ") { hex(it) }).append('\n') }
        sb.append('\n').append(p).append("_fm_patches:\n")
        patches.forEachIndexed { i, m -> sb.append(p).append("_fm_patch_").append(i).append(":\n.db ").append(m.joinToString(", ") { hex(it) }).append('\n') }
        sb.append('\n').append(p).append("_patterns:\n")
        tracks.forEachIndexed { i, t -> sb.append(p).append("_track_").append(i).append(":\n.db ").append(t.joinToString(", ") { hex(it) }).append('\n') }
        return sb.toString()
    }

    private fun hex(v: Int) = "0x" + (v and 0xFF).toString(16).padStart(2, '0')
}
