@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import decknative.o3_export_free
import decknative.o3_export_vgm
import decknative.pk_export_free
import decknative.pk_export_vgm
import decknative.ym_export
import decknative.ym_export_free
import kotlinx.cinterop.*
import org.thortracker.system.opl3.Opl3VgmRenderer
import org.thortracker.system.opl3.Opl3VgmTags
import org.thortracker.system.pokey.PokeyVgmRenderer
import org.thortracker.system.pokey.PokeyVgmTags
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

/** POKEY VGM export through the native player linked into the Deck (`pokey/pokey_export.h`); registered by [main]. */
internal object DeckPokeyVgm : PokeyVgmRenderer {
    override fun render(image: ByteArray, maxTicks: Int, tags: PokeyVgmTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val vgm = image.usePinned {
            pk_export_vgm(it.addressOf(0).reinterpret(), image.size.convert(), maxTicks.coerceAtLeast(0),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "VGM export failed")
        try {
            vgm.readBytes(size.value.toInt())
        } finally {
            pk_export_free(vgm)
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
