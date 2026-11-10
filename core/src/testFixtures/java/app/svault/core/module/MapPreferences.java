package app.svault.core.module;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 記憶體裡的模組設定（測試用；原本 desktop 與 android 的產生器測試各有一份，T-117 合成這一份）。 */
public final class MapPreferences implements ModulePreferences {

    /** 存著的值（測試可以直接看）。 */
    public final Map<String, Object> values = new HashMap<>();

    @Override
    public Optional<String> string(String key) {
        return values.get(key) instanceof String s ? Optional.of(s) : Optional.empty();
    }

    @Override
    public Optional<Long> number(String key) {
        return values.get(key) instanceof Long n ? Optional.of(n) : Optional.empty();
    }

    @Override
    public Optional<Boolean> bool(String key) {
        return values.get(key) instanceof Boolean b ? Optional.of(b) : Optional.empty();
    }

    @Override
    public void put(String key, Object value) {
        values.put(key, value);
    }
}
