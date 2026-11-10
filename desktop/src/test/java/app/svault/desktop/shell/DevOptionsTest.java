package app.svault.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.desktop.ui.ThemeMode;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 元件展示頁與截圖參數只在開發版生效。
 */
class DevOptionsTest {

    private static final List<String> ARGS = List.of("--ui-gallery", "--theme=dark", "--size=900x600",
            "--snapshot=out.png", "--gallery-state=toasts", "--drive=key=TAB|exit", "--demo-modules");

    @Test
    @DisplayName("打包版（沒有 svault.dev）：--ui-gallery 等參數一律忽略")
    void ignoredWithoutDevProperty() {
        assertEquals(DevOptions.NONE, DevOptions.parse(ARGS, false));
    }

    @Test
    @DisplayName("開發版：解析展示頁、主題、大小、截圖與狀態")
    void parsedInDevMode() {
        DevOptions d = DevOptions.parse(ARGS, true);
        assertTrue(d.gallery());
        assertEquals(ThemeMode.DARK, d.theme());
        assertEquals(900, d.width());
        assertEquals(600, d.height());
        assertTrue(d.fixedSize());
        assertEquals(Paths.get("out.png"), d.snapshot());
        assertEquals("toasts", d.galleryState());
        assertEquals("key=TAB|exit", d.drive());
        assertTrue(d.demoModules());
    }

    @Test
    @DisplayName("開發版沒有參數時是一般啟動")
    void noArgs() {
        assertEquals(DevOptions.NONE, DevOptions.parse(List.of(), true));
    }
}
