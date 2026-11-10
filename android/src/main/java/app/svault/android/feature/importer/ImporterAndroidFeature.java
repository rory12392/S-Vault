package app.svault.android.feature.importer;

import app.svault.android.R;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.MenuPage;
import java.util.List;

/**
 * 匯入（IMP，US-14）的 Android 模組類別（T-62）：選單的「從 Chrome 匯入」精靈（{@link ImportWizardFragment}）。重複判定經
 * {@code ModuleHost.unlockedEntries()} 唯讀取用，寫入只經 {@code ModuleHost.addEntries}。
 * 文字資源放 {@code res/values/imp_strings.xml}。
 */
public final class ImporterAndroidFeature implements AndroidFeature {

    /** 模組代號（設定鍵 {@code modules.importer.*}）。 */
    public static final String ID = "importer";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        // FeatureModule 是 core 的介面，這裡拿不到 Context 讀字串資源；只在模組的擴充點整個失敗時當段落標題
        return "從 Chrome 匯入";
    }

    @Override
    public List<MenuPage> menuPages() {
        return List.of(new MenuPage(R.string.imp_page_title, ImportWizardFragment.class));
    }
}
