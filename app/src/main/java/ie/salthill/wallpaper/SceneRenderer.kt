package ie.salthill.wallpaper

import android.content.Context
import android.graphics.*
import androidx.core.graphics.PathParser
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** All bitmaps and paths are prepared once off the rendering thread. No WebView. */
class SceneRenderer(context: Context, private val width: Int, private val height: Int) {
    private val scale = max(width / 1920f, min(height / 1080f, width / 820f))
    private val sceneHeight = max(1080f, height / scale)
    private val sceneTop = min(0f, height - 1080f * scale)
    private val seaTop = sceneHeight - 770f
    private val bitmaps = HashMap<String, Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val boatPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val boatMotion = BoatMotion()
    private val foamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.rgb(242, 252, 255) }
    private val destination = RectF()
    private val matrix = Matrix()
    private val projection = Matrix()
    private val pathMatrix = Matrix()
    private val water: BitmapShader
    private val watercolor: BitmapShader
    private val waterPaths: List<Pair<Path, Int>>
    private val shiftedPaths: List<Pair<Path, Int>>
    private val foam: Path
    private val shiftedFoam = Path()
    private val stars = Path()
    private val shootingStar = ShootingStarClock()
    private val shootingTravelY = min(width / scale * .28f, seaTop * .42f)
    private val shootingAngle = -Math.toDegrees(kotlin.math.atan2(
        shootingTravelY.toDouble(), (width / scale * .62f).toDouble())).toFloat()
    private val shootingTail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 72f, 0f,
            intArrayOf(0xfff5fbff.toInt(), 0xffbdd8ff.toInt(), 0x00bdd8ff),
            floatArrayOf(0f, .2f, 1f), Shader.TileMode.CLAMP)
    }
    private val shootingHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xfff5fbff.toInt() }
    private val rainLines = FloatArray(80 * 4)
    private val phase = WaveClock()
    private var motionSeconds = 0.0
    private var cloudOffset = 0f
    private var weather = FrameWeather()
    private var gradient: Shader? = null
    private var lastTheme: Theme? = null
    private var lastTide = Float.NaN
    private val grey = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(.4f) })
    private val cloudDay = PorterDuffColorFilter(Color.rgb(230, 230, 230), PorterDuff.Mode.SRC_IN)
    private val haze: Shader = LinearGradient(0f, seaTop, 0f, seaTop + 460f,
        intArrayOf(0xffb8cfdd.toInt(), 0xc0bbd5e6.toInt(), 0x60bfdcec, 0x00cce4ef), floatArrayOf(0f, .18f, .55f, 1f), Shader.TileMode.CLAMP)
    private val cloudNames = Array(9) { "cloud-${it + 1}" }
    private val greyCloudNames = Array(9) { "cloud-grey-${it + 1}" }
    private val sunsetCloudNames = Array(9) { "cloud-sunset-${it + 1}" }
    private val cloudNumbers = intArrayOf(1, 2, 6, 7, 8, 9, 4, 5, 3, 2, 1, 4)
    private val cloudX = floatArrayOf(1164f,-7f,2669f,1861f,3214f,3255f,617f,1993f,2460f,3833f,5004f,4457f)
    private val cloudY = floatArrayOf(-60f,-45f,-60f,-102f,-101f,50f,-30f,83f,-46f,-45f,-60f,-30f)
    private val cloudWidths = floatArrayOf(768f,722f,500f,722f,395f,770f,779f,684f,409.5f)
    private val cloudHeights = floatArrayOf(432f,498f,500f,346f,227f,325f,321f,234f,191.8f)

    init {
        val names = listOf("blackrock", "rocks", "clare", "hooker", "hot-chocolate", "umbrella", "pinwheel-handle", "pinwheel-spinner", "sea-texture", "watercolor-texture") + (1..9).flatMap { listOf("cloud-$it", "cloud-grey-$it", "cloud-sunset-$it") }
        val sample = if (scale < .7f) 2 else 1
        names.forEach { name ->
            val options = BitmapFactory.Options().apply { inSampleSize = if (name == "sea-texture") 1 else sample }
            bitmaps[name] = context.assets.open("art/$name.webp").use { BitmapFactory.decodeStream(it, null, options)!! }
        }
        water = BitmapShader(bitmaps.getValue("sea-texture"), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        watercolor = BitmapShader(bitmaps.getValue("watercolor-texture"), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        matrix.setScale(1920f / bitmaps.getValue("watercolor-texture").width, 1920f / bitmaps.getValue("watercolor-texture").width)
        watercolor.setLocalMatrix(matrix)
        // A planar homography compresses both wavelength and height toward the horizon.
        projection.setPolyToPoly(floatArrayOf(0f,0f,1920f,0f,1920f,3200f,0f,3200f), 0,
            floatArrayOf(840f,seaTop,1080f,seaTop,2200f,sceneHeight,-280f,sceneHeight), 0, 4)
        val paths = JSONArray(context.assets.open("art/wave-paths.json").bufferedReader().use { it.readText() })
        waterPaths = (0 until paths.length()).map {
            val entry = paths.getJSONObject(it)
            PathParser.createPathFromPathData(entry.getString("path"))!! to (entry.getDouble("alpha") * 255).toInt()
        }
        shiftedPaths = waterPaths.map { Path() to it.second }
        val foamData = JSONObject(context.assets.open("art/wave-foam.json").bufferedReader().use { it.readText() })
        foam = PathParser.createPathFromPathData(foamData.getString("path"))!!
        val starRandom = java.util.Random(71L)
        for (i in 0 until 60) stars.addCircle(starRandom.nextFloat() * 1920, starRandom.nextFloat() * (sceneHeight - 765), .7f + starRandom.nextFloat() * 2.2f, Path.Direction.CW)
        update(FrameWeather())
    }

    fun update(value: FrameWeather) {
        weather = value
        boatMotion.setWind(value.wind)
        if (lastTheme != value.theme) {
            val colors = when (value.theme) {
                Theme.DAY -> intArrayOf(0xff9381ff.toInt(), 0xffb8b8ff.toInt(), 0xfff8f7ff.toInt())
                Theme.GREY -> intArrayOf(0xffa3a3a3.toInt(), 0xffa3a3a3.toInt(), 0xffc3c3c3.toInt(), 0xffe2e2e2.toInt())
                Theme.SUNSET -> intArrayOf(0xffff6392.toInt(), 0xffff6392.toInt(), 0xffffe45e.toInt(), 0xffffe45e.toInt())
                Theme.NIGHT -> intArrayOf(0xff191747.toInt(), 0xff34316d.toInt(), 0xff7161af.toInt())
            }
            // Use the web sky's actual hold points rather than smoothing its
            // colours across the whole sky. SUNSET covers sunrise as well.
            val stops = when (value.theme) {
                Theme.GREY -> floatArrayOf(0f, .5f, .75f, 1f)
                Theme.SUNSET -> floatArrayOf(0f, .7f, .9f, 1f)
                else -> floatArrayOf(0f, .6f, 1f)
            }
            gradient = LinearGradient(0f, 0f, 0f, seaTop, colors, stops, Shader.TileMode.CLAMP)
            waterPaint.colorFilter = if (value.theme == Theme.GREY || value.theme == Theme.NIGHT) grey else null
            // Atmospheric perspective is cached with the theme. Grey/night boats
            // lose colour and merge into the distant sea instead of staying bright red.
            val saturation: Float; val hazeMix: Float; val brightness: Float; val hazeColour: Int
            when (value.theme) {
                Theme.GREY -> { saturation = .15f; hazeMix = .55f; brightness = .9f; hazeColour = 0xffb4c4cd.toInt(); boatPaint.alpha = 145 }
                Theme.NIGHT -> { saturation = .08f; hazeMix = .6f; brightness = .55f; hazeColour = 0xff3b4963.toInt(); boatPaint.alpha = 140 }
                Theme.SUNSET -> { saturation = .4f; hazeMix = .4f; brightness = .9f; hazeColour = 0xffceb4c2.toInt(); boatPaint.alpha = 155 }
                else -> { saturation = .55f; hazeMix = .4f; brightness = 1f; hazeColour = 0xffb8cfdd.toInt(); boatPaint.alpha = 160 }
            }
            val factor = (1f - hazeMix) * brightness
            val atmosphere = ColorMatrix().apply { setSaturation(saturation) }
            atmosphere.postConcat(ColorMatrix(floatArrayOf(
                factor, 0f, 0f, 0f, Color.red(hazeColour) * hazeMix,
                0f, factor, 0f, 0f, Color.green(hazeColour) * hazeMix,
                0f, 0f, factor, 0f, Color.blue(hazeColour) * hazeMix,
                0f, 0f, 0f, 1f, 0f)))
            boatPaint.colorFilter = ColorMatrixColorFilter(atmosphere)
            lastTheme = value.theme
        }
        val tide = ((value.tide?.height ?: 0.0) * 45 + 120).coerceIn(20.0, 300.0).toFloat()
        if (tide != lastTide) {
            pathMatrix.setScale(1f, 396f / 392f)
            pathMatrix.postTranslate(0f, sceneHeight - tide - 396f)
            waterPaths.forEachIndexed { i, entry -> entry.first.transform(pathMatrix, shiftedPaths[i].first) }
            foam.transform(pathMatrix, shiftedFoam)
            lastTide = tide
        }
    }

    fun advance(seconds: Double) {
        val speed = (weather.wind / 6).coerceIn(.5, 2.0) * 612 / 50
        phase.advance(seconds, if (weather.tide?.rising == false) -speed else speed)
        motionSeconds += seconds.coerceIn(0.0, .25)
        shootingStar.advance(seconds, weather.theme == Theme.NIGHT)
        boatMotion.advance(seconds)
        cloudOffset = ((cloudOffset + seconds * 7.68) % 3840).toFloat()
    }

    private fun art(canvas: Canvas, name: String, x: Float, y: Float, w: Float, h: Float = -1f, artPaint: Paint = paint) {
        val bitmap = bitmaps.getValue(name)
        destination.set(x, y, x + w, y + if (h >= 0f) h else w * bitmap.height / bitmap.width)
        canvas.drawBitmap(bitmap, null, destination, artPaint)
    }

    fun draw(canvas: Canvas) {
        canvas.drawColor(0xff829fbd.toInt()) // Hardware buffers are not preserved between frames.
        canvas.save()
        canvas.translate(0f, sceneTop)
        canvas.scale(scale, scale)
        paint.color = Color.WHITE; paint.alpha = 255; paint.colorFilter = null
        paint.shader = gradient
        // The web sunset sky is 80% opaque over the same blue backdrop.
        paint.alpha = if (weather.theme == Theme.SUNSET) 204 else 255
        canvas.drawRect(0f, 0f, 1920f, seaTop + 5, paint)
        paint.shader = watercolor; paint.alpha = 45
        canvas.drawRect(0f, 0f, 1920f, seaTop + 5, paint)
        paint.shader = null; paint.alpha = 255
        if (weather.theme == Theme.NIGHT) {
            paint.color = 0xfffff59d.toInt(); canvas.drawPath(stars, paint)
            if (shootingStar.visible) {
                // Anchor in the visible sky, including narrow portrait home screens.
                val x = width / scale * (.8f - shootingStar.progress * .62f)
                val y = seaTop * .12f + shootingTravelY * shootingStar.progress
                canvas.save(); canvas.translate(x, y); canvas.rotate(shootingAngle)
                shootingTail.alpha = shootingStar.alpha; shootingHead.alpha = shootingStar.alpha
                canvas.drawRect(0f, -1f, 72f, 1f, shootingTail)
                canvas.drawCircle(0f, 0f, 1.6f, shootingHead)
                canvas.restore()
            }
        } else {
            val names = when (weather.theme) {
                Theme.GREY -> greyCloudNames
                Theme.SUNSET -> sunsetCloudNames
                else -> cloudNames
            }
            // Grey and golden-hour shading is baked once into the assets:
            // neutral upper clouds and pink undersides, rather than flat tint.
            paint.colorFilter = if (weather.theme == Theme.DAY) cloudDay else null
            for (i in cloudNumbers.indices) {
                val n = cloudNumbers[i]
                val x = cloudX[i] - cloudOffset
                if (x + cloudWidths[n-1] >= 0 && x <= 1920) art(canvas, names[n-1], x, sceneHeight - 1100 + cloudY[i], cloudWidths[n-1], cloudHeights[n-1])
            }
            paint.colorFilter = null
        }
        matrix.set(projection); matrix.preTranslate(0f, phase.phase.toFloat())
        water.setLocalMatrix(matrix); waterPaint.shader = water; waterPaint.alpha = 255
        canvas.drawRect(0f, seaTop, 1920f, sceneHeight, waterPaint)
        paint.shader = haze
        canvas.drawRect(0f, seaTop, 1920f, seaTop + 460, paint)
        paint.shader = null
        art(canvas, "clare", 0f, sceneHeight - 825, 1920f, 95f)
        // The boat stays distant, while bobbing and rolling with wind-driven swell.
        canvas.save()
        canvas.translate(82f, sceneHeight - 702.5f + boatMotion.offsetY)
        canvas.rotate(boatMotion.roll)
        art(canvas, "hooker", -10f, -9.5f, 20f, 19f, boatPaint)
        canvas.restore()
        art(canvas, "blackrock", 0f, sceneHeight + 5 - 1920f * bitmaps.getValue("blackrock").height / bitmaps.getValue("blackrock").width, 1920f)
        for (i in shiftedPaths.indices) { waterPaint.alpha = shiftedPaths[i].second; canvas.drawPath(shiftedPaths[i].first, waterPaint) }
        // Foam uses the exact upper edge of the water path, never an independently placed sprite.
        foamPaint.strokeWidth = 12f; foamPaint.alpha = 35; canvas.drawPath(shiftedFoam, foamPaint)
        foamPaint.strokeWidth = 4.5f; foamPaint.alpha = 180; canvas.drawPath(shiftedFoam, foamPaint)
        art(canvas, "rocks", 0f, sceneHeight + 5 - 1412f * bitmaps.getValue("rocks").height / bitmaps.getValue("rocks").width, 1412f)
        if (weather.rain > .09 && weather.snow <= 0) art(canvas, "umbrella", 161f, sceneHeight - 1035, 90f)
        if (weather.temperature <= 5) art(canvas, "hot-chocolate", 198f, sceneHeight - 962, 28f)
        if (weather.wind >= 6) {
            art(canvas, "pinwheel-handle", 190f, sceneHeight - 974, 25f)
            canvas.save(); canvas.rotate((motionSeconds * weather.wind * 30 % 360).toFloat(), 199.5f, sceneHeight - 960.5f)
            art(canvas, "pinwheel-spinner", 187f, sceneHeight - 973, 25f); canvas.restore()
        }
        if (weather.snow > 0) {
            paint.color = Color.WHITE; paint.alpha = 190
            for (i in 0 until 48) {
                val x = ((i * 173 + motionSeconds * 9) % 1920).toFloat()
                val y = ((i * 127 + motionSeconds * 25) % sceneHeight).toFloat()
                canvas.drawCircle(x, y, (2 + i % 3).toFloat(), paint)
            }
        } else if (weather.rain > .09) {
            paint.color = 0xffe4f3ff.toInt(); paint.alpha = 100; paint.strokeWidth = 1.5f
            for (i in 0 until 80) {
                val x = ((i * 193 + motionSeconds * 18) % 1920).toFloat()
                val y = ((i * 131 + motionSeconds * 340) % sceneHeight).toFloat()
                rainLines[i*4] = x; rainLines[i*4+1] = y; rainLines[i*4+2] = x-4; rainLines[i*4+3] = y+18
            }
            canvas.drawLines(rainLines, paint)
        }
        paint.alpha = 255
        if (weather.theme == Theme.NIGHT) { paint.color = 0x38080c30; canvas.drawRect(0f,0f,1920f,sceneHeight,paint) }
        canvas.restore()
    }

    fun close() { bitmaps.values.forEach { it.recycle() }; bitmaps.clear() }
}
