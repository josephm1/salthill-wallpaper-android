package ie.salthill.wallpaper

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.TextClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Test fun legacyDefaultMigratesToSalthillButCustomPlacesSurvive() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("latitude", "54.9596").putString("longitude", "-7.734439").commit()
        assertEquals(SceneSettings.SALTHILL, SceneSettings.read(context).locationMode)
        assertEquals(SceneSettings.SALTHILL_LATITUDE, SceneSettings.read(context).latitude, .00001)
        prefs.edit().clear().putString("latitude", "51.5").putString("longitude", "-0.12").commit()
        assertEquals(SceneSettings.SAVED, SceneSettings.read(context).locationMode)
        assertEquals(51.5, SceneSettings.read(context).latitude, .00001)
    }

    @Test fun materialControlsSavePresetAndMotionAndUseDublinTime() {
        SceneSettings(51.5,-0.12,15, "").save(context)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.findViewById<MaterialButton>(R.id.location_salthill).performClick()
                it.findViewById<MaterialButton>(R.id.motion_economy).performClick()
                assertEquals("Europe/Dublin", it.findViewById<TextClock>(R.id.local_clock).timeZone)
            }
            val settings = SceneSettings.read(context)
            assertEquals(SceneSettings.SALTHILL, settings.locationMode)
            assertEquals(SceneSettings.SALTHILL_LATITUDE, settings.latitude, .00001)
            assertEquals(10, settings.fps)
        }
    }

    @Test @Suppress("DEPRECATION") @android.annotation.SuppressLint("MissingPermission")
    fun approximateOneOffLocationSavesAPlaceAndCanSwitchBackToSalthill() {
        shell("pm grant ${context.packageName} android.permission.ACCESS_COARSE_LOCATION")
        shell("appops set ${context.packageName} android:mock_location allow")
        val manager = context.getSystemService(LocationManager::class.java)
        manager.addTestProvider(LocationManager.NETWORK_PROVIDER, false,false,false,false,false,false,false,Criteria.POWER_LOW,Criteria.ACCURACY_COARSE)
        manager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        try {
            val fix = Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = 53.26; longitude = -9.09; accuracy = 1000f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            manager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, fix)
            SceneSettings.read(context).atSalthill().save(context)
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<MaterialButton>(R.id.location_current).performClick() }
                val deadline = System.currentTimeMillis() + 8000
                while (SceneSettings.read(context).locationMode != SceneSettings.CURRENT && System.currentTimeMillis() < deadline) Thread.sleep(100)
                val result = SceneSettings.read(context)
                assertEquals(SceneSettings.CURRENT, result.locationMode)
                assertTrue(result.locationUpdatedAt > 0)
                assertEquals(53.26, result.latitude, .1) // Approximate permission deliberately permits fuzzing.
                scenario.onActivity {
                    assertNull(it.findViewById<TextClock>(R.id.local_clock).timeZone)
                    it.findViewById<MaterialButton>(R.id.location_salthill).performClick()
                }
                assertEquals(SceneSettings.SALTHILL, SceneSettings.read(context).locationMode)
            }
        } finally {
            manager.removeTestProvider(LocationManager.NETWORK_PROVIDER)
            shell("appops set ${context.packageName} android:mock_location default")
        }
    }
}
