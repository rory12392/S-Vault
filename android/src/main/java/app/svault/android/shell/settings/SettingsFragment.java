package app.svault.android.shell.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.start.BackupActivity;
import app.svault.android.ui.SecureWindows;
import app.svault.android.ui.Toasts;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleToggle;
import app.svault.core.storage.IntSetting;
import app.svault.core.storage.Settings;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * 設定畫面（T-24）：外觀、安全、備份、各模組的段落、關於。放在
 * {@link FeatureHostActivity} 裡（不另外宣告 Activity，D-109 的做法），所以一樣有 {@code FLAG_SECURE} 與上鎖導回。
 * 每個選項改了就經 {@link SettingsChanges} 生效並寫檔，沒有「儲存」按鈕。
 *
 * <p>模組段落的 Fragment 在 {@link #onStart()} 才加入：此時 {@code commitNow} 會同步跑到段落的
 * {@code onCreateView}，建立畫面的例外也在錯誤邊界內（同 {@link FeatureHostActivity}）。重建畫面（旋轉、換主題）時
 * {@link FeatureHostActivity} 只還原成空白的佔位，這裡一樣在 {@code onStart} 重新建立（T-22；以前由 FragmentManager
 * 直接還原，模組的例外會讓 App 結束）。
 */
public class SettingsFragment extends Fragment {

    /** 錯誤邊界與 Top app bar 用的代號（設定是外殼的頁面，不是模組）。 */
    public static final String PAGE_ID = "shell.settings";

    private static final String STATE_CONTAINERS = "moduleContainers";

    private SettingsChanges changes;
    private ModuleGuard guard;
    private List<ModuleSections.Section> sections = List.of();
    private int[] containers = new int[0];
    private boolean pendingModules;
    private View themeRow;
    private View autoLockRow;
    private View clipboardRow;

    /** 給 {@link FeatureHostActivity} 建立實例。 */
    public SettingsFragment() {
        super(R.layout.fragment_settings);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        SvaultApplication app = (SvaultApplication) requireActivity().getApplication();
        changes = app.settingsChanges();
        guard = app.modules().guard();
        LinearLayout body = view.findViewById(R.id.settings_body);
        Settings now = changes.current();

        SettingsRows.header(body, getString(R.string.settings_section_appearance));
        themeRow = SettingsRows.row(body, R.id.settings_theme_row, getString(R.string.settings_theme),
                getString(R.string.settings_theme_description), false, v -> chooseTheme());

        SettingsRows.header(body, getString(R.string.settings_section_security));
        autoLockRow = SettingsRows.row(body, R.id.settings_auto_lock_row, getString(R.string.settings_auto_lock),
                getString(R.string.settings_auto_lock_description), false,
                v -> chooseSeconds(R.string.settings_auto_lock, Settings.AUTO_LOCK_ANDROID_SECONDS,
                        SettingsChoices::backgroundLock, autoLockRow));
        clipboardRow = SettingsRows.row(body, R.id.settings_clipboard_row, getString(R.string.settings_clipboard),
                getString(R.string.settings_clipboard_description), false,
                v -> chooseSeconds(R.string.settings_clipboard, Settings.CLIPBOARD_CLEAR_SECONDS,
                        SettingsChoices::clipboard, clipboardRow));

        SettingsRows.header(body, getString(R.string.settings_section_backup));
        SettingsRows.row(body, R.id.settings_backup_row, getString(R.string.settings_backup),
                getString(R.string.settings_backup_description), true,
                v -> startActivity(BackupActivity.unlocked(requireContext())));

        addModuleSections(body, app, savedInstanceState);

        // 「功能模組」區段（T-38）：停用中的模組也列出，開關顯示設定裡的值（可能還沒重新啟動）
        List<ModuleToggle> toggles = app.modules().toggles(now);
        if (!toggles.isEmpty()) {
            SettingsRows.header(body, getString(R.string.settings_section_modules));
            ModuleToggleRows.add(body, toggles, f -> changes.change(f, this::saveFailed));
        }

        SettingsRows.header(body, getString(R.string.settings_section_about));
        SettingsRows.row(body, R.id.settings_about_row, getString(R.string.settings_about),
                getString(R.string.settings_about_description), true,
                v -> startActivity(FeatureHostActivity.intent(requireContext(), AboutFragment.PAGE_ID,
                        R.string.about_title, AboutFragment.class, null)));
        showValues(now);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (pendingModules) {
            pendingModules = false;
            loadModulePages();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 重建時（旋轉、換主題）FragmentManager 依容器 id 還原段落的佔位，所以沿用同樣的 id
        outState.putIntArray(STATE_CONTAINERS, containers);
    }

    private void showValues(Settings now) {
        SettingsRows.value(themeRow, getString(SettingsChoices.themeLabel(now.theme())));
        SettingsRows.value(autoLockRow, SettingsChoices.duration(now.autoLockAndroidSeconds()).text(requireContext()));
        SettingsRows.value(clipboardRow, SettingsChoices.duration(now.clipboardClearSeconds()).text(requireContext()));
    }

    private void chooseTheme() {
        List<String> options = SettingsChoices.THEMES;
        String current = changes.current().theme();
        choose(R.string.settings_theme, options.size(), i -> getString(SettingsChoices.themeLabel(options.get(i))),
                options.indexOf(current), i -> s -> s.withTheme(options.get(i)));
    }

    private void chooseSeconds(@StringRes int title, IntSetting setting, IntFunction<List<Integer>> optionsFor,
            View row) {
        int current = setting.read(changes.current());
        List<Integer> options = optionsFor.apply(current);
        choose(title, options.size(), i -> SettingsChoices.duration(options.get(i)).text(requireContext()),
                options.indexOf(current), i -> s -> setting.write(s, options.get(i)));
    }

    private void choose(@StringRes int title, int count, IntFunction<String> label, int checked,
            IntFunction<Function<Settings, Settings>> change) {
        CharSequence[] labels = new CharSequence[count];
        for (int i = 0; i < count; i++) {
            labels[i] = label.apply(i);
        }
        SecureWindows.inherit(new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    // 先關掉對話框：換主題會重建 Activity，開著的對話框會留在舊視窗
                    dialog.dismiss();
                    if (which != checked) {
                        Function<Settings, Settings> f = change.apply(which);
                        showValues(changes.change(f::apply, this::saveFailed));
                    }
                })
                .setNegativeButton(R.string.settings_cancel, null)
                .create()).show();
    }

    private void saveFailed() {
        View view = getView();
        if (view != null) {
            Toasts.show(view, getString(R.string.settings_save_failed), Toasts.Kind.ERROR);
        }
    }

    private void addModuleSections(LinearLayout body, SvaultApplication app, @Nullable Bundle saved) {
        sections = ModuleSections.collect(app.modules());
        int[] savedIds = saved == null ? null : saved.getIntArray(STATE_CONTAINERS);
        boolean restoring = savedIds != null && savedIds.length == sections.size();
        containers = new int[sections.size()];
        for (int i = 0; i < sections.size(); i++) {
            ModuleSections.Section s = sections.get(i);
            String title = s.titleRes() == 0 ? s.fallbackTitle()
                    : guard.call(s.moduleId(), "settingsTitle", () -> getString(s.titleRes()))
                            .orElse(s.fallbackTitle());
            SettingsRows.header(body, title);
            FrameLayout box = new FrameLayout(requireContext());
            box.setId(restoring ? savedIds[i] : View.generateViewId());
            body.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            containers[i] = box.getId();
        }
        // 重建時也重新載入：容器裡是 FeatureHostActivity 還原的佔位（或上次失敗時是空的），換成在錯誤邊界內新建的段落
        pendingModules = true;
    }

    private void loadModulePages() {
        FragmentManager fm = getChildFragmentManager();
        for (int i = 0; i < sections.size(); i++) {
            ModuleSections.Section s = sections.get(i);
            int container = containers[i];
            boolean ok = s.page().isPresent() && guard.run(s.moduleId(), "settingsSection", () -> {
                Fragment f = fm.getFragmentFactory().instantiate(requireContext().getClassLoader(),
                        s.page().get().getName());
                fm.beginTransaction().replace(container, f).commitNow();
            });
            if (!ok) {
                // 拿掉建立到一半的段落，只留錯誤文字
                guard.run(s.moduleId(), "removeSettingsSection", () -> {
                    Fragment broken = fm.findFragmentById(container);
                    if (broken != null) {
                        fm.beginTransaction().remove(broken).commitNowAllowingStateLoss();
                    }
                });
                showModuleError(i);
            }
        }
    }

    private void showModuleError(int index) {
        ViewGroup box = requireView().findViewById(containers[index]);
        box.removeAllViews();
        TextView error = (TextView) LayoutInflater.from(requireContext())
                .inflate(R.layout.settings_module_error, box, false);
        box.addView(error);
    }
}
