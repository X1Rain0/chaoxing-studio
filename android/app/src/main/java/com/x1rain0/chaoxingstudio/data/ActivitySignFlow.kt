package com.x1rain0.chaoxingstudio.data

internal enum class ActivitySignFlow(val actionLabel: String, val submitLabel: String) {
    GENERAL("确认签到", "确认签到"),
    PHOTO("预览并选择签到照片", "使用所选照片并签到"),
    QR("扫码签到", "确认二维码并签到"),
    GESTURE("画手势签到", "确认手势并签到"),
    LOCATION("确认签到位置", "确认位置并签到"),
    CODE("输入签到码", "使用本次签到码签到"),
    UNSUPPORTED("暂不支持", "暂不支持"),
}

internal fun activitySignFlow(kind: Int): ActivitySignFlow = when (kind) {
    SignKinds.GENERAL -> ActivitySignFlow.GENERAL
    SignKinds.PHOTO -> ActivitySignFlow.PHOTO
    SignKinds.QR -> ActivitySignFlow.QR
    SignKinds.GESTURE -> ActivitySignFlow.GESTURE
    SignKinds.LOCATION -> ActivitySignFlow.LOCATION
    SignKinds.CODE -> ActivitySignFlow.CODE
    else -> ActivitySignFlow.UNSUPPORTED
}
