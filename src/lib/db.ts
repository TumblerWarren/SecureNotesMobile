import * as SQLite from 'expo-sqlite';
import * as FileSystem from 'expo-file-system/legacy';

let db: SQLite.SQLiteDatabase | null = null;
const DB_NAME = 'secure_notes_internal.db';

export async function initDB() {
    // Ensure SQLite directory exists
    const docDir = FileSystem.documentDirectory;
    if (!docDir) throw new Error('Document directory not available');

    const sqliteDir = docDir + 'SQLite';
    const dirInfo = await FileSystem.getInfoAsync(sqliteDir);
    if (!dirInfo.exists) {
        await FileSystem.makeDirectoryAsync(sqliteDir);
    }

    // Delete existing DB if any (Fresh Start)
    const path = sqliteDir + '/' + DB_NAME;
    const fileInfo = await FileSystem.getInfoAsync(path);
    if (fileInfo.exists) {
        try {
            if (db) {
                try {
                    await db.closeAsync();
                } catch (e) {
                    console.log('DB already closed or failed to close:', e);
                }
                db = null;
            }
            await FileSystem.deleteAsync(path, { idempotent: true });
            await FileSystem.deleteAsync(path + '-wal', { idempotent: true });
            await FileSystem.deleteAsync(path + '-shm', { idempotent: true });
        } catch (e) {
            console.warn('Failed to delete existing DB in initDB:', e);
        }
    }

    db = await SQLite.openDatabaseAsync(DB_NAME);

    // Create tables if not exist
    await db.execAsync(`
    CREATE TABLE IF NOT EXISTS notes (
      id TEXT PRIMARY KEY,
      title TEXT,
      content TEXT,
      updatedAt INTEGER,
      isPinned INTEGER DEFAULT 0,
      type TEXT DEFAULT 'text'
    );
  `);

    // Migration: Add columns if they don't exist
    await ensureSchema();
}

async function ensureSchema() {
    if (!db) return;
    try {
        await db.execAsync('ALTER TABLE notes ADD COLUMN isPinned INTEGER DEFAULT 0;');
    } catch (e) {
        // Column likely already exists
    }
    try {
        await db.execAsync("ALTER TABLE notes ADD COLUMN type TEXT DEFAULT 'text';");
    } catch (e) {
        // Column likely already exists
    }
}

// ... importDB, exportDB, runQuery, deleteNote, runCommand ...

export async function resetDB() {
    try {
        if (db) {
            try {
                await db.closeAsync();
            } catch (e) {
                console.log('DB already closed or failed to close:', e);
            }
            db = null;
        }
        const docDir = FileSystem.documentDirectory;
        if (docDir) {
            const sqliteDir = docDir + 'SQLite';
            const path = sqliteDir + '/' + DB_NAME;
            const info = await FileSystem.getInfoAsync(path);
            if (info.exists) {
                await FileSystem.deleteAsync(path, { idempotent: true });
                await FileSystem.deleteAsync(path + '-wal', { idempotent: true });
                await FileSystem.deleteAsync(path + '-shm', { idempotent: true });
            }
        }
    } catch (e) {
        console.warn('resetDB failed:', e);
    }
}

export async function importDB(data: Uint8Array) {
    // Write data to the internal SQLite file
    const docDir = FileSystem.documentDirectory;
    if (!docDir) throw new Error('Document directory not available');

    const sqliteDir = docDir + 'SQLite';
    const dirInfo = await FileSystem.getInfoAsync(sqliteDir);
    if (!dirInfo.exists) {
        await FileSystem.makeDirectoryAsync(sqliteDir);
    }

    const path = sqliteDir + '/' + DB_NAME;

    // Convert to base64
    let binary = '';
    const len = data.byteLength;
    for (let i = 0; i < len; i++) {
        binary += String.fromCharCode(data[i]);
    }
    const base64 = btoa(binary);

    await FileSystem.deleteAsync(path + '-wal', { idempotent: true });
    await FileSystem.deleteAsync(path + '-shm', { idempotent: true });

    await FileSystem.writeAsStringAsync(path, base64, {
        encoding: FileSystem.EncodingType.Base64
    });

    // Re-open DB
    if (db) {
        try {
            await db.closeAsync();
        } catch (e) {
            console.log('DB already closed or failed to close:', e);
        }
    }
    db = await SQLite.openDatabaseAsync(DB_NAME);

    // Ensure schema is up to date after import
    await ensureSchema();
}

export async function exportDB(): Promise<Uint8Array> {
    if (db) {
        try {
            // Force WAL checkpoint to ensure all data is in the main DB file
            await db.runAsync('PRAGMA wal_checkpoint(TRUNCATE);');
        } catch (e) {
            console.warn('Failed to checkpoint DB:', e);
        }
    }

    const docDir = FileSystem.documentDirectory;
    if (!docDir) throw new Error('Document directory not available');

    const sqliteDir = docDir + 'SQLite';
    const path = sqliteDir + '/' + DB_NAME;

    const content = await FileSystem.readAsStringAsync(path, {
        encoding: FileSystem.EncodingType.Base64
    });

    // No need to re-open as we didn't close it

    const binaryString = atob(content);
    const bytes = new Uint8Array(binaryString.length);
    for (let i = 0; i < binaryString.length; i++) {
        bytes[i] = binaryString.charCodeAt(i);
    }
    return bytes;
}

export async function runQuery(sql: string, params: any[] = []): Promise<any[]> {
    if (!db) throw new Error('DB not initialized');
    const result = await db.getAllAsync(sql, params);
    return result;
}

export async function deleteNote(id: string): Promise<void> {
    if (!db) throw new Error('DB not initialized');
    await db.runAsync('DELETE FROM notes WHERE id = ?', [id]);
}

export async function runCommand(sql: string, params: any[] = []): Promise<void> {
    if (!db) throw new Error('DB not initialized');
    await db.runAsync(sql, params);
}


