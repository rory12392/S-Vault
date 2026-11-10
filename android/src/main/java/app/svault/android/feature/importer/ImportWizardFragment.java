package app.svault.android.feature.importer;

import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import app.svault.android.R;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.ui.Banner;
import app.svault.android.ui.ProgressOverlay;
import app.svault.android.ui.Toasts;
import app.svault.core.feature.importer.ImportPlan;
import app.svault.core.feature.importer.ImportWizardModel;
import app.svault.core.feature.importer.ImportWizardModel.Busy;
import app.svault.core.feature.importer.ImportWizardModel.PreviewRow;
import app.svault.core.feature.importer.ImportWizardModel.Problem;
import app.svault.core.feature.importer.ImportWizardModel.Step;
import app.svault.core.module.ModuleGuard;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import java.util.List;

/**
 * 選單的「從 Chrome 匯入」（{@code MenuPage}；T-62，行為與文字對齊 desktop 的 T-61）。外殼的
 * {@code FeatureHostActivity} 載入，頁首與 {@code FLAG_SECURE} 由外殼提供。頂部步驟（1 選擇檔案 → 2 預覽 → 3 完成），依
 * {@link ImportWizardModel} 的狀態重畫；讀檔與匯入中用 {@link ProgressOverlay}；錯誤用 error Banner（模組拿不到外殼的
 * Toast 主機，D-IMP-05）。
 *
 * <p>選檔用系統的檔案選擇器（{@code ACTION_OPEN_DOCUMENT}，不取長期權限）。選擇器以固定的鍵登記在 Activity 的
 * {@code ActivityResultRegistry}：外殼重建時重新建立本頁（不由 FragmentManager 還原），用 Fragment 自己的登記會換鍵而收不到
 * 結果。狀態在 Activity 範圍的 {@link ImportViewModel}。每個操作都包在模組自己的 {@link ModuleGuard} 裡。
 */
public class ImportWizardFragment extends Fragment {

    /** 預覽的密碼：固定長度，不透露密碼長度（AC-14-1）。 */
    static final String MASK = "••••••••";
    /** 行號清單最多列幾個，其餘以「…」代替（幾千列格式錯誤時不把頁面撐爆）。 */
    static final int MAX_LINES_SHOWN = 20;
    /** 選擇器在 Activity 上的登記鍵（重建後同一個鍵才收得到結果）。 */
    static final String PICK_KEY = "app.svault.importer.pick-csv";
    /** 系統檔案選擇器顯示的類型：Chrome 匯出的是 {@code text/csv}，有些提供者標成其他類型。 */
    private static final String[] CSV_TYPES = {"text/*", "application/csv", "application/vnd.ms-excel",
        "application/octet-stream"};
    private static final String TAG = "SVault";

    private ImportWizardModel model;
    private ModuleGuard guard;
    private ActivityResultLauncher<String[]> picker;
    private NestedScrollView scroll;
    private TextView steps;
    private Banner problem;
    private View chooseSection;
    private View previewSection;
    private View doneSection;
    private ProgressOverlay overlay;
    /** 上一次畫的步驟：換步驟時捲回頂端。 */
    private Step rendered;

