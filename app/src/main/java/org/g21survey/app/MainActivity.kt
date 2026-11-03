package org.g21survey.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs

private data class RasterTile(
    val bitmap: Bitmap,
    val westing: Double,
    val northing: Double,
    val pixelWidth: Double,
    val pixelHeight: Double,
)

class MainActivity : Activity() {
    private val loader = Executors.newSingleThreadExecutor()
    private lateinit var mapView: RasterMapView
    private lateinit var gpsButton: Button
    private var gpsEnabled = false
    private var listening = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val (easting, northing) = BritishNationalGrid.fromWgs84(location.latitude, location.longitude)
            mapView.setPosition(easting, northing, if (location.hasAccuracy()) location.accuracy else null)
        }

        @Deprecated("Required on older Android versions")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        mapView = RasterMapView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(12), dp(12))
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titles.addView(TextView(this).apply {
            text = "g21 Survey"
            textSize = 20f
            setTextColor(Color.rgb(32, 45, 37))
        })
        // titles.addView(TextView(this).apply {
        //     text = "Basemap · EPSG:27700"
        //     textSize = 13f
        //     setTextColor(Color.rgb(100, 110, 103))
        // })
        heading.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        gpsButton = Button(this).apply {
            text = "GPS: off"
            setOnClickListener { setGps(!gpsEnabled) }
        }
        heading.addView(gpsButton)
        heading.addView(Button(this).apply {
            text = "Fit map"
            setOnClickListener { mapView.fitMap() }
        })

        root.addView(heading)
        root.addView(
            mapView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)
        loadBasemap()
    }

    private fun loadBasemap() {
        loader.execute {
            try {
                val tiles = readTiles()
                runOnUiThread {
                    if (!isFinishing) mapView.setTiles(tiles)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        mapView.setError(error.message ?: "Unable to load the basemap.")
                    }
                }
            }
        }
    }

    private fun readTiles(): List<RasterTile> {
        val names = assets.list("basemap")
            ?.filter { it.endsWith(".jpg", ignoreCase = true) }
            ?.sorted()
            .orEmpty()
        check(names.isNotEmpty()) { "No basemap JPEG files were found in app/src/main/assets/basemap." }

        return names.map { imageName ->
            val worldName = imageName.substringBeforeLast('.') + ".wld"
            val values = assets.open("basemap/$worldName").bufferedReader().useLines { lines ->
                lines.map(String::trim).filter(String::isNotEmpty).map(String::toDouble).toList()
            }
            check(values.size == 6) { "Expected six world-file values in $worldName." }

            val pixelX = values[0]
            val rowRotation = values[1]
            val columnRotation = values[2]
            val pixelY = values[3]
            val centerX = values[4]
            val centerY = values[5]
            check(
                abs(rowRotation) < 1e-10 &&
                    abs(columnRotation) < 1e-10 &&
                    pixelX > 0.0 &&
                    pixelY < 0.0,
            ) { "Unsupported rotated or non-north-up world file: $worldName." }

            val bitmap = assets.open("basemap/$imageName").use { stream ->
                BitmapFactory.decodeStream(
                    stream,
                    null,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                        inScaled = false
                    },
                )
            } ?: error("Unable to decode basemap image $imageName.")

            RasterTile(
                bitmap = bitmap,
                westing = centerX - pixelX / 2.0,
                northing = centerY - pixelY / 2.0,
                pixelWidth = pixelX,
                pixelHeight = -pixelY,
            )
        }
    }

    private fun setGps(enabled: Boolean) {
        if (enabled && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
            return
        }
        gpsEnabled = enabled
        gpsButton.text = if (enabled) "GPS: on" else "GPS: off"
        if (enabled) {
            startLocationUpdates()
        } else {
            stopLocationUpdates()
            mapView.clearPosition()
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (listening || !gpsEnabled) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Turn on device location (GPS).", Toast.LENGTH_LONG).show()
        }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(locationListener::onLocationChanged)
            listening = true
        } catch (error: SecurityException) {
            gpsEnabled = false
            gpsButton.text = "GPS: off"
        }
    }

    private fun stopLocationUpdates() {
        if (!listening) return
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        listening = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            setGps(true)
        } else {
            Toast.makeText(this, "Location permission is needed to show your position.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
    }

    override fun onPause() {
        stopLocationUpdates()
        super.onPause()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LOCATION_REQUEST = 1
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }
}

