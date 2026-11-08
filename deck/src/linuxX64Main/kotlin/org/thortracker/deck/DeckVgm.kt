@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import decknative.ay_export_free
import decknative.ay_export_vgm
import decknative.o3_export_free
import decknative.o3_export_vgm
import decknative.ym_export
import decknative.ym_export_free
import kotlinx.cinterop.*
import org.thortracker.system.ay.AyVgmRenderer
import org.thortracker.system.ay.AyVgmTags
import org.thortracker.system.opl3.Opl3VgmRenderer
import org.thortracker.system.opl3.Opl3VgmTags
import org.thortracker.system.ym2612.YmExportRenderer
import org.thortracker.system.ym2612.YmExportTags
import platform.posix.size_tVar

/** OPL3 VGM export through the native player linked into the Deck (`opl3/opl3_export.h`); registered by [main]. */
internal object DeckOpl3Vgm : Opl3VgmRenderer {
    override fun render(image: ByteArray, maxTicks: Int, tags: Opl3VgmTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val vgm = image.usePinned {
            o3_export_vgm(it.addressOf(0).reinterpret(), image.size.convert(), maxTicks.coerceAtLeast(0).convert(),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "VGM export failed")
        try {
            vgm.readBytes(size.value.toInt())
        } finally {
            o3_export_free(vgm)
        }
    }
}

/** Mega Drive VGM/XGM/XGC export through the native player linked into the Deck (`ym/ym_export.h`); registered by [main]. */
internal object DeckYmExport : YmExportRenderer {
    override fun render(image: ByteArray, format: Int, maxTicks: Int, tags: YmExportTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val file = image.usePinned {
            ym_export(it.addressOf(0).reinterpret(), image.size.convert(), format, maxTicks.coerceAtLeast(0).convert(),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "export failed")
        try {
            file.readBytes(size.value.toInt())
        } finally {
            ym_export_free(file)
        }
    }
}

/**
 * AY-3-8910 / YM2149 VGM export through the native player linked into the Deck (`ay/ay_export.h`); [variant] is the
 * image's variant byte (0 ZX Spectrum 128, 1 MSX, 2 Atari ST, 3 Amstrad CPC). Registered by [main].
 */
internal object DeckAyVgm : AyVgmRenderer {
    override fun render(variant: Int, image: ByteArray, maxTicks: Int, tags: AyVgmTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val vgm = image.usePinned {
            ay_export_vgm(variant, it.addressOf(0).reinterpret(), image.size.convert(), maxTicks.coerceAtLeast(0),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "VGM export failed")
        try {
            vgm.readBytes(size.value.toInt())
        } finally {
            ay_export_free(vgm)
        }
    }
}
