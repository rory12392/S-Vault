package app.svault.android.feature.health;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import app.svault.android.R;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.feature.health.HealthReportModel;
import app.svault.core.module.ModulePreferences;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 設定的「密碼健檢」段落（{@code SettingsSection}；T-53，同 desktop 的 T-52）：一列「上次檢查外洩」，
 * 值是 {@code yyyy/MM/dd HH:mm}（裝置時區）或「尚未檢查」（{@code modules.health.lastBreachCheck}）。只是顯示，不能點。
 * 每次回到設定頁都重讀（從健檢頁檢查完回來時會更新）。
 */
public class HealthSettingsFragment extends Fragment {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private TextView value;

    /** 給外殼的設定頁建立實例。 */
    public HealthSettingsFragment() {
        super(R.layout.hlt_settings);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View row = view.findViewById(R.id.hlt_settings_last_check);
        ((TextView) row.findViewById(R.id.settings_row_title)).setText(R.string.hlt_settings_last_check);
        TextView desc = row.findViewById(R.id.settings_row_description);
        desc.setText(R.string.hlt_settings_last_check_description);
        desc.setVisibility(View.VISIBLE);
        row.setClickable(false);
        value = row.findViewById(R.id.settings_row_value);
        value.setVisibility(View.VISIBLE);
    }

    @Override
    public void onResume() {
        super.onResume();
        ModulePreferences prefs = ModuleHost.of(requireContext()).preferences(HealthAndroidFeature.ID);
        value.setText(lastBreachCheck(prefs, ZoneId.systemDefault())
                .orElseGet(() -> getString(R.string.hlt_settings_last_check_never)));
    }

    /**
     * @param prefs 本模組的設定
     * @param zone  顯示用的時區
     * @return 上次檢查外洩的時間（{@code yyyy/MM/dd HH:mm}）；沒有紀錄時為空（顯示「尚未檢查」）
     */
    static Optional<String> lastBreachCheck(ModulePreferences prefs, ZoneId zone) {
        Optional<Long> millis = prefs.number(HealthReportModel.KEY_LAST_BREACH_CHECK);
        if (millis.isEmpty() || millis.get() <= 0) {
            return Optional.empty();
        }
        return Optional.of(TIME.format(Instant.ofEpochMilli(millis.get()).atZone(zone)));
    }
}
