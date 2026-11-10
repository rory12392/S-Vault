package app.svault.android.platform;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * 儀器測試用的文件提供者：{@code content://app.svault.android.test.documents/<檔名>} 對應測試 APK 快取資料夾裡的檔案。
 * 用 {@link ParcelFileDescriptor#parseMode} 處理模式，和系統的檔案型提供者一樣支援 {@code "wt"} 截斷。
 */
public class TestDocumentProvider extends ContentProvider {

    static final String AUTHORITY = "app.svault.android.test.documents";

    static Uri uri(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + name);
    }

    private File file(Uri uri) {
        return new File(getContext().getCacheDir(), "docs/" + uri.getLastPathSegment());
    }

    @Override
    public boolean onCreate() {
        return new File(getContext().getCacheDir(), "docs").mkdirs() || true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME});
        if (file(uri).isFile()) {
            c.addRow(new Object[] {uri.getLastPathSegment()});
        }
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        // 測試在 App 的行程，碰不到測試 APK 的資料夾：準備檔案也經過提供者（?create=true）
        if (!f.isFile() && !uri.getBooleanQueryParameter("create", false)) {
            throw new FileNotFoundException(uri.getLastPathSegment());
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return file(uri).delete() ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
