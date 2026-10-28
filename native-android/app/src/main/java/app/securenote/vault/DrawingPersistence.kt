package app.securenote.vault

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Encoded stroke payloads are reused across saves; this encoder is confined to the save gate. */
internal class DrawingEncoder {
    private data class Cached(val stroke: NoteStroke, val row: DrawingStrokeRow)

    private var cache = emptyMap<String, Cached>()

    fun prepare(
        blockId: String,
        strokes: List<NoteStroke>,
        aspect: Float,
        paper: String,
    ): Pair<String, List<DrawingStrokeRow>> {
        require(
            strokes.size <= NoteDrawing.MAX_POINTS &&
                strokes.map { it.id }.toSet().size == strokes.size
        )
        require(
            strokes.sumOf { it.points.size } <= NoteDrawing.MAX_POINTS &&
                strokes.all { it.points.size in 1..NoteDrawing.MAX_STROKE_POINTS }
        )
        require(
            aspect.isFinite() &&
                aspect in 0.25f..4f &&
                paper in setOf("Blank", "Lined", "Grid", "Dots")
        )
        val next = LinkedHashMap<String, Cached>()
        strokes.forEach { stroke ->
            require(stroke.id.length in 1..64)
            val previous = cache[stroke.id]
            next[stroke.id] =
                if (
                    previous != null &&
                        previous.stroke === stroke &&
                        previous.row.blockId == blockId
                )
                    previous
                else {
                    val payload = NoteDrawing.encode(listOf(stroke), 1f, "Blank")
                    val digest =
                        MessageDigest.getInstance("SHA-256")
                            .digest(payload.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                    Cached(stroke, DrawingStrokeRow(blockId, stroke.id, digest, payload))
                }
        }
        cache = next
        // IDs alone do not change when an existing stroke is recolored. Include a
        // deterministic content revision so observers invalidate hydrated payloads too.
        val revision = MessageDigest.getInstance("SHA-256")
        next.values.forEach {
            revision.update(it.row.id.toByteArray())
            revision.update(0.toByte())
            revision.update(it.row.digest.toByteArray())
        }
        val fingerprint = revision.digest().joinToString("") { "%02x".format(it) }
        val manifest =
            JSONObject()
                .put("version", 2)
                .put("revision", fingerprint)
                .put("aspect", aspect.toDouble())
                .put("paper", paper)
                .put("strokeIds", JSONArray(strokes.map { it.id }))
                .toString()
        return manifest to next.values.map { it.row }
    }
}

internal object DrawingPersistence {
    fun isManifest(content: String): Boolean =
        content.trimStart().startsWith("{") &&
            runCatching { JSONObject(content).optInt("version") == 2 }.getOrDefault(false)

    suspend fun hydrate(dao: Items, block: NoteBlock): NoteBlock? {
        if (block.kind != "drawing" || !isManifest(block.content)) return block
        // Read the current manifest and payloads in the same database snapshot. A newer
        // save may have deleted strokes since the observable block query first emitted.
        val (current, payloads) = dao.drawingSnapshot(block.id)
        if (current == null) return null
        if (!isManifest(current.content)) return current
        val manifest = JSONObject(current.content)
        val aspect = manifest.getDouble("aspect").toFloat()
        val paper = manifest.getString("paper")
        require(
            aspect.isFinite() &&
                aspect in 0.25f..4f &&
                paper in setOf("Blank", "Lined", "Grid", "Dots")
        )
        val ids = manifest.getJSONArray("strokeIds")
        require(ids.length() <= NoteDrawing.MAX_POINTS)
        val rows = payloads.associateBy { it.id }
        val seen = hashSetOf<String>()
        var count = 0
        val strokes =
            List(ids.length()) { i ->
                val id = ids.getString(i)
                require(seen.add(id))
                val row = checkNotNull(rows[id]) { "Drawing stroke is missing" }
                NoteDrawing.decode(row.content).single().also {
                    require(it.id == id)
                    count += it.points.size
                    require(count <= NoteDrawing.MAX_POINTS)
                }
            }
        return current.copy(
            content =
                NoteDrawing.encode(
                    strokes,
                    manifest.getDouble("aspect").toFloat(),
                    manifest.getString("paper"),
                )
        )
    }
}
