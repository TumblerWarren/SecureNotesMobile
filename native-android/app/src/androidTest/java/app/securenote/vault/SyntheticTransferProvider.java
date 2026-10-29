package app.securenote.vault;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Test APK only. Serves synthetic in-memory bytes; never reads or deletes device files. */
public final class SyntheticTransferProvider extends DocumentsProvider {
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS});
        c.addRow(new Object[]{"synthetic", "root", "Synthetic transfer fixtures", 0}); return c;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        if (deleted.contains(id)) throw new FileNotFoundException("Synthetic source deleted");
        String[] columns = projection == null ? new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS} : projection;
        MatrixCursor c = new MatrixCursor(columns); Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Document.COLUMN_DOCUMENT_ID: values[i] = id; break;
                case Document.COLUMN_DISPLAY_NAME: values[i] = "synthetic-original.txt"; break;
                case Document.COLUMN_MIME_TYPE: values[i] = "text/plain"; break;
                case Document.COLUMN_SIZE: values[i] = (long)payload(id).length; break;
                case Document.COLUMN_FLAGS: values[i] = Document.FLAG_SUPPORTS_DELETE; break;
            }
        }
        c.addRow(values); return c;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) { return new MatrixCursor(new String[]{Document.COLUMN_DOCUMENT_ID}); }
    private byte[] payload(String id) { return ("Synthetic private transfer fixture " + id).getBytes(StandardCharsets.UTF_8); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (deleted.contains(id) || !"r".equals(mode)) throw new FileNotFoundException("Synthetic source unavailable");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            byte[] bytes = payload(id);
            new Thread(() -> { try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { out.write(bytes); } catch (java.io.IOException ignored) {} }).start();
            return pipe[0];
        } catch (java.io.IOException e) { throw new FileNotFoundException("Synthetic pipe failed"); }
    }
    @Override public void deleteDocument(String id) { deleted.add(id); }
}
