package app.svault.desktop.shell;

import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModuleClipboard;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ModuleVaultWriter;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.ScopedPreferences;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.ModuleHost;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 探索 desktop 的功能模組（{@code META-INF/services/app.svault.desktop.shell.ext.DesktopFeature}）。
 */
public final class DesktopModules {

    private static final Logger LOG = Logger.getLogger(DesktopModules.class.getName());

    private DesktopModules() {
    }

    /**
     * 只探索、不呼叫 {@link DesktopFeature#attach}（測試、展示頁）。
     *
     * @param settings 啟用狀態的來源
     * @param listener 模組出錯時的通知對象（外殼用來顯示錯誤狀態）
     * @return 探索結果；外殼之後呼叫擴充內容時沿用 {@link ModuleRegistry#guard()}
     */
    public static ModuleRegistry<DesktopFeature> discover(ModuleSettings settings, ModuleFailureListener listener) {
        return ModuleRegistry.discover(DesktopFeature.class, DesktopModules.class.getClassLoader(), settings,
                new ModuleGuard(listener));
    }

    /**
     * 探索後對每個啟用中的模組呼叫 {@link DesktopFeature#attach}（T-22）。{@code attach} 失敗的模組當作停用：
     * 它拿不到外殼的服務，擴充點也就不呼叫。在 JavaFX 執行緒呼叫。
     *
     * @param settings 啟用狀態的來源
     * @param listener 模組出錯時的通知對象
     * @param hosts    依模組代號建立該模組專用的外殼服務
     * @return 探索結果
     */
    public static ModuleRegistry<DesktopFeature> discover(ModuleSettings settings, ModuleFailureListener listener,
            Function<String, ModuleHost> hosts) {
        return discover(settings, listener, hosts, List.of());
    }

    /**
     * 同 {@link #discover(ModuleSettings, ModuleFailureListener, Function)}，另外加上不在 services 檔裡的模組
     * （開發版的 {@code --demo-modules}，T-30）。
     *
     * @param settings 啟用狀態的來源
     * @param listener 模組出錯時的通知對象
     * @param hosts    依模組代號建立該模組專用的外殼服務
     * @param extra    排在探索結果之後的模組
     * @return 探索結果
     */
    public static ModuleRegistry<DesktopFeature> discover(ModuleSettings settings, ModuleFailureListener listener,
            Function<String, ModuleHost> hosts, List<? extends DesktopFeature> extra) {
        ModuleRegistry<DesktopFeature> discovered = discover(settings, listener);
        ModuleRegistry<DesktopFeature> found = extra.isEmpty() ? discovered
                : ModuleRegistry.of(concat(discovered.all(), extra), settings, discovered.guard());
        ModuleGuard guard = found.guard();
        Set<String> failed = new HashSet<>();
        for (DesktopFeature module : found.enabled()) {
            String id = guard.call(module.getClass().getName(), "id", module::id).orElse("?");
            if (!guard.run(id, "attach", () -> module.attach(hosts.apply(id)))) {
                failed.add(id);
            }
        }
        if (failed.isEmpty()) {
            return found;
        }
        return ModuleRegistry.of(found.all(),
                id -> failed.contains(id) ? Optional.of(Boolean.FALSE) : settings.enabled(id), guard);
    }

