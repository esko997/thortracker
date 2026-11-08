package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import org.thortracker.system.ws.WsEnvelope
import org.thortracker.system.ws.WsFeed
import org.thortracker.system.ws.WsInstrument
import org.thortracker.system.ws.WsKind
import org.thortracker.system.ws.WsOutput
import org.thortracker.system.ws.WsSample
import org.thortracker.system.ws.WsSound

/**
 * The songs `tools/audio/ws/ws_golden.py` builds with the reference builder (`ws_song.py`) for [WsGoldenTest], written
 * in Thor Tracker's model. Everything is explicit here so the Python mirror can be written from this file alone:
 *
 * Conventions: a cell is (note, instrument, effects): note null = none, [OFF] = the OFF byte `0xFE`, else the absolute
 * note; instrument null = no byte; effects = (letter, parameter) in columns 0, 1, ... (no blank columns). Each order o
 * is its own pattern o, "Order o", whose length is the order's rows; lanes not listed are empty. Envelopes are (values,
 * loop, release) with -1 = none ([env]). Fields not listed for an instrument are [WsInstrument]'s defaults: name
 * "Golden", tone, wave 0, tap 0, sample 0, CPU feed, root 48, rate 1, no loop, loop start 0, not backwards, sweep 0,
 * sweep time 0, no LFSR reset, volume [15], other envelopes empty, absolute pitch. Fields of other kinds or the other
 * feed ARE set below on purpose where noted: the lowering must leave them out.
 *
 * [mono] ("mono.bin.gz", original WonderSwan, model 0): speed 6, 2 orders, loop order 1, voice timer 3, speaker shift
 * 2, output speaker.
 * - Waves: W0[i] = i / 2; W1[i] = 15 if i < 16 else 0; W2[i] = (5 i) mod 16 (i = 0..31).
 * - Samples (all mono, rate 6000): S0 "a" = 40 bytes (37 i + 11) mod 256; S1 "b" = the same 40 bytes as S0 (stored
 *   once); S2 "c" = 3 bytes [128, 0, 255].
 * - Instruments: 1 = tone, wave 1, volume [15, 12, 8, 4, 0], arpeggio [0, 12] loop 0, sweep -5, sweep time 3;
 *   2 = noise, tap 5, LFSR reset, volume [15, 10] loop 1, pitch [-8, 8] relative, taps [0, 3, 7] loop 2, and (not
 *   lowered) wave 2, sweep 9, waves [1, 1]; 3 = sample, CPU, sample 0, root 57, loop from 10, volume [15], pitch [4]
 *   absolute; 4 = empty; 5 = sample, CPU, sample 1, root 48, one-shot with (not lowered) loop start 7, volume [15, 12,
 *   8, 4, 0] (slot 1's bytes: stored once), arpeggio [-64, 63, 0] loop 0 release 2, and (not lowered) waves [1, 2],
 *   rate 3, backwards; 6 = tone, wave 2, volume [10],
 *   waves [0, 1, 2] loop 0, relative pitch flag with an empty pitch envelope (the flag is written).
 * - Order 0, 4 rows: lane 0: row 0 (57, 1, 0:37, W:02), row 2 (OFF), row 3 (-, -, B:01); lane 1: row 0 (48, 3, 8:F4,
 *   T:02), row 1 (50, -, P:90); lane 2: row 0 (60, 6, U:05, V:1F), row 1 (-, -, 1:10), row 3 (-, -, K:03); lane 3: row 0
 *   (72, 2, N:07, S:02), row 2 (70, -, G:01).
 * - Order 1, 2 rows: lane 0: row 0 (19, 1, C:0F, A:21, 4:37, F:03); lane 1: row 1 (95, 5); lanes 2 and 3 empty (their
 *   identical encodings are stored once).
 *
 * [color] ("color.bin.gz", WonderSwan Color, model 1): speed 3, 1 order of 4 rows, loop order 0, voice timer 1,
 * speaker shift 0, output headphones.
 * - Waves: W0[i] = 15 − i / 2.
 * - Samples: S0 "m" mono 20 bytes 12 i; S1 "s" stereo 16 bytes (8 frames), byte i = 200 if i even else 56; S2 "m2"
 *   mono, the same 20 bytes as S0 (unsigned and signed copies stored once); S3 "c" mono 5 bytes 255 − i (no DMA
 *   instrument: no signed copy). Rates 6000 (not lowered).
 * - Instruments: 1 = tone, wave 0, volume [15]; 2 = sample, DMA, sample 0, rate 3, one-shot, backwards, volume [15, 8],
 *   and (not lowered for DMA) arpeggio [1, 2], pitch [3] relative, root 60; 3 = sample, DMA, sample 1, rate 0, loop,
 *   volume [12]; 4 = sample, CPU, sample 3, root 60, loop from 4, volume [15]; 5 = sample, DMA, sample 2, rate 2,
 *   volume [15]; 6..63 empty; 64 = noise, tap 1, volume [15].
 * - Order 0, 4 rows: lane 0: row 0 (57, 1); lane 1: row 0 (48, 2, H:01), row 2 (-, -, H:00); lane 2 empty; lane 3: row 1
 *   (60, 64); lane 4: row 0 (60, 3, 8:F0), row 2 (50, 5), row 3 (62, 4, H:01).
 */
