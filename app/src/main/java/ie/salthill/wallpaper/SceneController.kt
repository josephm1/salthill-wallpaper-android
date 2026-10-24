package ie.salthill.wallpaper

import android.content.*
import android.os.*
import android.util.Log
import android.view.SurfaceHolder
import java.util.concurrent.Executors

/** Visibility and surface lifetime are the only owners of the render loop. */
class SceneController(context: Context, private val holder: SurfaceHolder) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val repository = WeatherRepository.get(app)
    private val power = app.getSystemService(PowerManager::class.java)
    private var renderer: SceneRenderer? = null
    private var visible = false
    private var surface = false
    private var destroyed = false
    private var subscribed = false
    private var registered = false
    private var generation = 0
    private var lastNanos = 0L
    internal var framesDrawn = 0L
        private set
    private var fps = 15
    private val updateWeather: () -> Unit = {
        renderer?.update(repository.frame())
        fps = SceneSettings.read(app).fps.let { if (power.isPowerSaveMode) minOf(10, it) else it }
        scheduleFrame()
    }
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updateWeather() }
    }
    private val minute = object : Runnable {
        override fun run() {
            if (!active()) return
            updateWeather(); repository.refresh()
            main.postDelayed(this, 60_000)
        }
    }
    private val frame = object : Runnable {
        override fun run() {
            if (!active()) return
            val scene = renderer ?: return
            val started = SystemClock.elapsedRealtimeNanos()
            val elapsed = if (lastNanos == 0L) 0.0 else (started - lastNanos) / 1e9
            lastNanos = started
            scene.advance(elapsed)
            try {
                val canvas = holder.lockHardwareCanvas()
                try { scene.draw(canvas) } finally { holder.unlockCanvasAndPost(canvas) }
                framesDrawn++
            } catch (error: RuntimeException) {
                Log.w("Salthill", "Surface not ready for a frame", error)
            }
            if (active()) {
                val spent = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
                main.postDelayed(this, (1000L / fps - spent).coerceAtLeast(1))
            }
        }
    }
    private fun active() = visible && surface && !destroyed
    private fun scheduleFrame() {
        main.removeCallbacks(frame)
        if (active() && renderer != null) main.post(frame)
    }
    private fun reconcile() {
        if (active()) {
            if (!subscribed) { subscribed = true; repository.subscribe(updateWeather) }
            if (!registered) {
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(powerReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
                else app.registerReceiver(powerReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
                registered = true
            }
            lastNanos = 0
            updateWeather()
            main.removeCallbacks(minute); main.postDelayed(minute, 60_000)
        } else {
            main.removeCallbacks(frame); main.removeCallbacks(minute); lastNanos = 0
            if (subscribed) { repository.unsubscribe(updateWeather); subscribed = false }
            if (registered) { app.unregisterReceiver(powerReceiver); registered = false }
        }
    }
    fun setVisible(value: Boolean) { visible = value; reconcile() }
    fun setSurface(value: Boolean) { surface = value; reconcile() }
    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || destroyed) return
        val ticket = ++generation
        loader.execute {
            val newRenderer = runCatching { SceneRenderer(app, width, height) }.getOrElse {
                Log.e("Salthill", "Cannot prepare artwork", it); return@execute
            }
            main.post {
                if (destroyed || ticket != generation) newRenderer.close()
                else {
                    renderer?.close(); renderer = newRenderer
                    newRenderer.update(repository.frame()); lastNanos = 0
                    scheduleFrame()
                }
            }
        }
    }
    fun redraw() { scheduleFrame() }
    fun close() {
        destroyed = true; generation++; reconcile()
        loader.shutdownNow(); renderer?.close(); renderer = null
    }
}
