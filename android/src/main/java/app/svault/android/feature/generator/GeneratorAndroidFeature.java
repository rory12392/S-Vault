package app.svault.android.feature.generator;

import android.content.Context;
import androidx.fragment.app.FragmentActivity;
import app.svault.android.R;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.MenuPage;
import app.svault.android.shell.ext.PasswordFieldAction;
import app.svault.android.shell.ext.PasswordFill;
import java.util.List;

/**
 * 密碼產生器（GEN，US-04）的 Android 模組類別。註冊選單的「密碼產生器」（{@link MenuPage}，
 * {@link GeneratorPageFragment}；「複製」走 host 的剪貼簿，T-42）與編輯畫面密碼欄旁的「產生」（{@link PasswordFieldAction}，
 * 開 {@link GeneratorSheet}，經 {@link PasswordFill} 回填，T-44）。選項記在 {@code modules.generator.*}
 * （核心的 {@code GeneratorSettings}）。文字資源放 {@code res/values/gen_strings.xml}。
 */
public final class GeneratorAndroidFeature implements AndroidFeature {

    /** 模組代號（設定鍵 {@code modules.generator.*}）。 */
    public static final String ID = "generator";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        // FeatureModule 是 core 的介面，這裡拿不到 Context 讀字串資源；只在模組的擴充點整個失敗時當段落標題
        return "密碼產生器";
    }

    @Override
    public List<MenuPage> menuPages() {
        return List.of(new MenuPage(R.string.gen_page_title, GeneratorPageFragment.class));
    }

    @Override
    public List<PasswordFieldAction> passwordFieldActions() {
        return List.of(new GenerateAction());
    }

    /** 編輯畫面密碼欄旁的「產生」：開底部 sheet，按「使用這個密碼」才回填（T-37 的非同步版，T-44）。 */
    private static final class GenerateAction implements PasswordFieldAction {

        @Override
        public int labelRes() {
            return R.string.gen_action_generate;
        }

        @Override
        public char[] perform(Context context) {
            // 只用非同步版：結果要等使用者在 sheet 確認
            return null;
        }

        @Override
        public void perform(Context context, PasswordFill fill) {
            GeneratorSheet.show((FragmentActivity) context, fill);
        }
    }
}