private class RasterMapView(context: android.content.Context) : View(context) {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 88, 82)
        textSize = 16f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 118, 210) }
    private val markerOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val accuracyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 25, 118, 210) }
    private var positionX: Double? = null
    private var positionY: Double? = null
    private var positionAccuracy: Float? = null
    private val tiles = mutableListOf<RasterTile>()
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!hasInitialFit) return false
                val oldScale = mapScale
                val newScale = (oldScale * detector.scaleFactor).coerceIn(
                    fitScale * 0.4f,
                    fitScale * 32f,
                )
                val ratio = newScale / oldScale
                offsetX = detector.focusX - (detector.focusX - offsetX) * ratio
                offsetY = detector.focusY - (detector.focusY - offsetY) * ratio
                mapScale = newScale
                invalidate()
                return true
            }
        },
    )

    private var minWesting = 0.0
    private var maxWesting = 0.0
    private var minNorthing = 0.0
    private var maxNorthing = 0.0
    private var mapScale = 1f
    private var fitScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var hasInitialFit = false
    private var errorMessage: String? = null

    init {
        setBackgroundColor(Color.rgb(239, 240, 235))
        isClickable = true
    }

    fun setTiles(newTiles: List<RasterTile>) {
        tiles.clear()
        tiles.addAll(newTiles)
        minWesting = tiles.minOf { it.westing }
        maxWesting = tiles.maxOf { it.westing + it.bitmap.width * it.pixelWidth }
        minNorthing = tiles.minOf { it.northing - it.bitmap.height * it.pixelHeight }
        maxNorthing = tiles.maxOf { it.northing }
        errorMessage = null
        fitMap()
    }

    fun setPosition(easting: Double, northing: Double, accuracyMetres: Float?) {
        positionX = easting
        positionY = northing
        positionAccuracy = accuracyMetres
        invalidate()
    }

    fun clearPosition() {
        positionX = null
        positionY = null
        positionAccuracy = null
        invalidate()
    }

    fun setError(message: String) {
        errorMessage = message
        invalidate()
    }

    fun fitMap() {
        if (tiles.isEmpty() || width == 0 || height == 0) return
        val mapWidth = maxWesting - minWesting
        val mapHeight = maxNorthing - minNorthing
        fitScale = minOf(width / mapWidth.toFloat(), height / mapHeight.toFloat())
        mapScale = fitScale
        offsetX = (width - mapWidth.toFloat() * mapScale) / 2f - minWesting.toFloat() * mapScale
        offsetY = (height - mapHeight.toFloat() * mapScale) / 2f + maxNorthing.toFloat() * mapScale
        hasInitialFit = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (tiles.isNotEmpty()) fitMap()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tiles.isEmpty()) {
            val message = errorMessage ?: "Loading basemap…"
            canvas.drawText(message, width / 2f, height / 2f, textPaint)
            return
        }

        tiles.forEach { tile ->
            val left = offsetX + tile.westing.toFloat() * mapScale
            val top = offsetY - tile.northing.toFloat() * mapScale
            val right = left + (tile.bitmap.width * tile.pixelWidth * mapScale).toFloat()
            val bottom = top + (tile.bitmap.height * tile.pixelHeight * mapScale).toFloat()
            canvas.drawBitmap(tile.bitmap, null, RectF(left, top, right, bottom), bitmapPaint)
        }

        val easting = positionX
        val northing = positionY
        if (easting != null && northing != null) {
            val x = offsetX + easting.toFloat() * mapScale
            val y = offsetY - northing.toFloat() * mapScale
            positionAccuracy?.let { accuracy ->
                val radius = accuracy * mapScale
                if (radius > 8f * resources.displayMetrics.density) canvas.drawCircle(x, y, radius, accuracyFill)
            }
            val markerRadius = 8f * resources.displayMetrics.density
            canvas.drawCircle(x, y, markerRadius, markerFill)
            canvas.drawCircle(x, y, markerRadius, markerOutline)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && hasInitialFit) {
                    offsetX += event.x - lastTouchX
                    offsetY += event.y - lastTouchY
                    invalidate()
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
        return true
    }
}
