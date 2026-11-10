package app.svault.android.testmodules;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.MenuPage;
import app.svault.android.shell.ext.SettingsSection;
import java.util.List;

/**
 * 儀器測試用的模組（T-22 的 {@code ModuleErrorScreensTest}）：APK 裡的真模組都是空殼，測不到模組段落的錯誤狀態。
 * 放在 {@code shell} 以外的套件：外殼重建畫面時只照常還原 {@code app.svault.android.shell..} 的 Fragment。
 * 段落標題用 0（外殼改用模組名稱），因為測試 APK 的字串資源不在 App 的資源表裡。
 */
public final class TestModules {

    /** 正常段落的文字。 */
    public static final String OK_TEXT = "測試模組的正常段落";
    /** 只在重建時才會出錯的段落，新建時的文字。 */
    public static final String RESTORE_TEXT = "測試模組的重建段落";

    private TestModules() {
    }

    /** 段落正常的模組。 */
    public static final class Healthy implements AndroidFeature {
        @Override
        public String id() {
            return "testok";
        }

        @Override
        public String displayName() {
            return "測試正常";
        }

        @Override
        public List<SettingsSection> settingsSections() {
            return List.of(new SettingsSection(0, OkFragment.class));
        }
    }

    /** 段落建立畫面就丟例外的模組。 */
    public static final class Crashing implements AndroidFeature {
        @Override
        public String id() {
            return "testcrash";
        }

        @Override
        public String displayName() {
            return "測試出錯";
        }

        @Override
        public List<SettingsSection> settingsSections() {
            return List.of(new SettingsSection(0, CrashFragment.class));
        }
    }

    /** 段落只在被 FragmentManager 還原時丟例外的模組（T-24 發現的已知限制）。 */
    public static final class RestoreCrashing implements AndroidFeature {
        @Override
        public String id() {
            return "testrestore";
        }

        @Override
        public String displayName() {
            return "測試重建";
        }

        @Override
        public List<SettingsSection> settingsSections() {
            return List.of(new SettingsSection(0, RestoreCrashFragment.class));
        }
    }

    /**
     * 有選單頁面的模組（T-30 的 {@code ModuleMenuScreensTest}）：標題資源用 0，選單與 Top app bar 用模組名稱。
     */
    public static class Paged implements AndroidFeature {
        @Override
        public String id() {
            return "testpage";
        }

        @Override
        public String displayName() {
            return "測試頁面";
        }

        @Override
        public List<MenuPage> menuPages() {
            return List.of(new MenuPage(0, OkFragment.class));
        }
    }

    /** 選單頁面建立畫面就丟例外的模組。 */
    public static final class PageCrashing implements AndroidFeature {
        @Override
        public String id() {
            return "testpagecrash";
        }

        @Override
        public String displayName() {
            return "測試頁面出錯";
        }

        @Override
        public List<MenuPage> menuPages() {
            return List.of(new MenuPage(0, CrashFragment.class));
        }
    }

    /** 和 {@link Paged} 一樣、但測試裡設成停用的模組：選單不該有它。 */
    public static final class PageDisabled extends Paged {
        /** 停用它的代號。 */
        public static final String ID = "testpageoff";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String displayName() {
            return "測試頁面停用";
        }
    }

    /** 正常段落。 */
    public static final class OkFragment extends Fragment {
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            TextView v = new TextView(inflater.getContext());
            v.setText(OK_TEXT);
            return v;
        }
    }

    /** 建立畫面就丟例外。 */
    public static final class CrashFragment extends Fragment {
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            throw new IllegalStateException("測試：段落建立失敗");
        }
    }

    /** 新建時正常；被 FragmentManager 以儲存的狀態還原時丟例外。 */
    public static final class RestoreCrashFragment extends Fragment {
        private static final String KEY = "restored";

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (savedInstanceState != null && savedInstanceState.getBoolean(KEY)) {
                throw new IllegalStateException("測試：還原失敗");
            }
        }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            TextView v = new TextView(inflater.getContext());
            v.setText(RESTORE_TEXT);
            return v;
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putBoolean(KEY, true);
        }
    }
}
