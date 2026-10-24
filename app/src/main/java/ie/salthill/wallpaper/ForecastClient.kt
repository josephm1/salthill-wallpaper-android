package ie.salthill.wallpaper

import org.json.JSONObject

internal data class ForecastSnapshot(
    val hours: List<WeatherHour> = emptyList(), val days: List<SolarDay> = emptyList(),
    val tides: List<Tide> = emptyList(), val hourlyTides: Boolean = false,
    val weatherEntry: ForecastEntry? = null, val tideEntry: ForecastEntry? = null,
    val weatherFailed: Boolean = false,
) {
    fun weatherCovered(now: Long) = hours.any { it.time <= now && now - it.time < 3_600_000 } &&
        days.any { it.start <= now && now - it.start < 93_600_000 }
    fun tideCovered(now: Long) = tideAt(tides, now, smooth = !hourlyTides) != null
    fun needsRefresh(now: Long) = shouldFetch(weatherEntry, now, WEATHER_CACHE_MS, weatherCovered(now)) ||
        shouldFetch(tideEntry, now, TIDE_CACHE_MS, tideCovered(now))
}

/** No timers. Called by the repository's single worker only while a scene is visible. */
internal class ForecastClient(
    private val cache: ForecastCache, private val fetch: (String) -> JSONObject,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun cached(weatherUrl: String, tideUrl: String): ForecastSnapshot {
        val weather = cache.get(weatherUrl); val tide = cache.get(tideUrl)
        val parsedWeather = runCatching { parseWeather(JSONObject(weather?.body ?: "")) }.getOrNull()
        val parsedTide = runCatching { parseTideForecast(JSONObject(tide?.body ?: "")) }.getOrNull()
        return ForecastSnapshot(parsedWeather?.first ?: emptyList(), parsedWeather?.second ?: emptyList(),
            parsedTide?.samples ?: emptyList(), parsedTide?.hourly ?: false, weather, tide,
            weatherFailed = (weather?.failures ?: 0) > 0)
    }
    fun refresh(weatherUrl: String, tideUrl: String, initial: ForecastSnapshot, active: () -> Boolean): ForecastSnapshot {
        var state = initial
        if (active() && shouldFetch(state.weatherEntry, clock(), WEATHER_CACHE_MS, state.weatherCovered(clock()))) {
            try {
                val json = fetch(weatherUrl); val parsed = parseWeather(json); val now = clock()
                require(ForecastSnapshot(hours = parsed.first, days = parsed.second).weatherCovered(now))
                state = state.copy(hours = parsed.first, days = parsed.second, weatherFailed = false,
                    weatherEntry = cache.success(weatherUrl, json.toString(), now))
            } catch (error: Exception) {
                state = state.copy(weatherFailed = true, weatherEntry = failed(weatherUrl, error))
            }
        }
        // Recheck visibility after weather completes: never start a second request for a hidden scene.
        if (active() && shouldFetch(state.tideEntry, clock(), TIDE_CACHE_MS, state.tideCovered(clock()))) {
            try {
                val json = fetch(tideUrl); val parsed = parseTideForecast(json); val now = clock()
                if (tideAt(parsed.samples, now, smooth = !parsed.hourly) == null) {
                    // Valid response but no local sea-level coverage: don't poll it every five minutes.
                    state = state.copy(tideEntry = cache.failure(tideUrl, now, now + TIDE_CACHE_MS))
                } else state = state.copy(tides = parsed.samples, hourlyTides = parsed.hourly,
                    tideEntry = cache.success(tideUrl, json.toString(), now))
            } catch (error: Exception) { state = state.copy(tideEntry = failed(tideUrl, error)) }
        }
        return state
    }
    private fun failed(address: String, error: Exception): ForecastEntry {
        val now = clock()
        val earliest = if (error is ForecastHttpException) maxOf(error.retryAt,
            if (error.code in listOf(400, 401, 403, 404)) now + TIDE_CACHE_MS else 0) else 0
        return cache.failure(address, now, earliest)
    }
}

internal fun parseWeather(data: JSONObject): Pair<List<WeatherHour>, List<SolarDay>> {
    val h = data.getJSONObject("hourly"); val times = h.getJSONArray("time")
    fun number(name: String, i: Int, fallback: Double = Double.NaN) = h.optJSONArray(name)?.optDouble(i, fallback) ?: fallback
    val result = (0 until times.length()).map { i ->
        WeatherHour(times.getLong(i) * 1000, number("apparent_temperature", i), number("wind_speed_10m", i),
            number("wind_direction_10m", i, 0.0), number("cloud_cover", i), number("rain", i, 0.0) + number("showers", i, 0.0),
            number("snowfall", i, 0.0) * 7, number("visibility", i, 10000.0))
    }.filter { it.temperature.isFinite() && it.wind.isFinite() && it.cloud.isFinite() }.sortedBy { it.time }
    val d = data.getJSONObject("daily"); val dates = d.getJSONArray("time")
    val solar = (0 until dates.length()).map { SolarDay(dates.getLong(it) * 1000,
        d.getJSONArray("sunrise").getLong(it) * 1000, d.getJSONArray("sunset").getLong(it) * 1000) }.sortedBy { it.start }
    require(result.isNotEmpty() && solar.isNotEmpty())
    return result to solar
}
