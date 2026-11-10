package app.svault.android.feature.health;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import app.svault.android.R;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.ui.Banner;
import app.svault.android.ui.ConfirmDialogs;
import app.svault.android.ui.EmptyState;
import app.svault.android.ui.ProgressOverlay;
import app.svault.android.ui.Toasts;
import app.svault.core.feature.health.HealthReportModel;
import app.svault.core.feature.health.HealthReportModel.Analysis;
import app.svault.core.feature.health.HealthReportModel.Breach;
import app.svault.core.feature.health.HealthReportModel.Row;
import app.svault.core.module.ModuleGuard;
import com.google.android.material.button.MaterialButton;
import java.util.List;
import java.util.UUID;

/**
 * 選單的「密碼健檢」（{@code MenuPage}；T-53，行為對齊 desktop 的 T-52）。外殼的 {@code FeatureHostActivity}
 * 載入，頁首與 {@code FLAG_SECURE} 由外殼提供。內容依序：摘要（弱、重複、外洩）→「檢查外洩」→ 連線失敗的 Banner →
 * 「外洩的密碼」（完成後）→「弱密碼」→「重複使用的密碼」（一組一個小標題）；都沒有時 EmptyState。每列是條目名稱＋原因，
 * 點列以 {@code ModuleHost.navigator} 開條目詳細（疊在本頁上，返回回到本頁；AC-13-1）。
 *
 * <p>狀態在 Activity 範圍的 {@link HealthViewModel}：重建時接著顯示結果或進度，不重算。外洩檢查前一定先確認（AC-13-2）。
 * 每個操作都包在模組自己的 {@link ModuleGuard} 裡；出錯時 error 提示，頁面照常。
 */
public class HealthReportFragment extends Fragment {

    private static final String TAG = "SVault";

    private HealthReportModel model;
    private ModuleGuard guard;
    private View summaryWeak;
    private View summaryReused;
    private View summaryBreached;
    private MaterialButton checkBreach;
    private Banner breachFailed;
    private Banner analyzeFailed;
    private LinearLayout sections;
    private EmptyState empty;
    private ProgressOverlay overlay;
    /** 顯示中的確認對話框（儀器測試按鈕用）；沒有時 {@code null}。 */
    @Nullable AlertDialog confirm;

    /** 給 {@code FeatureHostActivity} 建立實例。 */
    public HealthReportFragment() {
        super(R.layout.hlt_page);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        guard = new ModuleGuard((id, action, error) -> {
            // 只記類型：例外訊息可能帶網址或條目內容
            Log.w(TAG, "健檢出錯（" + action + "）：" + error.getClass().getName());
            if (getView() != null) {
                Toasts.show(getView(), getString(R.string.hlt_error), Toasts.Kind.ERROR);
            }
        });
        HealthViewModel vm = new ViewModelProvider(requireActivity()).get(HealthViewModel.class);
        model = vm.model(requireContext());

        summaryWeak = summaryRow(view, R.id.hlt_summary_weak, R.string.hlt_summary_weak,
                R.string.hlt_summary_weak_description);
        summaryReused = summaryRow(view, R.id.hlt_summary_reused, R.string.hlt_summary_reused,
                R.string.hlt_summary_reused_description);
        summaryBreached = summaryRow(view, R.id.hlt_summary_breached, R.string.hlt_summary_breached,
                R.string.hlt_summary_breached_description);
        checkBreach = view.findViewById(R.id.hlt_check_breach);
        breachFailed = view.findViewById(R.id.hlt_breach_failed);
        analyzeFailed = view.findViewById(R.id.hlt_analyze_failed);
        sections = view.findViewById(R.id.hlt_sections);
        empty = view.findViewById(R.id.hlt_empty);
        overlay = view.findViewById(R.id.hlt_overlay);
        checkBreach.setOnClickListener(v -> guard.run(HealthAndroidFeature.ID, "checkBreach", this::confirmBreach));

        model.setListener(new HealthReportModel.Listener() {
            @Override
            public void changed() {
                guard.run(HealthAndroidFeature.ID, "render", HealthReportFragment.this::render);
            }

            @Override
            public void breachProgress(int done, int total) {
                guard.run(HealthAndroidFeature.ID, "progress", () -> showBreachProgress(done, total));
            }
        });
        render();
        if (vm.start()) {
            // 剛解鎖時條目畫面可能還在載入：載入好才算（已上鎖時外殼本來就會導回解鎖畫面）
            HealthReportModel m = model;
            ModuleHost.of(requireContext()).whenEntriesLoaded(requireContext().getMainExecutor(),
                    () -> guard.run(HealthAndroidFeature.ID, "analyze", m::analyze));
        }
    }

    @Override
    public void onDestroyView() {
        if (model != null) {
            model.setListener(null);
        }
        if (confirm != null) {
            // 重建時對話框不還原（使用者再按一次「檢查外洩」），不讓舊視窗洩漏
            confirm.dismiss();
        }
        super.onDestroyView();
    }

    /** 「檢查外洩」：先確認（會連網、只送雜湊前 5 碼），確認後才開始（AC-13-2）。 */
    private void confirmBreach() {
        HealthReportModel m = model;
        confirm = ConfirmDialogs.show(requireContext(), getString(R.string.hlt_breach_confirm_title),
                getString(R.string.hlt_breach_confirm_body), getString(R.string.hlt_breach_confirm_ok), false,
                (d, which) -> guard.run(HealthAndroidFeature.ID, "checkBreach", m::checkBreach));
        confirm.setOnDismissListener(d -> confirm = null);
    }

