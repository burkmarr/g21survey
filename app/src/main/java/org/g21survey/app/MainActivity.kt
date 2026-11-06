package org.g21survey.app

import android.animation.ValueAnimator
import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.widget.Toast
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.io.IOException
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs

private data class RasterTile(
    val bitmap: Bitmap,
    val westing: Double,
    val northing: Double,
    val pixelWidth: Double,
    val pixelHeight: Double,
)

private data class GpxSaveResult(val folder: String, val filename: String)

class MainActivity : Activity() {
    private val loader = Executors.newSingleThreadExecutor()
    private val saver = Executors.newSingleThreadExecutor()
    private lateinit var mapView: RasterMapView
    private lateinit var screenTitle: TextView
    private lateinit var trackingScreen: View
    private lateinit var trackingButton: Button
    private lateinit var trackingSaveButton: Button
    private lateinit var trackingClearButton: Button
    private lateinit var trackingFolderButton: Button
    private lateinit var trackingFolderLabel: TextView
    private lateinit var trackingStatus: TextView
    private lateinit var trackingSummary: TextView
    private lateinit var movementThresholdPicker: NumberPicker
    private lateinit var mapNavigationItem: NavigationItem
    private lateinit var trackingNavigationItem: NavigationItem
    private var listening = false
    private var trackingService: TrackingService? = null
    private var serviceBound = false
    private var pendingTrackStart = false
    private var activityResumed = false
    private var isSavingTrack = false

    private val trackingObserver = object : TrackingService.Observer {
        override fun onTrackChanged(
            snapshot: TrackSnapshot?,
            newPoint: TrackPoint?,
            trackReplaced: Boolean,
            currentPosition: TrackPoint?,
        ) {
            runOnUiThread {
                if (!isFinishing) {
                    updateTrackingUi(snapshot)
                    mapView.setTrackingActive(snapshot?.isTracking == true)
                    if (trackReplaced) mapView.clearTrack()
                    if (newPoint != null) mapView.appendTrackPoint(newPoint)
                    if (currentPosition != null) {
                        mapView.setPosition(
                            currentPosition.easting,
                            currentPosition.northing,
                            currentPosition.accuracyMeters,
                        )
                    }
                    if (snapshot?.isTracking == true) {
                        stopLocationUpdates()
                    } else if (activityResumed) {
                        startLocationUpdates()
                    }
                }
            }
        }
    }

