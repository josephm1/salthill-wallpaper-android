package ie.salthill.wallpaper

import android.Manifest
import android.app.WallpaperManager
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {
    private val weather by lazy { WeatherRepository.get(this) }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var chosen: SceneSettings
    private lateinit var locationChoices: MaterialButtonToggleGroup
    private lateinit var motionChoices: MaterialButtonToggleGroup
    private lateinit var tide: TextInputEditText
    private var updatingViews = false
    private var locationSignal: CancellationSignal? = null
    private var locationTimeout: Runnable? = null
    private val update: () -> Unit = { renderWeather() }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate()
        else {
            renderLocation()
            Snackbar.make(findViewById(R.id.settings_root), "Location permission wasn’t granted. Salthill is available without it.", Snackbar.LENGTH_LONG)
                .setAction("App settings") { launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }.show()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_settings)
        chosen = SceneSettings.read(this)
        locationChoices = findViewById(R.id.location_choices)
        motionChoices = findViewById(R.id.motion_choices)
        tide = findViewById(R.id.tide_url)
        tide.setText(chosen.tideUrl)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_scroll)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        renderLocation()
        updatingViews = true
        motionChoices.check(when (chosen.fps) { 10 -> R.id.motion_economy; 30 -> R.id.motion_smooth; else -> R.id.motion_balanced })
        updatingViews = false
        renderMotion()
        locationChoices.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !updatingViews) {
                if (id == R.id.location_salthill) {
                    cancelLocation(); chosen = chosen.atSalthill(); persist(); renderLocation()
                } else requestLocation()
            }
        }
        motionChoices.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !updatingViews) {
                chosen = chosen.copy(fps = when (id) { R.id.motion_economy -> 10; R.id.motion_smooth -> 30; else -> 15 })
                persist(); renderMotion()
            }
        }
        findViewById<MaterialButton>(R.id.location_refresh).setOnClickListener { requestLocation() }
        findViewById<MaterialButton>(R.id.preview_scene).setOnClickListener { if (saveTides()) startActivity(Intent(this, PreviewActivity::class.java)) }
        findViewById<MaterialButton>(R.id.set_wallpaper).setOnClickListener {
            if (saveTides()) launch(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, SalthillWallpaper::class.java)))
        }
        findViewById<MaterialButton>(R.id.set_screensaver).setOnClickListener { if (saveTides()) launch(Intent(Settings.ACTION_DREAM_SETTINGS)) }
        findViewById<MaterialButton>(R.id.advanced_toggle).setOnClickListener {
            findViewById<View>(R.id.advanced_content).let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        findViewById<MaterialButton>(R.id.save_tide).setOnClickListener { if (saveTides()) message("Tide settings saved") }
        findViewById<MaterialButton>(R.id.attribution).setOnClickListener { launch(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open-meteo.com/"))) }
        renderWeather()
    }

    private fun persist() { chosen.save(this); weather.refresh(); renderWeather() }
    private fun renderLocation() {
        updatingViews = true
        if (chosen.locationMode == SceneSettings.SALTHILL) locationChoices.check(R.id.location_salthill)
        else if (chosen.locationMode == SceneSettings.CURRENT) locationChoices.check(R.id.location_current)
        else { locationChoices.isSelectionRequired = false; locationChoices.clearChecked(); locationChoices.isSelectionRequired = true }
        updatingViews = false
        findViewById<TextView>(R.id.location_summary).text = when (chosen.locationMode) {
            SceneSettings.SALTHILL -> "Salthill, Galway"
            SceneSettings.CURRENT -> "My current location"
            else -> "Your saved weather location"
        }
        findViewById<TextView>(R.id.location_detail).text = if (chosen.locationMode == SceneSettings.CURRENT) {
            "Approximate location saved. Update it when you travel; no background tracking."
        } else "Time is automatic. Sunrise and sunset follow this weather location."
        findViewById<View>(R.id.location_refresh).visibility = if (chosen.locationMode == SceneSettings.CURRENT) View.VISIBLE else View.GONE
        findViewById<TextClock>(R.id.local_clock).timeZone = if (chosen.locationMode == SceneSettings.SALTHILL) "Europe/Dublin" else null
        findViewById<TextView>(R.id.clock_caption).text = if (chosen.locationMode == SceneSettings.SALTHILL) "Salthill · local time" else getString(R.string.local_time)
    }
    private fun renderMotion() {
        findViewById<TextView>(R.id.motion_detail).text = when (chosen.fps) {
            10 -> "Lowest power · 10 fps. Animation pauses when hidden."
            30 -> "Smoother motion · 30 fps. Battery saver limits this to 10 fps."
            else -> "A gentle balance · 15 fps. Animation pauses when hidden."
        }
    }
    private fun renderWeather() {
        if (!::chosen.isInitialized) return
        val frame = weather.frame()
        findViewById<TextView>(R.id.tide_status).text = when (frame.tide?.rising) {
            true -> "Sea level rising · crest follows the coastal forecast"
            false -> "Sea level falling · crest follows the coastal forecast"
            null -> "Sea level unavailable · crest at its neutral height"
        }
        findViewById<TextView>(R.id.weather_summary).text = if (weather.hasForecast) {
            val label = when (frame.theme) { Theme.NIGHT -> "Night by the sea"; Theme.GREY -> "Cloudy skies"; Theme.SUNSET -> "Golden hour"; else -> "Day by the sea" }
            "$label · ${frame.temperature.roundToInt()}°"
        } else "Blackrock · Salthill"
        findViewById<TextView>(R.id.forecast_status).text = when {
            !weather.hasForecast -> if (weather.status.startsWith("Loading")) "Connecting to the forecast…" else "Weather unavailable. Your scene will keep running with its preview."
            weather.status.startsWith("Offline") -> "Offline · using your saved forecast."
            weather.lastUpdatedAt > 0 -> {
                val zone = if (chosen.locationMode == SceneSettings.SALTHILL) ZoneId.of("Europe/Dublin") else ZoneId.systemDefault()
                val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(weather.lastUpdatedAt))
                "Forecast updated at $time · refreshes quietly while visible."
            }
            else -> "Using your saved forecast."
        }
    }

    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
        else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @android.annotation.SuppressLint("MissingPermission") // Permission checked here and before every lookup.
    private fun locate() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) { renderLocation(); return }
        cancelLocation()
        val manager = getSystemService(LocationManager::class.java)
        if (!LocationManagerCompat.isLocationEnabled(manager)) {
            renderLocation()
            Snackbar.make(findViewById(R.id.settings_root), "Turn on device location to use your current place.", Snackbar.LENGTH_LONG)
                .setAction("Location settings") { launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }.show()
            return
        }
        val provider = listOf(LocationManager.NETWORK_PROVIDER, "fused").firstOrNull {
            manager.allProviders.contains(it) && runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (provider == null) { locationFailed(); return }
        findViewById<TextView>(R.id.location_summary).text = "Finding your current location…"
        findViewById<TextView>(R.id.location_detail).text = getString(R.string.location_privacy)
        val signal = CancellationSignal()
        locationSignal = signal
        val timeout = Runnable { if (locationSignal === signal) { cancelLocation(); locationFailed() } }
        locationTimeout = timeout
        main.postDelayed(timeout, 20_000)
        try {
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(this)) { location: Location? ->
                if (locationSignal !== signal) return@getCurrentLocation
                cancelLocation()
                if (location == null || !location.latitude.isFinite() || !location.longitude.isFinite()) locationFailed()
                else {
                    chosen = chosen.copy(latitude = location.latitude, longitude = location.longitude,
                        locationMode = SceneSettings.CURRENT, locationUpdatedAt = System.currentTimeMillis())
                    persist(); renderLocation(); message("Using your current location")
                }
            }
        } catch (_: Exception) { cancelLocation(); locationFailed() }
    }
    private fun cancelLocation() {
        locationSignal?.cancel(); locationSignal = null
        locationTimeout?.let { main.removeCallbacks(it) }; locationTimeout = null
    }
    private fun locationFailed() { renderLocation(); message("Couldn’t get a location. Try again outdoors or use Salthill.") }
    private fun saveTides(): Boolean {
        val url = tide.text?.toString()?.trim().orEmpty()
        val valid = url.isEmpty() || runCatching { URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
        findViewById<TextInputLayout>(R.id.tide_input).error = if (valid) null else "Enter an HTTPS forecast URL"
        if (!valid) { findViewById<View>(R.id.advanced_content).visibility = View.VISIBLE; return false }
        if (url != chosen.tideUrl) { chosen = chosen.copy(tideUrl = url); persist() }
        return true
    }
    private fun message(text: String) { Snackbar.make(findViewById(R.id.settings_root), text, Snackbar.LENGTH_LONG).show() }
    private fun launch(intent: Intent) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { message("This device doesn’t provide that screen") }
    }
    override fun onResume() { super.onResume(); weather.subscribe(update); renderWeather() }
    override fun onPause() { cancelLocation(); renderLocation(); weather.unsubscribe(update); super.onPause() }
}
