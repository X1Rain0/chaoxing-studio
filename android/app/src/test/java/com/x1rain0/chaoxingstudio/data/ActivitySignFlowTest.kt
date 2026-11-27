package com.x1rain0.chaoxingstudio.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivitySignFlowTest {
    @Test
    fun eachKnownKindGetsAnActionAndSubmissionLabel() {
        assertEquals("确认签到", activitySignFlow(SignKinds.GENERAL).actionLabel)
        assertEquals("确认签到", activitySignFlow(SignKinds.GENERAL).submitLabel)
        assertEquals("预览并选择签到照片", activitySignFlow(SignKinds.PHOTO).actionLabel)
        assertEquals("使用所选照片并签到", activitySignFlow(SignKinds.PHOTO).submitLabel)
        assertEquals("扫码签到", activitySignFlow(SignKinds.QR).actionLabel)
        assertEquals("确认二维码并签到", activitySignFlow(SignKinds.QR).submitLabel)
        assertEquals("画手势签到", activitySignFlow(SignKinds.GESTURE).actionLabel)
        assertEquals("确认手势并签到", activitySignFlow(SignKinds.GESTURE).submitLabel)
        assertEquals("确认签到位置", activitySignFlow(SignKinds.LOCATION).actionLabel)
        assertEquals("确认位置并签到", activitySignFlow(SignKinds.LOCATION).submitLabel)
        assertEquals("输入签到码", activitySignFlow(SignKinds.CODE).actionLabel)
        assertEquals("使用本次签到码签到", activitySignFlow(SignKinds.CODE).submitLabel)
    }

    @Test
    fun unknownKindIsMarkedUnsupported() {
        assertEquals(ActivitySignFlow.UNSUPPORTED, activitySignFlow(-1))
        assertEquals(ActivitySignFlow.UNSUPPORTED, activitySignFlow(99))
    }
}
