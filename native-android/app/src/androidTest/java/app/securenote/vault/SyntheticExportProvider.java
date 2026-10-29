package app.securenote.vault;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;

/** Test APK only. Owns only synthetic files below its private cache directory. */
public final class SyntheticExportProvider extends ContentProvider {
    private static class Entry {
        final String id, parent, name, mime; final File file;
        Entry(String id, String parent, String name, String mime, File file) { this.id=id; this.parent=parent; this.name=name; this.mime=mime; this.file=file; }
    }
    private final Map<String,Entry> entries = new LinkedHashMap<>();
    private final Set<String> reuseExisting = new HashSet<>();
    @Override public boolean onCreate() { return true; }
    private Entry find(String id) {
        Entry e=entries.get(id);
        if(e==null && id.startsWith("root-")) {
            UUID.fromString(id.substring(5));
            File file=new File(getContext().getCacheDir(), "synthetic-export-"+id); file.mkdirs();
            e=new Entry(id, null, "Synthetic destination", Document.MIME_TYPE_DIR, file); entries.put(id,e);
        }
        if(e==null) throw new IllegalArgumentException("Unknown synthetic document");
        return e;
    }
    private String id(Uri uri) { return DocumentsContract.getDocumentId(uri); }
    @Override public synchronized Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        List<String> parts=uri.getPathSegments();
        String[] columns=projection==null?new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE}:projection;
        MatrixCursor c=new MatrixCursor(columns);
        List<Entry> rows=new ArrayList<>();
        if("children".equals(uri.getLastPathSegment())) {
            String parent=parts.get(parts.size()-2); find(parent);
            for(Entry e: entries.values()) if(parent.equals(e.parent)) rows.add(e);
        } else rows.add(find(id(uri)));
        for(Entry e:rows) { Object[] row=new Object[columns.length]; for(int i=0;i<columns.length;i++) {
            switch(columns[i]) {
                case Document.COLUMN_DOCUMENT_ID: row[i]=e.id; break;
                case Document.COLUMN_DISPLAY_NAME: row[i]=e.name; break;
                case Document.COLUMN_MIME_TYPE: row[i]=e.mime; break;
                case Document.COLUMN_SIZE: row[i]=e.file.length(); break;
            }
        } c.addRow(row); } return c;
    }
    @Override public synchronized Bundle call(String method,String arg,Bundle extras) {
        if ("reuse-existing".equals(method)) { reuseExisting.add(arg); return new Bundle(); }
        if(extras==null) throw new IllegalArgumentException("Synthetic arguments missing");
        Uri uri=extras.getParcelable("uri");
        if("android:createDocument".equals(method)) {
            Entry parent=find(id(uri));
            String name=extras.getString(Document.COLUMN_DISPLAY_NAME), mime=extras.getString(Document.COLUMN_MIME_TYPE);
            if(name==null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals("..") || name.equals(".")) throw new IllegalArgumentException("Unsafe synthetic name");
            if(reuseExisting.contains(parent.id)) for(Entry e:entries.values()) if(parent.id.equals(e.parent)) {
                Bundle out=new Bundle();out.putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(uri,e.id));return out;
            }
            File file=new File(parent.file,name);
            try {
                if(file.exists()) {
                    // Deliberately hostile provider behaviour: return an existing URI. The app must refuse to overwrite it.
                    for(Entry e:entries.values()) if(parent.id.equals(e.parent)&&name.equals(e.name)) {
                        Bundle out=new Bundle();out.putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(uri,e.id));return out;
                    }
                    throw new IllegalStateException("Synthetic name collision");
                }
                if(Document.MIME_TYPE_DIR.equals(mime)) { if(!file.mkdir()) throw new IllegalStateException("Synthetic mkdir failed"); }
                else if(!file.createNewFile()) throw new IllegalStateException("Synthetic create failed");
                String child=UUID.randomUUID().toString(); entries.put(child,new Entry(child,parent.id,name,mime,file));
                Bundle out=new Bundle();out.putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(uri,child));return out;
            } catch(java.io.IOException e) { throw new IllegalStateException(e); }
        }
        if("android:deleteDocument".equals(method)) { delete(uri,null,null); return new Bundle(); }
        return super.call(method,arg,extras);
    }
    @Override public synchronized ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException { return ParcelFileDescriptor.open(find(id(uri)).file, ParcelFileDescriptor.parseMode(mode)); }
    @Override public String getType(Uri uri) { return find(id(uri)).mime; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public synchronized int delete(Uri uri,String selection,String[] args) {
        Entry e=find(id(uri)); reuseExisting.remove(e.id);
        List<Entry> deleting=new ArrayList<>(); collect(e,deleting);
        for(Entry child:deleting) { child.file.delete(); entries.remove(child.id); }
        return 1;
    }
    private void collect(Entry e,List<Entry> list) { for(Entry child:entries.values()) if(e.id.equals(child.parent)) collect(child,list); list.add(e); }
}
