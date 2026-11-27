package com.x1rain0.chaoxingstudio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

internal const val MAX_SELECTED_PHOTO_BYTES = 6 * 1024 * 1024
internal const val MAX_SELECTED_PHOTO_PIXELS = 40_000_000L

/** Kept only in the current sign dialog; neither URI nor bytes are persisted. */
internal data class SelectedPhoto(
    val mimeType: String,
    val bytes: ByteArray,
    val preview: Bitmap,
)

internal fun normalizePhotoMimeType(value: String?): String? = when (value?.substringBefore(';')?.trim()?.lowercase()) {
    "image/jpeg", "image/jpg", "image/pjpeg" -> "image/jpeg"
    "image/png" -> "image/png"
    else -> null
}

internal fun isPhotoSelectionReady(
    hasSelection: Boolean,
    hasDecodedPreview: Boolean,
    loadingSelection: Boolean,
    uploading: Boolean,
    hasError: Boolean,
): Boolean = hasSelection && hasDecodedPreview && !loadingSelection && !uploading && !hasError

/** Reads a Photo Picker URI with a hard byte cap and samples the preview before allocating pixels. */
internal fun readSelectedPhoto(context: Context, uri: Uri): SelectedPhoto {
    val resolver = context.contentResolver
    val claimedType = resolver.getType(uri)
    val claimedMime = if (claimedType.isNullOrBlank()) null else normalizePhotoMimeType(claimedType)
        ?: error("仅支持JPG或PNG图片；请转换格式后重选。")
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            check(output.size() + count <= MAX_SELECTED_PHOTO_BYTES) {
                "图片超过6MiB上限，请选择较小的JPG或PNG。"
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("无法读取所选图片。")
    check(bytes.isNotEmpty()) { "所选图片为空。" }

    val detectedMime = detectPhotoMimeType(bytes) ?: error("所选文件不是有效的JPG或PNG图片。")
    check(claimedMime == null || claimedMime == detectedMime) { "图片内容与文件类型不一致，请重新选择。" }
    val preview = decodePhotoThumbnail(bytes) ?: error("图片无法预览，请选择其他JPG或PNG图片。")
    return SelectedPhoto(mimeType = detectedMime, bytes = bytes, preview = preview)
}

internal fun detectPhotoMimeType(bytes: ByteArray): String? {
    if (bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) {
        return "image/png"
    }
    if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) {
        return "image/jpeg"
    }
    return null
}

internal fun decodePhotoThumbnail(bytes: ByteArray, maxEdgePx: Int = 768): Bitmap? {
    if (bytes.isEmpty() || bytes.size > MAX_SELECTED_PHOTO_BYTES || maxEdgePx <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width.toLong() * height.toLong() > MAX_SELECTED_PHOTO_PIXELS) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = thumbnailSampleSize(width, height, maxEdgePx)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

internal fun thumbnailSampleSize(width: Int, height: Int, maxEdgePx: Int = 768): Int {
    if (width <= 0 || height <= 0 || maxEdgePx <= 0) return 1
    var sample = 1
    while (width / sample > maxEdgePx || height / sample > maxEdgePx) sample *= 2
    return sample
}
