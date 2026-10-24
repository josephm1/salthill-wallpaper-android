package ie.salthill.wallpaper

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ForecastClientTest {
    private val hour = 3_600_000L
    private val base = 1_760_000_400_000L
    private val weatherA = "https://test/weather?place=A"
    private val tideA = "https://test/tide?place=A"
    private val weatherB = "https://test/weather?place=B"
    private val tideB = "https://test/tide?place=B"
    private class Storage { var text: String? = null; fun cache(capacity: Int = 8) = ForecastCache({ text }, { text = it }, capacity) }
    private fun weather(now: Long, temperature: Double = 12.0): JSONObject {
        val start = now / hour * hour
        val day = now / 86_400_000 * 86_400_000
        return JSONObject().put("hourly", JSONObject()
            .put("time", JSONArray((0..72).map { (start + it * hour) / 1000 }))
            .put("apparent_temperature", JSONArray((0..72).map { temperature }))
            .put("wind_speed_10m", JSONArray((0..72).map { 4 }))
            .put("cloud_cover", JSONArray((0..72).map { 80 })))
            .put("daily", JSONObject().put("time", JSONArray((0..3).map { (day + it * 86_400_000) / 1000 }))
                .put("sunrise", JSONArray((0..3).map { (day + it * 86_400_000 + 7 * hour) / 1000 }))
                .put("sunset", JSONArray((0..3).map { (day + it * 86_400_000 + 18 * hour) / 1000 })))
    }
    private fun marine(now: Long, level: Double = 0.0, missing: Boolean = false): JSONObject {
        val start = now / hour * hour - 6 * hour
        return JSONObject().put("hourly", JSONObject()
            .put("time", JSONArray((0..78).map { (start + it * hour) / 1000 }))
            .put("sea_level_height_msl", JSONArray((0..78).map { if (missing) JSONObject.NULL else level + it * .01 })))
    }
    @Test fun twelveHoursVisiblePerDayMakesNinetyEightCallsPerWeek() {
        var now = base; val requests = mutableListOf<String>(); val storage = Storage()
        val client = ForecastClient(storage.cache(), { url ->
            requests.add(url); if ("/weather" in url) weather(now) else marine(now)
        }, { now })
        for (day in 0..6) {
            var state = client.cached(weatherA, tideA)
            for (minute in 0 until 720) {
                now = base + day * 24 * hour + minute * 60_000L
                if (state.needsRefresh(now)) state = client.refresh(weatherA, tideA, state) { true }
            }
        }
        assertEquals(84, requests.count { "/weather" in it })
        assertEquals(14, requests.count { "/tide" in it })
    }
    @Test fun returningToSavedPlaceAfterRestartReusesItsSeparateForecasts() {
        val storage = Storage(); val requests = mutableListOf<String>()
        fun client() = ForecastClient(storage.cache(), { url ->
            requests.add(url)
            if ("/weather" in url) weather(base, if (url == weatherB) 22.0 else 12.0)
            else marine(base, if (url == tideB) 10.0 else 0.0)
        }, { base })
        var c = client()
        c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        c.refresh(weatherB, tideB, c.cached(weatherB, tideB)) { true }
        c = client()
        val a = c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        assertEquals(4, requests.size)
        assertEquals(12.0, a.hours.first().temperature, 0.0)
        assertTrue(a.tides.first().height < 1)
        assertEquals(22.0, c.cached(weatherB, tideB).hours.first().temperature, 0.0)
    }
    @Test fun changingTideEndpointDoesNotRefetchWeatherOrMixLocationsOnFailure() {
        val storage = Storage(); val requests = mutableListOf<String>(); var failB = false
        fun client() = ForecastClient(storage.cache(), { url ->
            requests.add(url)
            if (url == tideB && failB) throw java.io.IOException("offline")
            if ("/weather" in url) weather(base, if (url == weatherB) 22.0 else 12.0) else marine(base)
        }, { base })
        var c = client(); c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        failB = true
        c.refresh(weatherA, tideB, c.cached(weatherA, tideB)) { true }
        assertEquals(listOf(weatherA, tideA, tideB), requests)
        c.refresh(weatherB, tideB, c.cached(weatherB, tideB)) { true }
        c = client()
        val b = c.cached(weatherB, tideB)
        assertEquals(22.0, b.hours.first().temperature, 0.0)
        assertTrue(b.tides.isEmpty())
        assertTrue(c.cached(weatherA, tideA).tides.isNotEmpty())
    }
    @Test fun failuresPersistIncreasingBackoffKeepStaleDataAndResetAfterSuccess() {
        val storage = Storage(); var now = base; var fail = false; var requests = 0
        fun client() = ForecastClient(storage.cache(), { url ->
            requests++; if (fail) throw java.io.IOException("offline")
            if ("/weather" in url) weather(now) else marine(now)
        }, { now })
        var c = client(); c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        fail = true; now += hour
        var state = c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        assertEquals(1, state.weatherEntry!!.failures)
        assertTrue(state.hours.isNotEmpty())
        assertEquals(now + 300_000, state.weatherEntry!!.retryAt)
        c = client(); now += 240_000
        c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        assertEquals(3, requests)
        now += 60_000
        state = c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        assertEquals(now + 600_000, state.weatherEntry!!.retryAt)
        fail = false; now += 600_000
        state = c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        assertEquals(0, state.weatherEntry!!.failures)
        assertFalse(state.weatherFailed)
        assertEquals(0, state.weatherEntry!!.retryAt)
    }
    @Test fun rateLimitDeadlineSurvivesRestartAndSupportsBothHeaderFormats() {
        assertEquals(base + 120_000, retryAfter("120", base))
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(base + hour).atZone(ZoneOffset.UTC))
        assertEquals(base + hour, retryAfter(date, base))
        assertEquals(0, retryAfter("invalid", base))
        val storage = Storage(); var now = base; var requests = 0
        var c = ForecastClient(storage.cache(), { requests++; throw ForecastHttpException(429, now + 2 * hour) }, { now })
        var state = c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        assertEquals(now + 2 * hour, state.weatherEntry!!.retryAt)
        now += hour
        c = ForecastClient(storage.cache(), { requests++; throw AssertionError("Must retain backoff") }, { now })
        state = c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        assertEquals(2, requests)
        assertEquals(base + 2 * hour, state.tideEntry!!.retryAt)
    }
    @Test fun hiddenSceneStartsNoRequestsIncludingTheSecondRequestOfAPair() {
        var active = false; var requests = 0; val storage = Storage()
        val c = ForecastClient(storage.cache(), { requests++; active = false; weather(base) }, { base })
        val initial = c.cached(weatherA, tideA)
        c.refresh(weatherA, tideA, initial) { active }; assertEquals(0, requests)
        active = true
        c.refresh(weatherA, tideA, initial) { active }; assertEquals(1, requests)
    }
    @Test fun unsupportedMarineCoverageIsNegativeCachedForSixHours() {
        val storage = Storage(); var now = base; var tideCalls = 0
        val c = ForecastClient(storage.cache(), { url ->
            if (url == weatherA) weather(now) else { tideCalls++; marine(now, missing = true) }
        }, { now })
        var state = c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        assertTrue(state.tides.isEmpty())
        assertEquals(base + 6 * hour, state.tideEntry!!.retryAt)
        now += hour
        state = c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        assertEquals(1, tideCalls)
        assertTrue(state.hours.isNotEmpty())
    }
    @Test fun expiredCoverageRefreshesEvenWhenDownloadTimestampIsFresh() {
        val storage = Storage(); val cache = storage.cache(); var requests = 0
        cache.success(weatherA, weather(base - 4 * 24 * hour).toString(), base)
        cache.success(tideA, marine(base).toString(), base)
        val c = ForecastClient(cache, { requests++; weather(base) }, { base })
        assertFalse(c.cached(weatherA, tideA).weatherCovered(base))
        val state = c.refresh(weatherA, tideA, c.cached(weatherA, tideA)) { true }
        assertTrue(state.weatherCovered(base)); assertEquals(1, requests)
    }
    @Test fun cacheIsBoundedRecoversCorruptionAndDoesNotExposeEndpointSecrets() {
        val storage = Storage(); storage.text = "{corrupted"
        val cache = storage.cache(2)
        val secret = "https://custom/tides?token=private"
        cache.success(secret, "A", base); cache.success(weatherA, "B", base)
        cache.get(secret); cache.success(weatherB, "C", base)
        assertNull(cache.get(weatherA)); assertEquals("A", cache.get(secret)!!.body)
        assertFalse(storage.text!!.contains("private"))
        assertEquals("C", storage.cache(2).get(weatherB)!!.body)
        assertEquals(listOf(5,10,20,40,60,60,60).map { it * 60_000L }, (1..7).map(::retryDelay))
        assertTrue(shouldFetch(ForecastEntry(body = "old", fetchedAt = base + hour,
            attemptedAt = base + hour, retryAt = base + 2 * hour), base, hour, true))
    }
    @Test fun legacyImportDoesNotOverwriteANewerCacheOrItsBackoff() {
        val storage = Storage(); val cache = storage.cache()
        cache.importLegacy(weatherA, weather(base).toString(), base)
        val saved = cache.failure(weatherA, base + hour)
        cache.importLegacy(weatherA, "old", base - hour)
        val restored = storage.cache().get(weatherA)!!
        assertEquals(saved, restored)
    }
}
