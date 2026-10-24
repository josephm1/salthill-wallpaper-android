package ie.salthill.wallpaper

import kotlin.math.PI
import kotlin.math.cos

/** One phase for both water draws. Hidden time is deliberately excluded. */
class WaveClock {
    var phase = 0.0
        private set
    fun advance(elapsedSeconds: Double, pixelsPerSecond: Double) {
        phase = ((phase + elapsedSeconds.coerceIn(0.0, 0.25) * pixelsPerSecond) % 612.0 + 612.0) % 612.0
    }
}

/** A brief meteor on the existing visible-time clock; no independent timer or allocations. */
internal class ShootingStarClock {
    private var elapsed = 0.0
    var progress = 0f
        private set
    var alpha = 0
        private set
    val visible: Boolean get() = alpha > 0
    fun advance(seconds: Double, night: Boolean) {
        if (!night) { elapsed = 0.0; progress = 0f; alpha = 0; return }
        elapsed = (elapsed + seconds.coerceIn(0.0, .25)) % 30.0
        // First streak appears four visible seconds after night starts, then every thirty seconds.
        val time = elapsed - 4.0
        if (time < 0 || time >= .65) { progress = 0f; alpha = 0; return }
        progress = (time / .65).toFloat()
        alpha = (kotlin.math.sin(PI * progress) * 255).toInt().coerceIn(0, 255)
    }
}

/** Distant swell: wind controls amplitude/period, with continuous visible-time phase. */
internal class BoatMotion {
    private var strength = 0.0
    private var target = 0.0
    var phase = 0.0
        private set
    var offsetY = 0f
        private set
    var roll = 0f
        private set
    fun setWind(speed: Double) {
        target = (if (speed.isFinite()) speed else 0.0).coerceIn(0.0, 18.0) / 18.0
    }
    fun advance(seconds: Double) {
        val dt = seconds.coerceIn(0.0, .25)
        strength += (target - strength) * (dt * .7).coerceAtMost(1.0)
        phase = (phase + dt * (.65 + strength * .9)) % (2 * PI)
        offsetY = (kotlin.math.sin(phase) * (.15 + strength * strength * 1.65)).toFloat()
        roll = (kotlin.math.sin(phase + .7) * (.2 + strength * 3.8)).toFloat()
    }
}

data class Tide(val time: Long, val height: Double)
data class TideState(val height: Double, val rising: Boolean)
fun tideAt(tides: List<Tide>, now: Long, smooth: Boolean = true): TideState? {
    val before = tides.lastOrNull { it.time <= now } ?: return null
    val after = tides.firstOrNull { it.time > now } ?: return null
    if (!smooth && after.time - before.time > 3 * 3_600_000L) return null
    val fraction = (now - before.time).toDouble() / (after.time - before.time)
    val blend = if (smooth) (1.0 - cos(PI * fraction)) / 2.0 else fraction
    return TideState(before.height + (after.height - before.height) * blend, after.height > before.height)
}

enum class Theme { DAY, GREY, SUNSET, NIGHT }
data class WeatherHour(val time: Long, val temperature: Double, val wind: Double, val direction: Double,
                       val cloud: Double, val rain: Double, val snow: Double, val visibility: Double)
data class SolarDay(val start: Long, val sunrise: Long, val sunset: Long)
data class FrameWeather(val theme: Theme = Theme.DAY, val temperature: Double = 12.0,
                        val wind: Double = 3.0, val direction: Double = 250.0,
                        val rain: Double = 0.0, val snow: Double = 0.0,
                        val visibility: Double = 10000.0, val tide: TideState? = null)

fun themeAt(sunrise: Long, sunset: Long, now: Long, cloud: Double): Theme = when {
    kotlin.math.abs(now - sunrise) <= 900_000 || kotlin.math.abs(now - sunset) <= 900_000 -> Theme.SUNSET
    now <= sunrise || now >= sunset -> Theme.NIGHT
    cloud > 70 -> Theme.GREY
    else -> Theme.DAY
}

fun frameAt(hours: List<WeatherHour>, days: List<SolarDay>, tides: List<Tide>, now: Long, smoothTides: Boolean = true): FrameWeather {
    val hour = hours.lastOrNull { it.time <= now } ?: hours.firstOrNull() ?: return FrameWeather(tide = tideAt(tides, now, smoothTides))
    val day = days.lastOrNull { it.start <= now } ?: days.firstOrNull() ?: return FrameWeather(tide = tideAt(tides, now, smoothTides))
    return FrameWeather(themeAt(day.sunrise, day.sunset, now, hour.cloud), hour.temperature, hour.wind,
        hour.direction, hour.rain, hour.snow, hour.visibility, tideAt(tides, now, smoothTides))
}
