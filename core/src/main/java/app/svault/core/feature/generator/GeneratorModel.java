package app.svault.core.feature.generator;

import app.svault.core.feature.generator.GeneratorSettings.Mode;
import app.svault.core.module.ModuleClipboard;
import app.svault.core.security.PasswordStrength;
import java.nio.CharBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 產生器的狀態：選項、目前的結果與強度。兩個平台共用（T-117；原本 desktop 的 T-41 與 android 的 T-42
 * 各有一份），不依賴畫面，畫面只讀狀態、呼叫方法。
 *
 * <p>選項一改就重新產生（AC-04-1、AC-04-2），合法的選項存回 {@code modules.generator.*}；四種字元都沒選時是「選項不合法」：
 * 沒有結果、不能使用或複製，也不存檔（下次開啟仍是上一組合法的選項）。
 *
 * <p>強度（AC-04-4）交給核心的 {@code StrengthEstimator}，在背景執行緒算（第一次要載入字典），只套用最新一次的結果；
 * 算出來之前是「沒有強度」。只在 UI 執行緒（desktop 的 JavaFX 執行緒、android 的主執行緒）呼叫本類別的方法。
 *
 * <p>結果是機密：只以 {@code char[]} 保存，換新結果、{@link #clear()} 時清零；不記錄日誌、沒有輸出結果的 {@code toString()}。
 */
public final class GeneratorModel {

    private final PasswordGenerator generator;
    private final Function<CharSequence, PasswordStrength> estimator;
    private final Executor background;
    private final Executor ui;
    private final Consumer<GeneratorSettings> persist;
    private final List<Runnable> listeners = new ArrayList<>();

    private Mode mode;
    private int length;
    private final Set<CharClass> classes = EnumSet.noneOf(CharClass.class);
    /** 最後一組不是空的字元種類（存檔用）。 */
    private final Set<CharClass> savedClasses = EnumSet.noneOf(CharClass.class);
    private int words;
    private String separator;

    /** 目前的結果；選項不合法時為 {@code null}。 */
    private char[] result;
    private PasswordStrength strength;
    /** 每次產生加一：背景算完的強度如果屬於舊的結果就丟掉（只在 UI 執行緒寫，背景執行緒讀）。 */
    private volatile long generation;

    /**
     * @param initial    上次的選項（{@link GeneratorSettings#load}）
     * @param generator  產生器
     * @param estimator  強度評估（在 {@code background} 上呼叫）
     * @param background 算強度的執行緒
     * @param ui         回到 UI 執行緒
     * @param persist    存選項（{@code s -> s.save(prefs)}）
     */
    public GeneratorModel(GeneratorSettings initial, PasswordGenerator generator,
            Function<CharSequence, PasswordStrength> estimator, Executor background, Executor ui,
            Consumer<GeneratorSettings> persist) {
        this.generator = Objects.requireNonNull(generator);
        this.estimator = Objects.requireNonNull(estimator);
        this.background = Objects.requireNonNull(background);
        this.ui = Objects.requireNonNull(ui);
        this.persist = Objects.requireNonNull(persist);
        mode = initial.mode();
        length = initial.password().length();
        classes.addAll(initial.password().classes());
        savedClasses.addAll(classes);
        words = initial.passphrase().wordCount();
        separator = initial.passphrase().separator();
        regenerate();
    }

    /**
     * desktop 的面板與外層的按鈕各掛一個；android 的畫面重建時拿掉舊的、掛上新的（ViewModel 比畫面活得久，不能留著舊畫面的參考）。
     *
     * @param value 狀態改變時呼叫（UI 執行緒）
     */
    public void addListener(Runnable value) {
        listeners.add(Objects.requireNonNull(value));
    }

    /** @param value 之前 {@link #addListener} 的同一個物件；沒掛過就不做事 */
    public void removeListener(Runnable value) {
        listeners.remove(value);
    }

    public Mode mode() {
        return mode;
    }

    public int length() {
        return length;
    }

    public boolean has(CharClass c) {
        return classes.contains(c);
    }

    public int words() {
        return words;
    }

    public String separator() {
        return separator;
    }

    /** @return 目前結果的編號：換了新結果（或清掉）就不同；強度算完不會變 */
    public long resultId() {
        return generation;
    }

    /** @return 選項不合法（隨機密碼模式下四種字元都沒選），或已清掉 */
    public boolean invalid() {
        return result == null;
    }

    /** @return 目前結果的強度；選項不合法或還在算時為空 */
    public Optional<PasswordStrength> strength() {
        return Optional.ofNullable(strength);
    }

    public void setMode(Mode value) {
        if (value != mode) {
            mode = Objects.requireNonNull(value);
            changed(true);
        }
    }

    /**
     * @param value 長度；超出 8–128 時夾到範圍內
     * @param save  是否存檔（拖曳滑桿的過程中不存，放開時再 {@link #save()}）
     */
    public void setLength(int value, boolean save) {
        int clamped = Math.max(PasswordOptions.MIN_LENGTH, Math.min(PasswordOptions.MAX_LENGTH, value));
        if (clamped != length) {
            length = clamped;
            changed(save);
        }
    }

    public void setClass(CharClass c, boolean on) {
        if (on ? classes.add(c) : classes.remove(c)) {
            changed(true);
        }
    }

    /** @param value 單字數；超出 4–10 時夾到範圍內 */
    public void setWords(int value) {
        int clamped = Math.max(PassphraseOptions.MIN_WORDS, Math.min(PassphraseOptions.MAX_WORDS, value));
        if (clamped != words) {
            words = clamped;
            changed(true);
        }
    }

    /** @param value {@link PassphraseOptions#SEPARATORS} 其中之一 */
    public void setSeparator(String value) {
        if (!PassphraseOptions.SEPARATORS.contains(value)) {
            throw new IllegalArgumentException("分隔符號不在清單裡");
        }
        if (!value.equals(separator)) {
            separator = value;
            changed(true);
        }
    }

    /** 用同一組選項產生新的結果（「重新產生」）。 */
    public void regenerate() {
        wipe();
        long mine = ++generation;
        if (mode == Mode.PASSWORD && classes.isEmpty()) {
            notifyChanged();
            return;
        }
        result = mode == Mode.PASSWORD
                ? generator.password(new PasswordOptions(length, classes))
                : generator.passphrase(new PassphraseOptions(words, separator));
        // 給背景執行緒自己的一份：UI 這邊換新結果時會清掉 result，不能讓評估讀到清了一半的陣列
        char[] copy = result.clone();
        background.execute(() -> {
            try {
                // 舊結果的評估直接跳過：拖曳滑桿時不會排一長串過時的評估
                if (mine != generation) {
                    return;
                }
                PasswordStrength s = estimator.apply(CharBuffer.wrap(copy));
                ui.execute(() -> {
                    if (mine == generation && result != null) {
                        strength = s;
                        notifyChanged();
                    }
                });
            } finally {
                Arrays.fill(copy, '\0');
            }
        });
        notifyChanged();
    }

    /**
     * 存目前的選項。隨機密碼模式下選項不合法時不存；通行片語模式下四種字元都沒選時，字元種類存上一組合法的
     * （設定檔裡不能有空的字元種類）。
     */
    public void save() {
        if (invalid()) {
            return;
        }
        if (!classes.isEmpty()) {
            savedClasses.clear();
            savedClasses.addAll(classes);
        }
        persist.accept(new GeneratorSettings(mode, new PasswordOptions(length, savedClasses),
                new PassphraseOptions(words, separator)));
    }

    /**
     * 顯示用的文字。JavaFX 的文字節點與 android 的 {@code TextView} 都只收字串，這份副本無法清零；只在畫面上用，不保存、
     * 不記錄。
     *
     * @return 目前的結果；選項不合法時為空字串
     */
    public String display() {
        return result == null ? "" : new String(result);
    }

    /** @return 目前結果的副本（呼叫端用完清零）；選項不合法或已清掉時為空 */
    public Optional<char[]> take() {
        return result == null ? Optional.empty() : Optional.of(result.clone());
    }

    /**
     * 複製目前的結果：交給外殼的剪貼簿（{@link ModuleClipboard#copySecret}，T-34），清除秒數與上鎖時清除都由外殼決定。
     *
     * @param clipboard 剪貼簿
     * @return 多久後清除；選項不合法時為空
     */
    public Optional<Duration> copy(ModuleClipboard clipboard) {
        return result == null ? Optional.empty() : Optional.of(clipboard.copySecret(new String(result)));
    }

    /** 面板關閉、頁面結束時呼叫：清掉結果、拿掉所有監聽，之後算完的強度也不再套用。 */
    public void clear() {
        wipe();
        generation++;
        listeners.clear();
    }

    private void changed(boolean save) {
        regenerate();
        if (save) {
            save();
        }
    }

    private void notifyChanged() {
        for (Runnable l : List.copyOf(listeners)) {
            l.run();
        }
    }

    private void wipe() {
        if (result != null) {
            Arrays.fill(result, '\0');
            result = null;
        }
        strength = null;
    }
}
