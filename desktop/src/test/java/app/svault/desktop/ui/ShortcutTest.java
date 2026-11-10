package app.svault.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShortcutTest {

    @Test
    @DisplayName("14 個快捷鍵各不相同")
    void uniqueCombinations() {
        Set<String> seen = new HashSet<>();
        for (Shortcut s : Shortcut.values()) {
            assertTrue(seen.add(s.combination().getName()), "重複的快捷鍵：" + s);
        }
        assertEquals(14, seen.size());
    }

    @Test
    @DisplayName("每個快捷鍵的按鍵組合和預期的一致")
    void combinations() {
        assertEquals("Shortcut+L", Shortcut.LOCK.combination().getName());
        assertEquals("Shortcut+F", Shortcut.SEARCH.combination().getName());
        assertEquals("Alt+V", Shortcut.TOGGLE_PASSWORD.combination().getName());
        assertEquals("Shortcut+Comma", Shortcut.SETTINGS.combination().getName());
        assertEquals("Delete", Shortcut.DELETE.combination().getName());
        assertEquals("F2", Shortcut.RENAME_GROUP.combination().getName());
        assertEquals("Esc", Shortcut.CANCEL.combination().getName());
    }

    @Test
    @DisplayName("只有 Ctrl+C 與 Delete 在文字欄裡讓給文字編輯")
    void yieldsToTextInput() {
        for (Shortcut s : Shortcut.values()) {
            boolean expected = EnumSet.of(Shortcut.COPY_PASSWORD, Shortcut.DELETE).contains(s);
            if (expected) {
                assertTrue(s.yieldsToTextInput(), s.name());
            } else {
                assertFalse(s.yieldsToTextInput(), s.name());
            }
        }
    }
}
