package ie.salthill.wallpaper

import org.json.JSONObject
import java.time.Instant

internal data class TideForecast(val samples: List<Tide>, val hourly: Boolean)

/** Accept modelled hourly sea levels or an optional legacy tide-extremes feed. */
internal fun parseTideForecast(data: JSONObject): TideForecast {
    val hourly = data.optJSONObject("hourly")
    if (hourly != null) {
        val times = hourly.getJSONArray("time")
        val levels = hourly.getJSONArray("sea_level_height_msl")
        val samples = (0 until minOf(times.length(), levels.length())).mapNotNull { i ->
            val height = levels.optDouble(i, Double.NaN)
            val time = times.optLong(i, Long.MIN_VALUE)
            if (height.isFinite() && time != Long.MIN_VALUE) Tide(time * 1000, height) else null
        }.distinctBy { it.time }.sortedBy { it.time }
        return TideForecast(samples, true)
    }
    val array = data.getJSONArray("data")
    val samples = (0 until array.length()).mapNotNull { i -> runCatching {
        val entry = array.getJSONObject(i)
        Tide(Instant.parse(entry.getString("time")).toEpochMilli(), entry.getDouble("height")).takeIf { it.height.isFinite() }
    }.getOrNull() }.distinctBy { it.time }.sortedBy { it.time }
    return TideForecast(samples, false)
}
