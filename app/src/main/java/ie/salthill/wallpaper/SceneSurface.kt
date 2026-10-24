package ie.salthill.wallpaper

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView

class SceneSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val controller = SceneController(context, holder)
    init { holder.addCallback(this) }
    fun showScene(show: Boolean) = controller.setVisible(show)
    override fun surfaceCreated(holder: SurfaceHolder) { controller.setSurface(true) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { controller.resize(width, height) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { controller.setSurface(false) }
    internal fun frameCount() = controller.framesDrawn
    fun close() { controller.close(); holder.removeCallback(this) }
}