    private val trackingConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? TrackingService.TrackingBinder)?.getService() ?: return
            trackingService = service
            service.setMovementThreshold(movementThresholdMeters())
            service.setObserver(trackingObserver)
            val snapshot = service.snapshot()
            updateTrackingUi(snapshot)
            mapView.setTrackingActive(snapshot?.isTracking == true)
            val points = service.trackPoints()
            mapView.setTrack(points)
            (service.latestPosition() ?: points.lastOrNull())?.let {
                mapView.setPosition(it.easting, it.northing, it.accuracyMeters)
            }
            if (snapshot?.isTracking == true) stopLocationUpdates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService = null
            updateTrackingUi(null)
            mapView.setTrackingActive(false)
            if (activityResumed) startLocationUpdates()
        }
    }

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
        screenTitle = TextView(this).apply {
            text = "g21 Survey"
            textSize = 20f
            setTextColor(Color.rgb(32, 45, 37))
        }
        titles.addView(screenTitle)
        // titles.addView(TextView(this).apply {
        //     text = "Basemap · EPSG:27700"
        //     textSize = 13f
        //     setTextColor(Color.rgb(100, 110, 103))
        // })
        heading.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        mapView = RasterMapView(this)
        val content = FrameLayout(this)
        content.addView(
            mapView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        trackingScreen = createTrackingScreen()
        content.addView(
            trackingScreen,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        trackingScreen.visibility = View.GONE

        root.addView(heading)
        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        mapNavigationItem = NavigationItem(this, "Map", tracking = false)
        trackingNavigationItem = NavigationItem(this, "Tracking", tracking = true)
        mapNavigationItem.setItemSelected(true)
        trackingNavigationItem.setItemSelected(false)
        mapNavigationItem.setOnClickListener { showScreen(Screen.MAP) }
        trackingNavigationItem.setOnClickListener { showScreen(Screen.TRACKING) }
        navigation.addView(
            mapNavigationItem,
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        navigation.addView(
            trackingNavigationItem,
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        root.addView(navigation)

        setContentView(root)
        mapView.restoreView(readSavedView())
        loadBasemap()

        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
        }
    }

    private fun showScreen(screen: Screen) {
        val isMap = screen == Screen.MAP
        screenTitle.text = if (isMap) "g21 Survey" else "Tracking"
        mapView.visibility = if (isMap) View.VISIBLE else View.GONE
        mapView.setTrackingFlashVisible(isMap && activityResumed)
        trackingScreen.visibility = if (isMap) View.GONE else View.VISIBLE
        mapNavigationItem.setItemSelected(isMap)
        trackingNavigationItem.setItemSelected(!isMap)
    }

    private fun createTrackingScreen(): View {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }
        trackingStatus = TextView(this).apply {
            text = "Tracking is off"
            textSize = 16f
            setTextColor(Color.rgb(80, 88, 82))
            gravity = Gravity.CENTER
        }
        movementThresholdPicker = NumberPicker(this).apply {
            minValue = MIN_MOVEMENT_THRESHOLD_METRES
            maxValue = MAX_MOVEMENT_THRESHOLD_METRES
            wrapSelectorWheel = false
            setFormatter { value -> "$value m" }
            value = movementThresholdMeters()
            contentDescription = "Movement threshold in metres"
            setOnValueChangedListener { _, _, newValue ->
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_MOVEMENT_THRESHOLD, newValue)
                    .apply()
                trackingService?.setMovementThreshold(newValue)
            }
        }
        trackingButton = Button(this).apply {
            text = "Start tracking"
            setOnClickListener { onTrackingButtonClicked() }
        }
        trackingSaveButton = Button(this).apply {
            text = "Save GPX"
            isEnabled = false
            setOnClickListener { onSaveTrackClicked() }
        }
        trackingClearButton = Button(this).apply {
            text = "Clear GPX"
            isEnabled = false
            setOnClickListener { onClearTrackClicked() }
        }
        trackingFolderButton = Button(this).apply {
            text = "Choose save folder"
            setOnClickListener { chooseGpxFolder() }
        }
        trackingFolderLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(100, 110, 103))
            gravity = Gravity.CENTER
            maxLines = 2
        }
        controls.addView(
            trackingStatus,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )
        val thresholdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        thresholdRow.addView(TextView(this).apply {
            text = "Movement threshold"
            textSize = 16f
            setTextColor(Color.rgb(55, 65, 58))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        thresholdRow.addView(
            movementThresholdPicker,
            LinearLayout.LayoutParams(dp(100), dp(80)),
        )
        controls.addView(
            thresholdRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) },
        )
        controls.addView(
            trackingButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        controls.addView(
            trackingSaveButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        controls.addView(
            trackingClearButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        controls.addView(
            trackingFolderButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        controls.addView(
            trackingFolderLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
        updateGpxFolderLabel()
        screen.addView(controls)
        screen.addView(
            View(this),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
            setBackgroundColor(Color.rgb(247, 248, 246))
        }
        footer.addView(View(this).apply {
            setBackgroundColor(Color.rgb(225, 228, 224))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        trackingSummary = TextView(this).apply {
            text = "No current track"
            textSize = 14f
            setTextColor(Color.rgb(55, 65, 58))
            setPadding(0, dp(12), 0, 0)
        }
        footer.addView(trackingSummary)
        screen.addView(footer)
        return screen
    }

    private fun onTrackingButtonClicked() {
        val service = trackingService
        if (service == null) {
            Toast.makeText(this, "Tracking is still starting. Try again shortly.", Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = service.snapshot()
        if (snapshot?.isTracking == true) {
            trackingService?.pauseTrack()
            return
        }
        if (!hasLocationPermission()) {
            pendingTrackStart = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
            return
        }
        requestNotificationPermissionOrChooseTrack(snapshot)
    }

    private fun requestNotificationPermissionOrChooseTrack(snapshot: TrackSnapshot?) {
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingTrackStart = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            return
        }
        pendingTrackStart = false
        chooseTrackAction(snapshot)
    }

    private fun chooseTrackAction(snapshot: TrackSnapshot?) {
        if (snapshot != null) {
            AlertDialog.Builder(this)
                .setTitle("Continue this track?")
                .setMessage("A track is already in memory. Continue recording it or replace it with a new track?")
                .setPositiveButton("Continue current track") { _, _ ->
                    requestTrackingService(TrackingService.ACTION_CONTINUE)
                }
                .setNegativeButton("Replace with new track") { _, _ ->
                    requestTrackingService(TrackingService.ACTION_START_NEW)
                }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            requestTrackingService(TrackingService.ACTION_START_NEW)
        }
    }

    private fun requestTrackingService(action: String) {
        val intent = Intent(this, TrackingService::class.java).setAction(action)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateTrackingUi(snapshot: TrackSnapshot?) {
        val isTracking = snapshot?.isTracking == true
        trackingButton.isEnabled = !isSavingTrack
        trackingButton.text = if (isTracking) "Pause tracking" else "Start tracking"
        trackingSaveButton.isEnabled = !isSavingTrack && snapshot != null && !isTracking
        trackingClearButton.isEnabled = !isSavingTrack && snapshot != null && !isTracking
        trackingStatus.text = when {
            snapshot?.error != null -> snapshot.error
            isTracking -> "Tracking in progress"
            snapshot != null -> "Tracking paused"
            else -> "Tracking is off"
        }
        trackingSummary.text = snapshot?.let { current ->
            val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(current.startedAt))
            val elapsed = DateUtils.formatElapsedTime(
                ((System.currentTimeMillis() - current.startedAt).coerceAtLeast(0L)) / 1000L,
            )
            val length = formatTrackLength(current.lengthMeters)
            "Started: $started\nLength: $length\nElapsed: $elapsed\nGPS fixes: ${current.gpsFixCount}\nRecorded points: ${current.pointCount}"
        } ?: "No current track"
    }

    private fun onClearTrackClicked() {
        AlertDialog.Builder(this)
            .setTitle("Clear current GPX track?")
            .setMessage("This will permanently remove the track from memory.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear GPX") { _, _ ->
                if (trackingService?.clearTrack() != true) {
                    Toast.makeText(this, "The track could not be cleared.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun formatTrackLength(metres: Double): String {
        return if (metres < 1000.0) {
            "${metres.toInt()} m"
        } else {
            String.format(java.util.Locale.getDefault(), "%.2f km", metres / 1000.0)
        }
    }

    private fun chooseGpxFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        try {
            startActivityForResult(intent, SELECT_GPX_FOLDER_REQUEST)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No folder picker is available on this device.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateGpxFolderLabel() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val folder = if (prefs.contains(KEY_GPX_FOLDER_URI)) {
            prefs.getString(KEY_GPX_FOLDER_LABEL, "Selected folder")
        } else {
            "Not selected"
        }
        trackingFolderLabel.text = "GPX save folder: $folder"
    }

    private fun folderLabel(uri: Uri): String {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val path = documentId.substringAfter(':', documentId).trim('/')
        return path.ifBlank { "Internal storage" }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SELECT_GPX_FOLDER_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data
        if (uri == null) {
            Toast.makeText(this, "The selected folder could not be accessed.", Toast.LENGTH_LONG).show()
            return
        }
        val accessFlags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (accessFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) {
            Toast.makeText(this, "Write access was not granted for that folder.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, accessFlags)
            val label = folderLabel(uri)
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_GPX_FOLDER_URI, uri.toString())
                .putString(KEY_GPX_FOLDER_LABEL, label)
                .apply()
            updateGpxFolderLabel()
            Toast.makeText(this, "GPX folder selected.", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Unable to access the selected folder: ${error.message ?: "permission error"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun onSaveTrackClicked() {
        val service = trackingService
        val snapshot = service?.snapshot()
        if (service == null || snapshot == null || snapshot.isTracking) return
        if (!getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_GPX_FOLDER_URI)) {
            Toast.makeText(this, "Choose a GPX save folder before saving.", Toast.LENGTH_LONG).show()
            return
        }
        saveCurrentTrack()
    }

    private fun saveCurrentTrack() {
        val service = trackingService
        val snapshot = service?.snapshot()
        if (service == null || snapshot == null || snapshot.isTracking) return
        val points = service.trackPoints()
        isSavingTrack = true
        updateTrackingUi(snapshot)
        trackingSaveButton.text = "Saving…"
        saver.execute {
            try {
                val savedTrack = saveTrack(points)
                runOnUiThread {
                    if (!isFinishing) {
                        isSavingTrack = false
                        updateTrackingUi(trackingService?.snapshot())
                        AlertDialog.Builder(this)
                            .setTitle("Track saved")
                            .setMessage("Saved to ${savedTrack.folder}/${savedTrack.filename}.\n\nWould you like to clear the current track?")
                            .setPositiveButton("Clear current track") { _, _ ->
                                if (trackingService?.clearTrack() != true) {
                                    Toast.makeText(this, "The track could not be cleared.", Toast.LENGTH_LONG).show()
                                }
                            }
                            .setNegativeButton("Keep track", null)
                            .show()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        isSavingTrack = false
                        updateTrackingUi(trackingService?.snapshot())
                        Toast.makeText(
                            this,
                            "Unable to save GPX: ${error.message ?: "storage error"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun saveTrack(points: List<TrackPoint>): GpxSaveResult {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val filename = "g21-track-$timestamp.gpx"
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val folderUriString = prefs.getString(KEY_GPX_FOLDER_URI, null)
            ?: throw IOException("Choose a GPX save folder before saving.")
        val folderUri = Uri.parse(folderUriString)
        val savedFilename = saveTrackToSelectedFolder(folderUri, filename, points)
        val folder = prefs.getString(KEY_GPX_FOLDER_LABEL, "Selected folder") ?: "Selected folder"
        return GpxSaveResult(folder, savedFilename)
    }

    private fun saveTrackToSelectedFolder(
        treeUri: Uri,
        filename: String,
        points: List<TrackPoint>,
    ): String {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val fileUri = DocumentsContract.createDocument(
            contentResolver,
            parentUri,
            "application/gpx+xml",
            filename,
        ) ?: throw IOException("Android could not create the GPX file in the selected folder.")
        try {
            val output = contentResolver.openOutputStream(fileUri, "w")
                ?: throw IOException("Android could not open the GPX file for writing.")
            output.bufferedWriter(Charsets.UTF_8).use { writeGpx(it, points) }
        } catch (error: Exception) {
            try {
                DocumentsContract.deleteDocument(contentResolver, fileUri)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
        return filename
    }

    private fun writeGpx(writer: java.io.BufferedWriter, points: List<TrackPoint>) {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        writer.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        writer.appendLine(
            """<gpx version="1.1" creator="g21 Survey" xmlns="http://www.topografix.com/GPX/1/1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""",
        )
        writer.appendLine("  <trk>")
        writer.appendLine("    <name>g21 Survey track</name>")
        writer.appendLine("    <trkseg>")
        points.forEach { point ->
            if (
                !point.latitude.isFinite() ||
                !point.longitude.isFinite() ||
                point.latitude !in -90.0..90.0 ||
                point.longitude !in -180.0..180.0
            ) {
                throw IOException("The track contains an invalid GPS coordinate.")
            }
            writer.appendLine(
                """      <trkpt lat="${String.format(Locale.US, "%.5f", point.latitude)}" lon="${String.format(Locale.US, "%.5f", point.longitude)}">""",
            )
            writer.appendLine("        <time>${timeFormat.format(Date(point.timestamp))}</time>")
            writer.appendLine("      </trkpt>")
        }
        writer.appendLine("    </trkseg>")
        writer.appendLine("  </trk>")
        writer.appendLine("</gpx>")
    }

    private fun readSavedView(): MapViewState? {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SCALE)) return null
        val state = MapViewState(
            centerX = Double.fromBits(prefs.getLong(KEY_CENTER_X, 0L)),
            centerY = Double.fromBits(prefs.getLong(KEY_CENTER_Y, 0L)),
            scale = Double.fromBits(prefs.getLong(KEY_SCALE, 0L)),
        )
        return state.takeIf { it.centerX.isFinite() && it.centerY.isFinite() && it.scale.isFinite() && it.scale > 0.0 }
    }

    private fun saveView() {
        val state = mapView.currentView() ?: return
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CENTER_X, state.centerX.toRawBits())
            .putLong(KEY_CENTER_Y, state.centerY.toRawBits())
            .putLong(KEY_SCALE, state.scale.toRawBits())
            .apply()
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun movementThresholdMeters(): Int =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MOVEMENT_THRESHOLD, DEFAULT_MOVEMENT_THRESHOLD_METRES)
            .coerceIn(MIN_MOVEMENT_THRESHOLD_METRES, MAX_MOVEMENT_THRESHOLD_METRES)

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

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (listening || !hasLocationPermission() || trackingService?.snapshot()?.isTracking == true) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Turn on device location (GPS).", Toast.LENGTH_LONG).show()
        }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(locationListener::onLocationChanged)
            listening = true
        } catch (_: SecurityException) {
            // Permission was revoked; updates stay off until it is granted again.
        }
    }

    private fun stopLocationUpdates() {
        if (!listening) return
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        listening = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_REQUEST -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    startLocationUpdates()
                    if (pendingTrackStart) {
                        requestNotificationPermissionOrChooseTrack(trackingService?.snapshot())
                    }
                } else {
                    pendingTrackStart = false
                    Toast.makeText(this, "Location permission is needed to show your position.", Toast.LENGTH_LONG).show()
                }
            }
            NOTIFICATION_REQUEST -> {
                pendingTrackStart = false
                if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(
                        this,
                        "Tracking can continue, but Android may hide its notification.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                chooseTrackAction(trackingService?.snapshot())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        serviceBound = bindService(
            Intent(this, TrackingService::class.java),
            trackingConnection,
            BIND_AUTO_CREATE,
        )
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        mapView.setTrackingFlashVisible(mapView.visibility == View.VISIBLE)
        startLocationUpdates()
    }

    override fun onPause() {
        activityResumed = false
        mapView.setTrackingFlashVisible(false)
        stopLocationUpdates()
        saveView()
        super.onPause()
    }

    override fun onStop() {
        if (serviceBound) {
            trackingService?.setObserver(null)
            unbindService(trackingConnection)
            serviceBound = false
            trackingService = null
        }
        super.onStop()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LOCATION_REQUEST = 1
        const val NOTIFICATION_REQUEST = 2
        const val SELECT_GPX_FOLDER_REQUEST = 4
        const val PREFS = "map_view"
        const val KEY_CENTER_X = "center_x"
        const val KEY_CENTER_Y = "center_y"
        const val KEY_SCALE = "scale"
        const val KEY_GPX_FOLDER_URI = "gpx_folder_uri"
        const val KEY_GPX_FOLDER_LABEL = "gpx_folder_label"
        const val KEY_MOVEMENT_THRESHOLD = "movement_threshold"
        const val DEFAULT_MOVEMENT_THRESHOLD_METRES = 5
        const val MIN_MOVEMENT_THRESHOLD_METRES = 1
        const val MAX_MOVEMENT_THRESHOLD_METRES = 20
    }

    private enum class Screen {
        MAP,
        TRACKING,
    }

    override fun onDestroy() {
        loader.shutdownNow()
        saver.shutdown()
        super.onDestroy()
    }
}

private class NavigationItem(
    context: Context,
    private val label: String,
    private val tracking: Boolean,
) : LinearLayout(context) {
    private val icon = NavigationIcon(context, tracking)
    private val title = TextView(context).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = label
        val outValue = android.util.TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            setBackgroundResource(outValue.resourceId)
        }
        addView(icon, LayoutParams(dp(24), dp(24)))
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setItemSelected(selected: Boolean) {
        val color = if (selected) Color.rgb(32, 90, 60) else Color.rgb(100, 110, 103)
        icon.setTint(color)
        title.setTextColor(color)
        title.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class NavigationIcon(
    context: Context,
    private val tracking: Boolean,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var tint = Color.rgb(100, 110, 103)

    fun setTint(color: Int) {
        tint = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = tint
        val scale = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        if (tracking) {
            val path = Path().apply {
                moveTo(cx - 7f * scale, cy + 6f * scale)
                lineTo(cx - 3f * scale, cy + 1f * scale)
                lineTo(cx + 2f * scale, cy + 3f * scale)
                lineTo(cx + 7f * scale, cy - 5f * scale)
            }
            canvas.drawPath(path, paint)
            canvas.drawCircle(cx - 7f * scale, cy + 6f * scale, 1.8f * scale, paint)
            canvas.drawCircle(cx + 7f * scale, cy - 5f * scale, 1.8f * scale, paint)
        } else {
            val path = Path().apply {
                moveTo(cx - 8f * scale, cy - 5f * scale)
                lineTo(cx - 3f * scale, cy - 7f * scale)
                lineTo(cx + 3f * scale, cy - 5f * scale)
                lineTo(cx + 8f * scale, cy - 7f * scale)
                lineTo(cx + 8f * scale, cy + 5f * scale)
                lineTo(cx + 3f * scale, cy + 7f * scale)
                lineTo(cx - 3f * scale, cy + 5f * scale)
                lineTo(cx - 8f * scale, cy + 7f * scale)
                close()
            }
            canvas.drawPath(path, paint)
            canvas.drawLine(cx - 3f * scale, cy - 7f * scale, cx - 3f * scale, cy + 5f * scale, paint)
            canvas.drawLine(cx + 3f * scale, cy - 5f * scale, cx + 3f * scale, cy + 7f * scale, paint)
        }
    }
}

/** Map view centre in EPSG:27700 metres and scale in screen pixels per metre. */
private data class MapViewState(val centerX: Double, val centerY: Double, val scale: Double)

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
    private val trackUnderlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 7f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(204, 64, 42)
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val accuracyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 25, 118, 210) }
    private val density = resources.displayMetrics.density
    private val buttonFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val buttonOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 175, 172)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val buttonIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 118, 210)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val buttonDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 118, 210) }
    private val trackingFlashAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 800L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            flashRed = (it.animatedValue as Float) < 0.5f
            invalidate()
        }
    }
    private val buttonRadius = 22f * density
    private val buttonMargin = 16f * density
    private var touchStartedOnButton = false
    private var positionX: Double? = null
    private var positionY: Double? = null
    private var positionAccuracy: Float? = null
    private var trackingActive = false
    private var trackingFlashVisible = false
    private var flashRed = false
    private val trackPoints = mutableListOf<TrackPoint>()
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
    private var savedView: MapViewState? = null
    private var errorMessage: String? = null

    init {
        setBackgroundColor(Color.rgb(239, 240, 235))
        isClickable = true
    }

    fun setTrackingActive(active: Boolean) {
        trackingActive = active
        updateTrackingFlash()
        invalidate()
    }

    fun setTrackingFlashVisible(visible: Boolean) {
        trackingFlashVisible = visible
        updateTrackingFlash()
    }

    private fun updateTrackingFlash() {
        if (trackingActive && trackingFlashVisible) {
            if (!trackingFlashAnimator.isStarted) trackingFlashAnimator.start()
        } else {
            trackingFlashAnimator.cancel()
            flashRed = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTrackingFlash()
    }

    override fun onDetachedFromWindow() {
        trackingFlashAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateTrackingFlash()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateTrackingFlash()
    }

    private fun buttonCenterX() = buttonMargin + buttonRadius
    private fun buttonCenterY() = height - buttonMargin - buttonRadius

    private fun isOnButton(x: Float, y: Float): Boolean {
        val dx = x - buttonCenterX()
        val dy = y - buttonCenterY()
        return dx * dx + dy * dy <= buttonRadius * buttonRadius
    }

    private fun centreOnPosition() {
        val easting = positionX
        val northing = positionY
        if (easting == null || northing == null) {
            Toast.makeText(context, "Waiting for a GPS position…", Toast.LENGTH_SHORT).show()
            return
        }
        offsetX = width / 2f - easting.toFloat() * mapScale
        offsetY = height / 2f + northing.toFloat() * mapScale
        invalidate()
    }

    private fun drawLocateButton(canvas: Canvas) {
        val flashRedNow = trackingActive && flashRed
        buttonFill.color = if (flashRedNow) Color.RED else Color.WHITE
        buttonOutline.color = if (flashRedNow) Color.WHITE else Color.rgb(170, 175, 172)
        buttonIcon.color = if (flashRedNow) Color.WHITE else Color.rgb(25, 118, 210)
        buttonDot.color = if (flashRedNow) Color.WHITE else Color.rgb(25, 118, 210)
        val cx = buttonCenterX()
        val cy = buttonCenterY()
        canvas.drawCircle(cx, cy, buttonRadius, buttonFill)
        canvas.drawCircle(cx, cy, buttonRadius, buttonOutline)
        val ring = 7f * density
        val tick = 4f * density
        canvas.drawCircle(cx, cy, ring, buttonIcon)
        canvas.drawCircle(cx, cy, 2.5f * density, buttonDot)
        canvas.drawLine(cx, cy - ring - tick, cx, cy - ring + 1f * density, buttonIcon)
        canvas.drawLine(cx, cy + ring - 1f * density, cx, cy + ring + tick, buttonIcon)
        canvas.drawLine(cx - ring - tick, cy, cx - ring + 1f * density, cy, buttonIcon)
        canvas.drawLine(cx + ring - 1f * density, cy, cx + ring + tick, cy, buttonIcon)
    }

    fun setTiles(newTiles: List<RasterTile>) {
        tiles.clear()
        tiles.addAll(newTiles)
        minWesting = tiles.minOf { it.westing }
        maxWesting = tiles.maxOf { it.westing + it.bitmap.width * it.pixelWidth }
        minNorthing = tiles.minOf { it.northing - it.bitmap.height * it.pixelHeight }
        maxNorthing = tiles.maxOf { it.northing }
        errorMessage = null
        applyInitialView()
    }

    fun setPosition(easting: Double, northing: Double, accuracyMetres: Float?) {
        positionX = easting
        positionY = northing
        positionAccuracy = accuracyMetres
        invalidate()
    }

    fun setTrack(points: List<TrackPoint>) {
        trackPoints.clear()
        trackPoints.addAll(points)
        invalidate()
    }

    fun appendTrackPoint(point: TrackPoint) {
        trackPoints.add(point)
        invalidate()
    }

    fun clearTrack() {
        trackPoints.clear()
        invalidate()
    }

    fun restoreView(state: MapViewState?) {
        savedView = state
    }

    fun currentView(): MapViewState? {
        if (!hasInitialFit || width == 0 || height == 0) return null
        return MapViewState(
            centerX = ((width / 2f - offsetX) / mapScale).toDouble(),
            centerY = ((offsetY - height / 2f) / mapScale).toDouble(),
            scale = mapScale.toDouble(),
        )
    }

    fun setError(message: String) {
        errorMessage = message
        invalidate()
    }

    private fun updateFitScale() {
        val mapWidth = maxWesting - minWesting
        val mapHeight = maxNorthing - minNorthing
        fitScale = minOf(width / mapWidth.toFloat(), height / mapHeight.toFloat())
    }

    private fun applyInitialView() {
        if (tiles.isEmpty() || width == 0 || height == 0) return
        updateFitScale()
        val saved = savedView
        if (saved != null) {
            mapScale = saved.scale.toFloat().coerceIn(fitScale * 0.4f, fitScale * 32f)
            offsetX = width / 2f - saved.centerX.toFloat() * mapScale
            offsetY = height / 2f + saved.centerY.toFloat() * mapScale
        } else {
            val mapWidth = (maxWesting - minWesting).toFloat()
            val mapHeight = (maxNorthing - minNorthing).toFloat()
            mapScale = fitScale
            offsetX = (width - mapWidth * mapScale) / 2f - minWesting.toFloat() * mapScale
            offsetY = (height - mapHeight * mapScale) / 2f + maxNorthing.toFloat() * mapScale
        }
        hasInitialFit = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (tiles.isEmpty()) return
        if (!hasInitialFit) {
            applyInitialView()
            return
        }
        if (oldw > 0 && oldh > 0) {
            val centerX = (oldw / 2f - offsetX) / mapScale
            val centerY = (offsetY - oldh / 2f) / mapScale
            offsetX = w / 2f - centerX * mapScale
            offsetY = h / 2f + centerY * mapScale
        }
        updateFitScale()
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

        if (trackPoints.isNotEmpty()) {
            val path = Path()
            trackPoints.forEachIndexed { index, point ->
                val x = offsetX + point.easting.toFloat() * mapScale
                val y = offsetY - point.northing.toFloat() * mapScale
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (trackPoints.size > 1) {
                canvas.drawPath(path, trackUnderlay)
                canvas.drawPath(path, trackPaint)
            } else {
                val point = trackPoints.first()
                val x = offsetX + point.easting.toFloat() * mapScale
                val y = offsetY - point.northing.toFloat() * mapScale
                canvas.drawCircle(x, y, 4f * density, trackUnderlay)
                canvas.drawCircle(x, y, 2.5f * density, trackPaint)
            }
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
        drawLocateButton(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchStartedOnButton = hasInitialFit && isOnButton(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                if (touchStartedOnButton && isOnButton(event.x, event.y)) centreOnPosition()
                touchStartedOnButton = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> touchStartedOnButton = false
        }
        if (touchStartedOnButton) return true
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
