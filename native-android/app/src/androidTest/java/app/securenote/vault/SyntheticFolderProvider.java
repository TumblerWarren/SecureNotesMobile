package app.securenote.vault;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Test APK only, deliberately independent of the target APK's Kotlin runtime. */
public final class SyntheticFolderProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        List<String> parts=uri.getPathSegments();
        String id=parts.get(parts.size()-1);
        String[] ids;
        if ("children".equals(id)) {
            String parent=parts.get(parts.size()-2);
            ids="root".equals(parent)?new String[]{"photos","broken.pdf"}:"photos".equals(parent)?new String[]{"photo.jpg","clip.mp4"}:new String[0];
        } else ids=new String[]{id};
        String[] columns=projection!=null?projection:new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED};
        MatrixCursor cursor=new MatrixCursor(columns);
        for(String item:ids){ Object[] row=new Object[columns.length]; for(int i=0;i<columns.length;i++){
            String col=columns[i];
            if(Document.COLUMN_DOCUMENT_ID.equals(col)||Document.COLUMN_DISPLAY_NAME.equals(col))row[i]=item;
            else if(Document.COLUMN_MIME_TYPE.equals(col))row[i]=Arrays.asList("root","photos").contains(item)?Document.MIME_TYPE_DIR:item.endsWith("jpg")?"image/jpeg":item.endsWith("mp4")?"video/mp4":"application/pdf";
            else if(Document.COLUMN_SIZE.equals(col))row[i]=(long)payload(item).length;
            else if(Document.COLUMN_LAST_MODIFIED.equals(col))row[i]=1L;
            else if(Document.COLUMN_FLAGS.equals(col))row[i]=0;
        } cursor.addRow(row); }
        return cursor;
    }
    private static byte[] payload(String id){return ("Synthetic encrypted "+id).getBytes(StandardCharsets.UTF_8);}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        String id=uri.getLastPathSegment();
        if("broken.pdf".equals(id)||!"r".equals(mode))throw new FileNotFoundException("Synthetic unavailable source");
        try { ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            new Thread(()->{try(ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){out.write(payload(id));}catch(IOException ignored){}}).start();
            return pipe[0];
        }catch(IOException e){throw new FileNotFoundException("Synthetic pipe unavailable");}
    }
    @Override public String getType(Uri uri){return "application/octet-stream";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
