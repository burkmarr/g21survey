package org.g21survey.app

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal data class GridSquare(val easting: Double, val northing: Double, val sizeMetres: Double)

object BritishNationalGrid {
    private const val GRID_ASSET_NAME = "ostn15-etrs-to-osgb.bin"
    private const val GRID_ROWS = 1441
    private const val GRID_COLUMNS = 661
    private const val GRID_HEADER_BYTES = 48
    private const val SOUTH_LATITUDE_SECONDS = 176400.0
    private const val EAST_LONGITUDE_SECONDS = -7200.0
    private const val LATITUDE_INCREMENT_SECONDS = 30.0
    private const val LONGITUDE_INCREMENT_SECONDS = 60.0
    private const val AIRY_A = 6377563.396
    private const val AIRY_B = 6356256.909
    private const val F0 = 0.9996012717
    private const val N0 = -100000.0
    private const val E0 = 400000.0
    private val PHI0 = Math.toRadians(49.0)
    private val LAMBDA0 = Math.toRadians(-2.0)
    @Volatile private var gridShifts: FloatBuffer? = null

    fun initialize(assetManager: AssetManager) {
        if (gridShifts != null) return
        initialize(assetManager.open(GRID_ASSET_NAME).use { it.readBytes() })
    }

    internal fun initialize(bytes: ByteArray) {
        if (gridShifts != null) return
        synchronized(this) {
            if (gridShifts != null) return
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8)
            header.get(magic)
            check(String(magic, Charsets.US_ASCII) == "OSTN15V1") { "Invalid OSTN15 grid asset." }
            check(header.int == GRID_ROWS && header.int == GRID_COLUMNS) { "Unexpected OSTN15 grid dimensions." }
            check(
                header.double == SOUTH_LATITUDE_SECONDS &&
                    header.double == EAST_LONGITUDE_SECONDS &&
                    header.double == LATITUDE_INCREMENT_SECONDS &&
                    header.double == LONGITUDE_INCREMENT_SECONDS,
            ) { "Unexpected OSTN15 grid definition." }
            check(bytes.size == GRID_HEADER_BYTES + GRID_ROWS * GRID_COLUMNS * 2 * Float.SIZE_BYTES) {
                "Incomplete OSTN15 grid asset."
            }
            gridShifts = header.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().asReadOnlyBuffer()
        }
    }

    fun fromWgs84(latitude: Double, longitude: Double): Pair<Double, Double> {
        require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            "Invalid GPS latitude or longitude."
        }
        val (latitudeShift, longitudeShift) = interpolateShifts(latitude, longitude)
        val osgbLatitude = latitude + latitudeShift / 3600.0
        // NTv2 longitudes are positive west, whereas Android GPS longitudes are positive east.
        val osgbLongitude = longitude - longitudeShift / 3600.0
        return projectOsgb36(osgbLatitude, osgbLongitude)
    }

    fun toWgs84(easting: Double, northing: Double): Pair<Double, Double> {
        require(easting.isFinite() && northing.isFinite()) { "Invalid grid coordinates." }
        var latitude = 55.0
        var longitude = -2.0
        repeat(12) {
            val projected = projectOsgb36(latitude, longitude)
            val latStep = projectOsgb36(latitude + 0.00001, longitude)
            val lonStep = projectOsgb36(latitude, longitude + 0.00001)
            val a = (latStep.first - projected.first) / 0.00001
            val b = (lonStep.first - projected.first) / 0.00001
            val c = (latStep.second - projected.second) / 0.00001
            val d = (lonStep.second - projected.second) / 0.00001
            val determinant = a * d - b * c
            require(determinant.isFinite() && kotlin.math.abs(determinant) > 1.0) {
                "Grid position cannot be converted to GPS coordinates."
            }
            val eastError = easting - projected.first
            val northError = northing - projected.second
            latitude += (eastError * d - b * northError) / determinant
            longitude += (a * northError - eastError * c) / determinant
        }
        val osgbLatitude = latitude
        val osgbLongitude = longitude
        repeat(5) {
            val (latitudeShift, longitudeShift) = interpolateShifts(latitude, longitude)
            latitude = osgbLatitude - latitudeShift / 3600.0
            longitude = osgbLongitude + longitudeShift / 3600.0
        }
        val result = fromWgs84(latitude, longitude)
        require(kotlin.math.hypot(result.first - easting, result.second - northing) < 0.01) {
            "Grid position cannot be converted to GPS coordinates."
        }
        return latitude to longitude
    }

    private fun interpolateShifts(latitude: Double, longitude: Double): Pair<Double, Double> {
        val grid = gridShifts ?: throw IllegalStateException("OSTN15 grid data has not been initialized.")
        val rowPosition = (latitude * 3600.0 - SOUTH_LATITUDE_SECONDS) / LATITUDE_INCREMENT_SECONDS
        val westLongitudeSeconds = -longitude * 3600.0
        val columnPosition = (westLongitudeSeconds - EAST_LONGITUDE_SECONDS) / LONGITUDE_INCREMENT_SECONDS
        require(
            rowPosition in 0.0..(GRID_ROWS - 1).toDouble() &&
                columnPosition in 0.0..(GRID_COLUMNS - 1).toDouble(),
        ) { "The GPS position is outside OSTN15 coverage." }

        val row = floor(rowPosition).toInt().coerceAtMost(GRID_ROWS - 2)
        val column = floor(columnPosition).toInt().coerceAtMost(GRID_COLUMNS - 2)
        val rowFraction = rowPosition - row
        val columnFraction = columnPosition - column

        fun interpolate(component: Int): Double {
            fun node(nodeRow: Int, nodeColumn: Int): Double =
                grid.get((nodeRow * GRID_COLUMNS + nodeColumn) * 2 + component).toDouble()

            val southwest = node(row, column)
            val southeast = node(row, column + 1)
            val northwest = node(row + 1, column)
            val northeast = node(row + 1, column + 1)
            return southwest * (1.0 - rowFraction) * (1.0 - columnFraction) +
                southeast * (1.0 - rowFraction) * columnFraction +
                northwest * rowFraction * (1.0 - columnFraction) +
                northeast * rowFraction * columnFraction
        }

        return interpolate(0) to interpolate(1)
    }

    private fun projectOsgb36(latitude: Double, longitude: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latitude)
        val lambda = Math.toRadians(longitude)
        val e2 = 1 - (AIRY_B * AIRY_B) / (AIRY_A * AIRY_A)
        val n = (AIRY_A - AIRY_B) / (AIRY_A + AIRY_B)
        val sinLat = sin(phi)
        val cosLat = cos(phi)
        val nu = AIRY_A * F0 / sqrt(1 - e2 * sinLat * sinLat)
        val rho = AIRY_A * F0 * (1 - e2) / (1 - e2 * sinLat * sinLat).pow(1.5)
        val eta2 = nu / rho - 1

        val dPhi = phi - PHI0
        val sPhi = phi + PHI0
        val m = AIRY_B * F0 * (
            (1 + n + 1.25 * n * n + 1.25 * n.pow(3)) * dPhi -
                (3 * n + 3 * n * n + 2.625 * n.pow(3)) * sin(dPhi) * cos(sPhi) +
                (1.875 * n * n + 1.875 * n.pow(3)) * sin(2 * dPhi) * cos(2 * sPhi) -
                (35.0 / 24.0) * n.pow(3) * sin(3 * dPhi) * cos(3 * sPhi)
            )

        val tanLat = tan(phi)
        val i = m + N0
        val ii = nu / 2 * sinLat * cosLat
        val iii = nu / 24 * sinLat * cosLat.pow(3) * (5 - tanLat.pow(2) + 9 * eta2)
        val iiia = nu / 720 * sinLat * cosLat.pow(5) * (61 - 58 * tanLat.pow(2) + tanLat.pow(4))
        val iv = nu * cosLat
        val v = nu / 6 * cosLat.pow(3) * (nu / rho - tanLat.pow(2))
        val vi = nu / 120 * cosLat.pow(5) *
            (5 - 18 * tanLat.pow(2) + tanLat.pow(4) + 14 * eta2 - 58 * tanLat.pow(2) * eta2)

        val dLon = lambda - LAMBDA0
        val northing = i + ii * dLon.pow(2) + iii * dLon.pow(4) + iiia * dLon.pow(6)
        val easting = E0 + iv * dLon + v * dLon.pow(3) + vi * dLon.pow(5)
        return easting to northing
    }

    fun toGridReference(easting: Double, northing: Double, figures: Int): String {
        require(figures == 8 || figures == 10) { "Grid reference precision must be 8 or 10 figures." }
        require(easting.isFinite() && northing.isFinite())
        val east100k = floor(easting / 100_000.0).toInt()
        val north100k = floor(northing / 100_000.0).toInt()
        require(east100k in 0..6 && north100k in 0..12) {
            "The GPS position is outside the British National Grid."
        }

        val letters = gridLetters(east100k, north100k)
        val figuresPerCoordinate = figures / 2
        val divisor = 10.0.pow(5 - figuresPerCoordinate).toLong()
        val eastingDigits = floor(easting).toLong() % 100_000L / divisor
        val northingDigits = floor(northing).toLong() % 100_000L / divisor
        return String.format(
            java.util.Locale.UK,
            "%s%0${figuresPerCoordinate}d%0${figuresPerCoordinate}d",
            letters,
            eastingDigits,
            northingDigits,
        )
    }

    internal fun toSixFigureGridReference(gridReference: String): String {
        val normalized = gridReference.filterNot(Char::isWhitespace).uppercase(java.util.Locale.UK)
        require(normalized.length == 8 || normalized.length == 10 || normalized.length == 12) {
            "Grid reference must contain 6, 8 or 10 figures."
        }
        val letters = normalized.substring(0, 2)
        val digits = normalized.substring(2)
        require(digits.all { it in '0'..'9' }) { "Grid reference contains invalid digits." }
        val figuresPerCoordinate = digits.length / 2
        find100kSquare(letters)
        require(figuresPerCoordinate in 3..5) { "Grid reference has invalid precision." }
        val eastDigits = digits.substring(0, figuresPerCoordinate)
        val northDigits = digits.substring(figuresPerCoordinate)
        return letters + eastDigits.take(3) + northDigits.take(3)
    }

    internal fun gridSquareForReference(gridReference: String): GridSquare {
        val normalized = gridReference.filterNot(Char::isWhitespace).uppercase(java.util.Locale.UK)
        require(normalized.length == 8 || normalized.length == 10 || normalized.length == 12) {
            "Grid reference must contain 6, 8 or 10 figures."
        }
        val letters = normalized.substring(0, 2)
        val digits = normalized.substring(2)
        require(digits.all { it in '0'..'9' }) { "Grid reference contains invalid digits." }
        val figuresPerCoordinate = digits.length / 2
        val square = find100kSquare(letters)
        val size = 10.0.pow(5 - figuresPerCoordinate)
        val eastingDigits = digits.substring(0, figuresPerCoordinate).toLong()
        val northingDigits = digits.substring(figuresPerCoordinate).toLong()
        return GridSquare(
            square.first * 100_000.0 + eastingDigits * size,
            square.second * 100_000.0 + northingDigits * size,
            size,
        )
    }

    private fun find100kSquare(letters: String): Pair<Int, Int> =
        (0..12).asSequence().flatMap { north ->
            (0..6).asSequence().map { east -> east to north }
        }.firstOrNull { (east, north) -> gridLetters(east, north) == letters }
            ?: throw IllegalArgumentException("Grid reference has invalid grid letters.")

    private fun gridLetters(east100k: Int, north100k: Int): String {
        var firstLetter = 19 - north100k - (19 - north100k) % 5 + (east100k + 10) / 5
        var secondLetter = (19 - north100k) * 5 % 25 + east100k % 5
        if (firstLetter > 7) firstLetter++
        if (secondLetter > 7) secondLetter++
        require(firstLetter in 0..25 && secondLetter in 0..25) {
            "The GPS position is outside the British National Grid."
        }
        return "${'A' + firstLetter}${'A' + secondLetter}"
    }
}
