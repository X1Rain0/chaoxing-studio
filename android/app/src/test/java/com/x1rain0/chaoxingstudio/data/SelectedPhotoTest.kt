package com.x1rain0.chaoxingstudio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedPhotoTest {
    @Test
    fun thumbnailSampleSizeKeepsBothEdgesWithinBound() {
        assertEquals(1, thumbnailSampleSize(600, 400, 768))
        assertEquals(4, thumbnailSampleSize(3000, 1800, 768))
        assertEquals(8, thumbnailSampleSize(6000, 4000, 768))
        assertEquals(1, thumbnailSampleSize(0, 100, 768))
    }

    @Test
    fun manualPhotoSignNeedsASelectedDecodedPreviewAndNoPendingWork() {
        assertFalse(isPhotoSelectionReady(false, true, false, false, false))
        assertFalse(isPhotoSelectionReady(true, false, false, false, false))
        assertFalse(isPhotoSelectionReady(true, true, true, false, false))
        assertFalse(isPhotoSelectionReady(true, true, false, true, false))
        assertFalse(isPhotoSelectionReady(true, true, false, false, true))
        assertTrue(isPhotoSelectionReady(true, true, false, false, false))
    }

    @Test
    fun acceptsOnlyJpegAndPngMimeTypes() {
        assertEquals("image/jpeg", normalizePhotoMimeType("image/jpeg; charset=binary"))
        assertEquals("image/jpeg", normalizePhotoMimeType("image/jpg"))
        assertEquals("image/png", normalizePhotoMimeType("image/png"))
        assertNull(normalizePhotoMimeType("image/heic"))
        assertNull(normalizePhotoMimeType("image/gif"))
    }

    @Test
    fun detectsImageTypeFromMagicBytes() {
        assertEquals("image/jpeg", detectPhotoMimeType(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertEquals(
            "image/png",
            detectPhotoMimeType(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)),
        )
        assertNull(detectPhotoMimeType("not an image".encodeToByteArray()))
    }
}
