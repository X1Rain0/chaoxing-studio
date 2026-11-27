package com.x1rain0.chaoxingstudio.data

/** Numeric values match chaoxing.Kind in internal/chaoxing/types.go. */
internal object SignKinds {
    const val GENERAL = 0
    const val PHOTO = 1
    const val QR = 2
    const val GESTURE = 3
    const val LOCATION = 4
    const val CODE = 5
}

/** QR, gesture, and teacher codes require fresh user input for each activity. */
internal fun isAutoSignReady(kind: Int, config: MonitorConfig): Boolean = when (kind) {
    SignKinds.GENERAL, SignKinds.PHOTO -> true
    SignKinds.QR, SignKinds.GESTURE, SignKinds.CODE -> false
    SignKinds.LOCATION -> config.latitude.isNotBlank() && config.longitude.isNotBlank()
    else -> false
}
