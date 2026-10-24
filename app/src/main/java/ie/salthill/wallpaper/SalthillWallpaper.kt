package ie.salthill.wallpaper

import android.app.WallpaperColors
import android.graphics.Color
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class SalthillWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = CoastEngine()
    private inner class CoastEngine : Engine() {
        private var controller: SceneController? = null
        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setTouchEventsEnabled(false)
            setOffsetNotificationsEnabled(false)
            controller = SceneController(this@SalthillWallpaper, holder)
        }
        override fun onVisibilityChanged(visible: Boolean) { controller?.setVisible(visible) }
        override fun onSurfaceCreated(holder: SurfaceHolder) { super.onSurfaceCreated(holder); controller?.setSurface(true) }
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height); controller?.resize(width, height)
        }
        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) { controller?.redraw() }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) { controller?.setSurface(false); super.onSurfaceDestroyed(holder) }
        @android.annotation.TargetApi(27) // The platform calls this hook only on API 27+.
        override fun onComputeColors() = WallpaperColors(Color.valueOf(0xff829fbd.toInt()), Color.valueOf(0xff34316d.toInt()), null)
        override fun onDestroy() { controller?.close(); controller = null; super.onDestroy() }
    }
}
