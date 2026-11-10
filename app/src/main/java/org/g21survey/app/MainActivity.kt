package org.g21survey.app

import android.animation.ValueAnimator
import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.app.Dialog
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
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.text.format.DateUtils
import android.util.Base64
import android.widget.Toast
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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
private data class ShortcutTemplate(val code: String, val fields: List<String>)
private data class RecordCodeGrid(val code: String, val gridReference: String)
private data class ShortcutData(
    val headers: List<String>,
    val codeColumnIndex: Int,
    val templates: List<ShortcutTemplate>,
)
private data class ShortcutLoadResult(val data: ShortcutData?, val message: String)

class MainActivity : Activity() {
    private val loader = Executors.newSingleThreadExecutor()
    private val saver = Executors.newSingleThreadExecutor()
    private lateinit var mapView: RasterMapView
    private lateinit var screenTitle: TextView
    private lateinit var trackingScreen: View
    private lateinit var settingsScreen: View
    private lateinit var recordsScreen: View
    private lateinit var trackingButton: Button
    private lateinit var trackingSaveButton: Button
    private lateinit var trackingClearButton: Button
    private lateinit var projectFolderButton: Button
    private lateinit var projectFolderLabel: TextView
    private lateinit var trackingStatus: TextView
    private lateinit var trackingSummary: TextView
    private lateinit var movementThresholdPicker: NumberPicker
    private lateinit var mapNavigationItem: NavigationItem
    private lateinit var trackingNavigationItem: NavigationItem
    private lateinit var settingsNavigationItem: NavigationItem
    private lateinit var recordsNavigationItem: NavigationItem
    private lateinit var recordsStatus: TextView
    private lateinit var recordsButtons: LinearLayout
    private val recordTemplateButtons = mutableListOf<Pair<ShortcutTemplate, Button>>()
    private val recordedCodeGrids = mutableSetOf<RecordCodeGrid>()
    private val pendingCodeGrids = mutableSetOf<RecordCodeGrid>()
    private var listening = false
    private var trackingService: TrackingService? = null
    private var serviceBound = false
    private var pendingTrackStart = false
    private var activityResumed = false
    private var isSavingTrack = false
    private var isPreparingRecordsFile = false

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
                        updateRecordButtons(currentPosition)
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
                updateRecordButtons(it)
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
        BritishNationalGrid.initialize(assets)
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
        settingsScreen = createSettingsScreen()
        content.addView(
            settingsScreen,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        settingsScreen.visibility = View.GONE
        recordsScreen = createRecordsScreen()
        content.addView(
            recordsScreen,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        recordsScreen.visibility = View.GONE

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
        settingsNavigationItem = NavigationItem(this, "Settings", tracking = false, settings = true)
        recordsNavigationItem = NavigationItem(this, "Records", tracking = false, records = true)
        mapNavigationItem.setItemSelected(true)
        trackingNavigationItem.setItemSelected(false)
        settingsNavigationItem.setItemSelected(false)
        recordsNavigationItem.setItemSelected(false)
        mapNavigationItem.setOnClickListener { showScreen(Screen.MAP) }
        trackingNavigationItem.setOnClickListener { showScreen(Screen.TRACKING) }
        settingsNavigationItem.setOnClickListener { showScreen(Screen.SETTINGS) }
        recordsNavigationItem.setOnClickListener { showScreen(Screen.RECORDS) }
        navigation.addView(
            mapNavigationItem,
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        navigation.addView(
            trackingNavigationItem,
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        navigation.addView(
            settingsNavigationItem,
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        navigation.addView(
            recordsNavigationItem,
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        root.addView(navigation)

        setContentView(root)
        restoreRecordCodeGrids()
        mapView.restoreView(readSavedView())
        loadBasemap()
        loadShortcuts()

        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
        }
    }

    private fun showScreen(screen: Screen) {
        val isMap = screen == Screen.MAP
        val isTracking = screen == Screen.TRACKING
        val isSettings = screen == Screen.SETTINGS
        screenTitle.text = when (screen) {
            Screen.MAP -> "g21 Survey"
            Screen.TRACKING -> "Tracking"
            Screen.SETTINGS -> "Settings"
            Screen.RECORDS -> "Records"
        }
        mapView.visibility = if (isMap) View.VISIBLE else View.GONE
        mapView.setTrackingFlashVisible(isMap && activityResumed)
        trackingScreen.visibility = if (isTracking) View.VISIBLE else View.GONE
        settingsScreen.visibility = if (isSettings) View.VISIBLE else View.GONE
        recordsScreen.visibility = if (screen == Screen.RECORDS) View.VISIBLE else View.GONE
        mapNavigationItem.setItemSelected(isMap)
        trackingNavigationItem.setItemSelected(isTracking)
        settingsNavigationItem.setItemSelected(isSettings)
        recordsNavigationItem.setItemSelected(screen == Screen.RECORDS)
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

    private fun createSettingsScreen(): View {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }
        screen.addView(TextView(this).apply {
            text = "Grid reference precision"
            textSize = 16f
            setTextColor(Color.rgb(55, 65, 58))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val tenFigureOption = RadioButton(this).apply {
            id = View.generateViewId()
            text = "10 fig"
        }
        val eightFigureOption = RadioButton(this).apply {
            id = View.generateViewId()
            text = "8 fig"
        }
        val precisionOptions = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(tenFigureOption)
            addView(eightFigureOption)
            check(
                if (prefs.getInt(KEY_GRID_REFERENCE_PRECISION, DEFAULT_GRID_REFERENCE_PRECISION) == 8) {
                    eightFigureOption.id
                } else {
                    tenFigureOption.id
                },
            )
            setOnCheckedChangeListener { _, checkedId ->
                val precision = if (checkedId == eightFigureOption.id) 8 else 10
                prefs.edit().putInt(KEY_GRID_REFERENCE_PRECISION, precision).apply()
            }
        }
        screen.addView(
            precisionOptions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )
        val recordSecondsOption = CheckBox(this).apply {
            text = "Record seconds with time"
            isChecked = prefs.getBoolean(KEY_RECORD_TIME_SECONDS, DEFAULT_RECORD_TIME_SECONDS)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_RECORD_TIME_SECONDS, checked).apply()
            }
        }
        screen.addView(
            recordSecondsOption,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )
        screen.addView(TextView(this).apply {
            text = "Current location name"
            textSize = 16f
            setTextColor(Color.rgb(55, 65, 58))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        screen.addView(
            EditText(this).apply {
                hint = "Enter location name"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setSingleLine(true)
                setText(prefs.getString(KEY_CURRENT_LOCATION_NAME, ""))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        prefs.edit().putString(KEY_CURRENT_LOCATION_NAME, s?.toString().orEmpty()).apply()
                    }
                })
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )
        projectFolderButton = Button(this).apply {
            text = "Choose project folder"
            setOnClickListener { chooseGpxFolder() }
        }
        projectFolderLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(100, 110, 103))
            gravity = Gravity.CENTER
            maxLines = 2
        }
        screen.addView(projectFolderButton)
        screen.addView(
            projectFolderLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
        updateGpxFolderLabel()
        return screen
    }

    private fun createRecordsScreen(): View {
        recordsStatus = TextView(this).apply {
            text = "Select a project folder in Settings to load recording templates."
            textSize = 14f
            setTextColor(Color.rgb(80, 88, 82))
            gravity = Gravity.CENTER
        }
        recordsButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(12))
            addView(
                recordsStatus,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                recordsButtons,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
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
        if (isPreparingRecordsFile) return
        isPreparingRecordsFile = true
        updateTrackingUi(trackingService?.snapshot())
        saver.execute {
            try {
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val projectFolder = prefs.getString(KEY_GPX_FOLDER_URI, null)
                    ?: throw IOException("Choose a project folder in Settings before starting tracking.")
                val shortcutResult = readShortcutTemplates()
                val shortcuts = shortcutResult.data
                    ?: throw IOException(shortcutResult.message)
                val recordsUri = createRecordsFile(Uri.parse(projectFolder), shortcuts)
                val saved = prefs.edit()
                    .putString(KEY_ACTIVE_RECORDS_URI, recordsUri.toString())
                    .putString(KEY_ACTIVE_RECORDS_PROJECT_URI, projectFolder)
                    .putString(KEY_ACTIVE_RECORDS_INDEX_URI, recordsUri.toString())
                    .putStringSet(KEY_ACTIVE_RECORDS_CODE_GRIDS, emptySet())
                    .commit()
                if (!saved) throw IOException("Unable to save the active records file.")
                runOnUiThread {
                    if (!isFinishing) {
                        isPreparingRecordsFile = false
                        recordedCodeGrids.clear()
                        pendingCodeGrids.clear()
                        updateRecordButtons(trackingService?.latestPosition())
                        updateTrackingUi(trackingService?.snapshot())
                        startTrackingService(action)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        isPreparingRecordsFile = false
                        updateTrackingUi(trackingService?.snapshot())
                        Toast.makeText(
                            this,
                            "Unable to create records CSV: ${error.message ?: "storage error"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun startTrackingService(action: String) {
        val intent = Intent(this, TrackingService::class.java).setAction(action)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateTrackingUi(snapshot: TrackSnapshot?) {
        val isTracking = snapshot?.isTracking == true
        trackingButton.isEnabled = !isSavingTrack && !isPreparingRecordsFile
        trackingButton.text = when {
            isPreparingRecordsFile -> "Preparing records CSV…"
            isTracking -> "Pause tracking"
            else -> "Start tracking"
        }
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
        projectFolderLabel.text = "Project folder: $folder"
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
            Toast.makeText(this, "Project folder selected.", Toast.LENGTH_SHORT).show()
            loadBasemap()
            loadShortcuts()
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
            Toast.makeText(this, "Choose a project folder in Settings before saving.", Toast.LENGTH_LONG).show()
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
        val projectFolder = prefs.getString(KEY_GPX_FOLDER_LABEL, "Selected folder") ?: "Selected folder"
        val folder = "$projectFolder/tracks"
        return GpxSaveResult(folder, savedFilename)
    }

    private fun saveTrackToSelectedFolder(
        treeUri: Uri,
        filename: String,
        points: List<TrackPoint>,
    ): String {
        val projectDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val tracksDocumentId = findOrCreateDirectory(treeUri, projectDocumentId, "tracks")
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, tracksDocumentId)
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
                        Toast.makeText(
                            this,
                            error.message ?: "Unable to load the basemap.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun readTiles(): List<RasterTile> {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val treeUri = prefs.getString(KEY_GPX_FOLDER_URI, null)?.let(Uri::parse)
            ?: throw IOException("Choose a project folder in Settings to load the basemap.")
        val projectDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val basemapDocumentId = findDirectory(treeUri, projectDocumentId, "basemap")
            ?: throw IOException("The selected project folder has no basemap folder.")
        val children = listChildren(treeUri, basemapDocumentId)
        val filesByName = children.associateBy { it.displayName.lowercase(Locale.ROOT) }
        val images = children
            .filter { it.displayName.endsWith(".jpg", ignoreCase = true) }
            .sortedBy { it.displayName }
        if (images.isEmpty()) {
            throw IOException("The basemap folder contains no JPEG images.")
        }

        return images.map { image ->
            val imageName = image.displayName
            val worldName = imageName.substringBeforeLast('.') + ".wld"
            val worldFile = filesByName[worldName.lowercase(Locale.ROOT)]
                ?: throw IOException("The basemap folder is missing $worldName.")
            val worldUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, worldFile.documentId)
            val values = contentResolver.openInputStream(worldUri)
                ?.bufferedReader()
                ?.useLines { lines ->
                    lines.map(String::trim).filter(String::isNotEmpty).map(String::toDouble).toList()
                } ?: throw IOException("Unable to open basemap world file $worldName.")
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

            val imageUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, image.documentId)
            val bitmap = contentResolver.openInputStream(imageUri)?.use { stream ->
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

        private fun loadShortcuts() {
            loader.execute {
                try {
                    val result = readShortcutTemplates()
                    runOnUiThread {
                        if (!isFinishing) showShortcutTemplates(result)
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (!isFinishing) {
                            showShortcutTemplates(
                                ShortcutLoadResult(
                                    null,
                                    "Unable to load shortcuts.csv: ${error.message ?: "file error"}",
                                ),
                            )
                        }
                    }
                }
            }
        }

        private fun readShortcutTemplates(): ShortcutLoadResult {
            val treeUri = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GPX_FOLDER_URI, null)
                ?.let(Uri::parse)
                ?: return ShortcutLoadResult(
                    null,
                    "Select a project folder in Settings to load recording templates.",
                )
            val projectDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val shortcutsFile = listChildren(treeUri, projectDocumentId)
                .firstOrNull {
                    it.displayName.equals("shortcuts.csv", ignoreCase = true) &&
                        it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR
                }
                ?: return ShortcutLoadResult(null, "No shortcuts.csv found in the project folder.")
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, shortcutsFile.documentId)
            val csv = contentResolver.openInputStream(fileUri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: throw IOException("Unable to open shortcuts.csv.")
            val rows = parseCsv(csv.removePrefix("\uFEFF"))
            if (rows.isEmpty()) throw IOException("shortcuts.csv is empty.")
            val codeColumn = rows.first().indexOfFirst { it.trim().equals("code", ignoreCase = true) }
            if (codeColumn < 0) throw IOException("shortcuts.csv is missing the 'code' column.")
            val templates = rows.drop(1).mapNotNull { row ->
                val code = row.getOrNull(codeColumn)?.trim()?.takeIf(String::isNotEmpty)
                code?.let { ShortcutTemplate(it, row) }
            }
            val data = ShortcutData(rows.first(), codeColumn, templates)
            val message = if (templates.isEmpty()) {
                "No recording templates found in shortcuts.csv."
            } else {
                "Choose a biological recording template."
            }
            return ShortcutLoadResult(data, message)
        }

        private fun showShortcutTemplates(result: ShortcutLoadResult) {
            recordsStatus.text = result.message
            recordsButtons.removeAllViews()
            recordTemplateButtons.clear()
            val data = result.data ?: return
            data.templates.forEach { template ->
                val button = Button(this).apply {
                    text = template.code
                    setOnClickListener { onRecordTemplateClicked(data, template) }
                }
                recordTemplateButtons.add(template to button)
                recordsButtons.addView(
                    button,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) },
                )
            }
            updateRecordButtons(trackingService?.latestPosition())
        }

        private fun updateRecordButtons(position: TrackPoint?) {
            val gridReference = position?.let { point ->
                val precision = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_GRID_REFERENCE_PRECISION, DEFAULT_GRID_REFERENCE_PRECISION)
                try {
                    BritishNationalGrid.toGridReference(point.easting, point.northing, precision)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val projectMatches = prefs.getString(KEY_GPX_FOLDER_URI, null) ==
                prefs.getString(KEY_ACTIVE_RECORDS_PROJECT_URI, null)
            recordTemplateButtons.forEach { (template, button) ->
                val key = gridReference?.let { RecordCodeGrid(template.code, it) }
                val isAlreadyRecorded = projectMatches && key != null &&
                    (key in recordedCodeGrids || key in pendingCodeGrids)
                if (button.isEnabled == isAlreadyRecorded) {
                    button.isEnabled = !isAlreadyRecorded
                }
            }
        }

        private fun restoreRecordCodeGrids() {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val activeUri = prefs.getString(KEY_ACTIVE_RECORDS_URI, null)
            if (activeUri == null || activeUri != prefs.getString(KEY_ACTIVE_RECORDS_INDEX_URI, null)) {
                return
            }
            prefs.getStringSet(KEY_ACTIVE_RECORDS_CODE_GRIDS, emptySet()).orEmpty().forEach { encoded ->
                val parts = encoded.split(':', limit = 2)
                if (parts.size == 2) {
                    val code = Base64.decode(parts[0], Base64.NO_WRAP).toString(Charsets.UTF_8)
                    recordedCodeGrids.add(RecordCodeGrid(code, parts[1]))
                }
            }
        }

        private fun persistRecordCodeGrids() {
            val encoded = recordedCodeGrids.map { key ->
                val code = Base64.encodeToString(key.code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                "$code:${key.gridReference}"
            }.toSet()
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_ACTIVE_RECORDS_CODE_GRIDS, encoded)
                .apply()
        }

        private fun onRecordTemplateClicked(data: ShortcutData, template: ShortcutTemplate) {
            val service = trackingService
            if (service?.snapshot()?.isTracking != true) {
                Toast.makeText(this, "Start tracking before recording an observation.", Toast.LENGTH_LONG).show()
                return
            }
            val point = service.latestPosition()
            if (point == null) {
                Toast.makeText(this, "Waiting for a GPS fix before recording.", Toast.LENGTH_LONG).show()
                return
            }

            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val locationName = prefs.getString(KEY_CURRENT_LOCATION_NAME, "").orEmpty().trim()
            val projectUri = prefs.getString(KEY_GPX_FOLDER_URI, null)
            if (projectUri == null || prefs.getString(KEY_ACTIVE_RECORDS_PROJECT_URI, null) != projectUri) {
                Toast.makeText(
                    this,
                    "Pause tracking and start it again to create a records file for this project folder.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            val recordsUri = prefs.getString(KEY_ACTIVE_RECORDS_URI, null)?.let(Uri::parse)
            if (recordsUri == null) {
                Toast.makeText(this, "No records CSV is active. Start tracking again.", Toast.LENGTH_LONG).show()
                return
            }

            val date = SimpleDateFormat("dd/MM/yyyy", Locale.UK).format(Date(point.timestamp))
            val includeSeconds = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RECORD_TIME_SECONDS, DEFAULT_RECORD_TIME_SECONDS)
            val timePattern = if (includeSeconds) "HH:mm:ss" else "HH:mm"
            val time = SimpleDateFormat(timePattern, Locale.UK).format(Date(point.timestamp))
            val gridPrecision = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_GRID_REFERENCE_PRECISION, DEFAULT_GRID_REFERENCE_PRECISION)
            val gridReference = try {
                BritishNationalGrid.toGridReference(point.easting, point.northing, gridPrecision)
            } catch (error: IllegalArgumentException) {
                Toast.makeText(
                    this,
                    error.message ?: "The GPS position has no valid British National Grid reference.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            val recordKey = RecordCodeGrid(template.code, gridReference)
            if (recordKey in recordedCodeGrids || recordKey in pendingCodeGrids) return
            pendingCodeGrids.add(recordKey)
            updateRecordButtons(point)
            val values = data.headers.indices
                .filter { it != data.codeColumnIndex }
                .map { index ->
                    template.fields.getOrElse(index) { "" }
                        .replace("#date#", date, ignoreCase = true)
                        .replace("#time#", time, ignoreCase = true)
                        .replace("#gr#", gridReference, ignoreCase = true)
                        .replace("#site#", locationName, ignoreCase = true)
                        .replace("#comment#", "", ignoreCase = true)
                }

            saver.execute {
                try {
                    val output = contentResolver.openOutputStream(recordsUri, "wa")
                        ?: throw IOException("Android could not open the records CSV for appending.")
                    output.bufferedWriter(Charsets.UTF_8).use { it.appendLine(encodeCsvRow(values)) }
                    runOnUiThread {
                        if (!isFinishing) {
                            pendingCodeGrids.remove(recordKey)
                            recordedCodeGrids.add(recordKey)
                            persistRecordCodeGrids()
                            updateRecordButtons(trackingService?.latestPosition())
                            showRecordCreatedMessage(locationName)
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (!isFinishing) {
                            pendingCodeGrids.remove(recordKey)
                            updateRecordButtons(trackingService?.latestPosition())
                            Toast.makeText(
                                this,
                                "Unable to add record: ${error.message ?: "storage error"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }

        private fun showRecordCreatedMessage(locationName: String) {
            val isLocationMissing = locationName.isEmpty()
            val prefix = "Record added for "
            val message = if (isLocationMissing) {
                SpannableString("Record added, but no location name was set in Settings.")
            } else {
                SpannableString("$prefix$locationName.")
            }
            if (!isLocationMissing) {
                message.setSpan(
                    StyleSpan(Typeface.BOLD),
                    prefix.length,
                    prefix.length + locationName.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            val messageView = TextView(this).apply {
                text = message
                textSize = 16f
                setTextColor(Color.rgb(45, 50, 46))
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(18), dp(24), dp(18))
                background = GradientDrawable().apply {
                    setColor(if (isLocationMissing) Color.rgb(255, 193, 7) else Color.WHITE)
                    cornerRadius = dp(12).toFloat()
                    if (!isLocationMissing) setStroke(dp(1), Color.rgb(32, 90, 60))
                }
                elevation = dp(8).toFloat()
            }
            val dialog = Dialog(this)
            dialog.setContentView(messageView)
            dialog.setCancelable(true)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.show()
            dialog.window?.apply {
                setGravity(Gravity.CENTER)
                setLayout(
                    (resources.displayMetrics.widthPixels * 0.85f).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            Handler(Looper.getMainLooper()).postDelayed(
                { if (dialog.isShowing) dialog.dismiss() },
                if (isLocationMissing) RECORD_MESSAGE_DURATION_MS else RECORD_LOCATION_MESSAGE_DURATION_MS,
            )
        }

        private fun parseCsv(contents: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var inQuotes = false
            var index = 0
            while (index < contents.length) {
                val character = contents[index]
                if (inQuotes) {
                    if (character == '"') {
                        if (index + 1 < contents.length && contents[index + 1] == '"') {
                            field.append('"')
                            index++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(character)
                    }
                } else {
                    when (character) {
                        '"' -> {
                            if (field.isNotEmpty()) throw IOException("Invalid quoting in shortcuts.csv.")
                            inQuotes = true
                        }
                        ',' -> {
                            row.add(field.toString())
                            field.setLength(0)
                        }
                        '\r', '\n' -> {
                            row.add(field.toString())
                            field.setLength(0)
                            rows.add(row)
                            row = mutableListOf()
                            if (character == '\r' && index + 1 < contents.length && contents[index + 1] == '\n') {
                                index++
                            }
                        }
                        else -> field.append(character)
                    }
                }
                index++
            }
            if (inQuotes) throw IOException("Unclosed quoted field in shortcuts.csv.")
            if (field.isNotEmpty() || row.isNotEmpty()) {
                row.add(field.toString())
                rows.add(row)
            }
            return rows
        }

        private fun createRecordsFile(projectTreeUri: Uri, shortcuts: ShortcutData): Uri {
            val projectDocumentId = DocumentsContract.getTreeDocumentId(projectTreeUri)
            val existingNames = listChildren(projectTreeUri, projectDocumentId)
                .map { it.displayName }
                .toSet()
            var createdAt = System.currentTimeMillis()
            var filename = recordsFilename(createdAt)
            while (filename in existingNames) {
                createdAt++
                filename = recordsFilename(createdAt)
            }

            val fileUri = DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(projectTreeUri, projectDocumentId),
                "text/csv",
                filename,
            ) ?: throw IOException("Android could not create $filename.")
            try {
                val output = contentResolver.openOutputStream(fileUri, "w")
                    ?: throw IOException("Android could not open $filename for writing.")
                val headers = shortcuts.headers.filterIndexed { index, _ ->
                    index != shortcuts.codeColumnIndex
                }
                output.bufferedWriter(Charsets.UTF_8).use { it.appendLine(encodeCsvRow(headers)) }
            } catch (error: Exception) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, fileUri)
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
            return fileUri
        }

        private fun recordsFilename(timestamp: Long): String =
            "records-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(timestamp))}.csv"

        private fun encodeCsvRow(values: List<String>): String =
            values.joinToString(",") { value ->
                if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
                    "\"${value.replace("\"", "\"\"")}\""
                } else {
                    value
                }
            }

    private data class ProjectDocument(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
    )

    private fun listChildren(treeUri: Uri, parentDocumentId: String): List<ProjectDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val result = mutableListOf<ProjectDocument>()
        val cursor = contentResolver.query(childrenUri, columns, null, null, null)
            ?: throw IOException("Unable to read the selected project folder.")
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (it.moveToNext()) {
                result.add(
                    ProjectDocument(
                        documentId = it.getString(idColumn),
                        displayName = it.getString(nameColumn),
                        mimeType = it.getString(mimeColumn),
                    )
                )
            }
        }
        return result
    }

    private fun findDirectory(treeUri: Uri, parentDocumentId: String, name: String): String? =
        listChildren(treeUri, parentDocumentId)
            .firstOrNull {
                it.displayName.equals(name, ignoreCase = true) &&
                    it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            }
            ?.documentId

    private fun findOrCreateDirectory(treeUri: Uri, parentDocumentId: String, name: String): String {
        val children = listChildren(treeUri, parentDocumentId)
        val existing = children.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
        if (existing != null) {
            if (existing.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
                throw IOException("A file named $name already exists in the project folder.")
            }
            return existing.documentId
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        val createdUri = DocumentsContract.createDocument(
            contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: throw IOException("Android could not create the $name folder.")
        return DocumentsContract.getDocumentId(createdUri)
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
        const val KEY_ACTIVE_RECORDS_URI = "active_records_uri"
        const val KEY_ACTIVE_RECORDS_PROJECT_URI = "active_records_project_uri"
        const val KEY_ACTIVE_RECORDS_INDEX_URI = "active_records_index_uri"
        const val KEY_ACTIVE_RECORDS_CODE_GRIDS = "active_records_code_grids"
        const val KEY_GRID_REFERENCE_PRECISION = "grid_reference_precision"
        const val KEY_RECORD_TIME_SECONDS = "record_time_seconds"
        const val KEY_CURRENT_LOCATION_NAME = "current_location_name"
        const val KEY_MOVEMENT_THRESHOLD = "movement_threshold"
        const val RECORD_MESSAGE_DURATION_MS = 2500L
        const val RECORD_LOCATION_MESSAGE_DURATION_MS = 850L
        const val DEFAULT_GRID_REFERENCE_PRECISION = 10
        const val DEFAULT_RECORD_TIME_SECONDS = true
        const val DEFAULT_MOVEMENT_THRESHOLD_METRES = 5
        const val MIN_MOVEMENT_THRESHOLD_METRES = 1
        const val MAX_MOVEMENT_THRESHOLD_METRES = 20
    }

    private enum class Screen {
        MAP,
        TRACKING,
        SETTINGS,
        RECORDS,
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
    private val settings: Boolean = false,
    private val records: Boolean = false,
) : LinearLayout(context) {
    private val icon = NavigationIcon(context, tracking, settings, records)
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
    private val settings: Boolean,
    private val records: Boolean,
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
        } else if (settings) {
            val radius = 6f * scale
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawCircle(cx, cy, 2f * scale, paint)
            for (index in 0 until 8) {
                val angle = index * Math.PI / 4.0
                val innerRadius = 7f * scale
                val outerRadius = 9f * scale
                val startX = cx + kotlin.math.cos(angle).toFloat() * innerRadius
                val startY = cy + kotlin.math.sin(angle).toFloat() * innerRadius
                val endX = cx + kotlin.math.cos(angle).toFloat() * outerRadius
                val endY = cy + kotlin.math.sin(angle).toFloat() * outerRadius
                canvas.drawLine(startX, startY, endX, endY, paint)
            }
        } else if (records) {
            for (index in -1..1) {
                val y = cy + index * 6f * scale
                canvas.drawCircle(cx - 7f * scale, y, 1f * scale, paint)
                canvas.drawLine(cx - 3f * scale, y, cx + 8f * scale, y, paint)
            }
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
