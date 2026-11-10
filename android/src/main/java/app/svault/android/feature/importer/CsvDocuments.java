package app.svault.android.feature.importer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import app.svault.android.R;
import app.svault.core.feature.importer.CsvSource;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 系統檔案選擇器（{@code ACTION_OPEN_DOCUMENT}）選到的 CSV（T-62）。只用這次選檔給的暫時讀取權限，
 * 不 {@code takePersistableUriPermission}：匯入只讀一次，不需要長期權限。
 *
 * <p>Android 的文件沒有通用的檔案路徑。完成頁提示刪除時（AC-14-3）盡量給使用者找得到的位置：內部儲存空間的文件
 * （{@code com.android.externalstorage.documents}，文件 ID 是「{@code primary:Download/x.csv}」）換成
 * 「內部儲存空間/Download/x.csv」，下載項目提供者的 {@code raw:} 文件用它的路徑；其他提供者（雲端硬碟等）只有檔名。
 */
final class CsvDocuments implements CsvSource {

    private static final String EXTERNAL_STORAGE = "com.android.externalstorage.documents";
    private static final String DOWNLOADS = "com.android.providers.downloads.documents";
    private static final String PRIMARY = "primary:";
    private static final String RAW = "raw:";

    /** App 的 Context（不是頁面的：讀檔在背景，頁面可能已經重建）。 */
    private final Context app;
    private final ContentResolver resolver;
    private final Uri uri;
    private final String internalStorage;

    /**
     * @param context 任何屬於這個 App 的 Context（只保存 App 的 Context）
     * @param uri     選擇器給的文件
     */
    CsvDocuments(Context context, Uri uri) {
        this.app = context.getApplicationContext();
        this.resolver = app.getContentResolver();
        this.uri = uri;
        this.internalStorage = context.getString(R.string.imp_location_internal);
    }

    @Override
    public InputStream open() throws IOException {
        InputStream in = resolver.openInputStream(uri);
        if (in == null) {
            // 提供者當機時會回傳 null
            throw new FileNotFoundException("提供者沒有回傳內容");
        }
        return in;
    }

    @Override
    public String name() {
        try (Cursor c = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (RuntimeException e) {
            // 提供者不支援查詢或權限已收回：改用 Uri 的最後一段，讀檔時會再回報錯誤
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    @Override
    public String location() {
        String fallback = name();
        if (!DocumentsContract.isDocumentUri(app, uri)) {
            return fallback;
        }
        String id = DocumentsContract.getDocumentId(uri);
        if (EXTERNAL_STORAGE.equals(uri.getAuthority()) && id.startsWith(PRIMARY)) {
            return internalStorage + "/" + id.substring(PRIMARY.length());
        }
        if (DOWNLOADS.equals(uri.getAuthority()) && id.startsWith(RAW)) {
            return id.substring(RAW.length());
        }
        return fallback;
    }
}
