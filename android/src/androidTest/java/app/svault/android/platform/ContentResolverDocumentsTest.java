package app.svault.android.platform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.svault.core.storage.VaultIoException;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 在手機上確認 {@link ContentResolverDocuments} 對真的 ContentProvider 的行為（AC-06-2、AC-06-4、AC-09-5）：
 * {@code "wt"} 會截斷、刪除的文件查不到、沒有經過檔案選擇器的 Uri 拿不到長期權限。
 * Drive 的實際行為（離線、撤銷權限）另在 M-01 整合以 S-TD3 的測試檔人手驗證。
 */
@RunWith(AndroidJUnit4.class)
public class ContentResolverDocumentsTest {

    private static final String NAME = "t06-device.kdbx";
    private final ContentResolver resolver = ApplicationProvider.getApplicationContext().getContentResolver();
    private final ContentResolverDocuments docs = new ContentResolverDocuments(resolver);
    private final String uri = TestDocumentProvider.uri(NAME).toString();

    @Before
    public void seed() throws Exception {
        Uri create = TestDocumentProvider.uri(NAME).buildUpon().appendQueryParameter("create", "true").build();
        try (OutputStream out = resolver.openOutputStream(create, "wt")) {
            out.write("原本比較長的測試內容-0123456789".getBytes(StandardCharsets.UTF_8));
        }
    }

    @After
    public void cleanUp() {
        resolver.delete(TestDocumentProvider.uri(NAME), null, null);
    }

    @Test
    public void ac09_5_overwriteTruncates() throws Exception {
        byte[] shorter = "短".getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = docs.openOverwrite(uri)) {
            out.write(shorter);
        }
        assertArrayEquals(shorter, readAll());
    }

    @Test
    public void ac06_4_deletedDocumentIsNotFound() throws Exception {
        assertEquals(NAME, docs.queryDisplayName(uri));
        resolver.delete(TestDocumentProvider.uri(NAME), null, null);
        assertNull(docs.queryDisplayName(uri));
        try {
            docs.openRead(uri).close();
            fail("刪除後應該打不開");
        } catch (FileNotFoundException expected) {
            // 預期
        }
    }

    @Test
    public void ac06_2_uriNotFromPickerHasNoPersistedPermission() {
        assertFalse(docs.hasPersistedPermission(uri));
        try {
            SafVaultLocation.adopt(docs, uri);
            fail("沒有經過檔案選擇器的 Uri 不該拿到長期權限");
        } catch (VaultIoException e) {
            assertEquals(VaultIoException.Reason.ACCESS_DENIED, e.reason());
        }
    }

    private byte[] readAll() throws Exception {
        try (java.io.InputStream in = docs.openRead(uri)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }
}
