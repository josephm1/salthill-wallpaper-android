package ie.salthill.wallpaper

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneLifecycleTest {
    @Test fun rendersAtBoundedRateStopsWhileHiddenAndResumes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = System.currentTimeMillis()
        val t = now / 1000
        val data = JSONObject().put("hourly", JSONObject()
            .put("time", JSONArray().put(t - 3600).put(t).put(t + 3600))
            .put("apparent_temperature", JSONArray().put(12).put(12).put(12))
            .put("wind_speed_10m", JSONArray().put(3).put(3).put(3))
            .put("cloud_cover", JSONArray().put(30).put(30).put(30)))
            .put("daily", JSONObject().put("time", JSONArray().put(t - 43200))
                .put("sunrise", JSONArray().put(t - 3600)).put("sunset", JSONArray().put(t + 3600)))
        SceneSettings(54.9596, -7.734439, 15, "").save(context)
        context.getSharedPreferences("forecast", Context.MODE_PRIVATE).edit()
            .putString("source", "54.9596,-7.734439|").putString("weather", data.toString()).putLong("weatherTime", now).commit()
        ActivityScenario.launch(PreviewActivity::class.java).use { scenario ->
            fun count(): Long { var value = 0L; scenario.onActivity { value = it.scene.frameCount() }; return value }
            val deadline = System.currentTimeMillis() + 15000
            while (count() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Expected native surface frames", count() >= 3)
            val before = count()
            Thread.sleep(1000)
            val drawn = count() - before
            assertTrue("Must respect the 15fps frame budget: $drawn frames", drawn in 1..18)
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(250)
            val stopped = count()
            Thread.sleep(400)
            assertEquals("Hidden activity must draw no frames", stopped, count())
            scenario.moveToState(Lifecycle.State.RESUMED)
            val resumeDeadline = System.currentTimeMillis() + 5000
            while (count() == stopped && System.currentTimeMillis() < resumeDeadline) Thread.sleep(100)
            assertTrue("Native animation should resume", count() > stopped)
        }
    }
}
