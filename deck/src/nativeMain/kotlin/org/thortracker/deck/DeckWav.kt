@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import cnames.structs.ChipWav
import decknative.CHIP_WAV_ERR_FAULT
import decknative.CHIP_WAV_FIELDS
import decknative.chip_wav_close
import decknative.chip_wav_open
import decknative.chip_wav_run
import decknative.chip_wav_status
import kotlinx.cinterop.*
import org.thortracker.audio.WavJob
import org.thortracker.audio.WavOptions
import org.thortracker.audio.WavRenderer
import org.thortracker.audio.WavStatus

/** WAV export through the native engines linked into the Deck (`chip_wav.h`); registered by [main]. */
internal object DeckWav : WavRenderer {
    override fun open(engineId: String, image: ByteArray, options: WavOptions, spool: String): WavJob = memScoped {
        val problem = alloc<CPointerVar<ByteVar>>()
        val job = image.usePinned {
            chip_wav_open(engineId, it.addressOf(0).reinterpret(), image.size.convert(), options.loops,
                (options.fadeSeconds * 1000).coerceAtLeast(0).convert(), options.maxSeconds.coerceAtLeast(0).convert(), spool,
                problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "WAV export failed")
        Job(job)
    }

    private class Job(private var job: CPointer<ChipWav>?) : WavJob {
        private val values = LongArray(CHIP_WAV_FIELDS)

        override fun run(ticks: Int): Boolean = when (val r = chip_wav_run(job, ticks)) {
            0 -> true
            1 -> false
            CHIP_WAV_ERR_FAULT -> throw IllegalStateException("the driver faulted on this song data")
            else -> throw IllegalStateException("the file could not be written (disk full?)")
        }

        override fun status(): WavStatus {
            values.usePinned { chip_wav_status(job, it.addressOf(0), values.size) }
            return WavStatus(values[0], values[1], values[2].toInt(), values[3] != 0L, values[4], values[5] != 0L)
        }

        override fun close() {
            job?.let(::chip_wav_close)
            job = null
        }
    }
}
