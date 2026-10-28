package app.securenote.vault

import androidx.room3.Room
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FolderMigrationTest {
    @Test
    fun encryptedVersionThreeDatabaseMigratesWithoutLosingNotes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "migration-${UUID.randomUUID()}.db")
        val key = ByteArray(32) { (it + 1).toByte() }
        System.loadLibrary("sqlcipher")
        try {
            val schema =
                JSONObject(
                        instrumentation.context.assets
                            .open("app.securenote.vault.VaultDatabase/3.json")
                            .bufferedReader()
                            .use { it.readText() }
                    )
                    .getJSONObject("database")
            SQLCipherDriver(key, null, null).open(file.path).use { connection ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun sql(value: String) =
                        value.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                    connection.execSQL(sql(entity.getString("createSql")))
                    val indexes = entity.optJSONArray("indices")
                    if (indexes != null)
                        for (j in 0 until indexes.length()) connection.execSQL(
                            sql(indexes.getJSONObject(j).getString("createSql"))
                        )
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) connection.execSQL(setup.getString(i))
                connection.execSQL(
                    "INSERT INTO items(rowId,id,name,mime,size,updated,fileKey,body,pinned,searchText) VALUES(1,'old-note','Existing note','application/x-securenote',0,1,NULL,'Original body',0,'Original body')"
                )
                connection.execSQL("PRAGMA user_version=3")
            }
            val db =
                Room.databaseBuilder<VaultDatabase>(context, file.path)
                    .setDriver(SQLCipherDriver(key, null, null))
                    .addMigrations(FolderMigration)
                    .build()
            try {
                assertEquals("Original body", db.items().originalBody("old-note"))
                db.items().saveFolder(VaultFolder("folder", null, "Preserved", "preserved"))
                db.items().moveItems(listOf("old-note"), "folder")
                assertEquals(listOf("old-note"), db.items().members("folder"))
            } finally {
                db.close()
            }
        } finally {
            key.fill(0)
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }
}
