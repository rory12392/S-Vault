package app.svault.android.shell.settings;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import app.svault.android.R;
import app.svault.core.shell.Notices;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 關於／開源授權（T-24）。清單與授權全文是 core 資源檔裡的 {@code NOTICES.md} 複本
 * （{@link Notices}，D-124），在背景執行緒讀（CONVENTIONS §2：不在 UI 執行緒做 I/O）。展開一列才讀那一列的全文。
 */
public class AboutFragment extends Fragment {

    /** 錯誤邊界與 Top app bar 用的代號（外殼的頁面）。 */
    public static final String PAGE_ID = "shell.about";

    private static final String TAG = "SVault";
    private static final String STATE_EXPANDED = "expanded";

    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> new Thread(r, "svault-about"));
    private final Set<String> expanded = new HashSet<>();

    /** 給 {@code FeatureHostActivity} 建立實例。 */
    public AboutFragment() {
        super(R.layout.fragment_about);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            ArrayList<String> keys = savedInstanceState.getStringArrayList(STATE_EXPANDED);
            if (keys != null) {
                expanded.addAll(keys);
            }
        }
        ((TextView) view.findViewById(R.id.about_version))
                .setText(getString(R.string.about_version, versionName(requireContext())));
        ViewCompat.setAccessibilityHeading(view.findViewById(R.id.about_app_name), true);
        ViewCompat.setAccessibilityHeading(view.findViewById(R.id.about_licenses_title), true);
        reader.execute(() -> {
            List<Notices.Section> sections;
            try {
                sections = Notices.forPlatform(Notices.Platform.ANDROID);
            } catch (RuntimeException e) {
                // 資源檔不在 APK 裡（打包錯誤）：顯示錯誤，其他功能照常
                Log.w(TAG, "無法讀取開源授權清單：" + e.getClass().getName());
                sections = null;
            }
            List<Notices.Section> result = sections;
            view.post(() -> {
                if (getView() == view) {
                    show(view, result);
                }
            });
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_EXPANDED, new ArrayList<>(expanded));
    }

    @Override
    public void onDestroy() {
        reader.shutdownNow();
        super.onDestroy();
    }

    private void show(View view, @Nullable List<Notices.Section> sections) {
        view.findViewById(R.id.about_loading).setVisibility(View.GONE);
        if (sections == null || sections.isEmpty()) {
            view.findViewById(R.id.about_error).setVisibility(View.VISIBLE);
            return;
        }
        LinearLayout list = view.findViewById(R.id.about_licenses);
        for (Notices.Section section : sections) {
            SettingsRows.header(list, section.title());
            for (Notices.Item item : section.items()) {
                addItem(list, section.title() + "/" + item.name(), item);
            }
        }
    }

    private void addItem(LinearLayout list, String key, Notices.Item item) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.about_license_row, list, false);
        ((TextView) row.findViewById(R.id.about_license_name)).setText(item.name());
        ((TextView) row.findViewById(R.id.about_license_version))
                .setText(getString(R.string.about_item_version, item.version()));
        ((TextView) row.findViewById(R.id.about_license_license))
                .setText(getString(R.string.about_item_license, item.license()));
        ((TextView) row.findViewById(R.id.about_license_purpose))
                .setText(getString(R.string.about_item_purpose, item.purpose()));
        TextView text = row.findViewById(R.id.about_license_text);
        text.setContentDescription(getString(R.string.about_license_text, item.name()));
        View header = row.findViewById(R.id.about_license_header);
        header.setOnClickListener(v -> toggle(row, key, item));
        list.addView(row);
        setExpanded(row, item, expanded.contains(key));
    }

    private void toggle(View row, String key, Notices.Item item) {
        boolean open = !expanded.contains(key);
        if (open) {
            expanded.add(key);
        } else {
            expanded.remove(key);
        }
        setExpanded(row, item, open);
    }

    private void setExpanded(View row, Notices.Item item, boolean open) {
        View header = row.findViewById(R.id.about_license_header);
        ViewCompat.setStateDescription(header, getString(open ? R.string.about_expanded : R.string.about_collapsed));
        // 收合時箭頭朝下、展開時朝上（同一個 chevron_right 圖示轉向，不另加圖示）
        row.findViewById(R.id.about_license_chevron).setRotation(open ? -90 : 90);
        row.findViewById(R.id.about_license_details).setVisibility(open ? View.VISIBLE : View.GONE);
        TextView text = row.findViewById(R.id.about_license_text);
        if (open && text.length() == 0) {
            reader.execute(() -> {
                String full = licenseText(item);
                text.post(() -> text.setText(full));
            });
        }
    }

    private static String licenseText(Notices.Item item) {
        StringBuilder out = new StringBuilder();
        for (String id : item.licenseIds()) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            try {
                out.append(Notices.licenseText(id).trim());
            } catch (RuntimeException e) {
                Log.w(TAG, "無法讀取授權全文：" + id);
            }
        }
        return out.toString();
    }

    /**
     * 版本號（{@code versionName}）。專案沒有開 BuildConfig，所以從套件資訊讀，
     * 值相同（{@code android/build.gradle.kts} 的 {@code versionName}）。
     *
     * @param context 任一 Context
     * @return 版本號；讀不到時空字串
     */
    static String versionName(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = pm.getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                info = legacyPackageInfo(pm, context.getPackageName());
            }
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo legacyPackageInfo(PackageManager pm, String name)
            throws PackageManager.NameNotFoundException {
        return pm.getPackageInfo(name, 0);
    }
}
