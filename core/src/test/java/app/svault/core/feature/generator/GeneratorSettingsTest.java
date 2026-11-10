package app.svault.core.feature.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.svault.core.feature.generator.GeneratorSettings.Mode;
import app.svault.core.module.ModulePreferences;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GeneratorSettings}：上次的選項存在 {@code modules.generator.*}。
 */
class GeneratorSettingsTest {

    /** 記憶體裡的 {@link ModulePreferences}，型別規則同 {@code ScopedPreferences}（字串、Long、Boolean）。 */
    static final class MemoryPreferences implements ModulePreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public Optional<String> string(String key) {
            return values.get(key) instanceof String s ? Optional.of(s) : Optional.empty();
        }

        @Override
        public Optional<Long> number(String key) {
            return values.get(key) instanceof Long l ? Optional.of(l) : Optional.empty();
        }

        @Override
        public Optional<Boolean> bool(String key) {
            return values.get(key) instanceof Boolean b ? Optional.of(b) : Optional.empty();
        }

        @Override
        public void put(String key, Object value) {
            if (value == null) {
                values.remove(key);
            } else if (value instanceof String || value instanceof Long || value instanceof Boolean) {
                values.put(key, value);
            } else {
                throw new IllegalArgumentException("型別不支援：" + value.getClass());
            }
        }
    }

    @Test
    @DisplayName("第一次使用（沒有設定）是預設值：隨機密碼 20 字元四種字元、5 個單字以連字號分隔")
    void emptyIsDefaults() {
        GeneratorSettings s = GeneratorSettings.load(new MemoryPreferences());
        assertEquals(GeneratorSettings.DEFAULTS, s);
        assertEquals(Mode.PASSWORD, s.mode());
        assertEquals(20, s.password().length());
        assertEquals(EnumSet.allOf(CharClass.class), s.password().classes());
        assertEquals(new PassphraseOptions(5, "-"), s.passphrase());
    }

    @Test
    @DisplayName("AC-04-1、AC-04-2：選項存起來後讀回相同，鍵是 modules.generator 底下的相對鍵")
    void roundTrip() {
        MemoryPreferences prefs = new MemoryPreferences();
        GeneratorSettings s = GeneratorSettings.DEFAULTS
                .withMode(Mode.PASSPHRASE)
                .withPassword(new PasswordOptions(33, EnumSet.of(CharClass.LOWER, CharClass.DIGIT)))
                .withPassphrase(new PassphraseOptions(8, ""));
        s.save(prefs);
        assertEquals(s, GeneratorSettings.load(prefs));
        assertEquals(Map.of("mode", "passphrase", "length", 33L, "upper", false, "lower", true,
                "digits", true, "symbols", false, "words", 8L, "separator", ""), prefs.values);
    }

    @Test
    @DisplayName("設定檔裡的值不合法時，該組選項改用預設值，產生器照常可用")
    void invalidValuesFallBack() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.put("mode", "other");
        prefs.put("length", 500L);
        prefs.put("words", 2L);
        GeneratorSettings s = GeneratorSettings.load(prefs);
        assertEquals(GeneratorSettings.DEFAULTS, s);

        prefs.values.clear();
        for (String key : new String[] {"upper", "lower", "digits", "symbols"}) {
            prefs.put(key, false);
        }
        prefs.put("separator", "x");
        assertEquals(GeneratorSettings.DEFAULTS, GeneratorSettings.load(prefs));

        // 型別不對（例如被手改成字串）也視為沒有設定
        prefs.values.clear();
        prefs.values.put("length", "12");
        assertEquals(GeneratorSettings.DEFAULTS.password(), GeneratorSettings.load(prefs).password());
    }

    @Test
    @DisplayName("部分設定：只有長度時，字元種類用預設（全選）")
    void partialSettings() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.put("length", 12L);
        prefs.put("symbols", false);
        PasswordOptions p = GeneratorSettings.load(prefs).password();
        assertEquals(12, p.length());
        assertEquals(Set.of(CharClass.UPPER, CharClass.LOWER, CharClass.DIGIT), p.classes());
    }

    @Test
    @DisplayName("欄位不可為 null")
    void nullRejected() {
        assertThrows(NullPointerException.class,
                () -> new GeneratorSettings(null, PasswordOptions.allClasses(8), new PassphraseOptions(4, "-")));
        assertThrows(NullPointerException.class,
                () -> new GeneratorSettings(Mode.PASSWORD, null, new PassphraseOptions(4, "-")));
        assertThrows(NullPointerException.class,
                () -> new GeneratorSettings(Mode.PASSWORD, PasswordOptions.allClasses(8), null));
    }
}
