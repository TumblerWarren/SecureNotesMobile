package app.securenote.vault

import androidx.room3.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

enum class TrackerFieldType {
    TEXT,
    NUMBER,
    STATUS,
    CHECKBOX,
    IMAGE,
}

data class TrackerField(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val type: TrackerFieldType,
    val options: List<String> = emptyList(),
)

/** Field identities survive renaming and reordering; entry values use identities, never labels. */
data class TrackerFormat(val fields: List<TrackerField>) {
    fun validate() {
        require(fields.size <= 20) { "Use up to 20 fields in a tracker format." }
        require(fields.map { it.id }.distinct().size == fields.size) {
            "Field identities must be unique."
        }
        require(
            fields.map { it.label.trim().lowercase(Locale.ROOT) }.distinct().size == fields.size
        ) {
            "Give each field a different name."
        }
        fields.forEach { field ->
            require(field.id.isNotBlank() && field.id.length <= 100)
            require(field.label.isNotBlank() && field.label.trim().length <= 80) {
                "Field names must contain 1–80 characters."
            }
            require(!field.label.trim().equals("title", ignoreCase = true)) {
                "Title is already included in every tracker entry."
            }
            if (field.type == TrackerFieldType.STATUS) {
                require(field.options.size in 1..30) { "Add between 1 and 30 status choices." }
                require(field.options.all { it.isNotBlank() && it.trim().length <= 80 }) {
                    "Status choices must contain 1–80 characters."
                }
                require(
                    field.options.map { it.trim().lowercase(Locale.ROOT) }.distinct().size ==
                        field.options.size
                ) {
                    "Status choices must be different."
                }
            } else require(field.options.isEmpty()) { "Only status fields have choices." }
        }
    }

    fun encode(): String {
        validate()
        val array = JSONArray()
        fields.forEach { field ->
            array.put(
                JSONObject()
                    .put("id", field.id)
                    .put("label", field.label.trim())
                    .put("type", field.type.name)
                    .put("options", JSONArray(field.options.map(String::trim)))
            )
        }
        return JSONObject().put("version", 1).put("fields", array).toString()
    }

    companion object {
        fun decode(encoded: String): TrackerFormat {
            require(encoded.length <= 100_000) { "Tracker format is too large." }
            val json = JSONObject(encoded)
            require(json.getInt("version") == 1) { "This tracker format requires a newer app." }
            val fields = json.getJSONArray("fields")
            require(fields.length() <= 20)
            return TrackerFormat(
                    (0 until fields.length()).map { index ->
                        val field = fields.getJSONObject(index)
                        val options = field.getJSONArray("options")
                        TrackerField(
                            field.getString("id"),
                            field.getString("label"),
                            TrackerFieldType.valueOf(field.getString("type")),
                            (0 until options.length()).map { options.getString(it) },
                        )
                    }
                )
                .also { it.validate() }
        }

        fun reading() =
            TrackerFormat(
                listOf(
                    TrackerField(label = "Thumbnail", type = TrackerFieldType.IMAGE),
                    TrackerField(label = "Chapter count", type = TrackerFieldType.NUMBER),
                    TrackerField(
                        label = "Status",
                        type = TrackerFieldType.STATUS,
                        options = listOf("Planned", "In progress", "Completed"),
                    ),
                )
            )

        fun checklist() =
            TrackerFormat(listOf(TrackerField(label = "Done", type = TrackerFieldType.CHECKBOX)))

        fun blank() = TrackerFormat(emptyList())
    }
}

object TrackerValues {
    fun encode(values: Map<String, String>): String {
        require(
            values.size <= 200 && values.all { it.key.length <= 100 && it.value.length <= 20_000 }
        ) {
            "Tracker values are too large."
        }
        return JSONObject()
            .also { json -> values.forEach { (key, value) -> json.put(key, value) } }
            .toString()
            .also { require(it.length <= 500_000) { "Tracker entry is too large." } }
    }

    fun decode(encoded: String): Map<String, String> {
        require(encoded.length <= 500_000) { "Tracker entry is too large." }
        val json = JSONObject(encoded)
        require(json.length() <= 200)
        return json
            .keys()
            .asSequence()
            .associateWith { key ->
                val value = json.get(key)
                require(value is String) { "Invalid tracker value." }
                value
            }
            .also { encode(it) }
    }
}

@Entity(tableName = "tracker_categories")
data class TrackerCategory(
    @PrimaryKey val id: String,
    val name: String,
    val format: String,
    val updated: Long,
)

