package app.svault.android.platform;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 實測（T-106，D-191）：Drive 的檔案移到垃圾桶前後，SAF 給的資料有沒有差別，用來判斷 S-Vault 能不能看出「在垃圾桶中」。
 * 只在 {@code -e drivetrash <Uri>} 時執行；Uri 必須是 App 已有長期權限的 Drive 測試檔（S-TD3），不是真實保管庫。
 * 把查得到的每一欄、{@code findDocumentPath} 與能不能讀印在 logcat（tag {@code SVaultProbe}），檔案內容不印。
 */
@RunWith(AndroidJUnit4.class)
public class DriveTrashProbeTest {

    private static final String TAG = "SVaultProbe";

    @Test
    public void dumpDocumentState() throws Exception {
        String arg = InstrumentationRegistry.getArguments().getString("drivetrash");
        if (arg == null) {
            // 一般的 connectedDebugAndroidTest 不跑（AGP 會把 Assume 的略過算成失敗）
            return;
        }
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentResolver cr = context.getContentResolver();
        Uri uri = Uri.parse(arg);
        log("authority=" + uri.getAuthority() + " docId=" + DocumentsContract.getDocumentId(uri));
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c == null) {
                log("query=null");
            } else {
                log("rows=" + c.getCount());
                while (c.moveToNext()) {
                    for (int i = 0; i < c.getColumnCount(); i++) {
                        log("  " + c.getColumnName(i) + "=" + value(c, i));
                    }
                }
            }
        } catch (RuntimeException e) {
            log("query 失敗：" + e.getClass().getName() + " " + e.getMessage());
        }
        try {
            DocumentsContract.Path path = DocumentsContract.findDocumentPath(cr, uri);
            log("findDocumentPath=" + (path == null ? "null" : path.getRootId() + " " + path.getPath()));
        } catch (RuntimeException e) {
            log("findDocumentPath 失敗：" + e.getClass().getName() + " " + e.getMessage());
        }
        try (InputStream in = cr.openInputStream(uri)) {
            byte[] head = new byte[4];
            int n = in == null ? -1 : in.read(head);
            log("open 讀得到 " + n + " 位元組（只看長度）");
        } catch (Exception e) {
            log("open 失敗：" + e.getClass().getName() + " " + e.getMessage());
        }
    }

    private static String value(Cursor c, int i) {
        switch (c.getType(i)) {
            case Cursor.FIELD_TYPE_NULL:
                return "null";
            case Cursor.FIELD_TYPE_BLOB:
                return "<blob>";
            default:
                return c.getString(i);
        }
    }

    private static void log(String line) {
        Log.i(TAG, line);
    }
}
