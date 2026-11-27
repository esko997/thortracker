@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thortracker.deck

import kotlinx.cinterop.*
import platform.posix.*
import org.thortracker.system.studio.StudioModularProfiles

internal object NativeModularProfile {
    val current = StudioModularProfiles.linux(Platform.NAME == "Brick Hammer / NextUI", productName())

    // sysfs reports a virtual file size; read its bounded line instead of seeking to the end.
    private fun productName(): String? {
        val file = fopen("/sys/class/dmi/id/product_name", "r") ?: return null
        return try {
            memScoped { fgets(allocArray<ByteVar>(128), 128, file)?.toKString()?.trim() }
        } finally { fclose(file) }
    }
}
