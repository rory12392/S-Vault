package app.svault.core.feature.generator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.feature.generator.GeneratorSettings.Mode;
import app.svault.core.module.MapPreferences;
import app.svault.core.module.ModuleClipboard;
import app.svault.core.security.PasswordStrength;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 產生器的狀態（T-41、T-42 的兩份在 T-117 合成一份；不需要畫面）。 */
class GeneratorModelTest {

    private final MapPreferences prefs = new MapPreferences();
    private final List<Runnable> backgroundQueue = new ArrayList<>();
    private final AtomicInteger estimates = new AtomicInteger();
    private final AtomicInteger saves = new AtomicInteger();
    private final AtomicInteger notified = new AtomicInteger();

    private GeneratorModel model(GeneratorSettings initial) {
        GeneratorModel m = new GeneratorModel(initial, new PasswordGenerator(), pw -> {
            estimates.incrementAndGet();
            return pw.length() >= 16 ? PasswordStrength.STRONG : PasswordStrength.WEAK;
        }, backgroundQueue::add, Runnable::run, s -> {
            saves.incrementAndGet();
            s.save(prefs);
        });
        m.addListener(notified::incrementAndGet);
        return m;
    }

    private void runBackground() {
        List<Runnable> tasks = new ArrayList<>(backgroundQueue);
        backgroundQueue.clear();
        tasks.forEach(Runnable::run);
    }

    private static char[] take(GeneratorModel m) {
        return m.take().orElseThrow();
    }

    @Test
    @DisplayName("AC-04-1：長度與勾選的字元種類套用到結果，每種至少一個；長度夾在 8–128")
    void ac04_1_lengthAndClasses() {
        GeneratorModel m = model(GeneratorSettings.DEFAULTS.withPassword(
                new PasswordOptions(32, EnumSet.of(CharClass.UPPER, CharClass.DIGIT))));
        char[] pw = take(m);
        assertEquals(32, pw.length);
        String all = CharClass.UPPER.alphabet() + CharClass.DIGIT.alphabet();
        for (char c : pw) {
            assertTrue(all.indexOf(c) >= 0, "只能有大寫與數字");
        }
        assertTrue(new String(pw).chars().anyMatch(Character::isUpperCase));
        assertTrue(new String(pw).chars().anyMatch(Character::isDigit));

        m.setClass(CharClass.SYMBOL, true);
        String withSymbol = new String(take(m));
        assertTrue(withSymbol.chars().anyMatch(c -> CharClass.SYMBOL.alphabet().indexOf(c) >= 0));

        m.setLength(8, true);
        assertEquals(8, take(m).length);
        m.setLength(500, true);
        assertEquals(PasswordOptions.MAX_LENGTH, m.length());
        assertEquals(PasswordOptions.MAX_LENGTH, take(m).length);
        m.setLength(1, true);
        assertEquals(PasswordOptions.MIN_LENGTH, take(m).length);
    }

    @Test
    @DisplayName("AC-04-1：四種字元都不選時是「選項不合法」：沒有結果、不能使用或複製、不存檔")
    void ac04_1_noClassIsInvalid() {
        GeneratorModel m = model(GeneratorSettings.DEFAULTS);
        for (CharClass c : CharClass.values()) {
            m.setClass(c, false);
        }
        int savedBefore = saves.get();
        assertTrue(m.invalid());
        assertEquals("", m.display());
        assertTrue(m.take().isEmpty());
        assertTrue(m.copy(new FakeClipboard()).isEmpty());
        assertTrue(m.strength().isEmpty());
        m.save();
        assertEquals(savedBefore, saves.get(), "不合法的選項不存");
        // 存著的是最後一組合法的選項（只剩符號）
        assertEquals(EnumSet.of(CharClass.SYMBOL), GeneratorSettings.load(prefs).password().classes());

        m.setClass(CharClass.LOWER, true);
        assertFalse(m.invalid());
        assertEquals(GeneratorSettings.DEFAULT_LENGTH, take(m).length);
    }

