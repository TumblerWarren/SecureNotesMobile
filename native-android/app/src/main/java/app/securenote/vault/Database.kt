package app.securenote.vault

import androidx.paging.PagingSource
import androidx.room3.*
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "items",
    indices = [Index(value = ["id"], unique = true), Index(value = ["pinned", "updated", "rowId"])],
)
data class VaultItem(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val updated: Long,
    val fileKey: ByteArray? = null,
    val body: String? = null,
    val pinned: Boolean = false,
    val searchText: String = "",
)

@Fts4(contentEntity = VaultItem::class)
@Entity(tableName = "item_search")
data class SearchEntry(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val name: String,
    val searchText: String,
)

@Entity(
    tableName = "blocks",
    foreignKeys =
        [
            ForeignKey(
                entity = VaultItem::class,
                parentColumns = ["id"],
                childColumns = ["noteId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["noteId", "position"])],
)
data class NoteBlock(
    @PrimaryKey val id: String,
    val noteId: String,
    val position: Long,
    val kind: String,
    val content: String,
    val searchText: String = "",
)

@Entity(
    tableName = "drawing_strokes",
    primaryKeys = ["blockId", "id"],
    foreignKeys =
        [
            ForeignKey(
                entity = NoteBlock::class,
                parentColumns = ["id"],
                childColumns = ["blockId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class DrawingStrokeRow(
    val blockId: String,
    val id: String,
    val digest: String,
    val content: String,
)

data class DrawingStrokeStamp(val id: String, val digest: String)

@Entity(
    tableName = "previews",
    foreignKeys =
        [
            ForeignKey(
                entity = VaultItem::class,
                parentColumns = ["id"],
                childColumns = ["id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class Preview(@PrimaryKey val id: String, val bytes: ByteArray)

data class ItemSummary(
    val rowId: Long,
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val updated: Long,
    val pinned: Boolean,
)

@Entity(
    tableName = "folders",
    foreignKeys =
        [
            ForeignKey(
                entity = VaultFolder::class,
                parentColumns = ["id"],
                childColumns = ["parentId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
    indices = [Index("parentId")],
)
data class VaultFolder(
    @PrimaryKey val id: String,
    val parentId: String?,
    val name: String,
    val normalizedName: String,
    val color: Int = 0,
)

@Entity(
    tableName = "folder_items",
    foreignKeys =
        [
            ForeignKey(
                entity = VaultItem::class,
                parentColumns = ["id"],
                childColumns = ["itemId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = VaultFolder::class,
                parentColumns = ["id"],
                childColumns = ["folderId"],
                onDelete = ForeignKey.RESTRICT,
            ),
        ],
    indices = [Index("folderId")],
)
data class FolderItem(@PrimaryKey val itemId: String, val folderId: String)

@Entity(tableName = "import_jobs")
data class FolderImportJob(
    @PrimaryKey val id: String,
    val source: String,
    val destination: String?,
    val completed: Int = 0,
    val failed: Int = 0,
)

@Entity(
    tableName = "import_entries",
    primaryKeys = ["jobId", "sourceId"],
    foreignKeys =
        [
            ForeignKey(
                entity = FolderImportJob::class,
                parentColumns = ["id"],
                childColumns = ["jobId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class FolderImportEntry(
    val jobId: String,
    val sourceId: String,
    val vaultId: String,
    val signature: String,
)

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface Items {
    @Query("SELECT * FROM folders WHERE parentId IS :parent ORDER BY normalizedName,id")
    fun folders(parent: String?): Flow<List<VaultFolder>>

    @Query("SELECT * FROM folders ORDER BY normalizedName,id")
    fun allFolders(): Flow<List<VaultFolder>>

    @Query("SELECT * FROM folders WHERE id=:id") suspend fun folder(id: String): VaultFolder?

    @Query(
        "SELECT * FROM folders WHERE parentId IS :parent AND normalizedName=:name AND id != :exceptId LIMIT 1"
    )
    suspend fun sibling(parent: String?, name: String, exceptId: String = ""): VaultFolder?

    @Upsert suspend fun putFolder(folder: VaultFolder)

    @Query("DELETE FROM folders WHERE id=:id") suspend fun deleteFolder(id: String)

    @Upsert suspend fun putMembership(value: FolderItem)

    @Query("DELETE FROM folder_items WHERE itemId=:id") suspend fun unfile(id: String)

    @Query("SELECT itemId FROM folder_items WHERE folderId=:folder")
    suspend fun members(folder: String): List<String>

    @Query("SELECT * FROM folders WHERE parentId=:parent")
    suspend fun childFolders(parent: String): List<VaultFolder>

    @Query("SELECT * FROM import_jobs") fun importJobs(): Flow<List<FolderImportJob>>

    @Query("SELECT * FROM import_jobs WHERE id=:id")
    suspend fun importJob(id: String): FolderImportJob?

    @Upsert suspend fun putImportJob(job: FolderImportJob)

    @Query("DELETE FROM import_jobs WHERE id=:id") suspend fun deleteImportJob(id: String)

    @Query("SELECT * FROM import_entries WHERE jobId=:job AND sourceId=:source")
    suspend fun importEntry(job: String, source: String): FolderImportEntry?

    @Upsert suspend fun putImportEntry(entry: FolderImportEntry)

    @Transaction
    suspend fun moveItems(ids: List<String>, destination: String?) {
        require(destination == null || folder(destination) != null) {
            "Destination folder is unavailable"
        }
        ids.forEach { id ->
            require(display(id) != null)
            if (destination == null) unfile(id) else putMembership(FolderItem(id, destination))
        }
    }

    @Transaction
    suspend fun saveFolder(value: VaultFolder) {
        require(value.name.isNotBlank() && value.name.length <= 100)
        var parent = value.parentId
        val visited = mutableSetOf(value.id)
        while (parent != null) {
            require(visited.add(parent)) { "A folder cannot contain itself" }
            parent = checkNotNull(folder(parent)) { "Parent folder unavailable" }.parentId
        }
        require(sibling(value.parentId, value.normalizedName, value.id) == null) {
            "A folder with that name already exists"
        }
        putFolder(value)
    }

    @Transaction
    suspend fun dissolveFolder(id: String) {
        val current = checkNotNull(folder(id))
        val children = childFolders(id)
        children.forEach {
            require(sibling(current.parentId, it.normalizedName, it.id) == null) {
                "Rename conflicting child folders first"
            }
        }
        children.forEach { putFolder(it.copy(parentId = current.parentId)) }
        moveItems(members(id), current.parentId)
        deleteFolder(id)
    }

    @Query(
        "SELECT rowId,id,name,mime,size,updated,pinned FROM items WHERE (:scoped=0 OR (:folder IS NULL AND id NOT IN (SELECT itemId FROM folder_items)) OR id IN (SELECT itemId FROM folder_items WHERE folderId=:folder)) AND (:category='all' OR (:category='notes' AND mime='application/x-securenote') OR (:category='documents' AND mime!='application/x-securenote' AND mime NOT LIKE 'image/%' AND mime NOT LIKE 'audio/%' AND mime NOT LIKE 'video/%') OR (:category='media' AND (mime LIKE 'image/%' OR mime LIKE 'audio/%' OR mime LIKE 'video/%')) OR (:category='images' AND mime LIKE 'image/%') OR (:category='videos' AND mime LIKE 'video/%') OR (:category='audio' AND mime LIKE 'audio/%') OR (:category='pdf' AND mime='application/pdf') OR (:category='epub' AND (mime='application/epub+zip' OR name LIKE '%.epub'))) ORDER BY pinned DESC,updated DESC,rowId DESC"
    )
    fun pages(
        category: String = "all",
        folder: String? = null,
        scoped: Boolean = false,
    ): PagingSource<Int, ItemSummary>

    @Query(
        "SELECT rowId,id,name,mime,size,updated,pinned FROM items WHERE (:scoped=0 OR (:folder IS NULL AND id NOT IN (SELECT itemId FROM folder_items)) OR id IN (SELECT itemId FROM folder_items WHERE folderId=:folder)) AND (:category='all' OR (:category='notes' AND mime='application/x-securenote') OR (:category='documents' AND mime!='application/x-securenote' AND mime NOT LIKE 'image/%' AND mime NOT LIKE 'audio/%' AND mime NOT LIKE 'video/%') OR (:category='media' AND (mime LIKE 'image/%' OR mime LIKE 'audio/%' OR mime LIKE 'video/%')) OR (:category='images' AND mime LIKE 'image/%') OR (:category='videos' AND mime LIKE 'video/%') OR (:category='audio' AND mime LIKE 'audio/%') OR (:category='pdf' AND mime='application/pdf') OR (:category='epub' AND (mime='application/epub+zip' OR name LIKE '%.epub'))) AND rowId IN (SELECT rowid FROM item_search WHERE item_search MATCH :query) ORDER BY pinned DESC,updated DESC,rowId DESC"
    )
    fun search(
        query: String,
        category: String = "all",
        folder: String? = null,
        scoped: Boolean = false,
    ): PagingSource<Int, ItemSummary>

    @Query("SELECT fileKey FROM items WHERE id=:id") suspend fun objectKey(id: String): ByteArray?

    @Query("SELECT * FROM items WHERE id=:id") suspend fun find(id: String): VaultItem?

    @Query(
        "SELECT rowId,id,name,mime,size,updated,NULL AS fileKey,NULL AS body,pinned,'' AS searchText FROM items WHERE id=:id"
    )
    suspend fun display(id: String): VaultItem?

    @Query(
        "SELECT rowId,id,name,mime,size,updated,NULL AS fileKey,NULL AS body,pinned,'' AS searchText FROM items WHERE id=:id"
    )
    fun observeDisplay(id: String): Flow<VaultItem?>

    @Query("SELECT body FROM items WHERE id=:id") suspend fun originalBody(id: String): String?

    @Query("SELECT substr(searchText,1,140) FROM items WHERE id=:id")
    suspend fun snippet(id: String): String?

    // Files do not use the legacy note body. Reuse it for small, encrypted reader metadata.
    @Query("SELECT body FROM items WHERE id=:id AND fileKey IS NOT NULL")
    suspend fun readingState(id: String): String?

    @Query("UPDATE items SET body=:state WHERE id=:id AND fileKey IS NOT NULL")
    suspend fun saveReadingState(id: String, state: String)

    @Insert suspend fun insert(item: VaultItem)

    @Insert suspend fun insertAll(items: List<VaultItem>)

    @Query("UPDATE items SET name=:name,body=:body,searchText=:body,updated=:updated WHERE id=:id")
    suspend fun saveNote(id: String, name: String, body: String, updated: Long)

    @Query("UPDATE items SET name=:name,updated=:time WHERE id=:id")
    suspend fun rename(id: String, name: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE items SET pinned=NOT pinned WHERE id=:id") suspend fun togglePin(id: String)

    @Query("DELETE FROM items WHERE id=:id") suspend fun delete(id: String)

    @Query("SELECT id FROM items WHERE fileKey IS NOT NULL") suspend fun objectIds(): List<String>

    @Query("SELECT count(*) FROM items WHERE id=:id") suspend fun contains(id: String): Int

    @Query(
        "SELECT rowId,id,name,mime,size,updated,pinned FROM items ORDER BY pinned DESC,updated DESC,rowId DESC LIMIT 50"
    )
    suspend fun firstPage(): List<ItemSummary>

    @Query("SELECT * FROM blocks WHERE noteId=:id ORDER BY position,id")
    fun observeBlocks(id: String): Flow<List<NoteBlock>>

    @Query("SELECT * FROM blocks WHERE noteId=:id ORDER BY position,id")
    suspend fun blocks(id: String): List<NoteBlock>

    @Upsert suspend fun putBlock(block: NoteBlock)

    @Query("UPDATE blocks SET position=:position WHERE id=:id")
    suspend fun setBlockPosition(id: String, position: Long)

    @Query("UPDATE blocks SET content=:content,searchText=:searchText WHERE id=:id")
    suspend fun updateBlockText(id: String, content: String, searchText: String)

    @Query("SELECT searchText FROM blocks WHERE id=:id")
    suspend fun blockSearchText(id: String): String?

    @Transaction
    suspend fun editBlock(block: NoteBlock) {
        val previous = blockSearchText(block.id) ?: return
        updateBlockText(block.id, block.content, block.searchText)
        if (previous != block.searchText) updateNoteIndex(block.noteId, System.currentTimeMillis())
        else touchNote(block.noteId, System.currentTimeMillis())
    }

    @Query("SELECT * FROM drawing_strokes WHERE blockId=:id")
    suspend fun drawingStrokes(id: String): List<DrawingStrokeRow>

    @Query("SELECT id,digest FROM drawing_strokes WHERE blockId=:id")
    suspend fun drawingStamps(id: String): List<DrawingStrokeStamp>

    @Upsert suspend fun putDrawingStrokes(rows: List<DrawingStrokeRow>)

    @Query("DELETE FROM drawing_strokes WHERE blockId=:blockId AND id IN (:ids)")
    suspend fun removeDrawingStrokes(blockId: String, ids: List<String>)

    @Query("UPDATE blocks SET content=:content WHERE id=:id")
    suspend fun updateDrawingManifest(id: String, content: String)

    @Query("UPDATE items SET updated=:time WHERE id=:id")
    suspend fun touchNote(id: String, time: Long)

    @Query("SELECT count(*) FROM blocks WHERE id=:id AND kind='drawing'")
    suspend fun blockExists(id: String): Int

    @Query("SELECT * FROM blocks WHERE id=:id") suspend fun findBlock(id: String): NoteBlock?

    @Transaction
    suspend fun drawingSnapshot(id: String): Pair<NoteBlock?, List<DrawingStrokeRow>> =
        findBlock(id) to drawingStrokes(id)

    @Transaction
    suspend fun saveDrawing(block: NoteBlock, manifest: String, rows: List<DrawingStrokeRow>) {
        if (blockExists(block.id) == 0) return
        val previous = drawingStamps(block.id).associate { it.id to it.digest }
        val retained = rows.map { it.id }.toHashSet()
        putDrawingStrokes(rows.filter { previous[it.id] != it.digest })
        previous.keys
            .filter { it !in retained }
            .chunked(200)
            .forEach { removeDrawingStrokes(block.id, it) }
        updateDrawingManifest(block.id, manifest)
        touchNote(block.noteId, System.currentTimeMillis())
    }

    @Query("DELETE FROM blocks WHERE id=:id") suspend fun deleteBlockRow(id: String)

    @Query(
        "UPDATE items SET searchText=coalesce((SELECT group_concat(searchText,' ') FROM blocks WHERE noteId=:id),''),updated=:time WHERE id=:id"
    )
    suspend fun updateNoteIndex(id: String, time: Long)

    @Transaction
    suspend fun saveBlock(block: NoteBlock) {
        putBlock(block)
        updateNoteIndex(block.noteId, System.currentTimeMillis())
    }

    @Transaction
    suspend fun deleteBlock(block: NoteBlock) {
        deleteBlockRow(block.id)
        updateNoteIndex(block.noteId, System.currentTimeMillis())
    }

    @Query("SELECT bytes FROM previews WHERE id=:id") suspend fun preview(id: String): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPreview(preview: Preview)

    @Query("SELECT count(*) FROM items") suspend fun count(): Long

    @Query("SELECT count(*) FROM item_search WHERE item_search MATCH :query")
    suspend fun searchCount(query: String): Long
}

@Database(
    entities =
        [
            VaultItem::class,
            SearchEntry::class,
            NoteBlock::class,
            Preview::class,
            DrawingStrokeRow::class,
            VaultFolder::class,
            FolderItem::class,
            FolderImportJob::class,
            FolderImportEntry::class,
        ],
    version = 4,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun items(): Items
}
