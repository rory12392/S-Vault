package app.svault.desktop.feature.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.svault.desktop.shell.ext.MenuPage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 匯入模組的註冊（T-61）：選單頁「從 Chrome 匯入」，以及選檔方式（正式程式一律系統對話框；開發版畫面腳本才用環境變數的固定路徑）。
 */
class ImporterDesktopFeatureTest {

    @Test
    @DisplayName("AC-14-1：註冊一個 MenuPage「從 Chrome 匯入」；沒有 attach 時建立頁面丟例外（外殼顯示錯誤狀態）")
    void registersMenuPage() {
        ImporterDesktopFeature f = new ImporterDesktopFeature();
        assertEquals("importer", f.id());
        assertEquals("從 Chrome 匯入", f.displayName());
        List<MenuPage> pages = f.menuPages();
        assertEquals(1, pages.size());
        assertEquals("從 Chrome 匯入", pages.get(0).title());
        assertThrows(IllegalStateException.class, () -> pages.get(0).factory().get());
    }

    @Test
    @DisplayName("T-61：開發版設了 SVAULT_DEV_IMPORT_CSV 才用固定路徑；正式程式忽略它")
    void devPickerOnlyInDevMode() {
        Path p = Path.of("E:/fake/chrome.csv");
        ImporterDesktopFeature f = new ImporterDesktopFeature(Map.of(CsvPicker.DEV_ENV, p.toString())::get);

        assertEquals(p, f.picker(true).pick(null).orElseThrow());
        CsvPicker prod = f.picker(false);
        assertNotNull(prod);
        assertNotSame(f.picker(true).getClass(), prod.getClass());
        ImporterDesktopFeature unset = new ImporterDesktopFeature(Map.<String, String>of()::get);
        assertEquals(prod.getClass(), unset.picker(true).getClass());
    }
}
