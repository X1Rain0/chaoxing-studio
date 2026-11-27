package com.x1rain0.chaoxingstudio.data

/** Boot restoration is opt-in and only applies to an active background monitor. */
internal fun shouldRestoreMonitorOnBoot(config: MonitorConfig): Boolean =
    config.bootRestore && config.monitorWanted && config.mode == MonitorMode.BACKGROUND