object WsGoldenSongs {
    const val OFF = 0xFE

    fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = WsEnvelope(values.toList(), loop, release)

    private fun c(note: Int? = null, inst: Int? = null, vararg fx: Pair<Char, Int>): Cell {
        var cell = Cell(
            note = when (note) { null -> Cell.EMPTY; OFF -> Cell.CUT; else -> note },
            instrument = inst ?: 0,
        )
        fx.forEachIndexed { i, (letter, param) -> cell = cell.withEffect(i, code(letter), param) }
        return cell
    }

    private fun code(letter: Char) = (0..EffectSpec.MAX_CODE).first { EffectSpec.charOf(it) == letter }

    /** One order: its rows and its cells by (lane, row). */
    private class Order(val rows: Int, val cells: Map<Pair<Int, Int>, Cell>)

    private fun song(
        color: Boolean, speed: Int, loop: Int, orders: List<Order>, instruments: Map<Int, WsInstrument>,
        waves: List<List<Int>>, samples: List<WsSample>, timer: Int, shift: Int, output: WsOutput,
    ): Song {
        val lanes = if (color) 5 else 4
        return Song(
            title = "Golden",
            ticksPerRow = speed,
            loopOrder = loop,
            patterns = orders.mapIndexed { o, order ->
                Pattern("Order $o", (0 until lanes).map { lane -> List(Songs.ROWS) { row -> order.cells[lane to row] ?: Cell.BLANK } }, length = order.rows)
            },
            orders = orders.indices.toList(),
            sound = WsSound(
                color = color,
                instruments = List(64) { instruments[it + 1] },
                waves = waves,
                samples = samples,
                output = output,
                speakerShift = shift,
                timerReload = timer,
            ),
        )
    }

    private fun ins(kind: WsKind = WsKind.TONE) = WsInstrument(name = "Golden", kind = kind)

    private fun monoInstruments(): Map<Int, WsInstrument> = mapOf(
        1 to ins().copy(wave = 1, volume = env(15, 12, 8, 4, 0), arpeggio = env(0, 12, loop = 0), sweep = -5, sweepTime = 3),
        2 to ins(WsKind.NOISE).copy(tap = 5, lfsrReset = true, volume = env(15, 10, loop = 1), pitch = env(-8, 8), pitchRelative = true,
            taps = env(0, 3, 7, loop = 2), wave = 2, sweep = 9, waves = env(1, 1)),
        3 to ins(WsKind.SAMPLE).copy(sample = 0, root = 57, loop = true, loopStart = 10, volume = env(15), pitch = env(4)),
        5 to ins(WsKind.SAMPLE).copy(sample = 1, root = 48, loop = false, loopStart = 7, volume = env(15, 12, 8, 4, 0),
            arpeggio = env(-64, 63, 0, loop = 0, release = 2), waves = env(1, 2), rate = 3, backwards = true),
        6 to ins().copy(wave = 2, volume = env(10), waves = env(0, 1, 2, loop = 0), pitchRelative = true),
    )

