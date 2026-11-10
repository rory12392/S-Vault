package app.svault.android.shell.fake;

import android.content.Context;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.MenuPage;
import app.svault.android.shell.ext.PasswordFieldAction;
import app.svault.android.shell.ext.SettingsSection;
import app.svault.android.shell.ext.UnlockProvider;
import java.util.List;

/**
 * Android 的停用與故障注入測試用的假模組（在測試的 META-INF/services 註冊）。
 */
public final class FakeAndroidModules {

    private static final int FAKE_STRING = 1;

    private FakeAndroidModules() {
    }

    /** 每個擴充點都提供一項的正常模組。 */
    public static final class Alpha implements AndroidFeature {
        @Override
        public String id() {
            return "alpha";
        }

        @Override
        public String displayName() {
            return "Alpha";
        }

        @Override
        public List<MenuPage> menuPages() {
            return List.of(new MenuPage(FAKE_STRING, Fragment.class));
        }

        @Override
        public List<SettingsSection> settingsSections() {
            return List.of(new SettingsSection(FAKE_STRING, Fragment.class));
        }

        @Override
        public List<PasswordFieldAction> passwordFieldActions() {
            return List.of(new PasswordFieldAction() {
                @Override
                public int labelRes() {
                    return FAKE_STRING;
                }

                @Override
                public char[] perform(Context context) {
                    return new char[] {'a'};
                }
            });
        }

        @Override
        public List<UnlockProvider> unlockProviders() {
            return List.of(new UnlockProvider() {
                @Override
                public int labelRes() {
                    return FAKE_STRING;
                }

                @Override
                public boolean isAvailable(String vaultId) {
                    return true;
                }

                @Override
                public void start(FragmentActivity activity, String vaultId, Callback callback) {
                    callback.onCancel();
                }
            });
        }
    }

    /** 只提供頁面的正常模組。 */
    public static final class Beta implements AndroidFeature {
        @Override
        public String id() {
            return "beta";
        }

        @Override
        public String displayName() {
            return "Beta";
        }

        @Override
        public List<MenuPage> menuPages() {
            return List.of(new MenuPage(FAKE_STRING, Fragment.class));
        }
    }

    /** 每個擴充點都丟例外的模組。 */
    public static final class Faulty implements AndroidFeature {
        @Override
        public String id() {
            return "faulty";
        }

        @Override
        public String displayName() {
            return "Faulty";
        }

        @Override
        public List<MenuPage> menuPages() {
            throw new IllegalStateException("頁面失敗");
        }

        @Override
        public List<UnlockProvider> unlockProviders() {
            throw new NoClassDefFoundError("androidx/biometric/BiometricPrompt");
        }

        @Override
        public List<SettingsSection> settingsSections() {
            throw new IllegalStateException("設定段落失敗");
        }
    }

    /** 建構子就失敗的模組。 */
    public static final class BrokenConstructor implements AndroidFeature {
        /** 永遠失敗。 */
        public BrokenConstructor() {
            throw new IllegalStateException("建構失敗");
        }

        @Override
        public String id() {
            return "broken";
        }

        @Override
        public String displayName() {
            return "Broken";
        }
    }
}
