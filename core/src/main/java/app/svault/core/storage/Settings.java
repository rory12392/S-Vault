package app.svault.core.storage;

import app.svault.core.module.ModuleSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code settings.json} 的內容（不可變）：鍵是點分隔的路徑（CONVENTIONS §1，例如 {@code autoLock.desktopMinutes}、
 * {@code modules.health.enabled}），檔案中存成巢狀物件。程式不認得的鍵原樣保留。
 */
public final class Settings implements ModuleSettings {

    /** 主題（{@code light}／{@code dark}／{@code system}，D-19）。 */
    public static final String THEME = "theme";

    /** 主題的預設值：跟隨系統。 */
    public static final String THEME_SYSTEM = "system";

    /** 主題：淺色。 */
    public static final String THEME_LIGHT = "light";

    /** 主題：深色。 */
    public static final String THEME_DARK = "dark";

    /** {@link #AUTO_LOCK_DESKTOP_MINUTES} 的這個值表示「不自動上鎖」。 */
    public static final int AUTO_LOCK_OFF = 0;

    /**
     * PC 閒置幾分鐘後自動上鎖（AC-12-1）：1–60，{@value #AUTO_LOCK_OFF}＝不自動上鎖，預設 5。
     * Windows 鎖定螢幕時不論這個值都立即上鎖（T-18）。
     */
    public static final IntSetting AUTO_LOCK_DESKTOP_MINUTES = new IntSetting("autoLock.desktopMinutes", 0, 60, 5);

    /**
     * Android 切到背景幾秒後自動上鎖（AC-12-2）：0–3600，0＝切到背景立即上鎖，預設 30。
     * 螢幕關閉時不論這個值都立即上鎖（T-18）。畫面提供哪些選項由 T-24 決定，都要在這個範圍內。
     */
    public static final IntSetting AUTO_LOCK_ANDROID_SECONDS = new IntSetting("autoLock.androidSeconds", 0, 3600, 30);

    /** 複製密碼後幾秒清除剪貼簿（AC-05-1～AC-05-3）：10–300，預設 30（T-19 讀取）。 */
    public static final IntSetting CLIPBOARD_CLEAR_SECONDS = new IntSetting("clipboard.clearSeconds", 10, 300, 30);

    /** Windows 版解鎖時阻擋螢幕截圖（{@link #blockScreenCapture()}）。 */
    public static final String BLOCK_SCREEN_CAPTURE = "desktop.blockScreenCapture";

    private static final List<String> THEMES = List.of(THEME_LIGHT, THEME_DARK, THEME_SYSTEM);

    private final Map<String, Object> content;

    Settings(Map<String, Object> content) {
        this.content = deepCopy(content);
    }

    /** @return 沒有任何設定（所有值都是預設） */
    public static Settings defaults() {
        return new Settings(new LinkedHashMap<>());
    }

    /**
     * @param key 點分隔的鍵
     * @return 字串值；沒有或不是字串時為空
     */
    public Optional<String> string(String key) {
        return typed(key, String.class);
    }

    /**
     * @param key 點分隔的鍵
     * @return 整數值；沒有或不是整數時為空
     */
    public Optional<Long> number(String key) {
        return typed(key, Long.class);
    }

    /**
     * @param key 點分隔的鍵
     * @return 布林值；沒有或不是布林時為空
     */
    public Optional<Boolean> bool(String key) {
        return typed(key, Boolean.class);
    }

    /**
     * @return 主題設定值（{@value #THEME_LIGHT}／{@value #THEME_DARK}／{@value #THEME_SYSTEM}）；
     *         沒有設定或不認得的值時為 {@value #THEME_SYSTEM}
     */
    public String theme() {
        return string(THEME).filter(THEMES::contains).orElse(THEME_SYSTEM);
    }

    /**
     * @param theme {@value #THEME_LIGHT}／{@value #THEME_DARK}／{@value #THEME_SYSTEM}
     * @return 改過的新設定
     * @throws IllegalArgumentException 不認得的值
     */
    public Settings withTheme(String theme) {
        if (!THEMES.contains(theme)) {
            throw new IllegalArgumentException("不認得的主題：" + theme);
        }
        return with(THEME, theme);
    }

