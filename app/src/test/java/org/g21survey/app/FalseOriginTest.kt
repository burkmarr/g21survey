package org.g21survey.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test

class FalseOriginTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun initializeGrid() {
            BritishNationalGrid.initialize(File("src/main/assets/ostn15-etrs-to-osgb.bin").readBytes())
        }
    }

    @Test
    fun `eight figure origin maps first fix to square centre and preserves movement`() {
        val square = falseOriginSquare("sd 5212 1845")
        val offset = GridOffset.fromOrigin(square, 400000.0, 300000.0)
        val first = offset.apply(400000.0, 300000.0)
        assertEquals(352125.0, first.first, 0.00001)
        assertEquals(418455.0, first.second, 0.00001)
        val next = offset.apply(400012.5, 299980.0)
        assertEquals(first.first + 12.5, next.first, 0.00001)
        assertEquals(first.second - 20.0, next.second, 0.00001)
    }

    @Test
    fun `origin rejects incorrect precision and invalid references`() {
        listOf("", "SD521184", "SD5212318456", "II12345678", "SD123X5678").forEach { reference ->
            assertThrows(IllegalArgumentException::class.java) { falseOriginSquare(reference) }
        }
    }

    @Test
    fun `adjusted grid positions round trip through WGS84 for GPX`() {
        listOf(352125.0 to 518455.0, 530005.0 to 180005.0, 245005.0 to 670005.0).forEach {
            val (latitude, longitude) = BritishNationalGrid.toWgs84(it.first, it.second)
            val (easting, northing) = BritishNationalGrid.fromWgs84(latitude, longitude)
            assertEquals(it.first, easting, 0.01)
            assertEquals(it.second, northing, 0.01)
        }
    }

    @Test
    fun `origin outside OSTN15 coverage cannot be converted`() {
        val reference = BritishNationalGrid.toGridReference(699995.0, 1299995.0, 8)
        val square = falseOriginSquare(reference)
        assertThrows(IllegalArgumentException::class.java) {
            BritishNationalGrid.toWgs84(
                square.easting + square.sizeMetres / 2,
                square.northing + square.sizeMetres / 2,
            )
        }
    }
}
