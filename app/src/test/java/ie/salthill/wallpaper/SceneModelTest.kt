package ie.salthill.wallpaper

import org.junit.Assert.*
import org.junit.Test

class SceneModelTest {
    @Test fun shootingStarIsBriefRepeatsAndOnlyAdvancesWithTheVisibleNightClock() {
        val meteor = ShootingStarClock()
        repeat(16) { meteor.advance(.25, true) }
        assertFalse(meteor.visible)
        meteor.advance(.25, true)
        assertTrue(meteor.visible)
        assertTrue(meteor.progress in 0f..1f)
        val progress = meteor.progress
        meteor.advance(0.0, true) // First resumed frame excludes hidden elapsed time.
        assertEquals(progress, meteor.progress, 0f)
        meteor.advance(.25, true)
        meteor.advance(.25, true)
        assertFalse(meteor.visible)
        repeat(118) { meteor.advance(.25, true) }
        assertTrue(meteor.visible)
        meteor.advance(.1, false)
        assertFalse(meteor.visible)
        repeat(15) { meteor.advance(.25, true) }
        assertFalse(meteor.visible)
    }
    @Test fun strongerWindIncreasesDistantBoatSwellWithoutRestartingItsPhase() {
        val calm = BoatMotion().apply { setWind(0.0) }
        val windy = BoatMotion().apply { setWind(18.0) }
        var calmBob = 0f; var windyBob = 0f; var calmRoll = 0f; var windyRoll = 0f
        repeat(300) { i ->
            calm.advance(.1); windy.advance(.1)
            if (i > 100) {
                calmBob = maxOf(calmBob, kotlin.math.abs(calm.offsetY))
                windyBob = maxOf(windyBob, kotlin.math.abs(windy.offsetY))
                calmRoll = maxOf(calmRoll, kotlin.math.abs(calm.roll))
                windyRoll = maxOf(windyRoll, kotlin.math.abs(windy.roll))
            }
        }
        assertTrue(windyBob > calmBob * 5)
        assertTrue(windyRoll > calmRoll * 5)
        assertTrue(windyBob <= 1.8f && windyRoll <= 4f)
        val phase = windy.phase; val offset = windy.offsetY
        windy.setWind(2.0)
        assertEquals(phase, windy.phase, 0.0)
        windy.advance(0.0)
        assertEquals(phase, windy.phase, 0.0)
        assertEquals(offset, windy.offsetY, 0f)
        windy.setWind(Double.NaN); windy.advance(.25)
        assertTrue(windy.offsetY.isFinite() && windy.roll.isFinite())
    }
    @Test fun reverseWaterWrapsIndefinitelyWithoutASecondClock() {
        val clock = WaveClock()
        repeat(10000) { clock.advance(.1, -12.24) }
        assertTrue(clock.phase >= 0 && clock.phase < 612)
        val before = clock.phase
        clock.advance(.1, 12.24)
        assertEquals((before + 1.224) % 612, clock.phase, .000001)
    }
    @Test fun aPauseCannotJumpTheWaterByHours() {
        val clock = WaveClock()
        clock.advance(3600.0, 12.24)
        assertEquals(3.06, clock.phase, .000001)
        clock.advance(0.0, 12.24)
        assertEquals(3.06, clock.phase, .000001)
    }
    @Test fun solarTransitionsIncludeSunriseAndSunset() {
        val sunrise = 1_000_000L; val sunset = 40_000_000L
        assertEquals(Theme.NIGHT, themeAt(sunrise, sunset, sunrise - 1_000_000, 10.0))
        assertEquals(Theme.SUNSET, themeAt(sunrise, sunset, sunrise, 10.0))
        assertEquals(Theme.SUNSET, themeAt(sunrise, sunset, sunset, 10.0))
        assertEquals(Theme.GREY, themeAt(sunrise, sunset, sunrise + 2_000_000, 90.0))
        assertEquals(Theme.DAY, themeAt(sunrise, sunset, sunrise + 2_000_000, 30.0))
    }
    @Test fun shortTideArraysAreSufficientAndMissingBracketsReturnNull() {
        val tides = listOf(Tide(1000, 1.0), Tide(3000, 3.0))
        assertEquals(2.0, tideAt(tides, 2000)!!.height, .000001)
        assertTrue(tideAt(tides, 2000)!!.rising)
        assertNull(tideAt(tides, 4000))
        assertNull(tideAt(emptyList(), 2000))
        assertFalse(tideAt(listOf(Tide(1000, 3.0), Tide(3000, 1.0)), 2000)!!.rising)
    }
    @Test fun forecastChoosesPastHourAndTheNewSolarDay() {
        val hours = listOf(WeatherHour(0,10.0,3.0,250.0,30.0,1.0,0.0,1000.0), WeatherHour(10000,20.0,8.0,250.0,30.0,0.0,1.0,10000.0))
        val solar = listOf(SolarDay(0,1000,4000000), SolarDay(10000,20000,5000000))
        assertEquals(10.0, frameAt(hours,solar,emptyList(),9999).temperature,0.0)
        assertEquals(20.0, frameAt(hours,solar,emptyList(),10001).temperature,0.0)
        assertEquals(1.0, frameAt(hours,solar,emptyList(),10001).snow,0.0)
    }
    @Test fun hourlySeaLevelsInterpolateWithoutPretendingEachSampleIsAnExtreme() {
        val samples = listOf(Tide(0, -1.0), Tide(3_600_000, 1.0))
        assertEquals(-.5, tideAt(samples, 900_000, smooth = false)!!.height, .00001)
        assertTrue(tideAt(samples, 900_000, smooth = false)!!.rising)
        val falling = listOf(Tide(0, 1.0), Tide(3_600_000, -1.0))
        assertFalse(tideAt(falling, 900_000, smooth = false)!!.rising)
    }
    @Test fun missingHoursDoNotInventADayLongTideCurve() {
        assertNull(tideAt(listOf(Tide(0, -1.0), Tide(86_400_000, 1.0)), 900_000, smooth = false))
    }
}
