package app.securenote.vault

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackerFormatTest {
    @Test
    fun readingTemplateDefinesThumbnailChapterCountAndStatusWithStableRoundTrip() {
        val reading = TrackerFormat.reading()
        assertEquals(
            listOf("Thumbnail", "Chapter count", "Status"),
            reading.fields.map { it.label },
        )
        assertEquals(
            listOf(TrackerFieldType.IMAGE, TrackerFieldType.NUMBER, TrackerFieldType.STATUS),
            reading.fields.map { it.type },
        )
        assertEquals(listOf("Planned", "In progress", "Completed"), reading.fields.last().options)
        assertEquals(reading.fields.size, reading.fields.map { it.id }.toSet().size)
        assertEquals(reading, TrackerFormat.decode(reading.encode()))
    }

    @Test
    fun checklistAndBlankTemplatesCanBeCustomized() {
        val checklist = TrackerFormat.checklist()
        assertEquals("Done", checklist.fields.single().label)
        assertEquals(TrackerFieldType.CHECKBOX, checklist.fields.single().type)
        assertTrue(TrackerFormat.blank().fields.isEmpty())
        val custom =
            TrackerFormat(
                checklist.fields + TrackerField(label = "Notes", type = TrackerFieldType.TEXT)
            )
        assertEquals(custom, TrackerFormat.decode(custom.encode()))
        assertEquals(TrackerFormat.blank(), TrackerFormat.decode(TrackerFormat.blank().encode()))
    }

    @Test
    fun renamingReorderingAndRemovingFieldsPreservesValueIdentity() {
        val original = TrackerFormat.reading()
        val values =
            mapOf(
                original.fields[0].id to "vault-image-id",
                original.fields[1].id to "42",
                original.fields[2].id to "In progress",
            )
        val encoded = TrackerValues.encode(values)
        val reordered =
            TrackerFormat(original.fields.reversed().map { it.copy(label = "My ${it.label}") })
        val decoded = TrackerFormat.decode(reordered.encode())
        assertEquals(original.fields.map { it.id }.reversed(), decoded.fields.map { it.id })
        assertEquals("42", TrackerValues.decode(encoded)[decoded.fields[1].id])
        val removed = TrackerFormat(decoded.fields.filter { it.type != TrackerFieldType.NUMBER })
        assertEquals(2, TrackerFormat.decode(removed.encode()).fields.size)
        // Hiding a field does not reinterpret or erase the independently stored entry values.
        assertEquals(values, TrackerValues.decode(encoded))
    }

    @Test
    fun encodingTrimsLabelsAndChoicesWithoutChangingIdentities() {
        val field =
            TrackerField(
                label = " Status ",
                type = TrackerFieldType.STATUS,
                options = listOf(" Planned ", "Done "),
            )
        val decoded = TrackerFormat.decode(TrackerFormat(listOf(field)).encode()).fields.single()
        assertEquals(field.id, decoded.id)
        assertEquals("Status", decoded.label)
        assertEquals(listOf("Planned", "Done"), decoded.options)
    }

    @Test
    fun rejectsDuplicateLabelsRegardlessOfCaseAndWhitespace() {
        invalid { TrackerFormat(listOf(text("Progress"), text(" progress "))).validate() }
        invalid { TrackerFormat(listOf(text("Status"), text("STATUS"))).encode() }
    }

    @Test
    fun rejectsDuplicateStatusChoicesAndEmptyOrExcessiveChoices() {
        invalid { TrackerFormat(listOf(status(listOf("Done", " done ")))).validate() }
        invalid { TrackerFormat(listOf(status(emptyList()))).validate() }
        invalid { TrackerFormat(listOf(status(listOf(" ")))).validate() }
        invalid { TrackerFormat(listOf(status(List(31) { "Status $it" }))).validate() }
        invalid { TrackerFormat(listOf(status(listOf("x".repeat(81))))).validate() }
        TrackerFormat(listOf(status(List(30) { "Status $it" }))).validate()
    }

    @Test
    fun reservesBuiltInTitleAndRejectsInvalidFieldDefinitions() {
        listOf("Title", " title ", "TITLE").forEach { label ->
            invalid { TrackerFormat(listOf(text(label))).validate() }
        }
        invalid { TrackerFormat(listOf(text(" "))).validate() }
        invalid { TrackerFormat(listOf(text("x".repeat(81)))).validate() }
        invalid { TrackerFormat(listOf(text("A").copy(id = ""))).validate() }
        invalid { TrackerFormat(listOf(text("A").copy(id = "x".repeat(101)))).validate() }
        invalid {
            TrackerFormat(listOf(text("A").copy(id = "same"), text("B").copy(id = "same")))
                .validate()
        }
        invalid { TrackerFormat(listOf(text("A").copy(options = listOf("Unexpected")))).validate() }
    }

    @Test
    fun enforcesTwentyFieldLimitOnEncodeAndDecode() {
        val twenty = TrackerFormat(List(20) { text("Field $it") })
        assertEquals(twenty, TrackerFormat.decode(twenty.encode()))
        invalid { TrackerFormat(twenty.fields + text("Extra")).encode() }
        val encoded = JSONObject(twenty.encode())
        encoded
            .getJSONArray("fields")
            .put(
                JSONObject()
                    .put("id", "extra")
                    .put("label", "Extra")
                    .put("type", "TEXT")
                    .put("options", JSONArray())
            )
        invalid { TrackerFormat.decode(encoded.toString()) }
    }

    @Test
    fun decodingValidatesPayloadVersionAndDefinitions() {
        val valid = TrackerFormat(listOf(text("Notes"))).encode()
        invalid { TrackerFormat.decode(JSONObject(valid).put("version", 2).toString()) }
        val duplicate = JSONObject(valid)
        duplicate.getJSONArray("fields").put(duplicate.getJSONArray("fields").getJSONObject(0))
        invalid { TrackerFormat.decode(duplicate.toString()) }
        val title = JSONObject(valid)
        title.getJSONArray("fields").getJSONObject(0).put("label", "TITLE")
        invalid { TrackerFormat.decode(title.toString()) }
        invalid { TrackerFormat.decode(" ".repeat(100_001)) }
        assertThrows(RuntimeException::class.java) { TrackerFormat.decode("not json") }
        assertThrows(RuntimeException::class.java) { TrackerFormat.decode("{}") }
    }

    private fun text(label: String) = TrackerField(label = label, type = TrackerFieldType.TEXT)

    private fun status(options: List<String>) =
        TrackerField(label = "Status", type = TrackerFieldType.STATUS, options = options)

    private fun invalid(action: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, action)
    }
}
