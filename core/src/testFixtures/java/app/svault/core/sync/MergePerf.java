package app.svault.core.sync;

import app.svault.core.storage.MutableClock;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.MergeWriter;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import app.svault.core.vault.VaultException;
import app.svault.core.vault.VaultSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AC-07-7：合併 2,000 筆條目的兩個版本要多久。JVM（{@code MergePerfTest}）與測試手機的儀器測試共用（不依賴測試框架）。
 *
 * <p>量的是「合併」本身：三份快照（{@code Vault.snapshot()}，每個附件都會解開算 SHA-256；基準不封存，T-29）＋{@link ThreeWayMerger#merge}
 * ＋{@link MergeWriter#apply}。不含解密與序列化（KDF，另外由解鎖的 2 秒目標管）。兩邊各改 100 筆、各新增 50 筆，
 * 遠端另外刪除 20 筆，沒有衝突。
 */
public final class MergePerf {

    /** 效能測試檔（testFixtures 資源，PerfVaultFixtureTest 產生）。 */
    public static final String FIXTURE = "perf-2000.kdbx";
    /** 測試檔的條目數。 */
    public static final int ENTRIES = 2000;
    /** 一次合併後「合併 n 筆」應該是：遠端改的 100＋新增 50＋刪除 20。 */
    public static final int EXPECTED_CHANGED = 170;

    private static volatile String lastPhases = "";

    private MergePerf() {
    }

    /** @return 最後一次的分段時間（快照、合併、套用），印在測試輸出裡 */
    public static String lastPhases() {
        return lastPhases;
    }

    /**
     * @param runs 次數
     * @return 每次的毫秒數（依執行順序）
     * @throws VaultException      測試檔打不開
     * @throws IllegalStateException 合併結果不對（有衝突或筆數不符）
     */
    public static long[] measure(int runs) throws VaultException {
        byte[] fixture = TestVaults.fixture(FIXTURE);
        VaultCodec codec = new VaultCodec();
        long[] millis = new long[runs];
        try (Vault key = codec.open(fixture, TestVaults.password())) {
            for (int r = 0; r < runs; r++) {
                millis[r] = once(codec, fixture, key);
            }
        }
        return millis;
    }

    /**
     * @param millis 每次的毫秒數
     * @return 中位數
     */
    public static long median(long[] millis) {
        long[] sorted = millis.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long once(VaultCodec codec, byte[] fixture, Vault key) throws VaultException {
        // 基準和 SyncService 一樣只為合併開啟、不封存密碼（T-29，D-101 修訂）
        try (Vault base = codec.openForMerge(fixture, key);
             Vault local = codec.openWithKeyOf(fixture, key);
             Vault remote = codec.openWithKeyOf(fixture, key)) {
            MutableClock clock = new MutableClock(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC);
            List<EntryView> all = new ArrayList<>(base.entries());
            VaultEditor mine = new VaultEditor(local, clock);
            VaultEditor theirs = new VaultEditor(remote, clock);
            for (int i = 0; i < 100; i++) {
                EntryView a = all.get(i);
                mine.updateEntry(a.uuid(), new EntryDraft(a.title(), a.username(), "本機新密碼-" + i, a.url(), a.notes()));
                EntryView b = all.get(1000 + i);
                theirs.updateEntry(b.uuid(), new EntryDraft(b.title(), b.username(), b.password(), b.url(), "遠端改的備註"));
            }
            for (int i = 0; i < 50; i++) {
                mine.addEntry(local.id(), new EntryDraft("本機新增 " + i, "u", "p", "", ""));
                theirs.addEntry(remote.id(), new EntryDraft("遠端新增 " + i, "u", "p", "", ""));
            }
            clock.advance(Duration.ofMinutes(1));
            for (int i = 0; i < 20; i++) {
                theirs.recycleEntry(all.get(1500 + i).uuid());
            }

            long start = System.nanoTime();
            VaultSnapshot b = base.snapshot();
            VaultSnapshot l = local.snapshot();
            VaultSnapshot rs = remote.snapshot();
            long snapshots = System.nanoTime();
            MergeResult result = ThreeWayMerger.merge(b, l, rs);
            long merged = System.nanoTime();
            MergeWriter.apply(remote, local, result.plan());
            long end = System.nanoTime();
            long elapsed = (end - start) / 1_000_000;
            lastPhases = "快照 " + (snapshots - start) / 1_000_000 + "、合併 " + (merged - snapshots) / 1_000_000
                    + "、套用 " + (end - merged) / 1_000_000 + " ms";

            if (result.hasConflicts() || result.changedEntries() != EXPECTED_CHANGED) {
                throw new IllegalStateException("合併結果不對：" + result);
            }
            return elapsed;
        }
    }
}
