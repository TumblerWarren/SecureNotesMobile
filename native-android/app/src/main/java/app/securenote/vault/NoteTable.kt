package app.securenote.vault

import org.json.JSONArray

/** Immutable rectangular tables: malformed saved data is never replaced with a blank fallback. */
internal data class NoteTable(val rows: List<List<String>>) {
    val columns: Int
        get() = rows.first().size

    fun cell(row: Int, column: Int, value: String) =
        copy(
            rows =
                rows.mapIndexed { i, cells ->
                    if (i == row) cells.mapIndexed { j, old -> if (j == column) value else old }
                    else cells
                }
        )

    fun addRow(): NoteTable {
        require(rows.size < MAX_ROWS)
        return copy(rows = rows + listOf(List(columns) { "" }))
    }

    fun addColumn(): NoteTable {
        require(columns < MAX_COLUMNS)
        return copy(rows = rows.map { it + "" })
    }

    fun encode(): String =
        JSONArray()
            .apply {
                rows.forEach { put(JSONArray(it)) }
            }
            .toString()

    fun searchText() = rows.joinToString("\n") { it.joinToString(" ") }

    companion object {
        const val MAX_ROWS = 20
        const val MAX_COLUMNS = 12

        fun decode(value: String): NoteTable {
            val json = JSONArray(value)
            require(json.length() in 1..MAX_ROWS)
            val parsed =
                List(json.length()) { i ->
                    val row = json.getJSONArray(i)
                    require(row.length() in 0..MAX_COLUMNS)
                    List(row.length()) { col ->
                        when (val cell = row.get(col)) {
                            org.json.JSONObject.NULL -> ""
                            is String,
                            is Number,
                            is Boolean -> cell.toString()
                            else -> error("Unsupported table cell")
                        }
                    }
                }
            val width = (parsed.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
            return NoteTable(parsed.map { it + List(width - it.size) { "" } })
        }
    }
}
