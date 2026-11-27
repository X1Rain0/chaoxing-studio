package com.x1rain0.chaoxingstudio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChaoxingQrParserTest {
    @Test
    fun extractsEncFromUrlAndIgnoresOtherQueryParameters() {
        assertEquals(
            "1D0A628CK317F44CCC378M5KD92",
            extractChaoxingEnc("https://mobilelearn.chaoxing.com/page?courseId=1&enc=1D0A628CK317F44CCC378M5KD92&activeId=2#top"),
        )
    }

    @Test
    fun decodesEscapedEncAndMatchesParameterNameCaseInsensitively() {
        assertEquals("token-._~1234", extractChaoxingEnc("https://example.test/?ENC=token-._%7E1234"))
    }

    @Test
    fun acceptsDirectTokenButNotArbitraryUrlOrShortValue() {
        assertEquals("1D0A628CK317F44CCC378M5KD92", extractChaoxingEnc("1D0A628CK317F44CCC378M5KD92"))
        assertNull(extractChaoxingEnc("https://example.test/path"))
        assertNull(extractChaoxingEnc("short"))
    }

    @Test
    fun rejectsMissingEmptyMalformedAndOversizedValues() {
        assertNull(extractChaoxingEnc("https://example.test/?activeId=1"))
        assertNull(extractChaoxingEnc("https://example.test/?enc="))
        assertNull(extractChaoxingEnc("https://example.test/?enc=%ZZ"))
        assertNull(extractChaoxingEnc("x".repeat(4_097)))
        assertNull(extractChaoxingEnc("https://example.test/?enc=${"a".repeat(513)}"))
    }
}
