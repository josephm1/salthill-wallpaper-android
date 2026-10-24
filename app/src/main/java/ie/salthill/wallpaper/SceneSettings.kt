package ie.salthill.wallpaper

import android.content.Context

/** A saved weather place, not an always-running location subscription. */
data class SceneSettings(
    val latitude: Double, val longitude: Double, val fps: Int, val tideUrl: String,
    val locationMode: String = SAVED, val locationUpdatedAt: Long = 0L,
) {
    companion object {
        const val CURRENT = "current"
        const val SALTHILL = "salthill"
        const val SAVED = "saved"
        // Blackrock/Salthill preset, from Inland Fisheries Ireland's Galway guide.
        const val SALTHILL_LATITUDE = 53.25682
        const val SALTHILL_LONGITUDE = -9.0910

        fun read(context: Context): SceneSettings {
            val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val lat = p.getString("latitude", null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
            val lon = p.getString("longitude", null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
            // Migrate the untouched original project's coordinates to the named Salthill preset.
            // Preserve any location the user actually entered in the old settings screen.
            val legacyDefault = lat == 54.9596 && lon == -7.734439
            val mode = p.getString("locationMode", null) ?: if (lat == null || lon == null || legacyDefault) SALTHILL else SAVED
            return SceneSettings(
                if (mode == SALTHILL) SALTHILL_LATITUDE else lat ?: SALTHILL_LATITUDE,
                if (mode == SALTHILL) SALTHILL_LONGITUDE else lon ?: SALTHILL_LONGITUDE,
                p.getInt("fps", 15).takeIf { it in listOf(10, 15, 30) } ?: 15,
                p.getString("tideUrl", "") ?: "", mode, p.getLong("locationUpdatedAt", 0L),
            )
        }
    }
    fun save(context: Context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("latitude", latitude.toString()).putString("longitude", longitude.toString())
            .putInt("fps", fps).putString("tideUrl", tideUrl)
            .putString("locationMode", locationMode).putLong("locationUpdatedAt", locationUpdatedAt).apply()
    }
    fun atSalthill() = copy(latitude = SALTHILL_LATITUDE, longitude = SALTHILL_LONGITUDE, locationMode = SALTHILL, locationUpdatedAt = 0L)
    val locationKey: String get() = "$latitude,$longitude"
}