    /** @return PC 閒置上鎖的分鐘數；{@value #AUTO_LOCK_OFF}＝不自動上鎖（{@link #AUTO_LOCK_DESKTOP_MINUTES}） */
    public int autoLockDesktopMinutes() {
        return AUTO_LOCK_DESKTOP_MINUTES.read(this);
    }

    /** @return Android 背景上鎖的秒數（{@link #AUTO_LOCK_ANDROID_SECONDS}） */
    public int autoLockAndroidSeconds() {
        return AUTO_LOCK_ANDROID_SECONDS.read(this);
    }

    /** @return 剪貼簿清除秒數（{@link #CLIPBOARD_CLEAR_SECONDS}） */
    public int clipboardClearSeconds() {
        return CLIPBOARD_CLEAR_SECONDS.read(this);
    }

    /**
     * Windows 版解鎖時阻擋螢幕截圖與錄影（I-1，D-189；{@code WDA_EXCLUDEFROMCAPTURE}，和 Android 的 {@code FLAG_SECURE}
     * 同一種防護）。預設開；遠端桌面分享或錄教學影片的人可以在設定頁關掉。
     *
     * @return {@value #BLOCK_SCREEN_CAPTURE}；沒有設定時為 {@code true}
     */
    public boolean blockScreenCapture() {
        return bool(BLOCK_SCREEN_CAPTURE).orElse(Boolean.TRUE);
    }

    /**
     * @param key   點分隔的鍵
     * @param value 字串、{@link Long}、{@link Boolean}；{@code null} 表示移除
     * @return 改過的新設定（原物件不變）
     */
    public Settings with(String key, Object value) {
        if (value != null && !(value instanceof String || value instanceof Long || value instanceof Boolean)) {
            throw new IllegalArgumentException("設定值只能是字串、Long 或 Boolean：" + key);
        }
        Map<String, Object> copy = deepCopy(content);
        String[] path = key.split("\\.");
        Map<String, Object> node = copy;
        for (int i = 0; i < path.length - 1; i++) {
            Object child = node.get(path[i]);
            if (!(child instanceof Map)) {
                if (value == null) {
                    return new Settings(copy);
                }
                child = new LinkedHashMap<String, Object>();
                node.put(path[i], child);
            }
            node = VersionedJsonFile.asObject(child);
        }
        if (value == null) {
            node.remove(path[path.length - 1]);
        } else {
            node.put(path[path.length - 1], value);
        }
        return new Settings(copy);
    }

    @Override
    public Optional<Boolean> enabled(String moduleId) {
        return bool(moduleEnabledKey(moduleId));
    }

    /**
     * 設定頁「功能模組」區段的開關（T-38）：寫 {@code modules.<id>.enabled}，下次啟動時生效。
     *
     * @param moduleId 模組代號
     * @param enabled  啟用時為 {@code true}
     * @return 改過的新設定
     */
    public Settings withModuleEnabled(String moduleId, boolean enabled) {
        return with(moduleEnabledKey(moduleId), enabled);
    }

    private static String moduleEnabledKey(String moduleId) {
        return "modules." + moduleId + ".enabled";
    }

    /** @return 寫檔用的巢狀內容（複本） */
    Map<String, Object> content() {
        return deepCopy(content);
    }

    private <T> Optional<T> typed(String key, Class<T> type) {
        Object node = content;
        for (String part : key.split("\\.")) {
            if (!(node instanceof Map)) {
                return Optional.empty();
            }
            node = ((Map<?, ?>) node).get(part);
        }
        return type.isInstance(node) ? Optional.of(type.cast(node)) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static <T> T deepCopy(T value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                copy.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            }
            return (T) copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object o : (List<?>) value) {
                copy.add(deepCopy(o));
            }
            return (T) copy;
        }
        return value;
    }
}
