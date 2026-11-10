package app.svault.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 網址的「開啟」只交給瀏覽器 http／https。合併自 desktop 與 android 原本各自的測試。
 */
class LinksTest {

    @Test
    @DisplayName("http／https 原樣開啟；沒有 scheme 的網域補 https；前後的空白（含全形）去掉")
    void httpAndBareHosts() {
        assertEquals(Optional.of("https://github.com/login"), Links.browsable(" https://github.com/login "));
        assertEquals(Optional.of("https://github.com/login"), Links.browsable("\u3000github.com/login\u3000"));
        assertEquals(Optional.of("http://192.168.1.1"), Links.browsable("http://192.168.1.1"));
        assertEquals(Optional.of("https://github.com/login"), Links.browsable("github.com/login"));
        assertEquals(Optional.of("https://nas.local:5001/"), Links.browsable("nas.local:5001/"));
    }

    @Test
    @DisplayName("其他 scheme、空白、壞掉的網址不開")
    void rejectsOthers() {
        for (String bad : new String[] {"", "   ", null, "file:///C:/Windows/system32/calc.exe", "file:///sdcard/a.html",
                "javascript:alert(1)", "intent://scan/#Intent;scheme=zxing;end", "content://x/y", "mailto:a@b.c",
                "ftp://example.com", "https://", "a b.com", "\\\\server\\share"}) {
            assertTrue(Links.browsable(bad).isEmpty(), String.valueOf(bad));
        }
    }

    @Test
    @DisplayName("strip 同 String.strip：去掉前後的空白與全形空白，中間的不動")
    void stripLikeJdk() {
        for (String s : new String[] {"", " ", "a", " a b ", "\t\na\u3000", "\u3000\u3000", "\u2003x\u2003"}) {
            assertEquals(s.strip(), Texts.strip(s), s);
        }
    }
}
