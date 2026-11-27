package com.x1rain0.chaoxingstudio.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignReadinessTest {
    private fun config(
        latitude: String = "",
        longitude: String = "",
    ) = MonitorConfig(
        monitorWanted = true,
        mode = MonitorMode.BACKGROUND,
        bootRestore = false,
        autoSign = true,
        intervalSeconds = 60,
        selectedCoursesJson = "[]",
        latitude = latitude,
        longitude = longitude,
        address = "",
    )

    @Test
    fun generalAndPhotoDoNotRequireExtraInputs() {
        val empty = config()
        assertTrue(isAutoSignReady(SignKinds.GENERAL, empty))
        assertTrue(isAutoSignReady(SignKinds.PHOTO, empty))
    }

    @Test
    fun qrGestureAndCodeAlwaysRequireFreshManualInput() {
        val withLocation = config(latitude = "39.9", longitude = "116.4")
        assertFalse(isAutoSignReady(SignKinds.QR, withLocation))
        assertFalse(isAutoSignReady(SignKinds.GESTURE, withLocation))
        assertFalse(isAutoSignReady(SignKinds.CODE, withLocation))
    }

    @Test
    fun locationRequiresBothCoordinates() {
        assertFalse(isAutoSignReady(SignKinds.LOCATION, config()))
        assertFalse(isAutoSignReady(SignKinds.LOCATION, config(latitude = "39.9")))
        assertFalse(isAutoSignReady(SignKinds.LOCATION, config(longitude = "116.4")))
        assertTrue(isAutoSignReady(SignKinds.LOCATION, config(latitude = "39.9", longitude = "116.4")))
    }

    @Test
    fun unsupportedKindsAreNeverAutoSigned() {
        assertFalse(isAutoSignReady(99, config(latitude = "1", longitude = "2")))
    }
}
