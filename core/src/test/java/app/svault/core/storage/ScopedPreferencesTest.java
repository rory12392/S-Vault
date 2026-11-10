package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.svault.core.module.ModulePreferences;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模組自己的設定（T-22）：只讀寫 {@code modules.<id>.*}，變更走平台給的變更函式（立即生效、背景寫檔）。
 */
class ScopedPreferencesTest {

    private final LiveSettings live = LiveSettings.inMemory(Settings.defaults());
    private int changes;

    private ModulePreferences prefs(String moduleId) {
        return new ScopedPreferences(moduleId, live::current, op -> {
            changes++;
            live.update(op);
        });
    }

    @Test
    @DisplayName("讀寫只在 modules.<id>. 底下，經變更函式套用")
    void readsAndWritesOwnSection() {
        ModulePreferences gen = prefs("generator");
        gen.put("length", 20L);
        gen.put("options.symbols", Boolean.TRUE);
        gen.put("separator", "-");

        assertEquals(3, changes);
        assertEquals(Optional.of(20L), live.current().number("modules.generator.length"));
        assertEquals(Optional.of(Boolean.TRUE), gen.bool("options.symbols"));
        assertEquals(Optional.of("-"), gen.string("separator"));
        // 型別不同時讀成空
        assertEquals(Optional.empty(), gen.number("separator"));

        gen.put("separator", null);
        assertEquals(Optional.empty(), gen.string("separator"));
    }

    @Test
    @DisplayName("模組之間、模組與核心設定互不影響")
    void isolatedFromOthers() {
        prefs("generator").put("theme", "dark");
        prefs("health").put("length", 1L);

        assertEquals(Settings.THEME_SYSTEM, live.current().theme());
        assertEquals(Optional.empty(), prefs("health").string("theme"));
        assertEquals(Optional.empty(), prefs("generator").number("length"));
    }

    @Test
    @DisplayName("跳出自己段落的鍵、不支援的值、格式不對的模組代號一律拒絕")
    void rejectsEscapes() {
        ModulePreferences gen = prefs("generator");
        for (String bad : List.of("", ".theme", "a..b", "a.", "../theme", "a b")) {
            assertThrows(IllegalArgumentException.class, () -> gen.put(bad, "x"), bad);
            assertThrows(IllegalArgumentException.class, () -> gen.string(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> gen.put("length", 20));
        assertThrows(IllegalArgumentException.class, () -> prefs("Gen.x"));
        assertEquals(0, changes);
    }
}
