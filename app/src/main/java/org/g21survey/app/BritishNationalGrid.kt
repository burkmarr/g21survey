package org.g21survey.app

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Converts WGS84 latitude/longitude (degrees) to EPSG:27700 easting/northing using the OS Helmert + TM formulas. */
object BritishNationalGrid {
    private const val WGS84_A = 6378137.0
    private const val WGS84_B = 6356752.314245
    private const val AIRY_A = 6377563.396
    private const val AIRY_B = 6356256.909
    private const val F0 = 0.9996012717
    private const val N0 = -100000.0
    private const val E0 = 400000.0
    private val PHI0 = Math.toRadians(49.0)
    private val LAMBDA0 = Math.toRadians(-2.0)

    fun fromWgs84(latitude: Double, longitude: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latitude)
        val lambda = Math.toRadians(longitude)

        val e2Wgs = 1 - (WGS84_B * WGS84_B) / (WGS84_A * WGS84_A)
        val nuWgs = WGS84_A / sqrt(1 - e2Wgs * sin(phi).pow(2))
        val x1 = nuWgs * cos(phi) * cos(lambda)
        val y1 = nuWgs * cos(phi) * sin(lambda)
        val z1 = nuWgs * (1 - e2Wgs) * sin(phi)

        val tx = -446.448
        val ty = 125.157
        val tz = -542.060
        val s = 20.4894e-6
        val rx = Math.toRadians(-0.1502 / 3600)
        val ry = Math.toRadians(-0.2470 / 3600)
        val rz = Math.toRadians(-0.8421 / 3600)
        val x2 = tx + x1 * (1 + s) - y1 * rz + z1 * ry
        val y2 = ty + x1 * rz + y1 * (1 + s) - z1 * rx
        val z2 = tz - x1 * ry + y1 * rx + z1 * (1 + s)

        val e2 = 1 - (AIRY_B * AIRY_B) / (AIRY_A * AIRY_A)
        val p = sqrt(x2 * x2 + y2 * y2)
        var lat = atan2(z2, p * (1 - e2))
        repeat(10) {
            val nu = AIRY_A / sqrt(1 - e2 * sin(lat).pow(2))
            lat = atan2(z2 + e2 * nu * sin(lat), p)
        }
        val lon = atan2(y2, x2)

        val n = (AIRY_A - AIRY_B) / (AIRY_A + AIRY_B)
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val nu = AIRY_A * F0 / sqrt(1 - e2 * sinLat * sinLat)
        val rho = AIRY_A * F0 * (1 - e2) / (1 - e2 * sinLat * sinLat).pow(1.5)
        val eta2 = nu / rho - 1

        val dPhi = lat - PHI0
        val sPhi = lat + PHI0
        val m = AIRY_B * F0 * (
            (1 + n + 1.25 * n * n + 1.25 * n.pow(3)) * dPhi -
                (3 * n + 3 * n * n + 2.625 * n.pow(3)) * sin(dPhi) * cos(sPhi) +
                (1.875 * n * n + 1.875 * n.pow(3)) * sin(2 * dPhi) * cos(2 * sPhi) -
                (35.0 / 24.0) * n.pow(3) * sin(3 * dPhi) * cos(3 * sPhi)
            )

        val tanLat = tan(lat)
        val i = m + N0
        val ii = nu / 2 * sinLat * cosLat
        val iii = nu / 24 * sinLat * cosLat.pow(3) * (5 - tanLat.pow(2) + 9 * eta2)
        val iiia = nu / 720 * sinLat * cosLat.pow(5) * (61 - 58 * tanLat.pow(2) + tanLat.pow(4))
        val iv = nu * cosLat
        val v = nu / 6 * cosLat.pow(3) * (nu / rho - tanLat.pow(2))
        val vi = nu / 120 * cosLat.pow(5) *
            (5 - 18 * tanLat.pow(2) + tanLat.pow(4) + 14 * eta2 - 58 * tanLat.pow(2) * eta2)

        val dLon = lon - LAMBDA0
        val northing = i + ii * dLon.pow(2) + iii * dLon.pow(4) + iiia * dLon.pow(6)
        val easting = E0 + iv * dLon + v * dLon.pow(3) + vi * dLon.pow(5)
        return easting to northing
    }
}
