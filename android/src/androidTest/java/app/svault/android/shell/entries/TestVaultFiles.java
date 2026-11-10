package app.svault.android.shell.entries;

import app.svault.android.SvaultApplication;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.VaultLocation;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 儀器測試用的保管庫：放在記憶體裡的 {@link MemoryLocation}（core 的 testFixtures；不支援原子取代，和 SAF 文件
 * 一樣），交給 App 的 {@code VaultSession} 解鎖（走正式的開啟與存檔流程）。結束時上鎖，並把測試留在 App 本機資料
 * （最近開啟清單、備份）裡的紀錄刪掉。
 */
final class TestVaultFiles implements AutoCloseable {

    private final SvaultApplication app;
    private final List<VaultLocation> opened = new ArrayList<>();
    private final List<String> vaultIds = new ArrayList<>();
    private MemoryLocation current;

    TestVaultFiles(SvaultApplication app) {
        this.app = app;
    }

    /**
     * 把保管庫序列化成一個位置並解鎖（上一個先上鎖）。
     *
     * @param vault    保管庫（序列化後關閉）
     * @param fileName 檔名（畫面上的保管庫名稱＝去掉 .kdbx）
     */
    void unlock(Vault vault, String fileName) throws Exception {
        app.vaultSession().lock();
        ScreenShots.instr().waitForIdleSync();
        byte[] data;
        try (Vault v = vault) {
            data = new VaultCodec(TestVaults.fastParams()).serialize(v);
            vaultIds.add(v.id().toString());
        }
        current = new MemoryLocation(fileName, data, false);
        opened.add(current);
        app.vaultSession().unlock(current, TestVaults.password());
        ScreenShots.instr().waitForIdleSync();
    }

    /**
     * 測試自己解鎖的其他位置（T-36 的假 SAF 文件）：結束時一樣清掉最近開啟清單與本機資料（基準、暫存、備份）。
     *
     * @param location 位置
     * @param vaultId  保管庫 ID
     */
    void track(VaultLocation location, UUID vaultId) {
        opened.add(location);
        vaultIds.add(vaultId.toString());
    }

    /** @return 目前解鎖的位置（{@code offline(true)} 模擬離線、存檔失敗） */
    MemoryLocation location() {
        return current;
    }

    /**
     * 模擬另一台裝置改了遠端檔案（T-14）：開啟目前的內容、修改、存回同一個位置（不經過 App）。
     *
     * @param change 對另一台裝置的保管庫做的修改
     */
    void editRemote(Consumer<Vault> change) throws Exception {
        VaultCodec codec = new VaultCodec(TestVaults.fastParams());
        try (Vault other = codec.open(current.content(), TestVaults.password())) {
            change.accept(other);
            current.replace(codec.serialize(other));
        }
    }

    /** 上鎖後重新解鎖同一個位置（下次解鎖時讀 {@code pending/}、和遠端合併）。 */
    void relock() throws Exception {
        app.vaultSession().lock();
        ScreenShots.instr().waitForIdleSync();
        app.vaultSession().unlock(current, TestVaults.password());
        ScreenShots.instr().waitForIdleSync();
    }

    /**
     * 卡住保管庫的背景執行緒（截「載入中」「儲存中」）；{@code countDown()} 放行。
     *
     * @return 放行用的 latch
     */
    CountDownLatch holdWorker() {
        CountDownLatch hold = new CountDownLatch(1);
        app.worker().execute(() -> {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return hold;
    }

    @Override
    public void close() {
        app.vaultSession().lock();
        ScreenShots.instr().waitForIdleSync();
        RecentStore recent = new RecentStore(app.localStore().recentFile());
        for (VaultLocation l : opened) {
            try {
                recent.remove(l.persistentId());
            } catch (IOException e) {
                // 清理失敗不影響結果
            }
        }
        for (String id : vaultIds) {
            deleteTree(app.localStore().backupsDir().resolve(id));
            // T-14 起解鎖會留下合併基準與本機暫存（T-27 起以保管庫 ID＋位置命名）
            for (VaultLocation l : opened) {
                deleteTree(app.localStore().baseFile(UUID.fromString(id), l.persistentId()));
                deleteTree(app.localStore().pendingFile(UUID.fromString(id), l.persistentId()));
                // T-18：衝突沒解決時上鎖留下的選擇
                deleteTree(app.localStore().conflictFile(UUID.fromString(id), l.persistentId()));
            }
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // 清理失敗不影響結果
        }
    }
}
