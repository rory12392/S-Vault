package app.svault.core.storage;

import app.svault.core.module.ModulePreferences;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * {@link ModulePreferences} 的實作（T-22）：把相對鍵加上 {@code modules.<id>.} 前綴，讀寫執行中的 {@link Settings}。
 * 寫入交給平台的變更函式（desktop、android 都是「更新 {@link LiveSettings}＋背景寫檔」），所以和設定畫面的變更走同一條路，
 * 不會互相蓋掉。
 */
public final class ScopedPreferences implements ModulePreferences {

    // 每一段只允許英數、底線、連字號：擋掉 ".."、前後的點與 "../" 之類想跳出自己段落的鍵
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");
    private static final Pattern MODULE_ID = Pattern.compile("[a-z0-9]+");

    private final String prefix;
    private final Supplier<Settings> current;
    private final Consumer<UnaryOperator<Settings>> change;

    /**
     * @param moduleId 模組代號（全小寫英數）
     * @param current  目前的設定
     * @param change   套用變更並排定寫檔（平台實作）
     * @throws IllegalArgumentException 模組代號格式不對
     */
    public ScopedPreferences(String moduleId, Supplier<Settings> current, Consumer<UnaryOperator<Settings>> change) {
        if (moduleId == null || !MODULE_ID.matcher(moduleId).matches()) {
            throw new IllegalArgumentException("模組代號格式不對");
        }
        this.prefix = "modules." + moduleId + ".";
        this.current = Objects.requireNonNull(current, "current");
        this.change = Objects.requireNonNull(change, "change");
    }

    @Override
    public Optional<String> string(String key) {
        return current.get().string(fullKey(key));
    }

    @Override
    public Optional<Long> number(String key) {
        return current.get().number(fullKey(key));
    }

    @Override
    public Optional<Boolean> bool(String key) {
        return current.get().bool(fullKey(key));
    }

    @Override
    public void put(String key, Object value) {
        String full = fullKey(key);
        if (value != null && !(value instanceof String || value instanceof Long || value instanceof Boolean)) {
            throw new IllegalArgumentException("設定值只能是字串、Long 或 Boolean：" + key);
        }
        change.accept(s -> s.with(full, value));
    }

    private String fullKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("設定鍵格式不對：" + key);
        }
        return prefix + key;
    }
}
