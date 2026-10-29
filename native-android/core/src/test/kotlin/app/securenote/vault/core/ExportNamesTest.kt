package app.securenote.vault.core

import kotlin.test.*

class ExportNamesTest {
    @Test
    fun duplicatesKeepTheirExtensionAndDoNotOverwriteExistingNames() {
        val names = ExportNames(listOf("photo.jpg", "photo (2).JPG", "Report", "Report (2)"))
        assertEquals("photo (3).jpg", names.reserve("photo.jpg"))
        assertEquals("PHOTO (4).JPG", names.reserve("PHOTO.JPG"))
        assertEquals("Report (3)", names.reserve("Report", directory = true))
        assertEquals("song.mp3", names.reserve("song.mp3"))
        assertEquals("song (2).mp3", names.reserve("song.mp3"))
    }

    @Test
    fun filesAndFoldersShareOneNamespaceAndUnicodeAliasesCannotClash() {
        val names = ExportNames(listOf("café.txt"))
        assertEquals("cafe\u0301 (2).txt", names.reserve("cafe\u0301.txt"))
        assertEquals("Trip", names.reserve("Trip", directory = true))
        assertEquals("Trip (2)", names.reserve("Trip"))
        assertEquals(".hidden", names.reserve(".hidden"))
        assertEquals(".hidden (2)", names.reserve(".hidden"))
    }

    @Test
    fun unsafeNamesAreContainedWithoutReplacingOrdinaryNames() {
        assertEquals("original photo.jpeg", ExportNames.safe("original photo.jpeg"))
        assertEquals(".._secret", ExportNames.safe("../secret"))
        assertEquals("file", ExportNames.safe(".."))
        assertEquals("file", ExportNames.safe(""))
        assertFalse(ExportNames.safe("x\\y/z\n").contains(Regex("[\\\\/\\p{Cntrl}]")))
    }
}
