package app.svault.android.shell.entries;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.DialogFragment;
import app.svault.android.R;
import app.svault.android.ui.DiffRow;
import app.svault.android.ui.SecureWindows;
import app.svault.android.ui.SystemBars;
import app.svault.core.shell.ConflictChoices;
import app.svault.core.sync.ConflictView;
import app.svault.core.sync.Side;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 衝突對照（US-08；T-16）：全螢幕對話框，掛在顯示 Banner 的條目畫面上。標題「解決衝突（第 n／N 筆）」
 * ＋條目名稱（放在內容裡、可換行：Top app bar 的標題在最大字體會被截斷）、「全部用這台裝置的」「全部用另一台裝置的」、說明、逐欄 {@link DiffRow}（只列衝突的欄位，AC-08-1）；
 * 「上一筆」「下一筆」逐筆處理（AC-08-4），最後一筆是「完成並儲存」；還有欄位沒選時捲到那一欄並提示
 * 「還有欄位沒有選擇」（固定在底部按鈕上方，捲動後也看得到）。關閉鈕（可讀名稱「稍後處理」）與返回鍵關閉不解決（AC-08-3：不寫遠端）。
 *
 * <p>選擇放在 {@link EntriesModel}（{@link ConflictChoices}），這裡只顯示與修改；旋轉、深色模式切換重建時從 model
 * 取回，頁數存在 {@code savedInstanceState}。寫回由 {@link EntriesActivity#resolveConflicts()}。對話框的視窗另外設
 * {@code FLAG_SECURE}（不繼承 Activity 的設定）。
 */
public final class ConflictDialog extends DialogFragment {

    /** FragmentManager 的標記。 */
    static final String TAG = "conflicts";
    private static final String STATE_INDEX = "index";

    private final List<DiffRow> shown = new ArrayList<>();
    private final ZoneId zone = ZoneId.systemDefault();
    @Nullable private ConflictChoices choices;
    private int index;
    private TextView heading;
    private TextView entryName;
    private NestedScrollView scroll;
    private TextView missing;
    private LinearLayout rows;
    private MaterialButton previous;
    private MaterialButton next;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 用 App 的主題（不是浮動視窗）：對話框佔滿整個畫面
        setStyle(STYLE_NORMAL, R.style.Theme_SVault);
        EntriesModel model = host().model();
        choices = model == null ? null : model.conflictChoices();
        if (savedInstanceState != null) {
            index = savedInstanceState.getInt(STATE_INDEX);
        } else if (choices != null) {
            // 從第一筆沒選完的開始（D-106）
            index = Math.max(0, choices.firstIncomplete());
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        Window window = dialog.getWindow();
        if (window != null) {
            // 在內容畫出來之前設定：對話框是另一個視窗，不受 Activity 的 FLAG_SECURE 保護（AC-12-4）
            SecureWindows.secure(window);
            WindowCompat.setDecorFitsSystemWindows(window, false);
        }
        return dialog;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.entries_conflict, container, false);
        SystemBars.applyInsets(root);
        MaterialToolbar toolbar = root.findViewById(R.id.conflict_toolbar);
        heading = root.findViewById(R.id.conflict_heading);
        entryName = root.findViewById(R.id.conflict_entry);
        scroll = root.findViewById(R.id.conflict_scroll);
        missing = root.findViewById(R.id.conflict_missing);
        rows = root.findViewById(R.id.conflict_rows);
        previous = root.findViewById(R.id.conflict_previous);
        next = root.findViewById(R.id.conflict_next);
        toolbar.setNavigationOnClickListener(v -> dismiss());
        root.<MaterialButton>findViewById(R.id.conflict_use_this).setOnClickListener(v -> chooseAll(Side.LOCAL));
        root.<MaterialButton>findViewById(R.id.conflict_use_other).setOnClickListener(v -> chooseAll(Side.REMOTE));
        previous.setOnClickListener(v -> show(index - 1));
        next.setOnClickListener(v -> advance());
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (choices == null || choices.view().items().isEmpty()) {
            // 重建時已上鎖（model 不在了）：不顯示任何內容
            dismissAllowingStateLoss();
            return;
        }
        show(index);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_INDEX, index);
    }

    // ---------- 換頁 ----------

    private void show(int target) {
        List<ConflictView.Item> items = choices.view().items();
        index = Math.max(0, Math.min(target, items.size() - 1));
        ConflictView.Item item = items.get(index);
        String title = getString(R.string.conflict_title, index + 1, items.size());
        heading.setText(title);
        if (getDialog() != null) {
            // 視窗標題：TalkBack 開啟時讀出
            getDialog().setTitle(title);
        }
        entryName.setText(item.title().isEmpty() ? getString(R.string.conflict_untitled) : item.title());
        setMissing(false);
        shown.clear();
        rows.removeAllViews();
        for (ConflictView.Row row : item.rows()) {
            DiffRow diff = new DiffRow(requireContext());
            diff.bind(ConflictTexts.fieldName(requireContext(), row),
                    ConflictTexts.shown(requireContext(), row.local(), zone),
                    ConflictTexts.time(requireContext(), row.localTime(), zone),
                    ConflictTexts.maskable(row.local().form()),
                    ConflictTexts.shown(requireContext(), row.remote(), zone),
                    ConflictTexts.time(requireContext(), row.remoteTime(), zone),
                    ConflictTexts.maskable(row.remote().form()), row.secret());
            Side chosen = choices.get(item.entryId(), row.key());
            if (chosen != null) {
                diff.setChoice(chosen == Side.LOCAL ? DiffRow.Choice.THIS_DEVICE : DiffRow.Choice.OTHER_DEVICE);
            }
            diff.setOnChoiceListener(c -> {
                if (c != DiffRow.Choice.NONE) {
                    choices.choose(item.entryId(), row.key(),
                            c == DiffRow.Choice.THIS_DEVICE ? Side.LOCAL : Side.REMOTE);
                    refreshMissing();
                }
            });
            shown.add(diff);
            rows.addView(diff);
        }
        previous.setEnabled(index > 0);
        boolean last = index == items.size() - 1;
        next.setText(last ? R.string.conflict_finish : R.string.conflict_next);
        scroll.scrollTo(0, 0);
        // TalkBack 換頁後先讀新的標題（第 n／N 筆）
        heading.post(() -> heading.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED));
    }

    private void advance() {
        if (choices.firstUnchosen(index).isPresent()) {
            showMissing();
            return;
        }
        int incomplete = choices.firstIncomplete();
        if (index < choices.view().items().size() - 1) {
            show(index + 1);
        } else if (incomplete >= 0) {
            // 最後一筆選完了，但前面還有沒選完的：回到那一筆
            show(incomplete);
            showMissing();
        } else if (host().resolveConflicts()) {
            dismiss();
        }
    }

    private void chooseAll(Side side) {
        choices.chooseAll(index, side);
        DiffRow.Choice ui = side == Side.LOCAL ? DiffRow.Choice.THIS_DEVICE : DiffRow.Choice.OTHER_DEVICE;
        shown.forEach(r -> r.setChoice(ui));
        refreshMissing();
    }

    // ---------- 還有欄位沒選 ----------

    private void showMissing() {
        setMissing(true);
        ConflictView.Item item = choices.view().items().get(index);
        for (int i = 0; i < item.rows().size(); i++) {
            shown.get(i).setMissing(choices.get(item.entryId(), item.rows().get(i).key()) == null);
        }
        choices.firstUnchosen(index).ifPresent(row -> {
            DiffRow target = shown.get(item.rows().indexOf(row));
            scroll.post(() -> {
                scroll.smoothScrollTo(0, Math.max(0, rows.getTop() + target.getTop()));
                target.focusChoice();
            });
        });
    }

    private void refreshMissing() {
        if (missing.getVisibility() == View.VISIBLE && choices.firstUnchosen(index).isEmpty()) {
            setMissing(false);
        }
    }

    private void setMissing(boolean value) {
        missing.setVisibility(value ? View.VISIBLE : View.GONE);
        if (!value) {
            shown.forEach(r -> r.setMissing(false));
        }
    }

    private EntriesActivity host() {
        return (EntriesActivity) requireActivity();
    }
}
