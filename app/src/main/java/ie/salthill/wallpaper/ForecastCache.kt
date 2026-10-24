package ie.salthill.wallpaper

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal const val WEATHER_CACHE_MS = 3_600_000L
internal const val TIDE_CACHE_MS = 21_600_000L

internal data class ForecastEntry(
    val body: String? = null, val fetchedAt: Long = 0, val failures: Int = 0,
    val retryAt: Long = 0, val attemptedAt: Long = 0,
)

internal fun shouldFetch(entry: ForecastEntry?, now: Long, interval: Long, covered: Boolean): Boolean {
    if (entry == null) return true
    // A clock adjustment must not leave a future retry deadline stuck for hours.
    if (now >= entry.attemptedAt && now < entry.retryAt) return false
    return entry.fetchedAt <= 0 || entry.fetchedAt > now || now - entry.fetchedAt >= interval || !covered
}

internal fun retryDelay(failures: Int): Long = 300_000L * (1L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(12)

internal fun retryAfter(header: String?, now: Long): Long {
    val value = header?.trim() ?: return 0
    value.toLongOrNull()?.let { seconds ->
        if (seconds < 0) return 0
        return now + seconds.coerceAtMost((Long.MAX_VALUE - now) / 1000) * 1000
    }
    return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        .getOrDefault(0).coerceAtLeast(0)
}

internal class ForecastHttpException(val code: Int, val retryAt: Long) : Exception("Forecast HTTP $code")

/** Worker-thread owned. One bounded, atomic disk record; separate identities per endpoint/location. */
internal class ForecastCache(read: () -> String?, private val write: (String) -> Unit, private val capacity: Int = 8) {
    private val entries = LinkedHashMap<String, ForecastEntry>()
    init {
        require(capacity > 0)
        runCatching {
            val array = JSONObject(read() ?: "{}").optJSONArray("entries") ?: JSONArray()
            for (i in 0 until array.length()) runCatching {
                val item = array.getJSONObject(i)
                val key = item.getString("key")
                if (key.length == 64) entries[key] = ForecastEntry(
                    if (item.isNull("body")) null else item.getString("body"), item.optLong("time"),
                    item.optInt("failures").coerceIn(0, 32), item.optLong("retry"), item.optLong("attempt"))
            }
            trim()
        }
    }
    private fun key(address: String) = MessageDigest.getInstance("SHA-256")
        .digest(address.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    fun get(address: String): ForecastEntry? {
        val k = key(address)
        val value = entries.remove(k) ?: return null
        entries[k] = value
        return value
    }
    fun success(address: String, body: String, now: Long): ForecastEntry =
        store(address, ForecastEntry(body = body, fetchedAt = now))
    fun failure(address: String, now: Long, earliestRetry: Long = 0): ForecastEntry {
        val old = get(address) ?: ForecastEntry()
        val failures = (old.failures + 1).coerceAtMost(32)
        return store(address, old.copy(failures = failures, attemptedAt = now,
            retryAt = maxOf(now + retryDelay(failures), earliestRetry)))
    }
    fun importLegacy(address: String, body: String?, time: Long) {
        if (body != null && time > 0 && get(address) == null) success(address, body, time)
    }
    private fun trim() { while (entries.size > capacity) entries.remove(entries.keys.first()) }
    private fun store(address: String, value: ForecastEntry): ForecastEntry {
        val k = key(address); entries.remove(k); entries[k] = value; trim()
        val array = JSONArray()
        for ((id, entry) in entries) array.put(JSONObject().put("key", id)
            .put("body", entry.body ?: JSONObject.NULL).put("time", entry.fetchedAt)
            .put("failures", entry.failures).put("retry", entry.retryAt).put("attempt", entry.attemptedAt))
        write(JSONObject().put("entries", array).toString())
        return value
    }
}
