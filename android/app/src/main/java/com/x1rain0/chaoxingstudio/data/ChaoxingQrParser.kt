package com.x1rain0.chaoxingstudio.data

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Extract the short-lived `enc` value from a scanned Chaoxing QR URL. */
internal fun extractChaoxingEnc(rawValue: String): String? {
    val value = rawValue.trim()
    if (value.isEmpty() || value.length > MAX_QR_TEXT_LENGTH) return null

    val queryStart = value.indexOf('?')
    if (queryStart >= 0) {
        val query = value.substring(queryStart + 1).substringBefore('#')
        for (pair in query.split('&')) {
            val separator = pair.indexOf('=')
            val rawName = if (separator < 0) pair else pair.substring(0, separator)
            val name = decodeQueryComponent(rawName) ?: continue
            if (!name.equals("enc", ignoreCase = true)) continue
            if (separator < 0) return null
            val token = decodeQueryComponent(pair.substring(separator + 1))?.trim().orEmpty()
            return token.takeIf { it.isNotEmpty() && it.length <= MAX_ENC_LENGTH }
        }
        return null
    }

    // Some scanners/services may return the enc value directly instead of its URL.
    if (value.contains("://") || value.contains('/') || value.contains('?') || value.contains('#')) return null
    if (value.length !in MIN_DIRECT_ENC_LENGTH..MAX_ENC_LENGTH) return null
    return value.takeIf { token -> token.all { it.isLetterOrDigit() || it in "-_.~" } }
}

private fun decodeQueryComponent(value: String): String? =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()

private const val MAX_QR_TEXT_LENGTH = 4_096
private const val MIN_DIRECT_ENC_LENGTH = 12
private const val MAX_ENC_LENGTH = 512