    /** The original-model golden song (see the object comment). */
    fun mono(): Song {
        val a = List(40) { (37 * it + 11) % 256 }
        val o0 = mapOf(
            (0 to 0) to c(57, 1, '0' to 0x37, 'W' to 0x02), (0 to 2) to c(OFF), (0 to 3) to c(null, null, 'B' to 0x01),
            (1 to 0) to c(48, 3, '8' to 0xF4, 'T' to 0x02), (1 to 1) to c(50, null, 'P' to 0x90),
            (2 to 0) to c(60, 6, 'U' to 0x05, 'V' to 0x1F), (2 to 1) to c(null, null, '1' to 0x10), (2 to 3) to c(null, null, 'K' to 0x03),
            (3 to 0) to c(72, 2, 'N' to 0x07, 'S' to 0x02), (3 to 2) to c(70, null, 'G' to 0x01),
        )
        val o1 = mapOf(
            (0 to 0) to c(19, 1, 'C' to 0x0F, 'A' to 0x21, '4' to 0x37, 'F' to 0x03),
            (1 to 1) to c(95, 5),
        )
        return song(
            color = false, speed = 6, loop = 1, orders = listOf(Order(4, o0), Order(2, o1)), instruments = monoInstruments(),
            waves = listOf(List(32) { it / 2 }, List(32) { if (it < 16) 15 else 0 }, List(32) { (5 * it) % 16 }),
            samples = listOf(WsSample("a", a, rate = 6000), WsSample("b", a.toList(), rate = 6000), WsSample("c", listOf(128, 0, 255), rate = 6000)),
            timer = 3, shift = 2, output = WsOutput.SPEAKER,
        )
    }

    /** The Color golden song (see the object comment). */
    fun color(): Song {
        val m = List(20) { 12 * it }
        val instruments = mapOf(
            1 to ins().copy(wave = 0, volume = env(15)),
            2 to ins(WsKind.SAMPLE).copy(sample = 0, feed = WsFeed.DMA, rate = 3, loop = false, backwards = true, volume = env(15, 8),
                arpeggio = env(1, 2), pitch = env(3), pitchRelative = true, root = 60),
            3 to ins(WsKind.SAMPLE).copy(sample = 1, feed = WsFeed.DMA, rate = 0, loop = true, volume = env(12)),
            4 to ins(WsKind.SAMPLE).copy(sample = 3, feed = WsFeed.CPU, root = 60, loop = true, loopStart = 4, volume = env(15)),
            5 to ins(WsKind.SAMPLE).copy(sample = 2, feed = WsFeed.DMA, rate = 2, volume = env(15)),
            64 to ins(WsKind.NOISE).copy(tap = 1, volume = env(15)),
        )
        val o0 = mapOf(
            (0 to 0) to c(57, 1),
            (1 to 0) to c(48, 2, 'H' to 0x01), (1 to 2) to c(null, null, 'H' to 0x00),
            (3 to 1) to c(60, 64),
            (4 to 0) to c(60, 3, '8' to 0xF0), (4 to 2) to c(50, 5), (4 to 3) to c(62, 4, 'H' to 0x01),
        )
        return song(
            color = true, speed = 3, loop = 0, orders = listOf(Order(4, o0)), instruments = instruments,
            waves = listOf(List(32) { 15 - it / 2 }),
            samples = listOf(
                WsSample("m", m, rate = 6000),
                WsSample("s", List(16) { if (it % 2 == 0) 200 else 56 }, stereo = true, rate = 6000),
                WsSample("m2", m.toList(), rate = 6000),
                WsSample("c", List(5) { 255 - it }, rate = 6000),
            ),
            timer = 1, shift = 0, output = WsOutput.HEADPHONES,
        )
    }
}
