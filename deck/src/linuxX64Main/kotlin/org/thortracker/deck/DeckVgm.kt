@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import decknative.o3_export_free
import decknative.o3_export_vgm
import kotlinx.cinterop.*
import org.thortracker.system.opl3.Opl3VgmRenderer
import org.thortracker.system.opl3.Opl3VgmTags
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
