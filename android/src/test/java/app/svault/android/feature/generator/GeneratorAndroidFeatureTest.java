package app.svault.android.feature.generator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import app.svault.android.R;
import app.svault.android.shell.ext.MenuPage;
import app.svault.android.shell.ext.PasswordFieldAction;
import java.util.List;
import org.junit.Test;

/** T-42、T-44：android 模組註冊的擴充點。 */
public class GeneratorAndroidFeatureTest {

    /** 選單的「密碼產生器」：模組自己的標題字串、產生器頁的 Fragment。 */
    @Test
    public void ac04_menuPage() {
        List<MenuPage> pages = new GeneratorAndroidFeature().menuPages();
        assertEquals(List.of(new MenuPage(R.string.gen_page_title, GeneratorPageFragment.class)), pages);
    }

    /** 編輯畫面密碼欄旁的「產生」（T-44）：只用非同步版（開底部 sheet），同步版不回傳密碼。 */
    @Test
    public void ac04_passwordFieldAction() {
        List<PasswordFieldAction> actions = new GeneratorAndroidFeature().passwordFieldActions();
        assertEquals(1, actions.size());
        assertEquals(R.string.gen_action_generate, actions.get(0).labelRes());
        assertNull(actions.get(0).perform(null));
    }
}
