package com.x1rain0.chaoxingstudio.data

/** One-event input only. Never persist QR tokens or gesture/sign codes. */
internal data class ManualSignInput(
    val signCode: String = "",
    val enc: String = "",
    val latitude: String? = null,
    val longitude: String? = null,
    val address: String? = null,
    val photoObjectId: String = "",
)
