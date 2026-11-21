package com.poted.pilot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    @Test
    fun noLoneTileAndAllTilesPlaced() {
        for (n in 0..12) {
            val rows = balancedRows(n)
            assertEquals("n=$n", n, rows.sum())
            assertTrue("n=$n $rows", rows.all { it in 1..3 })
            if (n >= 2) assertTrue("n=$n $rows", rows.last() >= 2)
        }
    }

    @Test
    fun examples() {
        assertEquals(listOf(3), balancedRows(3))
        assertEquals(listOf(2, 2), balancedRows(4))
        assertEquals(listOf(3, 2), balancedRows(5))
        assertEquals(listOf(3, 2, 2), balancedRows(7))
    }
}
