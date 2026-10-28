package app.securenote.vault

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal object FolderMigration : androidx.room3.migration.Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS folders (id TEXT NOT NULL PRIMARY KEY,parentId TEXT,name TEXT NOT NULL,normalizedName TEXT NOT NULL,color INTEGER NOT NULL,FOREIGN KEY(parentId) REFERENCES folders(id) ON UPDATE NO ACTION ON DELETE RESTRICT)"
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_folders_parentId ON folders(parentId)")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS folder_items (itemId TEXT NOT NULL PRIMARY KEY,folderId TEXT NOT NULL,FOREIGN KEY(itemId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(folderId) REFERENCES folders(id) ON UPDATE NO ACTION ON DELETE RESTRICT)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_folder_items_folderId ON folder_items(folderId)"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS import_jobs (id TEXT NOT NULL PRIMARY KEY,source TEXT NOT NULL,destination TEXT,completed INTEGER NOT NULL,failed INTEGER NOT NULL)"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS import_entries (jobId TEXT NOT NULL,sourceId TEXT NOT NULL,vaultId TEXT NOT NULL,signature TEXT NOT NULL,PRIMARY KEY(jobId,sourceId),FOREIGN KEY(jobId) REFERENCES import_jobs(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
    }
}
