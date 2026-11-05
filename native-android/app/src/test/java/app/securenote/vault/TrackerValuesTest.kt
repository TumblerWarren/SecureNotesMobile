package app.securenote.vault

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackerValuesTest {
    @Test
    fun roundTripsUnicodeEscapesEmptyValuesAndChecklistStates() {
        val values =
            linkedMapOf(
                "title-field" to "Reading \"海\"\nChapter one",
                "done" to "true",
                "image" to "",
                "notes" to "\\story\\",
            )
        assertEquals(values, TrackerValues.decode(TrackerValues.encode(values)))
        assertEquals(
            emptyMap<String, String>(),
            TrackerValues.decode(TrackerValues.encode(emptyMap())),
        )
        assertEquals(
            "false",
            TrackerValues.decode(TrackerValues.encode(mapOf("done" to "false")))["done"],
        )
    }

    @Test
    fun valueAndKeyLimitsAreInclusiveAndCheckedOnEncodeAndDecode() {
        val values = mapOf("k".repeat(100) to "v".repeat(20_000))
        assertEquals(values, TrackerValues.decode(TrackerValues.encode(values)))
        invalid { TrackerValues.encode(mapOf("k".repeat(101) to "value")) }
        invalid { TrackerValues.encode(mapOf("key" to "v".repeat(20_001))) }
        invalid { TrackerValues.decode(JSONObject().put("k".repeat(101), "value").toString()) }
        invalid { TrackerValues.decode(JSONObject().put("key", "v".repeat(20_001)).toString()) }
    }

    @Test
    fun capsRetainedValueCountWithoutDiscardingHiddenFields() {
        val values = (0 until 200).associate { "field-$it" to "value-$it" }
        assertEquals(values, TrackerValues.decode(TrackerValues.encode(values)))
        invalid { TrackerValues.encode(values + ("extra" to "value")) }
        val json = JSONObject(values).put("extra", "value")
        invalid { TrackerValues.decode(json.toString()) }
    }

    @Test
    fun enforcesTotalEncodedSizeSoEveryAcceptedWriteCanBeRead() {
        // Individual fields are within their limits, but their combined payload exceeds 500 KB.
        val oversized = (0 until 26).associate { "field-$it" to "v".repeat(20_000) }
        invalid { TrackerValues.encode(oversized) }
        invalid { TrackerValues.decode(JSONObject(oversized).toString()) }
        invalid { TrackerValues.decode(" ".repeat(500_001)) }
    }

    @Test
    fun rejectsNonStringAndMalformedStoredValues() {
        invalid { TrackerValues.decode("{\"done\":true}") }
        invalid { TrackerValues.decode("{\"chapters\":42}") }
        invalid { TrackerValues.decode("{\"image\":null}") }
        invalid { TrackerValues.decode("{\"notes\":[]}") }
        assertThrows(RuntimeException::class.java) { TrackerValues.decode("not json") }
    }

    private fun invalid(action: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, action)
    }
}
