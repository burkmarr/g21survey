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
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.text.format.DateUtils
import android.util.Base64
import android.util.Xml
import android.widget.Toast
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.io.IOException
import org.xmlpull.v1.XmlPullParser
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

private data class RasterTile(
    val bitmap: Bitmap,
    val westing: Double,
    val northing: Double,
    val pixelWidth: Double,
    val pixelHeight: Double,
)

private data class SurveyFiles(val recordsUri: Uri, val trackUri: Uri)
private data class ShortcutTemplate(val code: String, val fields: List<String>, val isSurveyTaxon: Boolean)
private data class RecordCodeGrid(val code: String, val gridReference: String)
private data class RecordTemplateButton(
    val template: ShortcutTemplate,
    val button: Button,
    val identityCode: String = template.code,
    val rootView: View = button,
)
private data class ShortcutData(
    val headers: List<String>,
    val codeColumnIndex: Int,
    val surveyColumnIndex: Int,
    val tags: List<String>,
    val templates: List<ShortcutTemplate>,
) {
    val excludedColumnIndices: Set<Int>
        get() = setOf(codeColumnIndex, surveyColumnIndex)
}

private data class ShortcutLoadResult(val data: ShortcutData?, val message: String)
private data class PreviousSurveyFiles(
    val name: String,
    val displayLabel: String,
    val records: Uri?,
    val track: Uri?,
)
private data class PreviousSurveyContents(
    val records: List<SurveyRecord>,
    val squares: Set<String>,
    val tracks: List<List<TrackPoint>>,
    val warnings: List<String>,
)

