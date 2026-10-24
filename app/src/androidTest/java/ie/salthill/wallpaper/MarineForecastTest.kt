package ie.salthill.wallpaper

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarineForecastTest {
    @Test fun marineParserSkipsNullsAndKeepsHourlySamples() {
        val json = JSONObject("""{"hourly":{"time":[0,3600,7200,10800],"sea_level_height_msl":[null,-1,1,null]}}""")
        val forecast = parseTideForecast(json)
        assertTrue(forecast.hourly)
        assertEquals(2, forecast.samples.size)
        assertEquals(0.0, tideAt(forecast.samples, 5_400_000, smooth = false)!!.height, .0001)
        val legacy = parseTideForecast(JSONObject("""{"data":[{"time":"2026-10-22T08:00:00Z","height":1.0},{"time":"2026-10-22T14:00:00Z","height":3.0}]}"""))
        assertFalse(legacy.hourly)
        assertEquals(2, legacy.samples.size)
    }

    @Test fun cachedMarineDataDrivesCrestWithoutCustomEndpoint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val now = System.currentTimeMillis(); val t = now / 1000
        val settings = SceneSettings(55.1, -10.1, 15, "")
        settings.save(context)
        val weatherJSON = JSONObject().put("hourly", JSONObject()
            .put("time", JSONArray().put(t - 3600)).put("apparent_temperature", JSONArray().put(12))
            .put("wind_speed_10m", JSONArray().put(3)).put("cloud_cover", JSONArray().put(30)))
            .put("daily", JSONObject().put("time", JSONArray().put(t - 43200))
                .put("sunrise", JSONArray().put(t - 3600)).put("sunset", JSONArray().put(t + 3600)))
        val marineJSON = JSONObject().put("hourly", JSONObject().put("time", JSONArray().put(t - 3600).put(t + 3600))
            .put("sea_level_height_msl", JSONArray().put(-1).put(1)))
        context.getSharedPreferences("forecast", Context.MODE_PRIVATE).edit().clear()
            .putString("source", settings.locationKey + "|").putString("weather", weatherJSON.toString())
            .putString("tides", marineJSON.toString()).putLong("weatherTime", now).putLong("tideTime", now).commit()
        val repository = WeatherRepository.get(context)
        val listener: () -> Unit = {}
        var tide: TideState? = null
        try {
            instrumentation.runOnMainSync { repository.subscribe(listener) }
            val deadline = System.currentTimeMillis() + 8000
            do {
                instrumentation.runOnMainSync { tide = repository.frame().tide }
                if (tide == null) Thread.sleep(100)
            } while (tide == null && System.currentTimeMillis() < deadline)
            assertNotNull("Default Marine cache must reach the renderer's tide model", tide)
            assertTrue(tide!!.rising)
            assertEquals(0.0, tide!!.height, .02)
        } finally { instrumentation.runOnMainSync { repository.unsubscribe(listener) } }
    }
}
