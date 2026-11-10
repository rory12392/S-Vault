package app.svault.core.sync;

import app.svault.core.storage.MutableClock;
import app.svault.core.vault.MergeWriter;
import app.svault.core.vault.RestoreMarks;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultSnapshot;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一個資料驅動的合併案例（Architecture §4 的測試；AC-07-6）：從同一個基準出發，本機與遠端各自做一些修改，合併、
 * 套用到遠端、序列化、重新開啟，再檢查結果。
 *
 * <p>不依賴任何測試框架：{@link #run()} 回傳失敗訊息的清單（空的＝通過）。core 的 JUnit 6 測試與 android 的儀器測試
 * （JUnit 4）各自包一層，跑同一組 {@link MergeCases}。保管庫都用 {@link TestVaults#fastParams()} 在記憶體建立，
 * 或讀 testFixtures 的 KeePassXC 假資料檔。
 */
public final class MergeCase {

    /** 在一台「裝置」上做的修改。 */
    public interface Step {
        /**
         * @param device 這一邊的保管庫
         * @throws Exception 測試失敗
         */
        void run(MergeDevice device) throws Exception;
    }

    /** 檢查合併結果。 */
    public interface Check {
        /** @param outcome 合併結果與重新開啟後的內容 */
        void check(MergeOutcome outcome);
    }

    /** 衝突時的選擇（AC-08-2）。 */
    public interface Resolver {
        /**
         * @param result 有衝突的結果
         * @param ids    案例裡的名稱 → UUID
         * @return 每筆衝突的選擇
         */
        Map<UUID, Map<String, Side>> choose(MergeResult result, Map<String, UUID> ids);
    }

    private static final Step NOTHING = d -> { };
    private static final Instant START = Instant.parse("2026-10-20T01:00:00Z");

    private final String name;
    private String fixture;
    private Step before = NOTHING;
    private Step base = NOTHING;
    private Step local = NOTHING;
    private Step remote = NOTHING;
    private boolean remoteFromBefore;
    private boolean restored;
    private boolean withoutBase;
    private Resolver resolver;
    private Check check = o -> { };

    private MergeCase(String name) {
        this.name = name;
    }

    /**
     * @param name 案例名稱（測試名稱，標驗收條件編號）
     * @return 新案例（預設：S-Vault 建立的空保管庫，三邊都不做事）
     */
    public static MergeCase named(String name) {
        return new MergeCase(name);
    }

    /** @return 案例名稱 */
    public String name() {
        return name;
    }

    /** @param kdbx 用 testFixtures 的 KeePassXC 檔當起點（{@code TestVaults} 的常數） */
    public MergeCase fixture(String kdbx) {
        this.fixture = kdbx;
        return this;
    }

    /** @param step 基準之前的「舊版本」（{@link #remoteFromBefore()} 用來模擬還原舊備份） */
    public MergeCase before(Step step) {
        this.before = step;
        return this;
    }

    /** @param step 基準（上次同步）的內容 */
    public MergeCase base(Step step) {
        this.base = step;
        return this;
    }

    /** @param step 這台裝置在同步後的修改 */
    public MergeCase local(Step step) {
        this.local = step;
        return this;
    }

    /** @param step 另一台裝置在同步後的修改（時間比本機晚） */
    public MergeCase remote(Step step) {
        this.remote = step;
        return this;
    }

    /**
     * 遠端從 {@link #before} 的舊版本出發，原樣取代（看不到基準裡的東西）：Google Drive 恢復同步時以另一台離線期間的
     * 版本覆蓋雲端（AC-07-9）。
     */
    public MergeCase remoteFromBefore() {
        this.remoteFromBefore = true;
        return this;
    }

    /**
     * 另一台裝置從基準還原了 {@link #before} 的舊備份（Architecture §5 的備份還原）：和 {@link #remoteFromBefore()} 一樣
     * 從舊版本出發，但經過 S-Vault 的還原（{@code RestoreMarks}：被移除的記進刪除紀錄、被換掉的版本推入歷史）。
     */
    public MergeCase remoteRestored() {
        this.remoteFromBefore = true;
        this.restored = true;
        return this;
    }

    /** 合併時不給基準（第 6 點：這台裝置第一次同步、基準遺失）。 */
    public MergeCase withoutBase() {
        this.withoutBase = true;
        return this;
    }

    /** @param r 有衝突時的選擇；沒設定時有衝突就不套用 */
    public MergeCase resolve(Resolver r) {
        this.resolver = r;
        return this;
    }

    /** @param c 檢查 */
    public MergeCase expect(Check c) {
        this.check = c;
        return this;
    }

    /**
     * 執行案例。
     *
     * @return 失敗訊息；空清單表示通過
     */
    public List<String> run() {
        List<String> failures = new ArrayList<>();
        Map<String, UUID> ids = new HashMap<>();
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        VaultCodec codec = new VaultCodec(TestVaults.fastParams());
        List<Vault> open = new ArrayList<>();
        try {
            Vault first = fixture == null ? codec.create("合併測試", TestVaults.password())
                    : codec.open(TestVaults.fixture(fixture), TestVaults.password());
            open.add(first);
            before.run(new MergeDevice(first, clock, ids));
            byte[] oldBytes = codec.serialize(first);
            clock.advanceSeconds(60);
            base.run(new MergeDevice(first, clock, ids));
            byte[] baseBytes = codec.serialize(first);

            Vault l = track(open, codec.openWithKeyOf(baseBytes, first));
            clock.advanceSeconds(60);
            local.run(new MergeDevice(l, clock, ids));
            Vault r = track(open, codec.openWithKeyOf(remoteFromBefore ? oldBytes : baseBytes, first));
            clock.advanceSeconds(60);
            if (restored) {
                Vault current = track(open, codec.openWithKeyOf(baseBytes, first));
                RestoreMarks.apply(r, Collections.singletonList(current), clock.instant());
            }
            remote.run(new MergeDevice(r, clock, ids));
            clock.advanceSeconds(60);

            VaultSnapshot baseSnapshot = null;
            if (!withoutBase) {
                Vault b = track(open, codec.openWithKeyOf(baseBytes, first));
                baseSnapshot = b.snapshot();
            }
            MergeResult result = ThreeWayMerger.merge(baseSnapshot, l.snapshot(), r.snapshot());
            MergeResult conflicted = result;
            if (result.hasConflicts() && resolver != null) {
                result = result.resolve(resolver.choose(result, ids), clock.instant());
            }
            Vault merged = null;
            if (!result.hasConflicts()) {
                MergeWriter.apply(r, l, result.plan());
                merged = track(open, codec.openWithKeyOf(codec.serialize(r), first));
            }
            check.check(new MergeOutcome(conflicted, result, merged, l.snapshot(), ids, failures));
        } catch (Exception | AssertionError e) {
            failures.add("執行失敗：" + e);
        } finally {
            for (Vault v : open) {
                v.close();
            }
        }
        return failures;
    }

    private static Vault track(List<Vault> open, Vault v) {
        open.add(v);
        return v;
    }

    @Override
    public String toString() {
        return name;
    }
}
