package ie.salthill.wallpaper

import android.service.dreams.DreamService

/** The system decides when to run a charging/docked screensaver. No wake lock. */
class SalthillDream : DreamService() {
    private var scene: SceneSurface? = null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false; isFullscreen = true; isScreenBright = false
        scene = SceneSurface(this).also { setContentView(it) }
    }
    override fun onDreamingStarted() { super.onDreamingStarted(); scene?.showScene(true) }
    override fun onDreamingStopped() { scene?.showScene(false); super.onDreamingStopped() }
    override fun onDetachedFromWindow() { scene?.close(); scene = null; super.onDetachedFromWindow() }
}
