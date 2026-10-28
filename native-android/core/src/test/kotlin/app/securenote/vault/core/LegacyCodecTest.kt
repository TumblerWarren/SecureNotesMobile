package app.securenote.vault.core

import java.util.Base64
import kotlin.test.*

class LegacyCodecTest {
    private val fixture =
        Base64.getDecoder()
            .decode(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaG0YOgVwrTgLxKDzg/f8shi5KmWgTQf03JzdAmsUF20aizQDD4O+tgvTgfcQogslOlcMZNaRQXIPLwvBo/I/f3Zq5cXj6aalUjRTaempiTXzlWzWvXwzy5QaM4ikViHLFcRRb2/rRJR2ZfgsDoffj39wHuI5HKsneqD9d4tQ4nDWJLHF4AFRH7oPofJqLgcEtQA=="
            )

    @Test
    fun importsActualCryptoJs420UnicodePasswordFixture() {
        val password = "Legacy password 😀 café".toByteArray()
        val plain = LegacyCodec.decrypt(fixture.inputStream(), password)
        assertEquals(128, plain.size)
        assertEquals("legacy-fixture", plain.copyOfRange(100, 114).toString(Charsets.UTF_8))
        assertTrue(password.all { it == 0.toByte() })
        assertEquals(2, plain[18].toInt())
    }

    @Test
    fun rejectsWrongPasswordAndTruncatedInput() {
        assertFails { LegacyCodec.decrypt(fixture.inputStream(), "wrong password".toByteArray()) }
        assertFails {
            LegacyCodec.decrypt(
                fixture.copyOfRange(0, 20).inputStream(),
                "Legacy password 😀 café".toByteArray(),
            )
        }
    }
}