    @Test
    @DisplayName("AC-04-1：字元種類全關後切到通行片語，照樣存檔（字元種類存上一組合法的）")
    void ac04_1_noClassThenPassphrase() {
        GeneratorModel m = model(GeneratorSettings.DEFAULTS);
        for (CharClass c : CharClass.values()) {
            m.setClass(c, false);
        }
        m.setMode(Mode.PASSPHRASE);
        assertFalse(m.invalid());
        GeneratorSettings loaded = GeneratorSettings.load(prefs);
        assertEquals(Mode.PASSPHRASE, loaded.mode());
        assertEquals(EnumSet.of(CharClass.SYMBOL), loaded.password().classes());
        m.setMode(Mode.PASSWORD);
        assertTrue(m.invalid(), "回到隨機密碼時畫面上仍是都沒選");
    }

    @Test
    @DisplayName("AC-04-2：通行片語的單字數（4–10）與分隔符號")
    void ac04_2_passphrase() {
        GeneratorModel m = model(GeneratorSettings.DEFAULTS);
        m.setMode(Mode.PASSPHRASE);
        m.setWords(7);
        m.setSeparator(".");
        String phrase = new String(take(m));
        assertEquals(7, phrase.split("\\.").length);
        assertTrue(phrase.matches("[a-z-]+(\\.[a-z-]+){6}"), "小寫單字以句點分隔");

        m.setSeparator("");
        assertFalse(new String(take(m)).contains("."));
        m.setWords(20);
        assertEquals(PassphraseOptions.MAX_WORDS, m.words());
        m.setWords(0);
        assertEquals(PassphraseOptions.MIN_WORDS, m.words());
        assertThrows(IllegalArgumentException.class, () -> m.setSeparator("x"));

        m.setMode(Mode.PASSWORD);
        assertEquals(GeneratorSettings.DEFAULT_LENGTH, take(m).length);
    }

    @Test
    @DisplayName("AC-04-4：強度在背景算，只套用目前這個結果的強度")
    void ac04_4_strengthForCurrentResult() {
        GeneratorModel m = model(GeneratorSettings.DEFAULTS);
        assertTrue(m.strength().isEmpty(), "算出來之前沒有強度");
        m.regenerate();
        m.regenerate();
        runBackground();
        assertEquals(1, estimates.get(), "過時的結果不評估");
        assertEquals(Optional.of(PasswordStrength.STRONG), m.strength());

        m.setLength(8, true);
        assertTrue(m.strength().isEmpty(), "換了結果就先清掉舊的強度");
        runBackground();
        assertEquals(Optional.of(PasswordStrength.WEAK), m.strength());
    }

    @Test
    @DisplayName("AC-04-4：交給評估的是另一份副本，評估完清零")
    void ac04_4_estimatorCopyIsWiped() {
        List<char[]> seen = new ArrayList<>();
        List<CharSequence> given = new ArrayList<>();
        GeneratorModel m = new GeneratorModel(GeneratorSettings.DEFAULTS, new PasswordGenerator(), pw -> {
            seen.add(pw.toString().toCharArray());
            given.add(pw);
            return PasswordStrength.STRONG;
        }, Runnable::run, Runnable::run, s -> { });
        assertEquals(1, seen.size());
        assertArrayEquals(take(m), seen.get(0), "評估的是目前的結果");
        // 評估完之後，交出去的那份已經清零；畫面上的結果不受影響
        String after = given.get(0).toString();
        assertEquals(GeneratorSettings.DEFAULT_LENGTH, after.length());
        assertTrue(after.chars().allMatch(c -> c == 0), "評估用的副本要清零");
        assertEquals(new String(seen.get(0)), m.display());
    }

