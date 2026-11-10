package org.g21survey.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import kotlin.math.hypot
import kotlin.math.round

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val easting: Double,
    val northing: Double,
    val accuracyMeters: Float?,
    val timestamp: Long,
)

data class TrackSnapshot(
    val startedAt: Long,
    val pointCount: Int,
    val gpsFixCount: Int,
    val lengthMeters: Double,
    val isTracking: Boolean,
    val error: String?,
)

class TrackingService : Service() {
    interface Observer {
        fun onTrackChanged(
            snapshot: TrackSnapshot?,
            newPoint: TrackPoint? = null,
            trackReplaced: Boolean = false,
            currentPosition: TrackPoint? = null,
        )
    }

    inner class TrackingBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    private data class TrackSession(
        val startedAt: Long,
        val points: MutableList<TrackPoint> = mutableListOf(),
        var gpsFixCount: Int = 0,
        var lengthMeters: Double = 0.0,
    )

    private val binder = TrackingBinder()
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private var session: TrackSession? = null
    private var observer: Observer? = null
    private var tracking = false
    private var trackingError: String? = null
    private var movementThresholdMeters = DEFAULT_MOVEMENT_THRESHOLD_METRES
    private var latestPosition: TrackPoint? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!tracking) return
            val (easting, northing) =
                BritishNationalGrid.fromWgs84(location.latitude, location.longitude)
            val point = TrackPoint(
                latitude = roundCoordinate(location.latitude),
                longitude = roundCoordinate(location.longitude),
                easting = easting,
                northing = northing,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                timestamp = location.time,
            )
            latestPosition = point
            val current = session ?: run {
                trackingError = null
                publish(currentPosition = point)
                return
            }
            current.gpsFixCount += 1
            val previous = current.points.lastOrNull()
            if (
                previous != null &&
                hypot(point.easting - previous.easting, point.northing - previous.northing) <=
                movementThresholdMeters.toDouble()
            ) {
                trackingError = null
                publish(currentPosition = point)
                return
            }
            if (previous != null) {
                val distance = FloatArray(1)
                Location.distanceBetween(
                    previous.latitude,
                    previous.longitude,
                    point.latitude,
                    point.longitude,
                    distance,
                )
                current.lengthMeters += distance[0]
            }
            current.points.add(point)
            trackingError = null
            publish(newPoint = point, currentPosition = point)
        }

        @Deprecated("Required on older Android versions")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        override fun onProviderEnabled(provider: String) {
            trackingError = null
            publish()
        }
        override fun onProviderDisabled(provider: String) {
            trackingError = "GPS is off. Turn on device location to continue tracking."
            publish()
        }
    }

    override fun onCreate() {
        super.onCreate()
        BritishNationalGrid.initialize(assets)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_NEW -> startNewTrack()
            ACTION_CONTINUE -> continueTrack()
        }
        return START_NOT_STICKY
    }

    fun setObserver(value: Observer?) {
        observer = value
        publish()
    }

    fun snapshot(): TrackSnapshot? = session?.let {
        TrackSnapshot(
            startedAt = it.startedAt,
            pointCount = it.points.size,
            gpsFixCount = it.gpsFixCount,
            lengthMeters = it.lengthMeters,
            isTracking = tracking,
            error = trackingError,
        )
    }

    fun trackPoints(): List<TrackPoint> = session?.points?.toList().orEmpty()

    fun latestPosition(): TrackPoint? = latestPosition

    fun setMovementThreshold(metres: Int) {
        require(metres in MIN_MOVEMENT_THRESHOLD_METRES..MAX_MOVEMENT_THRESHOLD_METRES)
        movementThresholdMeters = metres
    }

    fun pauseTrack() {
        if (!tracking) return
        tracking = false
        trackingError = null
        locationManager.removeUpdates(locationListener)
        stopForegroundCompat()
        publish()
    }

    fun clearTrack(): Boolean {
        if (tracking) return false
        session = null
        trackingError = null
        publish(trackReplaced = true)
        return true
    }

    private fun startNewTrack() {
        if (tracking) {
            locationManager.removeUpdates(locationListener)
            tracking = false
            stopForegroundCompat()
        }
        session = TrackSession(System.currentTimeMillis())
        trackingError = null
        beginTracking(trackReplaced = true)
    }

    private fun continueTrack() {
        if (session == null) {
            startNewTrack()
            return
        }
        trackingError = null
        beginTracking()
    }

    @SuppressLint("MissingPermission")
    private fun beginTracking(trackReplaced: Boolean = false) {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            trackingError = "Location permission is needed to track."
            publish(trackReplaced = trackReplaced)
            return
        }
        if (tracking) return

        createNotificationChannel()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        tracking = true
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                locationListener,
                Looper.getMainLooper(),
            )
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                trackingError = "GPS is off. Turn on device location to continue tracking."
            }
        } catch (_: SecurityException) {
            tracking = false
            trackingError = "Location permission is needed to track."
            stopForegroundCompat()
        } catch (_: IllegalArgumentException) {
            tracking = false
            trackingError = "This device does not provide GPS location."
            stopForegroundCompat()
        }
        publish(trackReplaced = trackReplaced)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("G21 Survey is tracking")
            .setContentText("GPS path recording is active")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Path tracking",
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun publish(
        newPoint: TrackPoint? = null,
        trackReplaced: Boolean = false,
        currentPosition: TrackPoint? = null,
    ) {
        observer?.onTrackChanged(snapshot(), newPoint, trackReplaced, currentPosition)
    }

    private fun roundCoordinate(value: Double): Double = round(value * 100_000.0) / 100_000.0

    override fun onDestroy() {
        locationManager.removeUpdates(locationListener)
        observer = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_NEW = "org.g21survey.app.action.START_NEW_TRACK"
        const val ACTION_CONTINUE = "org.g21survey.app.action.CONTINUE_TRACK"
        private const val DEFAULT_MOVEMENT_THRESHOLD_METRES = 5
        private const val MIN_MOVEMENT_THRESHOLD_METRES = 1
        private const val MAX_MOVEMENT_THRESHOLD_METRES = 20
        private const val CHANNEL_ID = "path_tracking"
        private const val NOTIFICATION_ID = 1
    }
}
