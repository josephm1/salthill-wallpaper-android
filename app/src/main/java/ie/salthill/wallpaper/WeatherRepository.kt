package ie.salthill.wallpaper

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors

/** Shared by wallpaper, preview and screensaver. Network/disk work only on one worker, with no hidden polling. */
class WeatherRepository private constructor(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>()
    private var state = ForecastSnapshot()
    private var busy = false
    @Volatile private var activeSessions = 0
    private var loaded = false
    @Volatile private var source = ""
    // First touched on the worker, including SharedPreferences loading and JSON decoding.
    private val diskCache by lazy {
        val prefs = context.getSharedPreferences("forecast-v2", Context.MODE_PRIVATE)
        ForecastCache({ prefs.getString("data", null) }, { prefs.edit().putString("data", it).apply() })
    }
    private val client by lazy { ForecastClient(diskCache, ::fetch) }
    var status: String = "Loading saved weather…"
        private set

    val hasForecast: Boolean get() = state.hours.isNotEmpty()
    val lastUpdatedAt: Long get() = state.weatherEntry?.fetchedAt ?: 0
    fun frame(now: Long = System.currentTimeMillis()) =
        frameAt(state.hours, state.days, state.tides, now, smoothTides = !state.hourlyTides)
    fun subscribe(listener: () -> Unit) { listeners.add(listener); activeSessions = listeners.size; refresh() }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener); activeSessions = listeners.size }
    private fun publish() { listeners.toList().forEach { it() } }
    private fun sourceKey(settings: SceneSettings) = settings.locationKey + "|" + settings.tideUrl
    private fun current(key: String) = source == key && sourceKey(SceneSettings.read(context)) == key

    fun refresh() {
        if (listeners.isEmpty()) return
        val settings = SceneSettings.read(context)
        val key = sourceKey(settings)
        if (source != key) {
            source = key; loaded = false; state = ForecastSnapshot()
            status = "Loading weather…"; publish()
        }
        if (busy || (loaded && !state.needsRefresh(System.currentTimeMillis()))) return
        val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=${settings.latitude}&longitude=${settings.longitude}" +
            "&hourly=apparent_temperature,rain,showers,snowfall,cloud_cover,visibility,wind_speed_10m,wind_direction_10m" +
            "&daily=sunrise,sunset&wind_speed_unit=ms&timeformat=unixtime&timezone=auto&forecast_days=3"
        val tideUrl = settings.tideUrl.ifBlank {
            "https://marine-api.open-meteo.com/v1/marine?latitude=${settings.latitude}&longitude=${settings.longitude}" +
                "&hourly=sea_level_height_msl&timeformat=unixtime&timezone=GMT&forecast_days=3&past_hours=6&cell_selection=sea"
        }
        busy = true
        worker.execute {
            // Import the old single-place cache without discarding saved forecasts on upgrade.
            val legacy = context.getSharedPreferences("forecast", Context.MODE_PRIVATE)
            if (legacy.getString("source", "") == key) {
                diskCache.importLegacy(weatherUrl, legacy.getString("weather", null), legacy.getLong("weatherTime", 0))
                diskCache.importLegacy(tideUrl, legacy.getString("tides", null), legacy.getLong("tideTime", 0))
            }
            val cached = client.cached(weatherUrl, tideUrl)
            main.post {
                if (current(key)) { state = cached; updateStatus(false); publish() }
            }
            val updated = client.refresh(weatherUrl, tideUrl, cached) { activeSessions > 0 && source == key }
            main.post {
                busy = false
                if (current(key)) {
                    state = updated; loaded = true
                    updateStatus(updated.weatherEntry?.fetchedAt != cached.weatherEntry?.fetchedAt)
                    publish()
                } else refresh()
            }
        }
    }

    private fun updateStatus(updated: Boolean) {
        status = when {
            !hasForecast -> "Weather unavailable · showing preview"
            state.weatherFailed -> "Offline · showing saved weather"
            updated -> "Weather updated · ${Instant.ofEpochMilli(lastUpdatedAt)}"
            else -> "Saved weather · ${Instant.ofEpochMilli(lastUpdatedAt)}"
        } + if (state.tideCovered(System.currentTimeMillis())) "\nSea level: cached forecast"
            else "\nSea level unavailable · neutral level"
    }

    private fun fetch(address: String): JSONObject {
        require(address.startsWith("https://"))
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        try {
            val code = connection.responseCode
            if (code != 200) throw ForecastHttpException(code,
                retryAfter(connection.getHeaderField("Retry-After"), System.currentTimeMillis()))
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_000_000)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak") // Only the application context is retained.
        @Volatile private var instance: WeatherRepository? = null
        fun get(context: Context): WeatherRepository = instance ?: synchronized(this) {
            instance ?: WeatherRepository(context.applicationContext).also { instance = it }
        }
    }
}