    @Test
    @DisplayName("上次的選項存在 modules.generator.*；拖曳滑桿時不存，放開時存一次")
    void optionsRemembered() {
        GeneratorModel m = model(GeneratorSettings.DEFAULTS);
        m.setLength(40, false);
        assertEquals(0, saves.get());
        m.save();
        m.setClass(CharClass.SYMBOL, false);
        m.setMode(Mode.PASSPHRASE);
        m.setWords(8);
        m.setSeparator(" ");
        GeneratorSettings loaded = GeneratorSettings.load(prefs);
        assertEquals(Mode.PASSPHRASE, loaded.mode());
        assertEquals(40, loaded.password().length());
        assertEquals(EnumSet.of(CharClass.UPPER, CharClass.LOWER, CharClass.DIGIT), loaded.password().classes());
        assertEquals(new PassphraseOptions(8, " "), loaded.passphrase());
        assertEquals("passphrase", prefs.values.get("mode"));

        GeneratorModel reopened = model(GeneratorSettings.load(prefs));
        assertEquals(Mode.PASSPHRASE, reopened.mode());
        assertEquals(8, new String(take(reopened)).split(" ").length);
    }

    @Test
    @DisplayName("使用與複製拿到的是目前的結果；使用拿到獨立的副本；關閉後結果清掉")
    void takeCopyAndClear() {
        GeneratorModel m = model(GeneratorSettings.DEFAULTS);
        char[] a = take(m);
        char[] b = take(m);
        assertNotSame(a, b);
        assertArrayEquals(a, b);
        assertEquals(new String(a), m.display());

        FakeClipboard clipboard = new FakeClipboard();
        assertEquals(Optional.of(Duration.ofSeconds(30)), m.copy(clipboard));
        assertEquals(List.of(new String(a)), clipboard.secrets, "密碼走 copySecret，不走一般複製");
        assertEquals(0, clipboard.plain);

        long before = m.resultId();
        m.clear();
        assertTrue(m.invalid());
        assertEquals("", m.display());
        assertTrue(before != m.resultId());
        runBackground();
        assertTrue(m.strength().isEmpty(), "關閉後算完的強度不套用");
    }

    @Test
    @DisplayName("選項沒變時不重新產生也不存檔；每次改變都通知畫面")
    void noChangeNoWork() {
        GeneratorModel m = model(GeneratorSettings.DEFAULTS);
        String first = m.display();
        long id = m.resultId();
        m.setLength(GeneratorSettings.DEFAULT_LENGTH, true);
        m.setClass(CharClass.UPPER, true);
        m.setMode(Mode.PASSWORD);
        m.setWords(GeneratorSettings.DEFAULT_WORDS);
        m.setSeparator(GeneratorSettings.DEFAULT_SEPARATOR);
        assertEquals(first, m.display());
        assertEquals(id, m.resultId());
        assertEquals(0, saves.get());
        int before = notified.get();
        m.regenerate();
        assertTrue(notified.get() > before);
    }

    @Test
    @DisplayName("可以掛多個監聽（desktop 的面板與外層按鈕）；拿掉的不再收到（android 的畫面重建）；清掉時全部拿掉")
    void listeners() {
        GeneratorModel m = model(GeneratorSettings.DEFAULTS);
        AtomicInteger second = new AtomicInteger();
        Runnable l = second::incrementAndGet;
        m.addListener(l);
        int first = notified.get();
        m.regenerate();
        assertEquals(first + 1, notified.get());
        assertEquals(1, second.get());

        m.removeListener(l);
        m.regenerate();
        assertEquals(1, second.get(), "拿掉的監聽不再收到");
        assertEquals(first + 2, notified.get());

        m.clear();
        m.regenerate();
        assertEquals(first + 2, notified.get(), "清掉後不再通知");
    }

    /** 記錄收到什麼的剪貼簿（正式程式是外殼的 ClipboardGuard）。 */
    static final class FakeClipboard implements ModuleClipboard {

        final List<String> secrets = new ArrayList<>();
        int plain;

        @Override
        public Duration copySecret(String secret) {
            secrets.add(secret);
            return Duration.ofSeconds(30);
        }

        @Override
        public void copy(String text) {
            plain++;
        }
    }
}
