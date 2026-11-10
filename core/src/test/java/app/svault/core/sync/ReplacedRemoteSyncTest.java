package app.svault.core.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.BackupStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC-07-9（T-27）：遠端被不含這台修改的版本整份取代時不遺失資料，還原舊備份照常生效；保管庫 ID 相同的兩個檔案各自有
 * 自己的 {@code base/}、{@code pending/}。以 {@link SyncDevice} 走完整的解鎖、更新與存檔流程。
 */
class ReplacedRemoteSyncTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T07:00:00Z"), ZoneOffset.UTC);
    private MemoryLocation remote;
    private SyncDevice pc;
    private SyncDevice phone;

    @BeforeEach
    void setUp() throws Exception {
        remote = new MemoryLocation("content://drive/t17-衝突.kdbx", TestVaults.newVaultBytes("t17"));
        pc = new SyncDevice(tmp.resolve("pc"), codec, remote, clock);
        phone = new SyncDevice(tmp.resolve("phone"), codec, remote, clock);
        pc.unlock();
        pc.add("共同", "p0");
        phone.unlock();
    }

    @AfterEach
    void tearDown() {
        pc.session.lock();
        phone.session.lock();
    }

    @Test
    @DisplayName("AC-07-9：PC 暫停同步時新增、手機新增並上傳，PC 恢復同步以自己的版本取代雲端 → 手機重新解鎖後兩筆都在並寫回")
    void phoneKeepsItsEntryAfterPcReplacesRemote() throws Exception {
        byte[] pcOffline = remote.content();
        phone.add("手機新增", "m1");
        phone.session.lock();
        // PC 離線期間在自己那份上新增，恢復同步時 Drive 以它整份取代雲端（T-17 實測）
        remote.replace(editOffline(pcOffline, "PC 新增"));

        phone.unlock();
        SyncReport report = phone.session.refresh();

        assertEquals(SyncReport.Status.MERGED, report.status());
        assertEquals(1, report.noticeCounts().get(MergeNotice.Kind.REMOTE_REPLACED));
        assertEquals(List.of("PC 新增", "共同", "手機新增"), phone.titles());
        assertEquals(List.of("PC 新增", "共同", "手機新增"), titlesOf(remote.content()), "合併結果寫回雲端");
        assertFalse(phone.session.hasUnsavedChanges());

        SyncReport again = phone.session.refresh();
        assertEquals(SyncReport.Status.UP_TO_DATE, again.status(), "寫回後的雲端就是新的基準");
    }

    @Test
    @DisplayName("AC-07-9：被取代後 PC 讀到手機寫回的版本，照常三方合併（不再通知），兩台內容相同")
    void pcMergesNormallyAfterPhoneRepairs() throws Exception {
        byte[] pcOffline = remote.content();
        phone.add("手機新增", "m1");
        byte[] replaced = editOffline(pcOffline, "PC 新增");
        remote.replace(replaced);
        phone.session.refresh();

        // PC 的基準就是它恢復同步時上傳的那一份
        Path pcBase = pc.baseFile();
        pc.session.lock();
        Files.write(pcBase, replaced);
        pc.unlock();
        SyncReport report = pc.session.refresh();

        assertEquals(SyncReport.Status.MERGED, report.status());
        assertTrue(report.notices().isEmpty(), "手機寫回的版本是從 PC 的版本延伸出來的");
        assertEquals(phone.titles(), pc.titles());
    }

    @Test
    @DisplayName("AC-07-9：另一台還原舊備份造成的刪除，這台照常跟著刪除（和整份取代分得開）")
    void restoreDeletionsStillApply() throws Exception {
        pc.add("之後新增", "x1");
        phone.session.refresh();
        assertEquals(List.of("之後新增", "共同"), phone.titles());
        BackupStore.Backup beforeX = pc.saver.listBackups(pc.session.vault().id()).get(0);

        pc.session.restore(beforeX);
        SyncReport report = phone.session.refresh();

        assertEquals(List.of("共同"), phone.titles());
        assertTrue(report.notices().isEmpty(), "不是整份取代");
    }

    @Test
    @DisplayName("保管庫 ID 相同的兩個檔案（複製出來的）各自有自己的 base/：開另一個檔案不會用到前一個的基準")
    void copiedVaultHasItsOwnBase() throws Exception {
        MemoryLocation copy = new MemoryLocation("content://drive/t17-離線.kdbx", remote.content());
        phone.add("只在原檔", "o1");
        phone.session.lock();

        phone.session.unlock(copy, TestVaults.password());
        SyncReport report = phone.session.refresh();

        assertEquals(SyncReport.Status.UP_TO_DATE, report.status(), "複本的基準是它自己");
        Path originalBase = phone.store.baseFile(phone.session.vault().id(), remote.persistentId());
        Path copyBase = phone.store.baseFile(phone.session.vault().id(), copy.persistentId());
        assertNotEquals(originalBase, copyBase);
        assertArrayEquals(copy.content(), Files.readAllBytes(copyBase));
        assertEquals(List.of("共同", "只在原檔"), titlesOf(Files.readAllBytes(originalBase)));
        assertEquals(List.of("共同"), phone.titles());
        phone.session.lock();
    }

    // 另一台在自己那份上離線修改（不經過這台的基準）
    private byte[] editOffline(byte[] bytes, String title) throws Exception {
        try (Vault v = codec.open(bytes, TestVaults.password())) {
            clock.advanceSeconds(60);
            new VaultEditor(v, clock).addEntry(v.id(), new EntryDraft(title, "pc", "c1", "", ""));
            return codec.serialize(v);
        }
    }

    private List<String> titlesOf(byte[] bytes) throws Exception {
        try (Vault v = codec.open(bytes, TestVaults.password())) {
            List<String> out = new ArrayList<>();
            for (EntryView e : v.entries()) {
                out.add(e.title());
            }
            out.sort(null);
            return out;
        }
    }
}
