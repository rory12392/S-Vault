package app.svault.android.platform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.svault.core.storage.VaultIoException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Android 檔案位置（AC-06-2、AC-06-4、AC-09-5 的覆寫部分）：用假的文件提供者模擬 Drive 會遇到的狀況。
 */
public class SafVaultLocationTest {

    private static final String URI = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dabc";
    private static final byte[] ORIGINAL = "原本的保管庫內容-較長的一份".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW = "新內容".getBytes(StandardCharsets.UTF_8);

    private final FakeDocuments docs = new FakeDocuments();

    private SafVaultLocation picked() throws VaultIoException {
        docs.pick(URI, "我的密碼.kdbx", ORIGINAL);
        return SafVaultLocation.adopt(docs, URI);
    }

    private static void assertReason(VaultIoException.Reason expected, ThrowingAction action) {
        try {
            action.run();
            fail("應該丟出 VaultIoException（" + expected + "）");
        } catch (VaultIoException e) {
            assertEquals(expected, e.reason());
            // 訊息只放檔名，不放 Uri（Uri 含帳號與文件 ID）
            assertFalse(e.getMessage(), e.getMessage().contains("content://"));
        }
    }

    private interface ThrowingAction {
        void run() throws VaultIoException;
    }

    @Test
    public void ac06_2_adoptTakesPersistedPermissionAndUsesUriAsId() throws Exception {
        SafVaultLocation location = picked();
        assertTrue("選檔後要取得長期權限", docs.isPersisted(URI));
        assertEquals(URI, location.persistentId());
        assertEquals("我的密碼.kdbx", location.displayName());
        assertFalse("SAF 不能原子取代", location.supportsAtomicReplace());
    }

    @Test
    public void ac06_2_reopenAfterRestartWithoutPickingAgain() throws Exception {
        picked();
        docs.restartApp();
        SafVaultLocation again = SafVaultLocation.reopen(docs, URI, "我的密碼.kdbx");
        assertTrue(again.exists());
        assertArrayEquals(ORIGINAL, again.readAll());
        again.writeAll(NEW);
        assertArrayEquals(NEW, docs.content(URI));
    }

    @Test
    public void ac06_2_adoptWithoutPersistableGrantIsAccessDenied() {
        // 選擇器沒有給可保留的權限（例如用 ACTION_GET_CONTENT 開的）
        assertReason(VaultIoException.Reason.ACCESS_DENIED, () -> SafVaultLocation.adopt(docs, URI));
    }

    @Test
    public void ac09_5_writeOverwritesWholeFile() throws Exception {
        SafVaultLocation location = picked();
        location.writeAll(NEW);
        // 新內容比原檔短：舊內容不能殘留在檔尾
        assertArrayEquals(NEW, docs.content(URI));
    }

    @Test
    public void ac06_4_revokedPermissionIsAccessDenied() throws Exception {
        SafVaultLocation location = picked();
        docs.revoke(URI);
        assertReason(VaultIoException.Reason.ACCESS_DENIED, location::readAll);
        assertReason(VaultIoException.Reason.ACCESS_DENIED, location::exists);
        assertReason(VaultIoException.Reason.ACCESS_DENIED, () -> location.writeAll(NEW));
        assertArrayEquals("權限被撤銷時不能動到原檔", ORIGINAL, docs.content(URI));
    }

    @Test
    public void ac06_4_deletedFileIsNotFound() throws Exception {
        SafVaultLocation location = picked();
        docs.delete(URI);
        assertFalse(location.exists());
        assertReason(VaultIoException.Reason.NOT_FOUND, location::readAll);
        assertReason(VaultIoException.Reason.NOT_FOUND, () -> location.writeAll(NEW));
    }

    @Test
    public void ac06_4_offlineWithoutCacheIsIo() throws Exception {
        SafVaultLocation location = picked();
        docs.offline = true;
        assertTrue("離線時文件仍查得到", location.exists());
        assertReason(VaultIoException.Reason.IO, location::readAll);
        assertReason(VaultIoException.Reason.IO, () -> location.writeAll(NEW));
        assertArrayEquals(ORIGINAL, docs.content(URI));
    }

    /** T-106（D-191）：Drive 移到垃圾桶後名稱仍查得到、內容讀不到；看得出在垃圾桶時算「找不到」，不是「離線」。 */
    @Test
    public void d191_trashedFileIsNotFound() throws Exception {
        SafVaultLocation location = picked();
        docs.trashed = true;
        assertTrue("垃圾桶裡的文件名稱仍查得到", location.exists());
        assertReason(VaultIoException.Reason.NOT_FOUND, location::readAll);
        assertReason(VaultIoException.Reason.NOT_FOUND, () -> location.writeAll(NEW));
    }

    @Test
    public void ac09_5_interruptedOverwriteIsReported() throws Exception {
        SafVaultLocation location = picked();
        docs.failWriteAfter = 3;
        assertReason(VaultIoException.Reason.IO, () -> location.writeAll(NEW));
    }

    @Test
    public void ac09_5_providerIgnoringTruncateIsDetectedByReadBack() throws Exception {
        SafVaultLocation location = picked();
        docs.ignoresTruncate = true;
        assertReason(VaultIoException.Reason.IO, () -> location.writeAll(NEW));
    }

    @Test
    public void ac09_5_lateTruncationSettlesWithinRetries() throws Exception {
        // Drive 實測（T-11）：寫入較短的內容後，第一次讀回仍有舊檔尾，稍後才是截斷後的內容
        SafVaultLocation location = picked();
        docs.lateTruncateReads = 2;
        location.writeAll(NEW);
        assertArrayEquals(NEW, docs.content(URI));
    }

    @Test
    public void ac09_5_tailThatNeverGoesAwayIsStillDetected() throws Exception {
        SafVaultLocation location = picked();
        docs.lateTruncateReads = 1000;
        assertReason(VaultIoException.Reason.IO, () -> location.writeAll(NEW));
    }

    @Test
    public void ac09_5_readBackMismatchIsDetected() throws Exception {
        SafVaultLocation location = picked();
        docs.staleRead = ORIGINAL;
        assertReason(VaultIoException.Reason.IO, () -> location.writeAll(NEW));
    }

    @Test
    public void forgetReleasesPersistedPermission() throws Exception {
        SafVaultLocation location = picked();
        location.forget();
        assertFalse(docs.isPersisted(URI));
    }

    @Test
    public void displayNameFallsBackWhenUnknown() {
        SafVaultLocation location = SafVaultLocation.reopen(docs, URI, null);
        assertFalse(location.displayName().isEmpty());
        assertFalse(location.displayName().contains("content://"));
    }
}
