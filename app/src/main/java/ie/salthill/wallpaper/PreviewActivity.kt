package ie.salthill.wallpaper

import android.app.Activity
import android.os.Bundle

class PreviewActivity : Activity() {
    internal lateinit var scene: SceneSurface
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.setOnApplyWindowInsetsListener { view, insets -> view.setPadding(0, 0, 0, 0); insets }
        scene = SceneSurface(this); setContentView(scene)
    }
    override fun onResume() { super.onResume(); scene.showScene(true) }
    override fun onPause() { scene.showScene(false); super.onPause() }
    override fun onDestroy() { scene.close(); super.onDestroy() }
}
