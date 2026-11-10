package app.svault.android.feature.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.svault.android.R;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.MenuPage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/** 匯入模組的註冊（T-62）：選單頁「從 Chrome 匯入」，經 T-22 建立的 services 檔被外殼找到。 */
public class ImporterAndroidFeatureTest {

    /** AC-14-1：註冊一個 MenuPage「從 Chrome 匯入」，內容是匯入精靈；沒有其他擴充點。 */
    @Test
    public void ac14_1_registersMenuPage() {
        ImporterAndroidFeature f = new ImporterAndroidFeature();
        assertEquals("importer", f.id());
        assertEquals("從 Chrome 匯入", f.displayName());
        List<MenuPage> pages = f.menuPages();
        assertEquals(List.of(new MenuPage(R.string.imp_page_title, ImportWizardFragment.class)), pages);
        assertTrue(f.passwordFieldActions().isEmpty());
        assertTrue(f.settingsSections().isEmpty());
        assertTrue(f.unlockProviders().isEmpty());
    }

    /**
     * 外殼以 ServiceLoader 找得到本模組（services 檔由 T-22 建立，本模組不改）。直接讀正式程式的 services 檔：單元測試的
     * 類別路徑另有外殼測試故意建立失敗的假模組，不能整個載入。
     */
    @Test
    public void registeredInServicesFile() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("src/main/resources/META-INF/services/"
                + AndroidFeature.class.getName()), StandardCharsets.UTF_8);
        assertTrue(lines.toString(), lines.stream().map(String::trim)
                .anyMatch(ImporterAndroidFeature.class.getName()::equals));
    }
}
