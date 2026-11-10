package app.svault.core.module;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 功能模組的探索與啟用狀態。以 ServiceLoader 探索，不在核心維護手寫清單（CONVENTIONS §11）。
 *
 * <p>探索失敗（建構子丟例外、類別缺失）或代號重複的模組會被略過並回報，其他模組照常載入。
 *
 * @param <T> 平台的模組型別（{@code DesktopFeature}、{@code AndroidFeature}）
 */
public final class ModuleRegistry<T extends FeatureModule> {

    private final List<Entry<T>> entries;
    private final ModuleGuard guard;

    private ModuleRegistry(List<Entry<T>> entries, ModuleGuard guard) {
        this.entries = List.copyOf(entries);
        this.guard = guard;
    }

    /**
     * 以 ServiceLoader 探索模組。
     *
     * @param type        平台的模組介面，對應 {@code META-INF/services/<type 的完整名稱>}
     * @param classLoader 探索用的類別載入器
     * @param settings    啟用狀態的來源
     * @param guard       探索失敗時的錯誤邊界
     * @param <T>         模組型別
     * @return 探索結果
     */
    public static <T extends FeatureModule> ModuleRegistry<T> discover(
            Class<T> type, ClassLoader classLoader, ModuleSettings settings, ModuleGuard guard) {
        List<T> found = new ArrayList<>();
        Iterator<T> it = ServiceLoader.load(type, classLoader).iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                found.add(it.next());
            } catch (ServiceConfigurationError e) {
                // 單一模組載入失敗不影響其他模組；ServiceLoader 的迭代器在錯誤後可以繼續
                guard.report("?", "discover", e);
            }
        }
        return of(found, settings, guard);
    }

    /**
     * 以給定的模組清單建立（測試與手動組裝用）。
     *
     * @param modules  模組實例
     * @param settings 啟用狀態的來源
     * @param guard    讀取模組代號失敗時的錯誤邊界
     * @param <T>      模組型別
     * @return 註冊結果
     */
    public static <T extends FeatureModule> ModuleRegistry<T> of(
            List<? extends T> modules, ModuleSettings settings, ModuleGuard guard) {
        Objects.requireNonNull(settings, "settings");
        Map<String, Entry<T>> byId = new LinkedHashMap<>();
        for (T module : modules) {
            String className = module.getClass().getName();
            Optional<String> id = guard.call(className, "id", module::id);
            if (!id.isPresent() || id.get().trim().isEmpty()) {
                guard.report(className, "id", new IllegalStateException("模組代號是空的"));
                continue;
            }
            if (byId.containsKey(id.get())) {
                guard.report(id.get(), "discover", new IllegalStateException("模組代號重複：" + className));
                continue;
            }
            boolean byDefault = guard.call(id.get(), "enabledByDefault", module::enabledByDefault)
                    .orElse(Boolean.FALSE);
            boolean enabled = settings.enabled(id.get()).orElse(byDefault);
            byId.put(id.get(), new Entry<>(id.get(), module, enabled, byDefault));
        }
        return new ModuleRegistry<>(new ArrayList<>(byId.values()), guard);
    }

    /**
     * 向每個啟用中的模組收集一種擴充。某個模組丟例外或回傳 {@code null} 時略過該模組並回報，其他模組照常。
     *
     * @param action    擴充點名稱（記錄用），例如 {@code menuPages}
     * @param extractor 從模組取出擴充清單
     * @param <E>       擴充的型別
     * @return 依模組順序排列的擴充，每項記下來源模組
     */
    public <E> List<Contribution<E>> collect(String action,
            Function<? super T, ? extends List<? extends E>> extractor) {
        List<Contribution<E>> out = new ArrayList<>();
        for (Entry<T> entry : entries) {
            if (!entry.enabled()) {
                continue;
            }
            // 先複製成不可變清單：模組回傳的清單若含 null 或之後被修改，錯誤留在邊界內
            Optional<List<E>> items = guard.call(entry.id(), action,
                    () -> List.<E>copyOf(extractor.apply(entry.module())));
            items.ifPresent(list -> list.forEach(item -> out.add(new Contribution<>(entry.id(), item))));
        }
        return List.copyOf(out);
    }

    /**
     * 對每個停用中的模組執行 {@code body}（T-35 停用通知：讓模組清掉只屬於這台裝置的資料，例如 BIO 的 Keystore 金鑰）。
     * 每個模組各自包在錯誤邊界內，丟例外的模組被回報、其他模組照常。
     *
     * @param action 動作名稱（記錄用），例如 {@code onDisabled}
     * @param body   對一個停用模組要做的事
     */
    public void forEachDisabled(String action, Consumer<? super T> body) {
        for (Entry<T> entry : entries) {
            if (!entry.enabled()) {
                guard.run(entry.id(), action, () -> body.accept(entry.module()));
            }
        }
    }

    /**
     * 設定頁「功能模組」區段的列（T-38）：探索到的每個模組一列（含停用的），依探索順序。停用中的模組只被呼叫
     * {@link FeatureModule#displayName()}，包在錯誤邊界內；丟例外或空白時用模組代號。
     *
     * @param saved 目前的設定（開關顯示的值；可能和這次啟動時不同）
     * @return 每個模組的開關
     */
    public List<ModuleToggle> toggles(ModuleSettings saved) {
        Objects.requireNonNull(saved, "saved");
        List<ModuleToggle> out = new ArrayList<>();
        for (Entry<T> entry : entries) {
            String name = guard.call(entry.id(), "displayName", entry.module()::displayName)
                    .filter(n -> !n.trim().isEmpty()).orElse(entry.id());
            boolean enabled = saved.enabled(entry.id()).orElse(entry.byDefault());
            out.add(new ModuleToggle(entry.id(), name, entry.enabled(), enabled));
        }
        return List.copyOf(out);
    }

    /**
     * @return 這個註冊表使用的錯誤邊界（外殼呼叫擴充內容時沿用）
     */
    public ModuleGuard guard() {
        return guard;
    }

    /**
     * @return 探索到的全部模組（含停用的），依探索順序
     */
    public List<T> all() {
        return List.copyOf(entries.stream().map(Entry::module).collect(Collectors.toList()));
    }

    /**
     * @return 啟用中的模組，依探索順序
     */
    public List<T> enabled() {
        return List.copyOf(entries.stream().filter(Entry::enabled).map(Entry::module).collect(Collectors.toList()));
    }

    /**
     * @param moduleId 模組代號
     * @return 該模組啟用中時為 {@code true}；不存在或停用時為 {@code false}
     */
    public boolean isEnabled(String moduleId) {
        return entries.stream().anyMatch(e -> e.id().equals(moduleId) && e.enabled());
    }

    private record Entry<T>(String id, T module, boolean enabled, boolean byDefault) { }
}
