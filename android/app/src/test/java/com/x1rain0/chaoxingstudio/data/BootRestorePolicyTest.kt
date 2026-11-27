package com.x1rain0.chaoxingstudio.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRestorePolicyTest {
    private fun config(
        bootRestore: Boolean,
        monitorWanted: Boolean,
        mode: MonitorMode,
    ) = MonitorConfig(
        monitorWanted = monitorWanted,
        mode = mode,
        bootRestore = bootRestore,
        autoSign = false,
        intervalSeconds = 60,
        selectedCoursesJson = "[]",
        latitude = "",
        longitude = "",
        address = "",
    )

    @Test
    fun restoreRequiresOptInActiveMonitoringAndBackgroundMode() {
        assertFalse(shouldRestoreMonitorOnBoot(config(bootRestore = false, monitorWanted = true, mode = MonitorMode.BACKGROUND)))
        assertFalse(shouldRestoreMonitorOnBoot(config(bootRestore = true, monitorWanted = false, mode = MonitorMode.BACKGROUND)))
        assertFalse(shouldRestoreMonitorOnBoot(config(bootRestore = true, monitorWanted = true, mode = MonitorMode.FOREGROUND)))
        assertTrue(shouldRestoreMonitorOnBoot(config(bootRestore = true, monitorWanted = true, mode = MonitorMode.BACKGROUND)))
    }
}