    /** 給 {@code FeatureHostActivity} 建立實例。 */
    public ImportWizardFragment() {
        super(R.layout.imp_page);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        guard = new ModuleGuard((id, action, error) -> {
            // 只記類型：例外訊息可能帶檔名或 CSV 的內容
            Log.w(TAG, "匯入出錯（" + action + "）：" + error.getClass().getName());
            if (getView() != null) {
                Toasts.show(getView(), getString(R.string.imp_error), Toasts.Kind.ERROR);
            }
        });
        picker = requireActivity().getActivityResultRegistry().register(PICK_KEY, this,
                new ActivityResultContracts.OpenDocument(),
                uri -> guard.run(ImporterAndroidFeature.ID, "picked", () -> onPicked(uri)));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        model = new ViewModelProvider(requireActivity()).get(ImportViewModel.class).model(requireContext());
        scroll = view.findViewById(R.id.imp_scroll);
        steps = view.findViewById(R.id.imp_steps);
        problem = view.findViewById(R.id.imp_problem);
        chooseSection = view.findViewById(R.id.imp_choose_section);
        previewSection = view.findViewById(R.id.imp_preview_section);
        doneSection = view.findViewById(R.id.imp_done_section);
        overlay = view.findViewById(R.id.imp_overlay);
        // 換步驟時 TalkBack 讀出目前在第幾步
        steps.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        view.findViewById(R.id.imp_choose).setOnClickListener(v ->
                guard.run(ImporterAndroidFeature.ID, "choose", () -> {
                    // D-192：選擇器蓋在上面不算離開，「立即」上鎖時選完回來檔案不會作廢。開成功才打招呼：開不了（丟例外）時
                    // 不留寬限（Codex M-04 審查待辦 2）；離開前景在之後的主執行緒迴圈才發生，來得及
                    picker.launch(CSV_TYPES);
                    ModuleHost.of(requireContext()).expectExternalPicker();
                }));
        view.findViewById(R.id.imp_rechoose).setOnClickListener(v ->
                guard.run(ImporterAndroidFeature.ID, "restart", model::restart));
        view.findViewById(R.id.imp_again).setOnClickListener(v ->
                guard.run(ImporterAndroidFeature.ID, "restart", model::restart));
        view.findViewById(R.id.imp_import).setOnClickListener(v ->
                guard.run(ImporterAndroidFeature.ID, "import", model::importNow));
        RadioGroup dup = view.findViewById(R.id.imp_dup_group);
        dup.setOnCheckedChangeListener((group, checkedId) -> guard.run(ImporterAndroidFeature.ID, "duplicates",
                () -> model.setSkipDuplicates(checkedId != R.id.imp_dup_keep)));

        model.setListener(() -> guard.run(ImporterAndroidFeature.ID, "render", this::render));
        render();
    }

    @Override
    public void onDestroyView() {
        if (model != null) {
            model.setListener(null);
        }
        super.onDestroyView();
    }

    /**
     * 選擇器的結果（儀器測試直接呼叫，系統選擇器不由測試操作）。
     *
     * @param uri 選到的文件；使用者取消時 {@code null}
     */
    void onPicked(@Nullable Uri uri) {
        if (uri == null || model == null) {
            return;
        }
        model.open(new CsvDocuments(requireContext(), uri));
    }

    /** 依狀態重畫整頁（狀態改變的次數很少：選檔、讀完、匯入、選擇重複的處理方式）。 */
    private void render() {
        if (getView() == null) {
            return;
        }
        Step step = model.step();
        renderSteps(step);
        Problem p = model.problem().orElse(null);
        problem.setVisibility(p == null ? View.GONE : View.VISIBLE);
        if (p != null) {
            problem.show(Banner.Kind.ERROR, getString(problemText(p)), null, null);
        }
        chooseSection.setVisibility(step == Step.CHOOSE ? View.VISIBLE : View.GONE);
        previewSection.setVisibility(step == Step.PREVIEW ? View.VISIBLE : View.GONE);
        doneSection.setVisibility(step == Step.DONE ? View.VISIBLE : View.GONE);
        switch (step) {
            case CHOOSE -> { }
            case PREVIEW -> renderPreview();
            case DONE -> renderDone();
            default -> throw new IllegalStateException(step.name());
        }
        if (model.busy() == Busy.NONE) {
            overlay.hide();
        } else {
            overlay.show(model.busy() == Busy.READING ? getString(R.string.imp_progress_reading)
                    : getString(R.string.imp_progress_importing, model.toImport()));
        }
        if (model.busy() == Busy.NONE && rendered != step) {
            scroll.scrollTo(0, 0);
            rendered = step;
        }
    }

    /** 「1 選擇檔案 → 2 預覽 → 3 完成」：目前這一步粗體＋primary；TalkBack 讀「第 n 步，共 3 步：…」。 */
    private void renderSteps(Step current) {
        int[] names = {R.string.imp_step_choose, R.string.imp_step_preview, R.string.imp_step_done};
        Step[] all = Step.values();
        int primary = MaterialColors.getColor(steps, androidx.appcompat.R.attr.colorPrimary);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < all.length; i++) {
            if (i > 0) {
                sb.append(' ').append(getString(R.string.imp_step_arrow)).append(' ');
            }
            int start = sb.length();
            sb.append(getString(names[i]));
            if (all[i] == current) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(primary), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        steps.setText(sb);
        steps.setContentDescription(getString(R.string.imp_step_current, current.ordinal() + 1, all.length,
                getString(names[current.ordinal()])));
    }

