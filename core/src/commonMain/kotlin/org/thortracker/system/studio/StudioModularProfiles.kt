package org.thortracker.system.studio

/** Conservative real-time reservations. Format validity and offline rendering are independent. */
object StudioModularProfiles {
    private const val MIB = 1024L * 1024L

    fun android(manufacturer: String, model: String, soc: String?): StudioModularLimits = when {
        soc == "QCS8550" && manufacturer.equals("AYN", true) && model.equals("AYN Thor", true) ->
            StudioModularLimits("Thor QCS8550", 5000, 1024 * MIB, 12, 14)
        soc == "QCS8550" && model.equals("Retroid Pocket Nova", true) ->
            StudioModularLimits("Nova QCS8550", 5000, 1024 * MIB, 12, 14)
        else -> StudioModularLimits("Conservative Android", 256, 384 * MIB, 12, 14)
    }

    fun linux(brickBuild: Boolean, productName: String?): StudioModularLimits = when {
        brickBuild -> StudioModularLimits("Brick / NextUI", 640, 320 * MIB, 6, 8)
        productName == "Galileo" -> StudioModularLimits("Steam Deck OLED", 4800, 1024 * MIB, 6, 8)
        else -> StudioModularLimits("Conservative Linux", 256, 384 * MIB, 6, 8)
    }
}
