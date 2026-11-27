package com.x1rain0.chaoxingstudio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GesturePatternTest {
    @Test
    fun insertsMidpointsForHorizontalVerticalAndDiagonalJumps() {
        assertEquals(listOf(1, 2, 3), appendGesturePoint(listOf(1), 3))
        assertEquals(listOf(1, 4, 7), appendGesturePoint(listOf(1), 7))
        assertEquals(listOf(1, 5, 9), appendGesturePoint(listOf(1), 9))
    }

    @Test
    fun doesNotDuplicateVisitedOrOutOfRangeDots() {
        val path = listOf(2, 1)
        assertEquals(path, appendGesturePoint(path, 2))
        assertEquals(path, appendGesturePoint(path, 0))
        assertEquals(listOf(2, 1, 3), appendGesturePoint(path, 3))
    }

    @Test
    fun serializesPatternAsDigits() {
        assertEquals("1235789", gestureCode(listOf(1, 2, 3, 5, 7, 8, 9)))
        assertEquals("123", gestureCode(listOf(1, 2, 2, 3, 10)))
    }

    @Test
    fun digitEntryUsesTheSameMidpointRulesAndIgnoresNonDigits() {
        assertEquals(listOf(1, 2, 3), gesturePathFromDigits("13"))
        assertEquals("123", gestureCode(gesturePathFromDigits("13")))
        assertEquals(listOf(1, 2, 3, 5, 7), gesturePathFromDigits("1357"))
        assertEquals("12357", gestureCode(gesturePathFromDigits("1357")))
        assertEquals(listOf(1, 2, 3), gesturePathFromDigits("1x3"))
        assertEquals(listOf(1, 5, 9), gesturePathFromDigits("159"))
    }

    @Test
    fun mapsTouchCoordinatesToTheNearestGridDot() {
        assertEquals(1, gesturePointAt(50f, 50f, 300f, 300f))
        assertEquals(5, gesturePointAt(150f, 150f, 300f, 300f))
        assertEquals(9, gesturePointAt(250f, 250f, 300f, 300f))
        assertNull(gesturePointAt(10f, 10f, 300f, 300f))
        assertNull(gesturePointAt(150f, 150f, 0f, 300f))
    }
}
