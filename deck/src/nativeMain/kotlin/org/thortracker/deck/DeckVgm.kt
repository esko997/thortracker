@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import decknative.ay_export_free
import decknative.ay_export_vgm
import decknative.o3_export_free
import decknative.o3_export_vgm
import decknative.ws_export_free
import decknative.ws_export_vgm
import decknative.vb_export_free
import decknative.vb_export_vgm
import decknative.sa_export_free
import decknative.sa_export_vgm
import decknative.pk_export_free
import decknative.pk_export_vgm
import decknative.opn_export_free
import decknative.opn_export_vgm
import decknative.om_export_free
import decknative.om_export_vgm
import decknative.pc_export_free
import decknative.pc_export_vgm
import decknative.sc_export_free
import decknative.sc_export_vgm
import decknative.sms_export_free
import decknative.sms_export_vgm
import decknative.ym_export
import decknative.ym_export_free
import kotlinx.cinterop.*
import org.thortracker.system.ay.AyVgmRenderer
import org.thortracker.system.ay.AyVgmTags
import org.thortracker.system.opl3.Opl3VgmRenderer
import org.thortracker.system.opl3.Opl3VgmTags
import org.thortracker.system.ws.WsVgmRenderer
import org.thortracker.system.ws.WsVgmTags
import org.thortracker.system.vsu.VsuVgmRenderer
import org.thortracker.system.vsu.VsuVgmTags
import org.thortracker.system.saa.SaaVgmRenderer
import org.thortracker.system.saa.SaaVgmTags
import org.thortracker.system.pokey.PokeyVgmRenderer
import org.thortracker.system.pokey.PokeyVgmTags
import org.thortracker.system.opn.OpnVgmRenderer
import org.thortracker.system.opn.OpnVgmTags
import org.thortracker.system.opm.OpmVgmRenderer
import org.thortracker.system.opm.OpmVgmTags
import org.thortracker.system.pce.PceVgmRenderer
import org.thortracker.system.pce.PceVgmTags
import org.thortracker.system.scc.SccVgmRenderer
import org.thortracker.system.scc.SccVgmTags
import org.thortracker.system.sms.SmsVgmRenderer
import org.thortracker.system.sms.SmsVgmTags
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

/** WonderSwan / WonderSwan Color VGM export through the native player linked into the Deck (`ws/ws_export.h`); registered by [main]. */
internal object DeckWsVgm : WsVgmRenderer {
    override fun render(image: ByteArray, maxTicks: Int, tags: WsVgmTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val vgm = image.usePinned {
            ws_export_vgm(it.addressOf(0).reinterpret(), image.size.convert(), maxTicks.coerceAtLeast(0).convert(),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "VGM export failed")
        try {
            vgm.readBytes(size.value.toInt())
        } finally {
            ws_export_free(vgm)
        }
    }
}

/** Virtual Boy VGM export through the native player linked into the Deck (`vsu/vsu_export.h`); registered by [main]. */
internal object DeckVsuVgm : VsuVgmRenderer {
    override fun render(image: ByteArray, maxTicks: Int, tags: VsuVgmTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val vgm = image.usePinned {
            vb_export_vgm(it.addressOf(0).reinterpret(), image.size.convert(), maxTicks.coerceAtLeast(0),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "VGM export failed")
        try {
            vgm.readBytes(size.value.toInt())
        } finally {
            vb_export_free(vgm)
        }
    }
}

/** SAA1099 VGM export (SAM Coupe or CMS: the image's variant) through the native player linked into the Deck
 * (`saa/saa_export.h`); registered by [main]. */
internal object DeckSaaVgm : SaaVgmRenderer {
    override fun render(image: ByteArray, maxTicks: Int, tags: SaaVgmTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val vgm = image.usePinned {
            sa_export_vgm(it.addressOf(0).reinterpret(), image.size.convert(), maxTicks.coerceAtLeast(0).convert(),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "VGM export failed")
        try {
            vgm.readBytes(size.value.toInt())
        } finally {
            sa_export_free(vgm)
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

/** PC-88/98 OPN / OPNA VGM export through the native player linked into the Deck (`opn/opn_export.h`); registered by [main]. */
internal object DeckOpnVgm : OpnVgmRenderer {
    override fun render(image: ByteArray, maxTicks: Int, tags: OpnVgmTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val vgm = image.usePinned {
            opn_export_vgm(it.addressOf(0).reinterpret(), image.size.convert(), maxTicks.coerceAtLeast(0).convert(),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "VGM export failed")
        try {
            vgm.readBytes(size.value.toInt())
        } finally {
            opn_export_free(vgm)
        }
    }
}

/** SCC / SCC+ VGM export (the image's variant) through the native player linked into the Deck
 * (`scc/scc_export.h`); registered by [main]. */
internal object DeckSccVgm : SccVgmRenderer {
    override fun render(image: ByteArray, maxTicks: Int, tags: SccVgmTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val vgm = image.usePinned {
            sc_export_vgm(it.addressOf(0).reinterpret(), image.size.convert(), maxTicks.coerceAtLeast(0).convert(),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "VGM export failed")
        try {
            vgm.readBytes(size.value.toInt())
        } finally {
            sc_export_free(vgm)
        }
    }
}

/** PC Engine VGM export through the native player linked into the Deck (`pce/pce_export.h`); registered by [main]. */
internal object DeckPceVgm : PceVgmRenderer {
    override fun render(image: ByteArray, maxTicks: Int, tags: PceVgmTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val vgm = image.usePinned {
            pc_export_vgm(it.addressOf(0).reinterpret(), image.size.convert(), maxTicks.coerceAtLeast(0).convert(),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "VGM export failed")
        try {
            vgm.readBytes(size.value.toInt())
        } finally {
            pc_export_free(vgm)
        }
    }
}

/** OPM VGM export through the native player linked into the Deck (`opm/opm_export.h`); registered by [main]. */
internal object DeckOpmVgm : OpmVgmRenderer {
    override fun render(image: ByteArray, maxTicks: Int, tags: OpmVgmTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val vgm = image.usePinned {
            om_export_vgm(it.addressOf(0).reinterpret(), image.size.convert(), maxTicks.coerceAtLeast(0).convert(),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "VGM export failed")
        try {
            vgm.readBytes(size.value.toInt())
        } finally {
            om_export_free(vgm)
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

/** Master System VGM export through the Banjo machine linked into the Deck (`sms/sms_export.h`); registered by [main]. */
internal object DeckSmsVgm : SmsVgmRenderer {
    override fun render(image: ByteArray, maxTicks: Int, tags: SmsVgmTags): ByteArray = memScoped {
        val size = alloc<size_tVar>()
        val problem = alloc<CPointerVar<ByteVar>>()
        val file = image.usePinned {
            sms_export_vgm(it.addressOf(0).reinterpret(), image.size.convert(), maxTicks.coerceAtLeast(0).convert(),
                tags.title, tags.author, tags.date, size.ptr, problem.ptr)
        } ?: throw IllegalArgumentException(problem.value?.toKString() ?: "VGM export failed")
        try {
            file.readBytes(size.value.toInt())
        } finally {
            sms_export_free(file)
        }
    }
}
