package org.g21survey.app

import android.content.Context
import android.location.Location
import kotlin.math.round

internal fun falseOriginSquare(reference: String): GridSquare {
    val normalized = reference.filterNot(Char::isWhitespace)
    require(normalized.length == 10) { "Enter an 8-figure grid reference." }
    return BritishNationalGrid.gridSquareForReference(normalized)
}

internal class GridOffset(private val east: Double, private val north: Double) {
    fun apply(easting: Double, northing: Double): Pair<Double, Double> =
        easting + east to northing + north

    companion object {
        fun fromOrigin(square: GridSquare, easting: Double, northing: Double): GridOffset =
            GridOffset(
                square.easting + square.sizeMetres / 2 - easting,
                square.northing + square.sizeMetres / 2 - northing,
            )
    }
}

internal object FalseOrigin {
    private const val PREFS = "false_origin"
    private const val ENABLED = "enabled"
    private const val REFERENCE = "reference"
    private const val EAST_OFFSET = "east_offset"
    private const val NORTH_OFFSET = "north_offset"
    private var lastLocation: Location? = null
    var generation: Int = 0
        private set

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)
    fun reference(context: Context): String = prefs(context).getString(REFERENCE, "").orEmpty()
    fun setReference(context: Context, reference: String) {
        prefs(context).edit().putString(REFERENCE, reference).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean, reference: String) {
        if (enabled) {
            val square = falseOriginSquare(reference)
            BritishNationalGrid.toWgs84(
                square.easting + square.sizeMetres / 2,
                square.northing + square.sizeMetres / 2,
            )
        }
        prefs(context).edit().putBoolean(ENABLED, enabled).putString(REFERENCE, reference)
            .remove(EAST_OFFSET).remove(NORTH_OFFSET).apply()
        generation++
    }

    fun lastPosition(context: Context): TrackPoint? = lastLocation?.let { position(context, it) }

    fun position(context: Context, location: Location): TrackPoint {
        lastLocation = Location(location)
        val (rawEast, rawNorth) = BritishNationalGrid.fromWgs84(location.latitude, location.longitude)
        var easting = rawEast
        var northing = rawNorth
        var latitude = location.latitude
        var longitude = location.longitude
        val preferences = prefs(context)
        if (preferences.getBoolean(ENABLED, false)) {
            val offset = if (preferences.contains(EAST_OFFSET) && preferences.contains(NORTH_OFFSET)) {
                GridOffset(
                    Double.fromBits(preferences.getLong(EAST_OFFSET, 0)),
                    Double.fromBits(preferences.getLong(NORTH_OFFSET, 0)),
                )
            } else {
                val square = falseOriginSquare(reference(context))
                val targetEast = square.easting + square.sizeMetres / 2
                val targetNorth = square.northing + square.sizeMetres / 2
                preferences.edit()
                    .putLong(EAST_OFFSET, (targetEast - rawEast).toBits())
                    .putLong(NORTH_OFFSET, (targetNorth - rawNorth).toBits())
                    .apply()
                GridOffset.fromOrigin(square, rawEast, rawNorth)
            }
            val adjusted = offset.apply(rawEast, rawNorth)
            easting = adjusted.first
            northing = adjusted.second
            val coordinates = BritishNationalGrid.toWgs84(easting, northing)
            latitude = coordinates.first
            longitude = coordinates.second
        }
        return TrackPoint(
            round(latitude * 100_000.0) / 100_000.0,
            round(longitude * 100_000.0) / 100_000.0,
            easting,
            northing,
            if (location.hasAccuracy()) location.accuracy else null,
            location.time,
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
