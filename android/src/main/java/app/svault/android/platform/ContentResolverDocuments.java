package app.svault.android.platform;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * {@link DocumentAccess} 的正式實作：透過 {@link ContentResolver} 存取檔案選擇器給的 Uri（AC-06-2）。
 */
public final class ContentResolverDocuments implements DocumentAccess {

    private static final int READ_WRITE =
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    /** Google 雲端硬碟的文件提供者。 */
    private static final String DRIVE = "com.google.android.apps.docs.storage";
    /** Drive 的文件移到垃圾桶後拿掉的兩個旗標（T-106 實測：455 → 7）。 */
    private static final int MOVE_OR_RENAME =
            DocumentsContract.Document.FLAG_SUPPORTS_MOVE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME;

    private final ContentResolver resolver;

    /**
     * @param resolver 通常是 {@code Application.getContentResolver()}
     */
    public ContentResolverDocuments(ContentResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public String queryDisplayName(String uri) throws IOException {
        try (Cursor c = resolver.query(Uri.parse(uri), new String[] {OpenableColumns.DISPLAY_NAME}, null, null,
                null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            String name = c.isNull(0) ? null : c.getString(0);
            // 文件存在但提供者沒給名稱：仍算存在
            return name == null ? "" : name;
        } catch (IllegalArgumentException e) {
            // 部分提供者對已刪除的文件丟 IllegalArgumentException（FileNotFound 包在裡面）而不是回傳空結果
            if (e.getCause() instanceof FileNotFoundException) {
                return null;
            }
            throw new IOException(e.getClass().getSimpleName());
        }
    }

    @Override
    public boolean looksTrashed(String uri) {
        Uri target = Uri.parse(uri);
        if (!DRIVE.equals(target.getAuthority())) {
            return false;
        }
        try (Cursor c = resolver.query(target, new String[] {DocumentsContract.Document.COLUMN_FLAGS}, null, null,
                null)) {
            if (c == null || !c.moveToFirst() || c.isNull(0)) {
                return false;
            }
            int flags = c.getInt(0);
            // 垃圾桶裡的仍可寫（實測 7）；唯讀的共用檔本來就不能寫、也不能移動或改名，不算（Codex M-04 第二批審查 3）
            return (flags & MOVE_OR_RENAME) == 0 && (flags & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public InputStream openRead(String uri) throws IOException {
        InputStream in = resolver.openInputStream(Uri.parse(uri));
        if (in == null) {
            throw new FileNotFoundException("提供者沒有回傳內容");
        }
        return in;
    }

    @Override
    public OutputStream openOverwrite(String uri) throws IOException {
        // "wt"：寫入並截斷。只用 "w" 時部分提供者不截斷，新內容較短就會留下舊的檔尾（SafVaultLocation 讀回比對會抓到）
        OutputStream out = resolver.openOutputStream(Uri.parse(uri), "wt");
        if (out == null) {
            throw new FileNotFoundException("提供者沒有回傳寫入串流");
        }
        return out;
    }

    @Override
    public boolean hasPersistedPermission(String uri) {
        Uri target = Uri.parse(uri);
        for (UriPermission p : resolver.getPersistedUriPermissions()) {
            if (p.getUri().equals(target) && p.isReadPermission() && p.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void takePersistedPermission(String uri) {
        resolver.takePersistableUriPermission(Uri.parse(uri), READ_WRITE);
    }

    @Override
    public void releasePersistedPermission(String uri) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(uri), READ_WRITE);
        } catch (SecurityException e) {
            // 已經沒有權限了：放棄權限的目的已達成
        }
    }

    @Override
    public boolean deleteDocument(String uri) {
        try {
            return DocumentsContract.deleteDocument(resolver, Uri.parse(uri));
        } catch (FileNotFoundException | SecurityException | UnsupportedOperationException
                | IllegalArgumentException e) {
            // 只是收尾的清理：刪不掉就留著（使用者看得到檔名，可以自己刪）
            return false;
        }
    }
}