    private void showBreachProgress(int done, int total) {
        if (model.breach() != Breach.RUNNING || getView() == null) {
            return;
        }
        String message = getString(R.string.hlt_progress_breach, done, total);
        if (total == 0) {
            overlay.show(message);
        } else {
            overlay.showProgress(message, (int) (100L * done / total));
        }
    }

    /** 依狀態重畫整頁（狀態改變的次數很少：開始、完成）。 */
    private void render() {
        if (getView() == null) {
            return;
        }
        boolean ready = model.analysis() == Analysis.READY;
        value(summaryWeak, ready ? count(model.weakRows().size()) : "");
        value(summaryReused, ready ? count(model.reusedCount()) : "");
        value(summaryBreached, switch (model.breach()) {
            case NOT_CHECKED, RUNNING -> getString(R.string.hlt_summary_not_checked);
            case COMPLETED -> count(model.breachRows().size());
            case CONNECTION_FAILED -> getString(R.string.hlt_summary_connection_failed);
        });
        checkBreach.setEnabled(model.breach() != Breach.RUNNING);
        show(breachFailed, model.breach() == Breach.CONNECTION_FAILED, Banner.Kind.WARNING,
                R.string.hlt_breach_failed);
        show(analyzeFailed, model.analysis() == Analysis.FAILED, Banner.Kind.ERROR, R.string.hlt_error_analyze);

        sections.removeAllViews();
        if (model.breach() == Breach.COMPLETED) {
            header(R.string.hlt_section_breached);
            if (model.breachRows().isEmpty()) {
                View clean = row(getString(R.string.hlt_breach_clean), null);
                clean.setClickable(false);
            } else {
                entryRows(model.breachRows());
            }
        }
        boolean clean = ready && model.isClean();
        if (ready && !clean) {
            if (!model.weakRows().isEmpty()) {
                header(R.string.hlt_section_weak);
                entryRows(model.weakRows());
            }
            if (!model.reusedGroups().isEmpty()) {
                header(R.string.hlt_section_reused);
                for (List<Row> group : model.reusedGroups()) {
                    TextView label = (TextView) LayoutInflater.from(requireContext())
                            .inflate(R.layout.hlt_group_label, sections, false);
                    label.setText(getString(R.string.hlt_reused_group, group.size()));
                    sections.addView(label);
                    entryRows(group);
                }
            }
        }
        empty.setVisibility(clean ? View.VISIBLE : View.GONE);
        if (clean) {
            empty.show(R.drawable.sv_ic_check_circle, getString(R.string.hlt_empty_title),
                    getString(R.string.hlt_empty_description), null, null);
        }

        if (model.analysis() == Analysis.RUNNING) {
            overlay.show(getString(R.string.hlt_progress_analyzing));
        } else if (model.breach() == Breach.RUNNING) {
            showBreachProgress(model.breachDone(), model.breachTotal());
        } else {
            overlay.hide();
        }
    }

    private void header(int titleRes) {
        TextView header = (TextView) LayoutInflater.from(requireContext())
                .inflate(R.layout.settings_section_header, sections, false);
        header.setText(titleRes);
        ViewCompat.setAccessibilityHeading(header, true);
        sections.addView(header);
    }

    private void entryRows(List<Row> rows) {
        for (Row r : rows) {
            String title = r.title().isEmpty() ? getString(R.string.hlt_row_untitled) : r.title();
            View row = row(title, reason(r));
            row.findViewById(R.id.settings_row_chevron).setVisibility(View.VISIBLE);
            UUID entryId = r.entryId();
            row.setOnClickListener(v -> openEntry(entryId));
            // TalkBack：「輕觸兩下即可跳到條目：名稱」
            ViewCompat.replaceAccessibilityAction(row, AccessibilityActionCompat.ACTION_CLICK,
                    getString(R.string.hlt_row_open, title), null);
        }
    }

    private View row(CharSequence title, @Nullable CharSequence description) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.settings_row, sections, false);
        ((TextView) row.findViewById(R.id.settings_row_title)).setText(title);
        TextView desc = row.findViewById(R.id.settings_row_description);
        if (description != null) {
            desc.setText(description);
            desc.setVisibility(View.VISIBLE);
        }
        sections.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private String reason(Row r) {
        return switch (r.reason()) {
            case WEAK -> getString(R.string.hlt_reason_weak);
            case REUSED -> getString(R.string.hlt_reason_reused, r.count());
            case BREACHED -> getString(R.string.hlt_reason_breached, r.count());
        };
    }

    private void openEntry(UUID entryId) {
        guard.run(HealthAndroidFeature.ID, "openEntry",
                () -> ModuleHost.of(requireContext()).navigator(requireActivity()).openEntry(entryId));
    }

    private static View summaryRow(View root, int id, int titleRes, int descriptionRes) {
        View row = root.findViewById(id);
        ((TextView) row.findViewById(R.id.settings_row_title)).setText(titleRes);
        TextView desc = row.findViewById(R.id.settings_row_description);
        desc.setText(descriptionRes);
        desc.setVisibility(View.VISIBLE);
        // 摘要只是顯示：不能點，但保留可聚焦，TalkBack 把標題、說明、值合起來讀
        row.setClickable(false);
        return row;
    }

    private static void value(View row, CharSequence value) {
        TextView v = row.findViewById(R.id.settings_row_value);
        v.setText(value);
        v.setVisibility(value.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void show(Banner banner, boolean visible, Banner.Kind kind, int messageRes) {
        banner.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            banner.show(kind, getString(messageRes), null, null);
        }
    }

    private String count(long n) {
        return getString(R.string.hlt_summary_count, n);
    }
}