@Entity(
    tableName = "tracker_entries",
    foreignKeys =
        [
            ForeignKey(
                entity = TrackerCategory::class,
                parentColumns = ["id"],
                childColumns = ["categoryId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["categoryId", "updated"])],
)
data class TrackerEntry(
    @PrimaryKey val id: String,
    val categoryId: String,
    val title: String,
    val values: String,
    val updated: Long,
)

@Dao
interface Trackers {
    @Query("SELECT * FROM tracker_categories ORDER BY name COLLATE NOCASE,id")
    fun categories(): Flow<List<TrackerCategory>>

    @Query("SELECT * FROM tracker_categories WHERE id=:id")
    fun category(id: String): Flow<TrackerCategory?>

    @Query("SELECT * FROM tracker_entries WHERE categoryId=:categoryId ORDER BY updated DESC,id")
    fun entries(categoryId: String): Flow<List<TrackerEntry>>

    @Query("SELECT * FROM tracker_categories WHERE id=:id")
    suspend fun findCategory(id: String): TrackerCategory?

    @Query(
        "SELECT * FROM tracker_categories WHERE name=:name COLLATE NOCASE AND id!=:exceptId LIMIT 1"
    )
    suspend fun categoryNamed(name: String, exceptId: String): TrackerCategory?

    @Query("SELECT * FROM tracker_entries WHERE id=:id")
    suspend fun findEntry(id: String): TrackerEntry?

    @Upsert suspend fun saveCategory(category: TrackerCategory)

    @Upsert suspend fun saveEntry(entry: TrackerEntry)

    @Query("DELETE FROM tracker_categories WHERE id=:id") suspend fun deleteCategory(id: String)

    @Query("DELETE FROM tracker_entries WHERE id=:id") suspend fun deleteEntry(id: String)
}

/** All tracker content lives in the existing SQLCipher database and encrypted backups. */
class TrackerRepository(private val store: VaultStore) {
    private suspend fun <T> write(block: suspend (Trackers) -> T): T {
        val version = store.version()
        store.checkVersion(version)
        return store.transaction {
            store.checkVersion(version)
            block(store.trackerDao).also { store.checkVersion(version) }
        }
    }

    suspend fun saveCategory(category: TrackerCategory) = write { dao ->
        require(category.id.isNotBlank() && category.id.length <= 100)
        require(category.name.isNotBlank() && category.name.trim().length <= 100) {
            "Category names must contain 1–100 characters."
        }
        require(dao.categoryNamed(category.name.trim(), category.id) == null) {
            "A tracker category with that name already exists."
        }
        val format = TrackerFormat.decode(category.format)
        dao.findCategory(category.id)?.let { previous ->
            val oldFields = TrackerFormat.decode(previous.format).fields.associateBy { it.id }
            format.fields.forEach { field ->
                require(oldFields[field.id]?.let { it.type == field.type } != false) {
                    "Add a new field to use a different field type."
                }
            }
        }
        // Updating a format never rewrites entries, including hidden values for removed fields.
        dao.saveCategory(
            category.copy(
                name = category.name.trim(),
                format = format.encode(),
                updated = System.currentTimeMillis(),
            )
        )
    }

    suspend fun saveEntry(entry: TrackerEntry) = write { dao -> saveEntry(dao, entry) }

    private suspend fun saveEntry(dao: Trackers, entry: TrackerEntry) {
        require(entry.id.isNotBlank() && entry.id.length <= 100)
        require(entry.title.isNotBlank() && entry.title.trim().length <= 255) {
            "Entry titles must contain 1–255 characters."
        }
        val category =
            requireNotNull(dao.findCategory(entry.categoryId)) {
                "Tracker category is unavailable."
            }
        val previous = dao.findEntry(entry.id)
        require(previous == null || previous.categoryId == entry.categoryId) {
            "An entry cannot change category."
        }
        val oldValues = previous?.let { TrackerValues.decode(it.values) }.orEmpty()
        val values = TrackerValues.decode(entry.values)
        val fields = TrackerFormat.decode(category.format).fields.associateBy { it.id }
        values.forEach { (id, value) ->
            // Legacy status values and values for removed fields are retained until explicitly
            // edited.
            if (oldValues[id] != value && value.isNotEmpty()) {
                val field =
                    requireNotNull(fields[id]) {
                        "This field was removed. Reopen the entry before editing."
                    }
                when (field.type) {
                    TrackerFieldType.NUMBER ->
                        require(value.toLongOrNull()?.let { it >= 0 } == true) {
                            "${field.label} must be a non-negative whole number."
                        }
                    TrackerFieldType.CHECKBOX ->
                        require(value == "true" || value == "false") { "Invalid checklist value." }
                    TrackerFieldType.STATUS ->
                        require(value in field.options) {
                            "Choose an available status for ${field.label}."
                        }
                    TrackerFieldType.IMAGE ->
                        require(store.dao.display(value)?.mime?.startsWith("image/") == true) {
                            "Choose an image stored in this vault."
                        }
                    TrackerFieldType.TEXT -> Unit
                }
            }
        }
        dao.saveEntry(
            entry.copy(
                title = entry.title.trim(),
                values = TrackerValues.encode(oldValues + values),
                updated = System.currentTimeMillis(),
            )
        )
    }

    suspend fun setCheckbox(entryId: String, fieldId: String, checked: Boolean) = write { dao ->
        val entry = requireNotNull(dao.findEntry(entryId)) { "Tracker entry is unavailable." }
        val category =
            requireNotNull(dao.findCategory(entry.categoryId)) {
                "Tracker category is unavailable."
            }
        require(
            TrackerFormat.decode(category.format).fields.any {
                it.id == fieldId && it.type == TrackerFieldType.CHECKBOX
            }
        ) {
            "This checklist field is unavailable."
        }
        saveEntry(
            dao,
            entry.copy(
                values =
                    TrackerValues.encode(
                        TrackerValues.decode(entry.values) + (fieldId to checked.toString())
                    )
            ),
        )
    }

    suspend fun deleteCategory(id: String) = write { it.deleteCategory(id) }

    suspend fun deleteEntry(id: String) = write { it.deleteEntry(id) }
}

internal object TrackerMigration : androidx.room3.migration.Migration(4, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS tracker_categories (id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,format TEXT NOT NULL,updated INTEGER NOT NULL)"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS tracker_entries (id TEXT NOT NULL PRIMARY KEY,categoryId TEXT NOT NULL,title TEXT NOT NULL,`values` TEXT NOT NULL,updated INTEGER NOT NULL,FOREIGN KEY(categoryId) REFERENCES tracker_categories(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracker_entries_categoryId_updated ON tracker_entries(categoryId,updated)"
        )
    }
}