    private void renderPreview() {
        View v = requireView();
        ((TextView) v.findViewById(R.id.imp_total)).setText(getString(R.string.imp_preview_total, model.total()));
        ((TextView) v.findViewById(R.id.imp_source)).setText(model.name());

        List<Integer> skipped = model.skippedFormatLines();
        Banner skippedBanner = v.findViewById(R.id.imp_skipped);
        skippedBanner.setVisibility(skipped.isEmpty() ? View.GONE : View.VISIBLE);
        if (!skipped.isEmpty()) {
            skippedBanner.show(Banner.Kind.WARNING, getString(R.string.imp_preview_skipped, skipped.size(),
                    lines(skipped)), null, null);
        }

        List<Integer> dup = model.duplicateLines();
        v.findViewById(R.id.imp_duplicate_section).setVisibility(dup.isEmpty() ? View.GONE : View.VISIBLE);
        if (!dup.isEmpty()) {
            ((TextView) v.findViewById(R.id.imp_duplicates)).setText(getString(R.string.imp_duplicate_intro,
                    dup.size(), lines(dup)));
            ((TextView) v.findViewById(R.id.imp_dup_skip)).setText(getString(R.string.imp_duplicate_skip,
                    dup.size()));
            RadioGroup group = v.findViewById(R.id.imp_dup_group);
            int want = model.skipDuplicates() ? R.id.imp_dup_skip : R.id.imp_dup_keep;
            if (group.getCheckedRadioButtonId() != want) {
                group.check(want);
            }
        }

        List<PreviewRow> rows = model.preview();
        TextView first = v.findViewById(R.id.imp_first);
        first.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        first.setText(getString(R.string.imp_preview_first, rows.size()));
        LinearLayout list = v.findViewById(R.id.imp_preview);
        list.removeAllViews();
        for (PreviewRow r : rows) {
            list.addView(previewRow(list, r));
        }

        boolean nothing = model.toImport() == 0;
        v.findViewById(R.id.imp_nothing).setVisibility(nothing ? View.VISIBLE : View.GONE);
        MaterialButton go = v.findViewById(R.id.imp_import);
        go.setText(getString(R.string.imp_preview_import, ImportPlan.GROUP_NAME));
        go.setEnabled(!nothing);
    }

    private View previewRow(LinearLayout parent, PreviewRow r) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.imp_preview_row, parent, false);
        ((TextView) row.findViewById(R.id.imp_row_name)).setText(r.name());
        row.findViewById(R.id.imp_row_tag).setVisibility(r.duplicate() ? View.VISIBLE : View.GONE);
        ((TextView) row.findViewById(R.id.imp_row_username)).setText(getString(R.string.imp_preview_username,
                orBlank(r.username())));
        ((TextView) row.findViewById(R.id.imp_row_url)).setText(getString(R.string.imp_preview_url,
                orBlank(r.url())));
        TextView password = row.findViewById(R.id.imp_row_password);
        password.setText(getString(R.string.imp_preview_password, MASK));
        // TalkBack 不唸一串「點」
        password.setContentDescription(getString(R.string.imp_preview_masked));
        return row;
    }

    private void renderDone() {
        View v = requireView();
        ((TextView) v.findViewById(R.id.imp_done)).setText(getString(R.string.imp_done_title, model.imported()));
        ((TextView) v.findViewById(R.id.imp_done_group)).setText(getString(R.string.imp_done_group,
                ImportPlan.GROUP_NAME));
        ((Banner) v.findViewById(R.id.imp_delete_csv)).show(Banner.Kind.WARNING, getString(R.string.imp_done_warning),
                null, null);
        ((TextView) v.findViewById(R.id.imp_path)).setText(model.location());
    }

    private String orBlank(String value) {
        return value.isEmpty() ? getString(R.string.imp_preview_empty_value) : value;
    }

    /**
     * @param lines 行號
     * @return 「3、17」；超過 {@link #MAX_LINES_SHOWN} 個時列前面的再加「…」（同 desktop）
     */
    static String lines(List<Integer> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines.size(), MAX_LINES_SHOWN); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(lines.get(i));
        }
        if (lines.size() > MAX_LINES_SHOWN) {
            sb.append("、…");
        }
        return sb.toString();
    }

    static int problemText(Problem p) {
        return switch (p) {
            case READ_FAILED -> R.string.imp_problem_read_failed;
            case EMPTY -> R.string.imp_problem_empty;
            case MISSING_COLUMNS -> R.string.imp_problem_missing_columns;
            case NOT_UTF8 -> R.string.imp_problem_not_utf8;
            case TOO_LARGE -> R.string.imp_problem_too_large;
            case LOCKED -> R.string.imp_problem_locked;
            case CONFLICT -> R.string.imp_problem_conflict;
            case SAVE_FAILED -> R.string.imp_problem_save_failed;
        };
    }
}