class MainActivity : Activity() {
    private val loader = Executors.newSingleThreadExecutor()
    private val saver = Executors.newSingleThreadExecutor()
    private lateinit var mapView: RasterMapView
    private lateinit var screenTitle: TextView
    private lateinit var trackingScreen: View
    private lateinit var settingsScreen: View
    private lateinit var recordsScreen: View
    private lateinit var specialRecordScreen: LinearLayout
    private lateinit var editScreen: LinearLayout
    private lateinit var trackingButton: Button
    private lateinit var trackingSaveButton: Button
    private lateinit var projectFolderButton: Button
    private lateinit var projectFolderLabel: TextView
    private lateinit var trackingStatus: TextView
    private lateinit var trackingSummary: TextView
    private lateinit var mapNavigationItem: NavigationItem
    private lateinit var trackingNavigationItem: NavigationItem
    private lateinit var settingsNavigationItem: NavigationItem
    private lateinit var recordsNavigationItem: NavigationItem
    private lateinit var editNavigationItem: NavigationItem
    private lateinit var recordsStatus: TextView
    private lateinit var recordsButtons: LinearLayout
    private lateinit var previousSurveysList: LinearLayout
    private lateinit var previousSurveysStatus: TextView
    private val recordTemplateButtons = mutableListOf<RecordTemplateButton>()
    private val customRecordTemplateButtons = mutableListOf<RecordTemplateButton>()
    private val recordedCodeGrids = mutableSetOf<RecordCodeGrid>()
    private val pendingCodeGrids = mutableSetOf<RecordCodeGrid>()
    private val trackedGridReferences = mutableSetOf<String>()
    private var currentSurveyRecords: List<SurveyRecord> = emptyList()
    private var previousSurveyRecords: List<SurveyRecord> = emptyList()
    private var previousSurveySquares: Set<String> = emptySet()
    private var previousSurveysRequest = 0
    private var previousSurveysProject: String? = null
    private var previousSurveysShortcutData: ShortcutData? = null
    private var previousSurveyListLoaded = false
    private val previousSurveyFiles = mutableMapOf<String, PreviousSurveyFiles>()
    private val selectedPreviousSurveyNames = mutableSetOf<String>()
    private val previousSurveyContents = mutableMapOf<String, PreviousSurveyContents>()
    private val loadingPreviousSurveyNames = mutableSetOf<String>()
    private var listening = false
    private var trackingService: TrackingService? = null
    private var serviceBound = false
    private var pendingTrackStart = false
    private var activityResumed = false
    private var isSavingTrack = false
    private var isPreparingRecordsFile = false
    private var shortcutData: ShortcutData? = null
    private var speechCommentInput: EditText? = null
    private var currentScreen = Screen.MAP
    private var editRequest = 0
    private var locationConversionError: String? = null

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
                        addTrackedGridReferences(listOf(currentPosition))
                    } else if (snapshot?.error != null && trackingService?.latestPosition() == null) {
                        mapView.clearPosition()
                        updateRecordButtons(null)
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
            addTrackedGridReferences(points + listOfNotNull(service.latestPosition()))
            (service.latestPosition() ?: points.lastOrNull().takeIf {
                snapshot?.isTracking != true && snapshot?.error == null
            })?.let {
                mapView.setPosition(it.easting, it.northing, it.accuracyMeters)
                updateRecordButtons(it)
            }
            if (snapshot?.isTracking == true) stopLocationUpdates()
            if (currentScreen == Screen.EDIT) loadSurveyRecords()
            refreshSurveyTaxaGridCounts()
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
            try {
                val point = FalseOrigin.position(this@MainActivity, location)
                locationConversionError = null
                mapView.setPosition(point.easting, point.northing, point.accuracyMeters)
            } catch (error: IllegalArgumentException) {
                mapView.clearPosition()
                val message = error.message ?: "Could not convert the GPS position."
                if (locationConversionError != message) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
                locationConversionError = message
            }
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
        mapView.setFalseTracking(FalseOrigin.enabled(this))
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
        specialRecordScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        content.addView(
            specialRecordScreen,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        editScreen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(
            editScreen,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        editScreen.visibility = View.GONE

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
        trackingNavigationItem = NavigationItem(this, "Survey", tracking = true)
        settingsNavigationItem = NavigationItem(this, "Settings", tracking = false, settings = true)
        recordsNavigationItem = NavigationItem(this, "Records", tracking = false, records = true)
        editNavigationItem = NavigationItem(this, "Edit", tracking = false, edit = true)
        mapNavigationItem.setItemSelected(true)
        trackingNavigationItem.setItemSelected(false)
        settingsNavigationItem.setItemSelected(false)
        recordsNavigationItem.setItemSelected(false)
        editNavigationItem.setItemSelected(false)
        mapNavigationItem.setOnClickListener { showScreen(Screen.MAP) }
        trackingNavigationItem.setOnClickListener { showScreen(Screen.TRACKING) }
        settingsNavigationItem.setOnClickListener { showScreen(Screen.SETTINGS) }
        recordsNavigationItem.setOnClickListener { showScreen(Screen.RECORDS) }
        editNavigationItem.setOnClickListener { showScreen(Screen.EDIT) }
        navigation.addView(
            mapNavigationItem,
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        navigation.addView(
            trackingNavigationItem,
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        navigation.addView(
            recordsNavigationItem,
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        navigation.addView(
            editNavigationItem,
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        navigation.addView(
            settingsNavigationItem,
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        root.addView(navigation)

        setContentView(root)
        restoreRecordCodeGrids()
        restoreTrackedGridReferences()
        mapView.restoreView(readSavedView())
        loadBasemap()
        loadShortcuts()

        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
        }
    }

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        editRequest++
        if (screen != Screen.EDIT) speechCommentInput = null
        val isMap = screen == Screen.MAP
        val isTracking = screen == Screen.TRACKING
        val isSettings = screen == Screen.SETTINGS
        screenTitle.text = when (screen) {
            Screen.MAP -> "g21 Survey"
            Screen.TRACKING -> "Survey"
            Screen.SETTINGS -> "Settings"
            Screen.RECORDS -> "Records"
            Screen.EDIT -> "Edit"
        }
        mapView.visibility = if (isMap) View.VISIBLE else View.GONE
        mapView.setTrackingFlashVisible(isMap && activityResumed)
        trackingScreen.visibility = if (isTracking) View.VISIBLE else View.GONE
        settingsScreen.visibility = if (isSettings) View.VISIBLE else View.GONE
        recordsScreen.visibility = if (screen == Screen.RECORDS) View.VISIBLE else View.GONE
        specialRecordScreen.visibility = View.GONE
        editScreen.visibility = if (screen == Screen.EDIT) View.VISIBLE else View.GONE
        mapNavigationItem.setItemSelected(isMap)
        trackingNavigationItem.setItemSelected(isTracking)
        settingsNavigationItem.setItemSelected(isSettings)
        recordsNavigationItem.setItemSelected(screen == Screen.RECORDS)
        editNavigationItem.setItemSelected(screen == Screen.EDIT)
        if (screen == Screen.EDIT) loadSurveyRecords()
        if (screen == Screen.TRACKING) loadPreviousSurveys()
    }

    private fun createTrackingScreen(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val controls = sectionCard("Survey", "Start or end GPS path recording.")
        trackingStatus = TextView(this).apply {
            text = "Survey is off"
            textSize = 16f
            setTextColor(Color.rgb(55, 65, 58))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(247, 248, 246))
                cornerRadius = dp(6).toFloat()
            }
        }
        trackingButton = Button(this).apply {
            text = "Start survey"
            setOnClickListener { onTrackingButtonClicked() }
        }
        trackingSaveButton = Button(this).apply {
            text = "End survey"
            isEnabled = false
            setOnClickListener { onEndSurveyClicked() }
        }
        controls.addView(
            trackingStatus,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )
        controls.addView(
            trackingButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        controls.addView(
            trackingSaveButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        content.addView(controls)
        val summaryCard = sectionCard("Survey summary", "Track details for the current survey.")
        trackingSummary = TextView(this).apply {
            text = "No current track"
            textSize = 14f
            setTextColor(Color.rgb(55, 65, 58))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        summaryCard.addView(trackingSummary)
        content.addView(summaryCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        val previousCard = sectionCard("Previous surveys", "Select surveys to show their tracks and counts on the map.")
        previousSurveysStatus = TextView(this).apply {
            text = "Loading previous surveys..."
            textSize = 13f
            setTextColor(Color.rgb(100, 110, 103))
        }
        previousSurveysList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previousCard.addView(previousSurveysStatus)
        previousCard.addView(Button(this).apply {
            text = "Refresh previous surveys"
            setOnClickListener { loadPreviousSurveys(force = true) }
        })
        previousCard.addView(previousSurveysList)
        content.addView(previousCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(247, 248, 246))
            isFillViewport = true
            addView(content)
        }
    }

    private fun sectionCard(title: String, description: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(225, 228, 224))
            }
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(32, 90, 60))
        })
        card.addView(TextView(this).apply {
            text = description
            textSize = 13f
            setTextColor(Color.rgb(100, 110, 103))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) })
        return card
    }

    private fun createSettingsScreen(): View {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 246))
        }
        val settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        fun addCard(card: View) {
            settingsContent.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) })
        }

        val mapCountCard = sectionCard(
            "Map numbers",
            "Show survey-record totals or distinct taxa in each 100 m square. Species counts ignore records added with the # button.",
        )
        val recordsCountOption = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Number of records"
        }
        val speciesCountOption = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Number of species"
        }
        mapCountCard.addView(RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(recordsCountOption)
            addView(speciesCountOption)
            check(
                if (mapGridCountMode() == MapGridCountMode.SPECIES) {
                    speciesCountOption.id
                } else {
                    recordsCountOption.id
                },
            )
            setOnCheckedChangeListener { _, checkedId ->
                val mode = if (checkedId == speciesCountOption.id) {
                    MapGridCountMode.SPECIES
                } else {
                    MapGridCountMode.RECORDS
                }
                prefs.edit().putString(KEY_MAP_GRID_COUNT_MODE, mode.preferenceValue).apply()
                refreshSurveyTaxaGridCounts()
            }
        })
        addCard(mapCountCard)

        val recordingCard = sectionCard(
            "Recording",
            "Set the grid-reference precision and time detail used for new records.",
        )
        val tenFigureOption = RadioButton(this).apply {
            id = View.generateViewId()
            text = "10 figure"
        }
        val eightFigureOption = RadioButton(this).apply {
            id = View.generateViewId()
            text = "8 figure"
        }
        recordingCard.addView(TextView(this).apply {
            text = "Grid reference precision"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(55, 65, 58))
        })
        recordingCard.addView(RadioGroup(this).apply {
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
        })
        recordingCard.addView(CheckBox(this).apply {
            text = "Record seconds with time"
            isChecked = prefs.getBoolean(KEY_RECORD_TIME_SECONDS, DEFAULT_RECORD_TIME_SECONDS)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_RECORD_TIME_SECONDS, checked).apply()
            }
        })
        addCard(recordingCard)

        val movementCard = sectionCard(
            "Movement threshold",
            "A track point is recorded when you move farther than this distance.",
        )
        val thresholdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        thresholdRow.addView(TextView(this).apply {
            text = "Distance"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(55, 65, 58))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val thresholdValue = TextView(this).apply {
            text = "${movementThresholdMeters()} m"
            textSize = 16f
            gravity = Gravity.CENTER
            contentDescription = "Movement threshold in metres"
        }
        val decreaseThreshold = Button(this).apply {
            text = "-"
            contentDescription = "Decrease movement threshold"
            minWidth = dp(56)
            minHeight = dp(48)
        }
        val increaseThreshold = Button(this).apply {
            text = "+"
            contentDescription = "Increase movement threshold"
            minWidth = dp(56)
            minHeight = dp(48)
        }
        fun changeThreshold(delta: Int) {
            val newValue = (movementThresholdMeters() + delta)
                .coerceIn(MIN_MOVEMENT_THRESHOLD_METRES, MAX_MOVEMENT_THRESHOLD_METRES)
            prefs.edit().putInt(KEY_MOVEMENT_THRESHOLD, newValue).apply()
            trackingService?.setMovementThreshold(newValue)
            thresholdValue.text = "$newValue m"
            decreaseThreshold.isEnabled = newValue > MIN_MOVEMENT_THRESHOLD_METRES
            increaseThreshold.isEnabled = newValue < MAX_MOVEMENT_THRESHOLD_METRES
        }
        decreaseThreshold.setOnClickListener { changeThreshold(-1) }
        increaseThreshold.setOnClickListener { changeThreshold(1) }
        val initialThreshold = movementThresholdMeters()
        decreaseThreshold.isEnabled = initialThreshold > MIN_MOVEMENT_THRESHOLD_METRES
        increaseThreshold.isEnabled = initialThreshold < MAX_MOVEMENT_THRESHOLD_METRES
        thresholdRow.addView(decreaseThreshold)
        thresholdRow.addView(thresholdValue, LinearLayout.LayoutParams(
            dp(72),
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        thresholdRow.addView(increaseThreshold)
        movementCard.addView(thresholdRow)
        addCard(movementCard)

        val locationCard = sectionCard("Current location", "This name is added to new records.")
        locationCard.addView(EditText(this).apply {
            hint = "Enter location name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.rgb(205, 211, 206))
            }
            setText(prefs.getString(KEY_CURRENT_LOCATION_NAME, ""))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(KEY_CURRENT_LOCATION_NAME, s?.toString().orEmpty()).apply()
                }
            })
        })
        addCard(locationCard)

        val testingCard = sectionCard(
            "Testing",
            "Shift GPS positions to the midpoint of an 8-figure grid reference.",
        )
        val falseOriginInput = EditText(this).apply {
            hint = "False origin"
            contentDescription = "False origin"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
            setText(FalseOrigin.reference(this@MainActivity))
            isEnabled = !FalseOrigin.enabled(this@MainActivity)
        }
        val falseOriginToggle = CheckBox(this).apply {
            text = "Use false origin"
            isChecked = FalseOrigin.enabled(this@MainActivity)
        }
        falseOriginInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                FalseOrigin.setReference(this@MainActivity, s?.toString().orEmpty())
            }
        })
        falseOriginToggle.setOnCheckedChangeListener { button, checked ->
            try {
                FalseOrigin.setEnabled(this, checked, falseOriginInput.text.toString())
                falseOriginInput.isEnabled = !checked
                mapView.setFalseTracking(checked)
                val service = trackingService
                service?.onFalseOriginChanged()
                if (service?.snapshot()?.isTracking != true) {
                    val position = FalseOrigin.lastPosition(this)
                    if (position == null) mapView.clearPosition()
                    else mapView.setPosition(position.easting, position.northing, position.accuracyMeters)
                }
            } catch (error: IllegalArgumentException) {
                button.isChecked = false
                falseOriginInput.error = error.message ?: "Invalid false origin."
                Toast.makeText(this, falseOriginInput.error, Toast.LENGTH_LONG).show()
            }
        }
        testingCard.addView(falseOriginToggle)
        testingCard.addView(TextView(this).apply {
            text = "False origin"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(55, 65, 58))
        })
        testingCard.addView(falseOriginInput)
        addCard(testingCard)

        val projectCard = sectionCard("Project folder", "Choose the folder containing this project's files.")
        projectFolderButton = Button(this).apply {
            text = "Choose project folder"
            setOnClickListener { chooseGpxFolder() }
        }
        projectFolderLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(100, 110, 103))
            gravity = Gravity.START
            maxLines = 2
        }
        projectCard.addView(projectFolderButton)
        projectCard.addView(projectFolderLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        addCard(projectCard)
        updateGpxFolderLabel()
        screen.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(settingsContent)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
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
        if (getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SURVEY_END_ACTION)) {
            Toast.makeText(this, "Finish ending the previous survey first.", Toast.LENGTH_LONG).show()
            return
        }
        val service = trackingService
        if (service == null) {
            Toast.makeText(this, "Tracking is still starting. Try again shortly.", Toast.LENGTH_SHORT).show()
            return
        }
        if (service.snapshot() != null) {
            return
        }
        if (!hasLocationPermission()) {
            pendingTrackStart = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
            return
        }
        requestNotificationPermissionOrStartSurvey()
    }

    private fun requestNotificationPermissionOrStartSurvey() {
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingTrackStart = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            return
        }
        pendingTrackStart = false
        requestTrackingService()
    }

    private fun requestTrackingService() {
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
                if (prefs.contains(KEY_RECORDS_WRITE_JOURNAL)) {
                    val previousUri = prefs.getString(KEY_ACTIVE_RECORDS_URI, null)?.let(Uri::parse)
                        ?: throw IOException("The previous records update needs recovery, but its file is unavailable.")
                    readSurveyRecords(previousUri, shortcuts)
                }
                val files = createSurveyFiles(Uri.parse(projectFolder), shortcuts)
                val saved = prefs.edit()
                    .putString(KEY_ACTIVE_RECORDS_URI, files.recordsUri.toString())
                    .putString(KEY_ACTIVE_RECORDS_PROJECT_URI, projectFolder)
                    .putString(KEY_ACTIVE_RECORDS_INDEX_URI, files.recordsUri.toString())
                    .putStringSet(KEY_ACTIVE_RECORDS_CODE_GRIDS, emptySet())
                    .putString(KEY_ACTIVE_RECORDS_METADATA, "[]")
                    .putStringSet(KEY_ACTIVE_TRACKED_GRID_REFERENCES, emptySet())
                    .putString(KEY_ACTIVE_TRACK_URI, files.trackUri.toString())
                    .putString(KEY_ACTIVE_TRACK_PROJECT_URI, projectFolder)
                    .commit()
                if (!saved) {
                    val error = IOException("Unable to save the active survey files.")
                    cleanupSurveyFiles(files, error)
                    throw error
                }
                runOnUiThread {
                    if (!isFinishing) {
                        isPreparingRecordsFile = false
                        showShortcutTemplates(shortcutResult)
                        recordedCodeGrids.clear()
                        pendingCodeGrids.clear()
                        trackedGridReferences.clear()
                        currentSurveyRecords = emptyList()
                        updateSurveyGridOverlay()
                        updateRecordButtons(trackingService?.latestPosition())
                        updateTrackingUi(trackingService?.snapshot())
                        startTrackingService(TrackingService.ACTION_START_SURVEY)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        isPreparingRecordsFile = false
                        updateTrackingUi(trackingService?.snapshot())
                        Toast.makeText(
                            this,
                            "Unable to start survey: ${error.message ?: "storage error"}",
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
        val endPending = getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SURVEY_END_ACTION)
        trackingButton.isEnabled = !isSavingTrack && !isPreparingRecordsFile
        trackingButton.text = when {
            isPreparingRecordsFile -> "Preparing survey files…"
            else -> "Start survey"
        }
        trackingButton.isEnabled = trackingButton.isEnabled && snapshot == null && !endPending
        trackingSaveButton.isEnabled = !isSavingTrack && (snapshot != null || endPending)
        trackingSaveButton.text = if (isSavingTrack) "Saving…" else "End survey"
        trackingStatus.text = when {
            snapshot?.error != null -> snapshot.error
            isTracking -> "Survey in progress"
            endPending -> "Survey ending incomplete; press End survey to retry"
            snapshot != null -> "Survey stopped; end survey to save"
            else -> "Survey is off"
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

    private fun requestCommentSpeech(input: EditText) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a record comment")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechCommentInput = input
        try {
            startActivityForResult(intent, SPEECH_COMMENT_REQUEST)
        } catch (_: ActivityNotFoundException) {
            speechCommentInput = null
            Toast.makeText(this, "Speech recognition is not available on this device.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_COMMENT_REQUEST) {
            if (resultCode != RESULT_OK) return
            val input = speechCommentInput ?: return
            val recognizedText = data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (recognizedText.isNullOrBlank()) {
                Toast.makeText(this, "No speech was recognized.", Toast.LENGTH_SHORT).show()
                return
            }
            val comment = appendRecognizedSpeech(input.text.toString(), recognizedText)
            input.setText(comment)
            input.setSelection(comment.length)
            return
        }
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
            loadPreviousSurveys()
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Unable to access the selected folder: ${error.message ?: "permission error"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun onEndSurveyClicked() {
        val service = trackingService
        val snapshot = service?.snapshot()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (isSavingTrack) return
        val pending = prefs.getString(KEY_SURVEY_END_ACTION, null)
        if (pending != null) {
            val action = try {
                SurveyEndAction.valueOf(pending)
            } catch (error: IllegalArgumentException) {
                Toast.makeText(this, "Unable to retry ending the survey: invalid saved action.", Toast.LENGTH_LONG).show()
                return
            }
            endSurvey(action)
            return
        }
        if (service == null || snapshot == null) return
        AlertDialog.Builder(this)
            .setTitle("End survey")
            .setMessage("Archive the records and track, or permanently delete both files?")
            .setPositiveButton("Archive") { _, _ -> endSurvey(SurveyEndAction.ARCHIVE) }
            .setNegativeButton("Delete") { _, _ -> endSurvey(SurveyEndAction.DELETE) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun endSurvey(action: SurveyEndAction) {
        val service = trackingService
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.contains(KEY_SURVEY_END_ACTION)
        if (isSavingTrack || (!pending && service?.snapshot() == null)) return
        isSavingTrack = true
        if (service?.snapshot()?.isTracking == true) service.stopTrack()
        val points = service?.trackPoints().orEmpty()
        updateTrackingUi(service?.snapshot())
        if (currentScreen == Screen.EDIT) loadSurveyRecords()
        saver.execute {
            try {
                val project = prefs.getString(KEY_ACTIVE_TRACK_PROJECT_URI, null)
                    ?: throw IOException("No project folder is active for this survey.")
                if (project != prefs.getString(KEY_ACTIVE_RECORDS_PROJECT_URI, null)) {
                    throw IOException("The survey files belong to different project folders.")
                }
                val treeUri = Uri.parse(project)
                val recordsUri = prefs.getString(KEY_ACTIVE_RECORDS_URI, null)?.let(Uri::parse)
                    ?: throw IOException("No records CSV is active for this survey.")
                val trackUri = prefs.getString(KEY_ACTIVE_TRACK_URI, null)?.let(Uri::parse)
                    ?: throw IOException("No GPX file is active for this survey.")
                val sources = listOf(recordsUri, trackUri)
                if (!pending) {
                    val children = surveySourceDocuments(treeUri)
                    sources.forEach { uri ->
                        if (children.none { it.documentId == DocumentsContract.getDocumentId(uri) }) {
                            throw IOException("A survey file is missing from the surveys folder.")
                        }
                    }
                    if (action == SurveyEndAction.ARCHIVE && prefs.contains(KEY_RECORDS_WRITE_JOURNAL)) {
                        val shortcuts = readShortcutTemplates().data
                            ?: throw IOException("Unable to recover the records CSV; check the selected project.")
                        readSurveyRecords(recordsUri, shortcuts)
                    }
                    if (action == SurveyEndAction.ARCHIVE) saveTrackToDocument(trackUri, points)
                    if (!prefs.edit().putString(KEY_SURVEY_END_ACTION, action.name).commit()) {
                        throw IOException("Unable to save the survey ending state.")
                    }
                }
                finalizeSurvey(action, surveyFinalizationStorage(treeUri, sources))
                if (!prefs.edit()
                        .remove(KEY_ACTIVE_RECORDS_URI)
                        .remove(KEY_ACTIVE_RECORDS_PROJECT_URI)
                        .remove(KEY_ACTIVE_RECORDS_INDEX_URI)
                        .remove(KEY_ACTIVE_RECORDS_CODE_GRIDS)
                        .remove(KEY_ACTIVE_RECORDS_METADATA)
                        .remove(KEY_ACTIVE_TRACKED_GRID_REFERENCES)
                        .remove(KEY_ACTIVE_TRACK_URI)
                        .remove(KEY_ACTIVE_TRACK_PROJECT_URI)
                        .remove(KEY_RECORDS_WRITE_JOURNAL)
                        .remove(KEY_SURVEY_END_ACTION)
                        .remove(KEY_ARCHIVE_RECORDS_URI)
                        .remove(KEY_ARCHIVE_TRACK_URI)
                        .remove(KEY_ARCHIVE_READY)
                        .commit()
                ) throw IOException("Unable to clear the survey ending state.")
                runOnUiThread {
                    val cleared = service?.snapshot() == null || service.clearTrack()
                    if (!isFinishing) {
                        isSavingTrack = false
                        clearCustomRecordButtons()
                        if (!cleared) {
                            Toast.makeText(
                                this,
                                "Survey files processed, but the in-memory track could not be cleared.",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            recordedCodeGrids.clear()
                            pendingCodeGrids.clear()
                            trackedGridReferences.clear()
                            currentSurveyRecords = emptyList()
                            updateSurveyGridOverlay()
                            loadPreviousSurveys()
                            updateRecordButtons(trackingService?.latestPosition())
                            if (currentScreen == Screen.EDIT) loadSurveyRecords()
                            val result = if (action == SurveyEndAction.ARCHIVE) "archived" else "deleted"
                            Toast.makeText(this, "Survey $result.", Toast.LENGTH_LONG).show()
                        }
                        updateTrackingUi(trackingService?.snapshot())
                    }

                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        isSavingTrack = false
                        updateTrackingUi(trackingService?.snapshot())
                        Toast.makeText(
                            this,
                            "Unable to end survey: ${error.message ?: "storage error"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun surveySourceDocuments(treeUri: Uri): List<ProjectDocument> {
        val projectId = DocumentsContract.getTreeDocumentId(treeUri)
        val surveysId = findDirectory(treeUri, projectId, "surveys")
            ?: throw IOException("The surveys folder is missing.")
        val currentId = findDirectory(treeUri, surveysId, "current")
            ?: throw IOException("The surveys/current folder is missing.")
        return listChildren(treeUri, currentId)
    }

    private fun surveyFinalizationStorage(treeUri: Uri, sources: List<Uri>): SurveyFinalizationStorage {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val archiveKeys = listOf(KEY_ARCHIVE_RECORDS_URI, KEY_ARCHIVE_TRACK_URI)
        return object : SurveyFinalizationStorage {
            override fun archiveReady(): Boolean = prefs.getBoolean(KEY_ARCHIVE_READY, false)

            override fun copyToArchive(index: Int) {
                val source = sources[index]
                val document = surveySourceDocuments(treeUri)
                    .firstOrNull { it.documentId == DocumentsContract.getDocumentId(source) }
                    ?: throw IOException("A survey file is missing; the archive cannot be completed.")
                val projectId = DocumentsContract.getTreeDocumentId(treeUri)
                val surveysId = findOrCreateDirectory(treeUri, projectId, "surveys")
                val previousId = findOrCreateDirectory(treeUri, surveysId, "previous")
                val key = archiveKeys[index]
                val destination = prefs.getString(key, null)?.let(Uri::parse) ?: run {
                    if (listChildren(treeUri, previousId).any { it.displayName == document.displayName }) {
                        throw IOException("${document.displayName} already exists in surveys/previous.")
                    }
                    val created = DocumentsContract.createDocument(
                        contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, previousId),
                        document.mimeType,
                        document.displayName,
                    ) ?: throw IOException("Unable to create archive file ${document.displayName}.")
                    if (!prefs.edit().putString(key, created.toString()).commit()) {
                        deleteDocument(created)
                        throw IOException("Unable to save the archive file location.")
                    }
                    created
                }
                val input = contentResolver.openInputStream(source)
                    ?: throw IOException("Unable to read ${document.displayName}.")
                input.use {
                    val output = contentResolver.openOutputStream(destination, "wt")
                        ?: throw IOException("Unable to write archive file ${document.displayName}.")
                    output.use { target -> it.copyTo(target) }
                }
            }

            override fun markArchiveReady() {
                if (!prefs.edit().putBoolean(KEY_ARCHIVE_READY, true).commit()) {
                    throw IOException("Unable to save the archive completion state.")
                }
            }

            override fun deleteSource(index: Int) {
                val source = sources[index]
                if (surveySourceDocuments(treeUri).any {
                        it.documentId == DocumentsContract.getDocumentId(source)
                    }) {
                    deleteDocument(source)
                }
            }
        }
    }

    private fun saveTrackToDocument(fileUri: Uri, points: List<TrackPoint>) {
        val output = contentResolver.openOutputStream(fileUri, "wt")
            ?: throw IOException("Android could not open the survey GPX file for writing.")
        output.bufferedWriter(Charsets.UTF_8).use { writeGpx(it, points) }
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
            val surveyColumn = rows.first().indexOfFirst { it.trim().equals("survey", ignoreCase = true) }
            if (surveyColumn < 0) throw IOException("shortcuts.csv is missing the 'survey' column.")
            val tagsRow = rows.getOrNull(1)?.takeIf {
                it.getOrNull(codeColumn)?.trim()?.equals("tags", ignoreCase = true) == true
            }
            val templateRows = rows.drop(if (tagsRow == null) 1 else 2)
            val templates = templateRows.mapNotNull { row ->
                val code = row.getOrNull(codeColumn)?.trim()?.takeIf(String::isNotEmpty)
                code?.let {
                    val surveyValue = row.getOrNull(surveyColumn).orEmpty().trim()
                    val isSurveyTaxon = when {
                        surveyValue.isEmpty() -> false
                        surveyValue.equals("yes", ignoreCase = true) -> true
                        else -> throw IOException(
                            "The survey value for code '$it' must be 'yes' or blank.",
                        )
                    }
                    ShortcutTemplate(it, row, isSurveyTaxon)
                }
            }
            val data = ShortcutData(rows.first(), codeColumn, surveyColumn, tagsRow.orEmpty(), templates)
            val message = if (templates.isEmpty()) {
                "No recording templates found in shortcuts.csv."
            } else {
                "Choose a biological recording template."
            }
            return ShortcutLoadResult(data, message)
        }

        private fun showShortcutTemplates(result: ShortcutLoadResult) {
            shortcutData = result.data
            refreshSurveyTaxaGridCounts()
            loadPreviousSurveys()
            recordsStatus.text = result.message
            recordsButtons.removeAllViews()
            recordTemplateButtons.clear()
            customRecordTemplateButtons.clear()
            if (currentScreen == Screen.EDIT) loadSurveyRecords()
            val data = result.data ?: return
            val regularTemplates = data.templates.filter { it.code != "#" }
            regularTemplates.chunked(3).forEach { rowTemplates ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                rowTemplates.forEach { template ->
                    val button = Button(this).apply {
                        text = template.code
                        textSize = 13f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        minWidth = 0
                        minHeight = dp(48)
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                        setOnClickListener { onRecordTemplateClicked(data, template) }
                    }
                    recordTemplateButtons.add(RecordTemplateButton(template, button))
                    row.addView(button, LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                    })
                }
                repeat(3 - rowTemplates.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1f,
                    ).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                    })
                }
                recordsButtons.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(4) },
                )
            }
            data.templates.filter { it.code == "#" }.forEach { template ->
                val button = Button(this).apply {
                    text = template.code
                    setOnClickListener { onRecordTemplateClicked(data, template) }
                }
                recordTemplateButtons.add(RecordTemplateButton(template, button))
                recordsButtons.addView(
                    button,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) },
                )
            }
            updateRecordButtons(trackingService?.latestPosition())
            refreshCustomRecordButtons(data)
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
            recordTemplateButtons.forEach { entry ->
                val key = gridReference?.let { RecordCodeGrid(entry.identityCode, it) }
                val isAlreadyRecorded = projectMatches && key != null &&
                    (key in recordedCodeGrids || key in pendingCodeGrids)
                if (entry.button.isEnabled == isAlreadyRecorded) {
                    entry.button.isEnabled = !isAlreadyRecorded
                }
            }
        }

        private fun refreshCustomRecordButtons(data: ShortcutData) {
            val template = data.templates.singleOrNull { it.code == "#" } ?: return
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (isSavingTrack || prefs.contains(KEY_SURVEY_END_ACTION)) return
            if (prefs.getString(KEY_ACTIVE_RECORDS_PROJECT_URI, null) !=
                prefs.getString(KEY_GPX_FOLDER_URI, null)
            ) {
                return
            }
            val uri = prefs.getString(KEY_ACTIVE_RECORDS_URI, null)?.let(Uri::parse) ?: return
            saver.execute {
                try {
                    if (prefs.contains(KEY_SURVEY_END_ACTION) ||
                        prefs.getString(KEY_ACTIVE_RECORDS_URI, null) != uri.toString()
                    ) return@execute
                    val loadedRecords = readSurveyRecords(uri, data)
                    val records = applyCustomScientificLabels(
                        loadedRecords,
                        data.tags,
                        data.excludedColumnIndices,
                    )
                    if (records != loadedRecords) {
                        writeSurveyRecords(uri, data, loadedRecords, records)
                    }
                    runOnUiThread {
                        if (!isFinishing && shortcutData == data &&
                            !prefs.contains(KEY_SURVEY_END_ACTION) &&
                            prefs.getString(KEY_ACTIVE_RECORDS_URI, null) == uri.toString() &&
                            prefs.getString(KEY_ACTIVE_RECORDS_PROJECT_URI, null) ==
                                prefs.getString(KEY_GPX_FOLDER_URI, null)
                        ) {
                            clearCustomRecordButtons()
                            records.asSequence()
                                .mapNotNull { record ->
                                    val reference = record.customReferenceCode
                                    val buttonCode = record.customButtonCode
                                    if (reference.isNullOrBlank() || buttonCode.isNullOrBlank()) {
                                        null
                                    } else {
                                        SpecialRecordButton(
                                            reference,
                                            buttonCode,
                                            record.customButtonComment,
                                        )
                                    }
                                }
                                .forEach { specialButton ->
                                    val button = Button(this).apply {
                                        text = specialButton.label
                                        setOnClickListener {
                                            onRecordTemplateClicked(
                                                data,
                                                template,
                                                scientificOverride = specialButton.label,
                                                identityCode = specialButton.label,
                                            )
                                        }
                                    }
                                    val rootView = if (specialButton.comment.isNullOrBlank()) {
                                        button
                                    } else {
                                        LinearLayout(this).apply {
                                            orientation = LinearLayout.VERTICAL
                                            addView(button, LinearLayout.LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                            ))
                                            addView(TextView(this@MainActivity).apply {
                                                text = specialButton.comment
                                                textSize = 13f
                                                setTextColor(Color.rgb(100, 110, 103))
                                                setPadding(dp(12), dp(2), dp(12), dp(8))
                                            })
                                        }
                                    }
                                    val entry = RecordTemplateButton(
                                        template,
                                        button,
                                        specialButton.label,
                                        rootView,
                                    )
                                    customRecordTemplateButtons.add(entry)
                                    recordTemplateButtons.add(entry)
                                    recordsButtons.addView(
                                        rootView,
                                        LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                        ).apply { topMargin = dp(8) },
                                    )
                                }
                            updateRecordButtons(trackingService?.latestPosition())
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (!isFinishing && shortcutData == data) {
                            Toast.makeText(
                                this,
                                "Unable to load custom record buttons: ${error.message ?: "storage error"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }

        private fun clearCustomRecordButtons() {
            customRecordTemplateButtons.forEach { entry ->
                recordsButtons.removeView(entry.rootView)
                recordTemplateButtons.remove(entry)
            }
            customRecordTemplateButtons.clear()
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

        private fun restoreTrackedGridReferences() {
            trackedGridReferences.clear()
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val activeUri = prefs.getString(KEY_ACTIVE_RECORDS_URI, null)
            if (activeUri == null || activeUri != prefs.getString(KEY_ACTIVE_RECORDS_INDEX_URI, null)) {
                return
            }
            trackedGridReferences.addAll(
                prefs.getStringSet(KEY_ACTIVE_TRACKED_GRID_REFERENCES, emptySet()).orEmpty(),
            )
            updateSurveyGridOverlay()
        }

        private fun addTrackedGridReferences(points: List<TrackPoint>) {
            val references = points.mapNotNull { point ->
                try {
                    BritishNationalGrid.toSixFigureGridReference(
                        BritishNationalGrid.toGridReference(point.easting, point.northing, 8),
                    )
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            addTrackedReferences(references)
        }

        private fun addTrackedReferences(references: Collection<String>) {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_ACTIVE_RECORDS_URI, null) == null ||
                prefs.getString(KEY_ACTIVE_RECORDS_PROJECT_URI, null) !=
                prefs.getString(KEY_GPX_FOLDER_URI, null)
            ) {
                return
            }
            var changed = false
            references.forEach { reference ->
                if (trackedGridReferences.add(reference)) {
                    changed = true
                }
            }
            if (changed) {
                prefs.edit()
                    .putStringSet(KEY_ACTIVE_TRACKED_GRID_REFERENCES, trackedGridReferences.toSet())
                    .apply()
                updateSurveyGridOverlay()
            }
        }

        private fun previousSelectionKey(project: String): String =
            "previous_surveys_" + Base64.encodeToString(project.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        private fun loadPreviousSurveys(force: Boolean = false) {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val project = prefs.getString(KEY_GPX_FOLDER_URI, null)
            if (project == null) {
                previousSurveysRequest++
                previousSurveysProject = null
                previousSurveysShortcutData = null
                previousSurveyListLoaded = false
                previousSurveyFiles.clear()
                selectedPreviousSurveyNames.clear()
                previousSurveyContents.clear()
                loadingPreviousSurveyNames.clear()
                previousSurveyRecords = emptyList()
                previousSurveySquares = emptySet()
                mapView.setPreviousTracks(emptyList())
                updateSurveyGridOverlay()
                previousSurveysList.removeAllViews()
                previousSurveysStatus.text = "Select a project folder to view previous surveys."
                return
            }
            val data = shortcutData
            if (data == null) {
                previousSurveysStatus.text = "Loading shortcuts before previous surveys..."
                return
            }
            if (
                !force &&
                previousSurveyListLoaded &&
                previousSurveysProject == project &&
                previousSurveysShortcutData == data
            ) {
                refreshPreviousSurveySelection()
                return
            }

            val request = ++previousSurveysRequest
            previousSurveysProject = project
            previousSurveysShortcutData = data
            previousSurveyListLoaded = false
            previousSurveyFiles.clear()
            previousSurveyContents.clear()
            loadingPreviousSurveyNames.clear()
            selectedPreviousSurveyNames.clear()
            selectedPreviousSurveyNames.addAll(
                prefs.getStringSet(previousSelectionKey(project), emptySet()).orEmpty(),
            )
            val selectedForLoad = selectedPreviousSurveyNames.toSet()
            previousSurveyRecords = emptyList()
            previousSurveySquares = emptySet()
            mapView.setPreviousTracks(emptyList())
            updateSurveyGridOverlay()
            previousSurveysList.removeAllViews()
            previousSurveysStatus.text = "Loading previous surveys..."
            loader.execute {
                try {
                    val tree = Uri.parse(project)
                    val projectId = DocumentsContract.getTreeDocumentId(tree)
                    val surveysId = findDirectory(tree, projectId, "surveys")
                    val previousId = surveysId?.let { findDirectory(tree, it, "previous") }
                    val documents = previousId?.let { listChildren(tree, it) }.orEmpty()
                        .filter { it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }
                    val names = (documents.mapNotNull {
                        when {
                            it.displayName.startsWith("g21s-") && it.displayName.endsWith("-records.csv") ->
                                it.displayName.removeSuffix("-records.csv")
                            it.displayName.startsWith("g21s-") && it.displayName.endsWith("-track.gpx") ->
                                it.displayName.removeSuffix("-track.gpx")
                            else -> null
                        }
                    } + selectedForLoad).distinct().sortedDescending()
                    val files = names.map { name ->
                        fun uri(suffix: String): Uri? = documents
                            .firstOrNull { it.displayName == name + suffix }
                            ?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.documentId) }
                        val recordsUri = uri("-records.csv")
                        val locationIndex = data?.tags?.indexOfFirst { it.trim().equals("site", true) } ?: -1
                        val locationHeader = data?.headers?.getOrNull(locationIndex)
                        val displayLabel = try {
                            val csv = recordsUri?.let { parseCsv(readRecordsText(it).removePrefix("\uFEFF")) }
                            previousSurveyDisplayLabel(name, csv, locationHeader)
                        } catch (_: IOException) {
                            previousSurveyDisplayLabel(name, null, locationHeader)
                        } catch (_: SecurityException) {
                            previousSurveyDisplayLabel(name, null, locationHeader)
                        }
                        PreviousSurveyFiles(name, displayLabel, recordsUri, uri("-track.gpx"))
                    }
                    runOnUiThread {
                        if (isFinishing || request != previousSurveysRequest) return@runOnUiThread
                        previousSurveyFiles.clear()
                        previousSurveyFiles.putAll(files.associateBy { it.name })
                        previousSurveyListLoaded = true
                        files.forEach { survey ->
                            val checkbox = CheckBox(this).apply {
                                text = survey.displayLabel
                                isChecked = survey.name in selectedPreviousSurveyNames
                                setOnCheckedChangeListener { _, checked ->
                                    if (checked) {
                                        selectedPreviousSurveyNames.add(survey.name)
                                    } else {
                                        selectedPreviousSurveyNames.remove(survey.name)
                                    }
                                    prefs.edit().putStringSet(
                                        previousSelectionKey(project),
                                        selectedPreviousSurveyNames.toSet(),
                                    ).apply()
                                    if (checked) loadPreviousSurveyContents(survey, project, data, request)
                                    refreshPreviousSurveySelection()
                                }
                            }
                            previousSurveysList.addView(checkbox)
                        }
                        selectedPreviousSurveyNames.forEach { name ->
                            previousSurveyFiles[name]?.let { survey ->
                                loadPreviousSurveyContents(survey, project, data, request)
                            }
                        }
                        refreshPreviousSurveySelection()
                    }
                } catch (error: IOException) {
                    runOnUiThread {
                        if (!isFinishing && request == previousSurveysRequest) {
                            previousSurveysStatus.text = "Unable to load previous surveys: ${error.message}"
                        }
                    }
                } catch (error: SecurityException) {
                    runOnUiThread {
                        if (!isFinishing && request == previousSurveysRequest) {
                            previousSurveysStatus.text = "Unable to access previous surveys: ${error.message}"
                        }
                    }
                } catch (error: IllegalArgumentException) {
                    runOnUiThread {
                        if (!isFinishing && request == previousSurveysRequest) {
                            previousSurveysStatus.text = "Unable to load previous surveys: ${error.message}"
                        }
                    }
                }
            }
        }

        private fun loadPreviousSurveyContents(
            survey: PreviousSurveyFiles,
            project: String,
            data: ShortcutData,
            request: Int,
        ) {
            if (survey.name in previousSurveyContents || !loadingPreviousSurveyNames.add(survey.name)) return
            loader.execute {
                val contents = readPreviousSurveyContents(survey, data)
                runOnUiThread {
                    if (
                        !isFinishing &&
                        request == previousSurveysRequest &&
                        project == previousSurveysProject &&
                        data == previousSurveysShortcutData
                    ) {
                        loadingPreviousSurveyNames.remove(survey.name)
                        previousSurveyContents[survey.name] = contents
                        refreshPreviousSurveySelection()
                    }
                }
            }
        }

        private fun readPreviousSurveyContents(
            survey: PreviousSurveyFiles,
            data: ShortcutData,
        ): PreviousSurveyContents {
            val warnings = mutableListOf<String>()
            val records = mutableListOf<SurveyRecord>()
            val squares = mutableSetOf<String>()
            val tracks = mutableListOf<List<TrackPoint>>()
            try {
                val trackUri = survey.track ?: throw IOException("Track GPX is missing.")
                val track = readPreviousTrack(trackUri)
                tracks.addAll(track.segments)
                squares.addAll(track.squares)
            } catch (error: IOException) {
                warnings.add("${survey.name}: ${error.message}")
            } catch (error: org.xmlpull.v1.XmlPullParserException) {
                warnings.add("${survey.name}: invalid GPX: ${error.message}")
            } catch (error: IllegalArgumentException) {
                warnings.add("${survey.name}: invalid track: ${error.message}")
            } catch (error: SecurityException) {
                warnings.add("${survey.name}: cannot read track: ${error.message}")
            }
            try {
                val recordUri = survey.records ?: throw IOException("Records CSV is missing.")
                val scientificIndex = data.tags.indexOfFirst { it.trim().equals("scientific", true) }
                val gridIndex = data.tags.indexOfFirst { it.trim().equals("gr", true) }
                if (scientificIndex !in data.headers.indices || gridIndex !in data.headers.indices) {
                    throw IOException("shortcuts.csv needs scientific and gr tags to match records.")
                }
                val matched = matchPreviousRecords(
                    parseCsv(readRecordsText(recordUri).removePrefix("\uFEFF")),
                    data.headers[scientificIndex],
                    data.headers[gridIndex],
                    data.templates.map {
                        PreviousTemplate(it.code, it.fields.getOrNull(scientificIndex).orEmpty())
                    },
                )
                records.addAll(matched.records)
                squares.addAll(matched.squares)
                warnings.addAll(matched.warnings.map { "${survey.name}: $it" })
            } catch (error: IOException) {
                warnings.add("${survey.name}: ${error.message}")
            } catch (error: IllegalArgumentException) {
                warnings.add("${survey.name}: invalid records: ${error.message}")
            } catch (error: SecurityException) {
                warnings.add("${survey.name}: cannot read records: ${error.message}")
            }
            return PreviousSurveyContents(records, squares, tracks, warnings)
        }

        private fun refreshPreviousSurveySelection() {
            val selected = selectedPreviousSurveyNames.mapNotNull(previousSurveyContents::get)
            previousSurveyRecords = selected.flatMap { it.records }
            previousSurveySquares = selected.flatMapTo(mutableSetOf()) { it.squares }
            mapView.setPreviousTracks(selected.flatMap { it.tracks })
            updateSurveyGridOverlay()
            val warnings = selected.flatMap { it.warnings }
            val loadingCount = selectedPreviousSurveyNames.count {
                it !in previousSurveyContents && it in loadingPreviousSurveyNames
            }
            previousSurveysStatus.text = when {
                warnings.isNotEmpty() && loadingCount > 0 ->
                    warnings.joinToString("\n") + "\nLoading $loadingCount selected survey(s)..."
                warnings.isNotEmpty() -> warnings.joinToString("\n")
                loadingCount > 0 -> "Loading $loadingCount selected survey(s)..."
                previousSurveyFiles.isEmpty() -> "No previous surveys found."
                selectedPreviousSurveyNames.isEmpty() -> "Select one or more surveys."
                else -> "Selected surveys are shown on the map."
            }
        }

        private fun readPreviousTrack(uri: Uri): PreviousTrack {
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Unable to read track GPX.")
            return input.use {
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                parser.setInput(it, null)
                parsePreviousTrack(parser)
            }
        }

        private fun updateSurveyGridOverlay() {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val isActiveProject = prefs.getString(KEY_ACTIVE_RECORDS_URI, null) != null &&
                prefs.getString(KEY_ACTIVE_RECORDS_PROJECT_URI, null) ==
                prefs.getString(KEY_GPX_FOLDER_URI, null)
            val data = shortcutData
            val records = previousSurveyRecords + if (isActiveProject) currentSurveyRecords else emptyList()
            val counts = if (data == null) emptyMap() else surveyTaxaSquares(records, data)
            val squares = previousSurveySquares + if (isActiveProject) trackedGridReferences else emptySet()
            mapView.setSurveyGridSquares(
                mergeSurveyTrackSquares(counts, squares),
            )
        }

        private fun setRecordedCodeGrids(records: List<SurveyRecord>) {
            recordedCodeGrids.clear()
            records.forEach { recordedCodeGrids.add(RecordCodeGrid(it.recordIdentityCode, it.gridReference)) }
        }

        private fun surveyTaxaSquares(
            records: List<SurveyRecord>,
            data: ShortcutData,
            mode: MapGridCountMode = mapGridCountMode(),
        ): Map<GridSquare, Int> {
            val surveyCodes = data.templates.filter { it.isSurveyTaxon }.map { it.code }.toSet()
            return surveyTaxaGridCounts(
                records,
                surveyCodes,
                countSpecies = mode == MapGridCountMode.SPECIES,
            ).map { (reference, count) ->
                BritishNationalGrid.gridSquareForReference(reference) to count
            }.toMap()
        }

        private fun refreshSurveyTaxaGridCounts() {
            val data = shortcutData ?: return
            val mode = mapGridCountMode()
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val recordsValue = prefs.getString(KEY_ACTIVE_RECORDS_URI, null)
            val activeProject = prefs.getString(KEY_ACTIVE_RECORDS_PROJECT_URI, null)
            if (isSavingTrack || prefs.contains(KEY_SURVEY_END_ACTION) ||
                recordsValue == null || activeProject == null ||
                activeProject != prefs.getString(KEY_GPX_FOLDER_URI, null)
            ) {
                currentSurveyRecords = emptyList()
                updateSurveyGridOverlay()
                return
            }
            val uri = Uri.parse(recordsValue)
            saver.execute {
                try {
                    if (prefs.contains(KEY_SURVEY_END_ACTION) ||
                        prefs.getString(KEY_ACTIVE_RECORDS_URI, null) != recordsValue
                    ) return@execute
                    val records = readSurveyRecords(uri, data)
                    runOnUiThread {
                        if (!isFinishing &&
                            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .getString(KEY_ACTIVE_RECORDS_URI, null) == recordsValue &&
                            shortcutData === data &&
                            mapGridCountMode() == mode
                        ) {
                            currentSurveyRecords = records
                            updateSurveyGridOverlay()
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (!isFinishing) {
                            Toast.makeText(
                                this,
                                "Unable to update survey grid counts: ${error.message ?: "storage error"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }

        private fun recordHeaders(data: ShortcutData): List<String> =
            data.headers.filterIndexed { index, _ -> index !in data.excludedColumnIndices }

        private fun readRecordsText(uri: Uri): String =
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() } ?: throw IOException("Unable to read the records CSV.")

        private fun writeRecordsText(uri: Uri, text: String) {
            val output = contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Unable to open the records CSV for writing.")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }

        private fun readSurveyRecords(uri: Uri, data: ShortcutData): List<SurveyRecord> {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_ACTIVE_RECORDS_URI, null) != uri.toString() ||
                prefs.getString(KEY_ACTIVE_RECORDS_PROJECT_URI, null) != prefs.getString(KEY_GPX_FOLDER_URI, null)
            ) {
                throw IOException("The active survey or project folder has changed.")
            }
            var text = readRecordsText(uri)
            prefs.getString(KEY_RECORDS_WRITE_JOURNAL, null)?.let { pending ->
                val journal = JSONObject(pending)
                if (journal.getString("uri") != uri.toString()) {
                    throw IOException("An incomplete records update belongs to another survey.")
                }
                val before = journal.getString("before")
                if (text == journal.getString("after")) {
                    writeRecordsText(uri, before)
                    text = before
                } else if (text != before) {
                    throw IOException("An interrupted update left the records CSV incomplete; a recovery copy is stored on this device.")
                }
                if (!prefs.edit().remove(KEY_RECORDS_WRITE_JOURNAL).commit()) {
                    throw IOException("Unable to complete records recovery.")
                }
            }
            val rows = parseCsv(text.removePrefix("\uFEFF"))
            if (rows.firstOrNull() != recordHeaders(data)) {
                throw IOException("The records CSV header does not match shortcuts.csv.")
            }
            val metadata = prefs.getString(KEY_ACTIVE_RECORDS_METADATA, null)
                ?: throw IOException("Record code information is unavailable for this survey.")
            val entries = JSONArray(metadata)
            val records = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val fields = entry.getJSONArray("values")
                SurveyRecord(
                    entry.getString("code"),
                    entry.getString("grid"),
                    (0 until fields.length()).map { fields.getString(it) },
                    entry.optString("customReferenceCode").takeIf { it.isNotEmpty() },
                    entry.optString("customButtonCode").takeIf { it.isNotEmpty() },
                    entry.optString("customButtonComment").takeIf { it.isNotEmpty() },
                    entry.optString("identityCode").takeIf { it.isNotEmpty() },
                )
            }
            if (rows.drop(1) != records.map { it.values }) {
                throw IOException("The records CSV differs from the stored survey data; recording and editing are unavailable.")
            }
            return records
        }

        private fun writeSurveyRecords(
            uri: Uri,
            data: ShortcutData,
            oldRecords: List<SurveyRecord>,
            newRecords: List<SurveyRecord>,
        ) {
            fun csv(records: List<SurveyRecord>): String =
                (listOf(recordHeaders(data)) + records.map { it.values })
                    .joinToString(separator = "\n", postfix = "\n") { encodeCsvRow(it) }
            val metadata = JSONArray()
            newRecords.forEach { record ->
                metadata.put(JSONObject().apply {
                    put("code", record.code)
                    put("grid", record.gridReference)
                    put("values", JSONArray(record.values))
                    record.customReferenceCode?.let { put("customReferenceCode", it) }
                    record.customButtonCode?.let { put("customButtonCode", it) }
                    record.customButtonComment?.let { put("customButtonComment", it) }
                    record.identityCode?.let { put("identityCode", it) }
                })
            }
            val keys = newRecords.map {
                val code = Base64.encodeToString(it.recordIdentityCode.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                "$code:${it.gridReference}"
            }.toSet()
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val oldMetadata = prefs.getString(KEY_ACTIVE_RECORDS_METADATA, null)
            val oldKeys = prefs.getStringSet(KEY_ACTIVE_RECORDS_CODE_GRIDS, emptySet())?.toSet().orEmpty()
            val before = readRecordsText(uri)
            if (parseCsv(before.removePrefix("\uFEFF")) != listOf(recordHeaders(data)) + oldRecords.map { it.values }) {
                throw IOException("The records CSV changed during the update.")
            }
            val after = csv(newRecords)
            val journal = JSONObject().put("uri", uri.toString()).put("before", before).put("after", after)
            if (!prefs.edit().putString(KEY_RECORDS_WRITE_JOURNAL, journal.toString()).commit()) {
                throw IOException("Unable to prepare a recovery copy of the records CSV.")
            }
            try {
                writeRecordsText(uri, after)
                val saved = prefs.edit()
                    .putString(KEY_ACTIVE_RECORDS_METADATA, metadata.toString())
                    .putStringSet(KEY_ACTIVE_RECORDS_CODE_GRIDS, keys)
                    .remove(KEY_RECORDS_WRITE_JOURNAL)
                    .commit()
                if (!saved) throw IOException("Unable to save record code information.")
            } catch (error: Exception) {
                try {
                    if (!prefs.edit()
                            .putString(KEY_ACTIVE_RECORDS_METADATA, oldMetadata)
                            .putStringSet(KEY_ACTIVE_RECORDS_CODE_GRIDS, oldKeys)
                            .putString(KEY_RECORDS_WRITE_JOURNAL, journal.toString())
                            .commit()
                    ) throw IOException("Unable to restore record code information.")
                    writeRecordsText(uri, before)
                    if (!prefs.edit().remove(KEY_RECORDS_WRITE_JOURNAL).commit()) {
                        throw IOException("Unable to complete records recovery.")
                    }
                } catch (restoreError: Exception) {
                    throw IOException("Unable to save the record or restore survey data: ${restoreError.message}", error)
                }
                throw error
            }
        }

        private fun editMessage(message: String) {
            editScreen.removeAllViews()
            editScreen.addView(TextView(this).apply {
                text = message
                textSize = 16f
                setTextColor(Color.rgb(80, 88, 82))
                setPadding(dp(20), dp(24), dp(20), dp(12))
            })
        }

        private fun loadSurveyRecords() {
            speechCommentInput = null
            val request = ++editRequest
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val uri = prefs.getString(KEY_ACTIVE_RECORDS_URI, null)?.let(Uri::parse)
            if (trackingService?.snapshot() == null || isSavingTrack ||
                prefs.contains(KEY_SURVEY_END_ACTION) || uri == null
            ) {
                editMessage("Start a survey to view and edit its records.")
                return
            }
            editMessage("Loading records...")
            saver.execute {
                try {
                    val shortcutResult = readShortcutTemplates()
                    val data = shortcutResult.data
                        ?: throw IOException(shortcutResult.message)
                    val records = readSurveyRecords(uri, data)
                    runOnUiThread {
                        if (!isFinishing && currentScreen == Screen.EDIT && request == editRequest) {
                            shortcutData = data
                            setRecordedCodeGrids(records)
                            updateRecordButtons(trackingService?.latestPosition())
                            currentSurveyRecords = records
                            updateSurveyGridOverlay()
                            showSurveyRecords(uri, data, records)
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (!isFinishing && currentScreen == Screen.EDIT && request == editRequest) {
                            editMessage("Unable to load records: ${error.message ?: "storage error"}")
                        }
                    }
                }
            }
        }

        private fun showSurveyRecords(uri: Uri, data: ShortcutData, records: List<SurveyRecord>) {
            editScreen.removeAllViews()
            if (records.isEmpty()) {
                editMessage("No records in the current survey.")
                return
            }
            val cards = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
            records.indices.reversed().forEach { index ->
                val record = records[index]
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(247, 248, 246))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.rgb(225, 228, 224))
                    }
                }
                card.addView(TextView(this).apply {
                    text = "Code: ${displayCodeForRecord(record)}"
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.rgb(32, 45, 37))
                })
                listOf("scientific", "time", "gr", "quantity", "site", "comment").forEach { tag ->
                    val column = outputColumnForTag(data.tags, data.excludedColumnIndices, tag)
                    val value = column?.let { record.values.getOrNull(it) }.orEmpty()
                    card.addView(TextView(this).apply {
                        text = "${tag.replaceFirstChar { it.uppercase() }}: $value"
                        textSize = 14f
                        setTextColor(Color.rgb(55, 65, 58))
                    })
                }
                card.addView(Button(this).apply {
                    text = "Edit"
                    setOnClickListener { showRecordEditor(uri, data, index, record) }
                })
                cards.addView(card, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(12) })
            }
            editScreen.addView(ScrollView(this).apply { addView(cards) })
        }

        private fun showRecordEditor(uri: Uri, data: ShortcutData, index: Int, record: SurveyRecord) {
            val templateCodes = data.templates.map { it.code }.distinct()
            val codes = if (record.code in templateCodes) templateCodes else listOf(record.code) + templateCodes
            ++editRequest
            editScreen.removeAllViews()
            val form = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(24))
                setBackgroundColor(Color.rgb(247, 248, 246))
            }
            form.addView(TextView(this).apply {
                text = "Edit record"
                textSize = 21f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(32, 45, 37))
            })
            form.addView(TextView(this).apply {
                text = "Update the taxon or observation details."
                textSize = 14f
                setTextColor(Color.rgb(100, 110, 103))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(16) })

            fun sectionCard(title: String, description: String): LinearLayout {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(16))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.rgb(225, 228, 224))
                    }
                }
                card.addView(TextView(this).apply {
                    text = title
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.rgb(32, 90, 60))
                })
                card.addView(TextView(this).apply {
                    text = description
                    textSize = 13f
                    setTextColor(Color.rgb(100, 110, 103))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(14) })
                return card
            }

            fun label(text: String) = TextView(this).apply {
                this.text = text
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(55, 65, 58))
            }

            fun inputBackground(color: Int = Color.WHITE) = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.rgb(205, 211, 206))
            }

            val codeCard = sectionCard("Taxon", "Choose the code for this record.")
            codeCard.addView(label("Code"), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) })
            val codeInput = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, codes)
                setSelection(codes.indexOf(record.code))
                background = inputBackground()
                setPadding(dp(12), dp(4), dp(12), dp(4))
            }
            codeCard.addView(codeInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52),
            ))
            form.addView(codeCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) })

            val detailsCard = sectionCard("Observation details", "Edit quantity, site, or comment.")
            fun textInput(
                tag: String,
                multiline: Boolean = false,
                speechInput: Boolean = false,
                headerAccessory: View? = null,
                inputTypeOverride: Int? = null,
            ): EditText {
                val column = outputColumnForTag(data.tags, data.excludedColumnIndices, tag)
                val input = EditText(this).apply {
                    inputType = inputTypeOverride ?: (
                        InputType.TYPE_CLASS_TEXT or
                            if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                        )
                    setSingleLine(!multiline)
                    if (multiline) {
                        minLines = 4
                        gravity = Gravity.TOP or Gravity.START
                    } else {
                        minHeight = dp(48)
                    }
                    if (column != null && column in record.values.indices) {
                        setText(record.values[column])
                    } else {
                        hint = "Missing $tag tag in shortcuts.csv"
                        isEnabled = false
                    }
                    background = inputBackground(if (isEnabled) Color.WHITE else Color.rgb(242, 243, 241))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                val field = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                if (speechInput || headerAccessory != null) {
                    val heading = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(label(tag.replaceFirstChar { it.uppercase() }),
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                bottomMargin = dp(6)
                            })
                        val accessory = headerAccessory ?: Button(this@MainActivity).apply {
                            text = "Speak"
                            isEnabled = input.isEnabled
                            setOnClickListener { requestCommentSpeech(input) }
                        }
                        addView(accessory, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(4) })
                    }
                    field.addView(heading)
                } else {
                    field.addView(label(tag.replaceFirstChar { it.uppercase() }),
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(6) })
                }
                field.addView(input)
                detailsCard.addView(field, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(14) })
                return input
            }
            val quantityColumn = outputColumnForTag(data.tags, data.excludedColumnIndices, "quantity")
            val quantityValue = quantityColumn?.let { record.values.getOrNull(it) }.orEmpty()
            val useTextQuantity = CheckBox(this).apply {
                text = "Use text"
                isChecked = quantityValue.isNotBlank() && !isIntegerQuantity(quantityValue)
                contentDescription = "Use text input for quantity"
            }
            val quantity = textInput(
                "quantity",
                headerAccessory = useTextQuantity,
                inputTypeOverride = if (useTextQuantity.isChecked) {
                    InputType.TYPE_CLASS_TEXT
                } else {
                    InputType.TYPE_CLASS_NUMBER
                },
            )
            var clearInitialQuantityOnTap = isIntegerQuantity(quantityValue)
            quantity.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN && clearInitialQuantityOnTap) {
                    clearInitialQuantityOnTap = false
                    quantity.text.clear()
                }
                false
            }
            useTextQuantity.setOnCheckedChangeListener { _, useText ->
                val selection = quantity.selectionStart.coerceAtLeast(0)
                quantity.inputType = if (useText) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER
                quantity.setSelection(selection.coerceAtMost(quantity.text.length))
            }
            val site = textInput("site")
            val comment = textInput("comment", multiline = true, speechInput = true)
            speechCommentInput = comment
            form.addView(detailsCard)
            editScreen.addView(ScrollView(this).apply {
                isFillViewport = true
                addView(form)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.rgb(247, 248, 246))
            }
            editScreen.addView(View(this).apply {
                setBackgroundColor(Color.rgb(225, 228, 224))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            val cancel = Button(this).apply {
                text = "Cancel"
                setOnClickListener { loadSurveyRecords() }
            }
            val save = Button(this).apply { text = "Save" }
            val delete = Button(this).apply { text = "Delete" }
            listOf(cancel, save, delete).forEach {
                actions.addView(it, LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                })
            }
            editScreen.addView(actions)
            fun submit(deleting: Boolean) {
                if (trackingService?.snapshot() == null || isSavingTrack ||
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SURVEY_END_ACTION)
                ) {
                    Toast.makeText(this, "The survey is no longer available for editing.", Toast.LENGTH_LONG).show()
                    loadSurveyRecords()
                    return
                }
                val selectedCode = codes[codeInput.selectedItemPosition]
                val selectedTemplates = data.templates.filter { it.code == selectedCode }
                if (!deleting && selectedCode != record.code && selectedTemplates.size != 1) {
                    Toast.makeText(this, "The selected code is duplicated in shortcuts.csv.", Toast.LENGTH_LONG).show()
                    return
                }
                val selected = selectedTemplates.firstOrNull()
                val quantityValueToSave = quantity.text.toString()
                if (!useTextQuantity.isChecked &&
                    quantityValueToSave.isNotBlank() &&
                    !isIntegerQuantity(quantityValueToSave)
                ) {
                    Toast.makeText(
                        this,
                        "Enter a whole-number quantity or select Use text.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return
                }
                val changed = try {
                    if (deleting) null else editSurveyRecord(
                        record, selectedCode, selected?.fields.orEmpty(), data.tags, data.excludedColumnIndices,
                        quantityValueToSave, site.text.toString(), comment.text.toString(),
                    )
                } catch (error: IllegalArgumentException) {
                    Toast.makeText(this, "Cannot save record: ${error.message}", Toast.LENGTH_LONG).show()
                    return
                }
                val request = editRequest
                cancel.isEnabled = false
                save.isEnabled = false
                delete.isEnabled = false
                saver.execute {
                    try {
                        val records = readSurveyRecords(uri, data)
                        val updated = replaceSurveyRecord(records, index, record, changed)
                        writeSurveyRecords(uri, data, records, updated)
                        runOnUiThread {
                            if (!isFinishing) {
                                setRecordedCodeGrids(updated)
                                updateRecordButtons(trackingService?.latestPosition())
                                currentSurveyRecords = updated
                                updateSurveyGridOverlay()
                                refreshCustomRecordButtons(data)
                                if (currentScreen == Screen.EDIT && request == editRequest) loadSurveyRecords()
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            if (!isFinishing) {
                                if (error is DuplicateSurveyRecordException) {
                                    AlertDialog.Builder(this)
                                        .setTitle("Record already exists")
                                        .setMessage(
                                            "You cannot change this code because a record of this taxon has already been recorded at this location during this survey. You may want to delete this record instead.",
                                        )
                                        .setPositiveButton("OK") { _, _ ->
                                            codeInput.setSelection(codes.indexOf(record.code))
                                        }
                                        .show()
                                } else {
                                    Toast.makeText(
                                        this,
                                        "Unable to update record: ${error.message ?: "storage error"}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                if (currentScreen == Screen.EDIT && request == editRequest) {
                                    cancel.isEnabled = true
                                    save.isEnabled = true
                                    delete.isEnabled = true
                                }
                            }
                        }
                    }
                }
            }
            save.setOnClickListener { submit(false) }
            delete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete record?")
                    .setMessage("This will permanently remove the record from the survey.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ -> submit(true) }
                    .show()
            }
        }

        private fun showSpecialRecordForm(data: ShortcutData, template: ShortcutTemplate) {
            specialRecordScreen.removeAllViews()
            val form = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(16))
            }
            form.addView(TextView(this).apply {
                text = "Create record button"
                textSize = 21f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(32, 45, 37))
            })
            form.addView(TextView(this).apply {
                text = "Add a specimen reference and a reusable button code."
                textSize = 14f
                setTextColor(Color.rgb(100, 110, 103))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(18) })

            fun addField(labelText: String, input: EditText, accessory: View? = null) {
                val heading = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = labelText
                        textSize = 14f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.rgb(55, 65, 58))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    if (accessory != null) {
                        addView(accessory)
                    }
                }
                form.addView(heading, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) })
                input.background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1), Color.rgb(205, 211, 206))
                }
                input.setPadding(dp(12), dp(10), dp(12), dp(10))
                form.addView(input, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(14) })
            }

            val useTextReference = CheckBox(this).apply {
                text = "Use text"
                contentDescription = "Use text input for reference code"
            }
            val reference = EditText(this).apply {
                hint = "Required"
                inputType = InputType.TYPE_CLASS_NUMBER
                minHeight = dp(48)
            }
            useTextReference.setOnCheckedChangeListener { _, useText ->
                val selection = reference.selectionStart.coerceAtLeast(0)
                reference.inputType = if (useText) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER
                reference.setSelection(selection.coerceAtMost(reference.text.length))
            }
            addField("Reference code", reference, useTextReference)
            val buttonCode = EditText(this).apply {
                hint = "Required"
                inputType = InputType.TYPE_CLASS_TEXT
                minHeight = dp(48)
            }
            addField("Button code", buttonCode)
            val comment = EditText(this).apply {
                hint = "Optional"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            }
            speechCommentInput = comment
            addField(
                "Comment",
                comment,
                Button(this).apply {
                    text = "Speak"
                    setOnClickListener { requestCommentSpeech(comment) }
                },
            )

            specialRecordScreen.addView(ScrollView(this).apply {
                isFillViewport = true
                addView(form)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            specialRecordScreen.addView(View(this).apply {
                setBackgroundColor(Color.rgb(225, 228, 224))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            specialRecordScreen.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.rgb(247, 248, 246))
                addView(Button(this@MainActivity).apply {
                    text = "Cancel"
                    setOnClickListener { showScreen(Screen.RECORDS) }
                })
                addView(Button(this@MainActivity).apply {
                    text = "Create"
                    setOnClickListener {
                        val referenceValue = reference.text.toString().trim()
                        val buttonCodeValue = buttonCode.text.toString().trim()
                        if (referenceValue.isEmpty() || buttonCodeValue.isEmpty()) {
                            Toast.makeText(
                                this@MainActivity,
                                "Enter both a reference code and a button code.",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@setOnClickListener
                        }
                        if (!useTextReference.isChecked && !isIntegerQuantity(referenceValue)) {
                            Toast.makeText(
                                this@MainActivity,
                                "Enter a whole-number reference or select Use text.",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@setOnClickListener
                        }
                        onRecordTemplateClicked(
                            data,
                            template,
                            commentOverride = comment.text.toString(),
                            customButtonRegistration = SpecialRecordButton(
                                referenceValue,
                                buttonCodeValue,
                                comment.text.toString().takeIf(String::isNotBlank),
                            ),
                            onSuccess = { showScreen(Screen.RECORDS) },
                        )
                    }
                })
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            recordsScreen.visibility = View.GONE
            specialRecordScreen.visibility = View.VISIBLE
        }

        private fun onRecordTemplateClicked(
            data: ShortcutData,
            template: ShortcutTemplate,
            scientificOverride: String? = null,
            commentOverride: String? = null,
            customButtonRegistration: SpecialRecordButton? = null,
            identityCode: String? = null,
            onSuccess: (() -> Unit)? = null,
        ) {
            if (template.code == "#" && customButtonRegistration == null && identityCode == null) {
                showSpecialRecordForm(data, template)
                return
            }
            val service = trackingService
            if (service?.snapshot()?.isTracking != true) {
                Toast.makeText(this, "Start a survey before recording an observation.", Toast.LENGTH_LONG).show()
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
            if (
                projectUri == null ||
                prefs.getString(KEY_ACTIVE_RECORDS_PROJECT_URI, null) != projectUri ||
                prefs.getString(KEY_ACTIVE_TRACK_PROJECT_URI, null) != projectUri
            ) {
                Toast.makeText(
                    this,
                    "Select the project folder used when this survey started.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            val recordsUri = prefs.getString(KEY_ACTIVE_RECORDS_URI, null)?.let(Uri::parse)
            if (recordsUri == null) {
                Toast.makeText(this, "No records CSV is active. Start a new survey.", Toast.LENGTH_LONG).show()
                return
            }
            val trackUri = prefs.getString(KEY_ACTIVE_TRACK_URI, null)?.let(Uri::parse)
            if (trackUri == null) {
                Toast.makeText(this, "No GPX track is active. Start a new survey.", Toast.LENGTH_LONG).show()
                return
            }
            val trackPoints = service.trackPoints()

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
            val recordIdentityCode = identityCode ?: customButtonRegistration?.label ?: template.code
            val recordKey = RecordCodeGrid(recordIdentityCode, gridReference)
            if (recordKey in recordedCodeGrids || recordKey in pendingCodeGrids) return
            pendingCodeGrids.add(recordKey)
            updateRecordButtons(point)
            val values = renderShortcutValues(
                data.headers, data.excludedColumnIndices, data.tags, template.fields,
                date, time, gridReference, locationName,
                comment = commentOverride,
                scientific = scientificOverride ?: customButtonRegistration?.label,
            )

            saver.execute {
                try {
                    saveTrackToDocument(trackUri, trackPoints)
                    val records = readSurveyRecords(recordsUri, data)
                    if (customButtonRegistration != null &&
                        isSpecimenReferenceUsed(records, customButtonRegistration.referenceCode)
                    ) {
                        throw DuplicateSpecimenReferenceException()
                    }
                    if (records.any {
                            it.recordIdentityCode == recordKey.code &&
                                it.gridReference == recordKey.gridReference
                        }
                    ) {
                        throw IOException("This code has already been recorded at this grid reference.")
                    }
                    val updated = records + SurveyRecord(
                        template.code,
                        gridReference,
                        values,
                        customReferenceCode = customButtonRegistration?.referenceCode,
                        customButtonCode = customButtonRegistration?.buttonCode,
                        customButtonComment = customButtonRegistration?.comment,
                        identityCode = recordIdentityCode.takeIf { it != template.code },
                    )
                    writeSurveyRecords(recordsUri, data, records, updated)
                    runOnUiThread {
                        if (!isFinishing) {
                            pendingCodeGrids.remove(recordKey)
                            setRecordedCodeGrids(updated)
                            updateRecordButtons(trackingService?.latestPosition())
                            currentSurveyRecords = updated
                            updateSurveyGridOverlay()
                            showRecordCreatedMessage(locationName)
                            if (customButtonRegistration != null) refreshCustomRecordButtons(data)
                            onSuccess?.invoke()
                            if (currentScreen == Screen.EDIT) loadSurveyRecords()
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (!isFinishing) {
                            pendingCodeGrids.remove(recordKey)
                            updateRecordButtons(trackingService?.latestPosition())
                            if (error is DuplicateSpecimenReferenceException) {
                                AlertDialog.Builder(this)
                                    .setTitle("Reference code already used")
                                    .setMessage("That reference code has already been used for a custom record button in this survey.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            } else {
                                Toast.makeText(
                                    this,
                                    "Unable to save record and GPX checkpoint: ${error.message ?: "storage error"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
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

        private fun createSurveyFiles(projectTreeUri: Uri, shortcuts: ShortcutData): SurveyFiles {
            val projectDocumentId = DocumentsContract.getTreeDocumentId(projectTreeUri)
            val surveysDocumentId = findOrCreateDirectory(projectTreeUri, projectDocumentId, "surveys")
            val currentDocumentId = findOrCreateDirectory(projectTreeUri, surveysDocumentId, "current")
            val previousDocumentId = findDirectory(projectTreeUri, surveysDocumentId, "previous")
            val existingNames = (listChildren(projectTreeUri, currentDocumentId) +
                (previousDocumentId?.let { listChildren(projectTreeUri, it) } ?: emptyList()))
                .map { it.displayName }
                .toSet()
            var createdAt = System.currentTimeMillis()
            var recordsFileName = recordsFilename(createdAt)
            var trackFileName = trackFilename(createdAt)
            while (recordsFileName in existingNames || trackFileName in existingNames) {
                createdAt++
                recordsFileName = recordsFilename(createdAt)
                trackFileName = trackFilename(createdAt)
            }

            val recordsUri = createRecordsFile(projectTreeUri, currentDocumentId, shortcuts, recordsFileName)
            try {
                val trackUri = createTrackFile(projectTreeUri, currentDocumentId, trackFileName)
                return SurveyFiles(recordsUri, trackUri)
            } catch (error: Exception) {
                cleanupSurveyFiles(SurveyFiles(recordsUri, Uri.EMPTY), error, includeTrack = false)
                throw error
            }
        }

        private fun createRecordsFile(
            projectTreeUri: Uri,
            surveysDocumentId: String,
            shortcuts: ShortcutData,
            filename: String,
        ): Uri {
            val fileUri = DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(projectTreeUri, surveysDocumentId),
                "text/csv",
                filename,
            ) ?: throw IOException("Android could not create $filename.")
            try {
                val output = contentResolver.openOutputStream(fileUri, "w")
                    ?: throw IOException("Android could not open $filename for writing.")
                val headers = shortcuts.headers.filterIndexed { index, _ ->
                    index !in shortcuts.excludedColumnIndices
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

        private fun createTrackFile(
            projectTreeUri: Uri,
            surveysDocumentId: String,
            filename: String,
        ): Uri {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(projectTreeUri, surveysDocumentId)
            val fileUri = DocumentsContract.createDocument(
                contentResolver,
                parentUri,
                "application/gpx+xml",
                filename,
            ) ?: throw IOException("Android could not create $filename.")
            try {
                val output = contentResolver.openOutputStream(fileUri, "w")
                    ?: throw IOException("Android could not open $filename for writing.")
                output.bufferedWriter(Charsets.UTF_8).use { writeGpx(it, emptyList()) }
            } catch (error: Exception) {
                try {
                    deleteDocument(fileUri)
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
            return fileUri
        }

        private fun cleanupSurveyFiles(
            files: SurveyFiles,
            cause: Exception,
            includeTrack: Boolean = true,
        ) {
            listOfNotNull(files.recordsUri, files.trackUri.takeIf { includeTrack && it != Uri.EMPTY })
                .forEach { uri ->
                    try {
                        deleteDocument(uri)
                    } catch (cleanupError: Exception) {
                        cause.addSuppressed(cleanupError)
                    }
                }
        }

        private fun deleteDocument(uri: Uri) {
            if (!DocumentsContract.deleteDocument(contentResolver, uri)) {
                throw IOException("Android could not remove a survey file.")
            }
        }

        private fun recordsFilename(timestamp: Long): String =
            "g21s-${surveyTimestamp(timestamp)}-records.csv"

        private fun trackFilename(timestamp: Long): String =
            "g21s-${surveyTimestamp(timestamp)}-track.gpx"

        private fun surveyTimestamp(timestamp: Long): String =
            SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(timestamp))

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
                        requestNotificationPermissionOrStartSurvey()
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
                requestTrackingService()
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

    private fun mapGridCountMode(): MapGridCountMode =
        if (
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MAP_GRID_COUNT_MODE, DEFAULT_MAP_GRID_COUNT_MODE) ==
            MapGridCountMode.SPECIES.preferenceValue
        ) {
            MapGridCountMode.SPECIES
        } else {
            MapGridCountMode.RECORDS
        }

    private companion object {
        const val LOCATION_REQUEST = 1
        const val NOTIFICATION_REQUEST = 2
        const val SELECT_GPX_FOLDER_REQUEST = 4
        const val SPEECH_COMMENT_REQUEST = 5
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
        const val KEY_ACTIVE_RECORDS_METADATA = "active_records_metadata"
        const val KEY_ACTIVE_TRACKED_GRID_REFERENCES = "active_tracked_grid_references"
        const val KEY_RECORDS_WRITE_JOURNAL = "records_write_journal"
        const val KEY_ACTIVE_TRACK_URI = "active_track_uri"
        const val KEY_ACTIVE_TRACK_PROJECT_URI = "active_track_project_uri"
        const val KEY_SURVEY_END_ACTION = "survey_end_action"
        const val KEY_ARCHIVE_RECORDS_URI = "archive_records_uri"
        const val KEY_ARCHIVE_TRACK_URI = "archive_track_uri"
        const val KEY_ARCHIVE_READY = "archive_ready"
        const val KEY_GRID_REFERENCE_PRECISION = "grid_reference_precision"
        const val KEY_MAP_GRID_COUNT_MODE = "map_grid_count_mode"
        const val KEY_RECORD_TIME_SECONDS = "record_time_seconds"
        const val KEY_CURRENT_LOCATION_NAME = "current_location_name"
        const val KEY_MOVEMENT_THRESHOLD = "movement_threshold"
        const val RECORD_MESSAGE_DURATION_MS = 2500L
        const val RECORD_LOCATION_MESSAGE_DURATION_MS = 850L
        const val DEFAULT_GRID_REFERENCE_PRECISION = 10
        const val DEFAULT_MAP_GRID_COUNT_MODE = "records"
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
        EDIT,
    }

    private enum class MapGridCountMode(val preferenceValue: String) {
        RECORDS("records"),
        SPECIES("species"),
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
    private val edit: Boolean = false,
) : LinearLayout(context) {
    private val icon = NavigationIcon(context, tracking, settings, records, edit)
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
    private val edit: Boolean,
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
        } else if (edit) {
            canvas.drawLine(cx - 7f * scale, cy + 7f * scale, cx + 6f * scale, cy - 6f * scale, paint)
            canvas.drawLine(cx - 7f * scale, cy + 7f * scale, cx - 3f * scale, cy + 6f * scale, paint)
            canvas.drawLine(cx + 6f * scale, cy - 6f * scale, cx + 8f * scale, cy - 4f * scale, paint)
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
    private val surveySquareBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val surveyCountHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val surveyCountText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
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
    private val falseTrackingBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
    private val falseTrackingText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
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
    private var falseTracking = false
    private var trackingFlashVisible = false
    private var flashRed = false
    private val trackPoints = mutableListOf<TrackPoint>()
    private var previousTracks: List<List<TrackPoint>> = emptyList()
    private var surveyGridSquares: Map<GridSquare, Int> = emptyMap()
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

    fun setFalseTracking(active: Boolean) {
        falseTracking = active
        updateTrackingFlash()
        invalidate()
    }

    fun setTrackingFlashVisible(visible: Boolean) {
        trackingFlashVisible = visible
        updateTrackingFlash()
    }

    private fun updateTrackingFlash() {
        if ((trackingActive || falseTracking) && trackingFlashVisible) {
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

    fun clearPosition() {
        positionX = null
        positionY = null
        positionAccuracy = null
        invalidate()
    }

    fun setTrack(points: List<TrackPoint>) {
        trackPoints.clear()
        trackPoints.addAll(points)
        invalidate()
    }

    fun setPreviousTracks(tracks: List<List<TrackPoint>>) {
        previousTracks = tracks
        invalidate()
    }

    fun setSurveyGridSquares(squares: Map<GridSquare, Int>) {
        surveyGridSquares = squares.toMap()
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
            drawFalseTrackingNotice(canvas)
            return
        }

        tiles.forEach { tile ->
            val left = offsetX + tile.westing.toFloat() * mapScale
            val top = offsetY - tile.northing.toFloat() * mapScale
            val right = left + (tile.bitmap.width * tile.pixelWidth * mapScale).toFloat()
            val bottom = top + (tile.bitmap.height * tile.pixelHeight * mapScale).toFloat()
            canvas.drawBitmap(tile.bitmap, null, RectF(left, top, right, bottom), bitmapPaint)
        }

        (previousTracks + listOf(trackPoints)).filter { it.isNotEmpty() }.forEach { segment ->
            val path = Path()
            segment.forEachIndexed { index, point ->
                val x = offsetX + point.easting.toFloat() * mapScale
                val y = offsetY - point.northing.toFloat() * mapScale
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (segment.size > 1) {
                canvas.drawPath(path, trackUnderlay)
                canvas.drawPath(path, trackPaint)
            } else {
                val point = segment.first()
                val x = offsetX + point.easting.toFloat() * mapScale
                val y = offsetY - point.northing.toFloat() * mapScale
                canvas.drawCircle(x, y, 4f * density, trackUnderlay)
                canvas.drawCircle(x, y, 2.5f * density, trackPaint)
            }
        }

        surveyGridSquares.forEach { (square, count) ->
            val left = offsetX + square.easting.toFloat() * mapScale
            val right = offsetX + (square.easting + square.sizeMetres).toFloat() * mapScale
            val top = offsetY - (square.northing + square.sizeMetres).toFloat() * mapScale
            val bottom = offsetY - square.northing.toFloat() * mapScale
            if (right < 0f || left > width || bottom < 0f || top > height) return@forEach
            canvas.drawRect(left, top, right, bottom, surveySquareBorder)
            val centerX = (left + right) / 2f
            val centerY = (top + bottom) / 2f
            val textSize = minOf(14f * density, maxOf(8f * density, (bottom - top) * 0.65f))
            surveyCountHalo.textSize = textSize
            surveyCountText.textSize = textSize
            val baseline = centerY - (surveyCountText.ascent() + surveyCountText.descent()) / 2f
            val label = count.toString()
            canvas.drawText(label, centerX, baseline, surveyCountHalo)
            canvas.drawText(label, centerX, baseline, surveyCountText)
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
        drawFalseTrackingNotice(canvas)
    }

    private fun drawFalseTrackingNotice(canvas: Canvas) {
        if (!falseTracking || !flashRed) return
        val label = "False tracking"
        val halfWidth = falseTrackingText.measureText(label) / 2f + 12f * density
        val top = 12f * density
        val bottom = top + 32f * density
        canvas.drawRoundRect(width / 2f - halfWidth, top, width / 2f + halfWidth, bottom,
            4f * density, 4f * density, falseTrackingBackground)
        val baseline = (top + bottom - falseTrackingText.ascent() - falseTrackingText.descent()) / 2f
        canvas.drawText(label, width / 2f, baseline, falseTrackingText)
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
