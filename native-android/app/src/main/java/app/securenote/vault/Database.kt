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

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface Items {
    @Query(
        "SELECT rowId,id,name,mime,size,updated,pinned FROM items ORDER BY pinned DESC,updated DESC,rowId DESC"
    )
    fun pages(): PagingSource<Int, ItemSummary>

    @Query(
        "SELECT rowId,id,name,mime,size,updated,pinned FROM items WHERE rowId IN (SELECT rowid FROM item_search WHERE item_search MATCH :query) ORDER BY pinned DESC,updated DESC,rowId DESC"
    )
    fun search(query: String): PagingSource<Int, ItemSummary>

    @Query("SELECT fileKey FROM items WHERE id=:id") suspend fun objectKey(id: String): ByteArray?

    @Query("SELECT * FROM items WHERE id=:id") suspend fun find(id: String): VaultItem?

    @Query(
        "SELECT rowId,id,name,mime,size,updated,NULL AS fileKey,NULL AS body,pinned,'' AS searchText FROM items WHERE id=:id"
    )
    suspend fun display(id: String): VaultItem?

    @Query("SELECT body FROM items WHERE id=:id") suspend fun originalBody(id: String): String?

    @Insert suspend fun insert(item: VaultItem)

    @Insert suspend fun insertAll(items: List<VaultItem>)

    @Query("UPDATE items SET name=:name,body=:body,searchText=:body,updated=:updated WHERE id=:id")
    suspend fun saveNote(id: String, name: String, body: String, updated: Long)

    @Query("UPDATE items SET name=:name WHERE id=:id") suspend fun rename(id: String, name: String)

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
    entities = [VaultItem::class, SearchEntry::class, NoteBlock::class, Preview::class],
    version = 2,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun items(): Items
}