    private static List<DesktopFeature> concat(List<DesktopFeature> a, List<? extends DesktopFeature> b) {
        List<DesktopFeature> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * 正式程式的外殼服務：模組設定改了立即生效（{@link LiveSettings#update}），寫檔放到 {@code background}；
     * 條目導覽是整個程式共用的 {@code navigator}（解鎖後才接上條目畫面，T-30）。
     *
     * @param live       執行中的設定
     * @param background 寫 {@code settings.json} 的背景執行緒
     * @param navigator  以 UUID 開啟條目詳細
     * @return 依模組代號建立外殼服務；保管庫查詢一律為空（沒有接上 {@link ModuleVaultAccess}，測試用）
     */
    public static Function<String, ModuleHost> hosts(LiveSettings live, Executor background,
            EntryNavigator navigator) {
        return hosts(live, background, navigator, new ModuleVaultAccess(() -> { }));
    }

    /**
     * 同 {@link #hosts(LiveSettings, Executor, EntryNavigator)}，另外給唯讀的保管庫查詢（T-32）：整個程式共用一個
     * {@code vault}，外殼在解鎖（與還原後重新載入）時 {@link ModuleVaultAccess#open()}、上鎖時
     * {@link ModuleVaultAccess#revoke()}（{@code StartFlow}），條目畫面把每次換新的快照發布進來。
     *
     * @param live       執行中的設定
     * @param background 寫 {@code settings.json} 的背景執行緒
     * @param navigator  以 UUID 開啟條目詳細
     * @param vault      模組的唯讀保管庫
     * @return 依模組代號建立外殼服務；寫入一律是「已上鎖」（沒有接上 {@link ModuleVaultWriter}，測試用）
     */
    public static Function<String, ModuleHost> hosts(LiveSettings live, Executor background,
            EntryNavigator navigator, ModuleVaultAccess vault) {
        return hosts(live, background, navigator, vault, new ModuleVaultWriter());
    }

    /**
     * 同 {@link #hosts(LiveSettings, Executor, EntryNavigator, ModuleVaultAccess)}，另外給寫入條目的入口（T-33）：
     * 整個程式共用一個 {@code writer}，外殼在解鎖時和 {@code vault} 的租約一起接上（{@code ModuleVaultLink}）；
     * 每個模組的 host 帶自己的代號呼叫它（開放範圍由 {@link ModuleVaultWriter#ALLOWED_MODULES} 決定）。
     *
     * @param live       執行中的設定
     * @param background 寫 {@code settings.json} 的背景執行緒
     * @param navigator  以 UUID 開啟條目詳細
     * @param vault      模組的唯讀保管庫
     * @param writer     模組的寫入入口
     * @return 依模組代號建立外殼服務；剪貼簿一律為空（測試用）
     */
    public static Function<String, ModuleHost> hosts(LiveSettings live, Executor background,
            EntryNavigator navigator, ModuleVaultAccess vault, ModuleVaultWriter writer) {
        return hosts(live, background, navigator, vault, writer, id -> null);
    }

    /**
     * 同 {@link #hosts(LiveSettings, Executor, EntryNavigator, ModuleVaultAccess, ModuleVaultWriter)}，另外給剪貼簿
     * （T-34）：正式程式傳 {@code id -> ModuleClipboards.forModule(id, 外殼的 ClipboardGuard, Platform::isFxApplicationThread)}，
     * 和三欄的「複製密碼」同一個 {@code ClipboardGuard}（上鎖時一起清除）。
     *
     * @param live       執行中的設定
     * @param background 寫 {@code settings.json} 的背景執行緒
     * @param navigator  以 UUID 開啟條目詳細
     * @param vault      模組的唯讀保管庫
     * @param writer     模組的寫入入口
     * @param clipboards 依模組代號建立該模組的剪貼簿（建立 host 時呼叫一次；回傳 {@code null} 表示不給）
     * @return 依模組代號建立外殼服務
     */
    public static Function<String, ModuleHost> hosts(LiveSettings live, Executor background,
            EntryNavigator navigator, ModuleVaultAccess vault, ModuleVaultWriter writer,
            Function<String, ModuleClipboard> clipboards) {
        Objects.requireNonNull(vault, "vault");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(clipboards, "clipboards");
        return id -> {
            ScopedPreferences prefs = new ScopedPreferences(id, live::current, change -> {
                live.update(change);
                background.execute(() -> {
                    try {
                        // 寫的是寫檔當下最新的設定，連續變更時不會蓋回舊值
                        live.save();
                    } catch (IOException e) {
                        // 設定照常生效到程式結束；只記類型
                        LOG.log(Level.WARNING, "無法寫入模組設定：module={0} error={1}",
                                new Object[] {id, e.getClass().getName()});
                    }
                });
            });
            Optional<ModuleClipboard> clipboard = Optional.ofNullable(clipboards.apply(id));
            return new ModuleHost() {
                @Override
                public ModulePreferences preferences() {
                    return prefs;
                }

                @Override
                public EntryNavigator navigator() {
                    return navigator;
                }

                @Override
                public Optional<UnlockedEntries> unlockedEntries() {
                    return vault.unlockedEntries();
                }

                @Override
                public boolean whenEntriesLoaded(Executor executor, Runnable callback) {
                    return vault.whenLoaded(executor, callback);
                }

                @Override
                public void addEntries(EntryBatch batch, Executor executor, Consumer<EntryBatchResult> callback) {
                    writer.addEntries(id, batch, executor, callback);
                }

                @Override
                public Optional<ModuleClipboard> clipboard() {
                    return clipboard;
                }
            };
        };
    }
}
