package app.svault.core.feature.generator;

import app.svault.core.module.ModulePreferences;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 產生器上次使用的選項（{@code settings.json} 的 {@code modules.generator.*}，規格 §1「擁有的資料」）。
 * 兩個平台的產生器面板與獨立頁共用：開啟時 {@link #load}，選項改變時 {@link #save}。
 *
 * <p>只存選項，不存產生的值（{@code settings.json} 不加密）。讀到不合法的值（手改設定檔、舊版本）時，
 * 該組選項改用預設值，不讓產生器打不開。
 *
 * @param mode       上次的模式
 * @param password   隨機密碼的選項
 * @param passphrase 通行片語的選項
 */
public record GeneratorSettings(Mode mode, PasswordOptions password, PassphraseOptions passphrase) {

    /** 產生器的模式。 */
    public enum Mode {
        /** 隨機密碼（AC-04-1）。 */
        PASSWORD,
        /** 通行片語（AC-04-2）。 */
        PASSPHRASE
    }

    /** 預設長度 20：四種字元全選時約 131 位元熵，也在大多數網站的長度上限內。 */
    public static final int DEFAULT_LENGTH = 20;
    /** 預設 5 個單字：約 64.6 位元熵（EFF 建議至少 6 字用於高價值帳號，使用者可以調高）。 */
    public static final int DEFAULT_WORDS = 5;
    /** 預設分隔符號。 */
    public static final String DEFAULT_SEPARATOR = "-";

    /** 第一次使用時的選項：隨機密碼、20 字元、四種字元、5 個單字以連字號分隔。 */
    public static final GeneratorSettings DEFAULTS = new GeneratorSettings(Mode.PASSWORD,
            PasswordOptions.allClasses(DEFAULT_LENGTH), new PassphraseOptions(DEFAULT_WORDS, DEFAULT_SEPARATOR));

    // 相對鍵（ModulePreferences 會加上 modules.generator. 前綴）
    static final String KEY_MODE = "mode";
    static final String KEY_LENGTH = "length";
    static final String KEY_WORDS = "words";
    static final String KEY_SEPARATOR = "separator";
    static final Map<CharClass, String> CLASS_KEYS = Map.of(
            CharClass.UPPER, "upper", CharClass.LOWER, "lower", CharClass.DIGIT, "digits", CharClass.SYMBOL, "symbols");

    /**
     * @throws NullPointerException 任一欄位為 {@code null}
     */
    public GeneratorSettings {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(password);
        Objects.requireNonNull(passphrase);
    }

    /**
     * 讀取上次的選項；沒有設定或值不合法的部分用預設值。
     *
     * @param prefs 本模組的設定（{@code ModuleHost} 取得）
     * @return 選項
     */
    public static GeneratorSettings load(ModulePreferences prefs) {
        Mode mode = prefs.string(KEY_MODE).flatMap(GeneratorSettings::parseMode).orElse(DEFAULTS.mode());
        return new GeneratorSettings(mode, loadPassword(prefs), loadPassphrase(prefs));
    }

    /**
     * 寫入全部選項（立即生效並排定寫檔，在 UI 執行緒呼叫）。
     *
     * @param prefs 本模組的設定
     */
    public void save(ModulePreferences prefs) {
        prefs.put(KEY_MODE, mode.name().toLowerCase(Locale.ROOT));
        prefs.put(KEY_LENGTH, (long) password.length());
        for (CharClass c : CharClass.values()) {
            prefs.put(CLASS_KEYS.get(c), password.classes().contains(c));
        }
        prefs.put(KEY_WORDS, (long) passphrase.wordCount());
        prefs.put(KEY_SEPARATOR, passphrase.separator());
    }

    /**
     * @param newMode 新的模式
     * @return 只改模式的副本
     */
    public GeneratorSettings withMode(Mode newMode) {
        return new GeneratorSettings(newMode, password, passphrase);
    }

    /**
     * @param newPassword 新的隨機密碼選項
     * @return 只改隨機密碼選項的副本
     */
    public GeneratorSettings withPassword(PasswordOptions newPassword) {
        return new GeneratorSettings(mode, newPassword, passphrase);
    }

    /**
     * @param newPassphrase 新的通行片語選項
     * @return 只改通行片語選項的副本
     */
    public GeneratorSettings withPassphrase(PassphraseOptions newPassphrase) {
        return new GeneratorSettings(mode, password, newPassphrase);
    }

    private static PasswordOptions loadPassword(ModulePreferences prefs) {
        Set<CharClass> classes = EnumSet.noneOf(CharClass.class);
        for (CharClass c : CharClass.values()) {
            if (prefs.bool(CLASS_KEYS.get(c)).orElse(DEFAULTS.password().classes().contains(c))) {
                classes.add(c);
            }
        }
        long length = prefs.number(KEY_LENGTH).orElse((long) DEFAULT_LENGTH);
        if (classes.isEmpty() || length < PasswordOptions.MIN_LENGTH || length > PasswordOptions.MAX_LENGTH) {
            return DEFAULTS.password();
        }
        return new PasswordOptions((int) length, classes);
    }

    private static PassphraseOptions loadPassphrase(ModulePreferences prefs) {
        long words = prefs.number(KEY_WORDS).orElse((long) DEFAULT_WORDS);
        String separator = prefs.string(KEY_SEPARATOR).orElse(DEFAULT_SEPARATOR);
        if (words < PassphraseOptions.MIN_WORDS || words > PassphraseOptions.MAX_WORDS
                || !PassphraseOptions.SEPARATORS.contains(separator)) {
            return DEFAULTS.passphrase();
        }
        return new PassphraseOptions((int) words, separator);
    }

    private static Optional<Mode> parseMode(String value) {
        for (Mode m : Mode.values()) {
            if (m.name().toLowerCase(Locale.ROOT).equals(value)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
