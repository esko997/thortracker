package org.thortracker.audio

import android.os.Build
import org.thortracker.system.studio.StudioModularProfiles

internal object AndroidModularProfile {
    val current = StudioModularProfiles.android(Build.MANUFACTURER, Build.MODEL,
        if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null)
}
