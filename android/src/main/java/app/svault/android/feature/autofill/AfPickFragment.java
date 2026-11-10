package app.svault.android.feature.autofill;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.service.autofill.Dataset;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.svault.android.R;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.ui.ConfirmDialogs;
import app.svault.android.ui.EmptyState;
import app.svault.android.ui.EntryRow;
import app.svault.android.ui.Toasts;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.vault.EntryView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 自動填入的條目選擇頁（AC-10-3、AC-10-5，T-83）：建議清單的「搜尋保管庫」選了之後，由外殼的 {@code FeatureHostActivity}
 * 載入（上鎖時外殼先導向解鎖畫面）。列出保管庫裡有帳號或密碼的條目，可以搜尋；挑了之後把那一筆的 {@link Dataset} 交回系統
 * （{@link AutofillManager#EXTRA_AUTHENTICATION_RESULT}），系統直接填入。
 *
 * <p>挑的條目和請求不符（{@link PickCheck}；瀏覽器沒有網域時一律不符）時先顯示 danger 確認，按「仍要填入」才交回；
 * 取消就留在這一頁，什麼都不交回。查到的條目只放在清單裡，畫面結束就丟掉，不寫日誌。
 */
public class AfPickFragment extends Fragment {

    private static final Logger LOG = Logger.getLogger(AfPickFragment.class.getName());

    private final ModuleGuard guard = new ModuleGuard((moduleId, action, error) -> { });
    private final Rows rows = new Rows();
    private AuthArgs args;
    private PickCheck check;
    private View loading;
    private RecyclerView list;
    private EmptyState empty;
    private EditText search;
    // 不符的確認對話框（儀器測試按它的按鈕）
    AlertDialog warning;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.af_pick, container, false);
        loading = root.findViewById(R.id.af_pick_loading);
        list = root.findViewById(R.id.af_pick_list);
        empty = root.findViewById(R.id.af_pick_empty);
        search = root.findViewById(R.id.af_pick_search);
        list.setLayoutManager(new LinearLayoutManager(inflater.getContext()));
        list.setAdapter(rows);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Optional<AuthArgs> parsed = AuthArgs.from(getArguments());
        if (parsed.isEmpty() || !AutofillAndroidFeature.enabled(requireContext())) {
            // 不是由本模組的「搜尋保管庫」開的，或之後被停用：不給任何東西
            finish(Activity.RESULT_CANCELED, null);
            return;
        }
        args = parsed.get();
        check = new PickCheck(args.packageName(), args.fields().webDomain(), args.fields().webScheme(),
                new PackageSigners(requireContext()), AutofillAndroidFeature.pins(requireContext()));
        TextView source = view.findViewById(R.id.af_pick_source);
        source.setText(getString(R.string.af_pick_source, check.requestSource()));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                guard.run(AutofillAndroidFeature.ID, "pickSearch", () -> refresh());
            }
        });
        // 已上鎖時不會通知：外殼本來就會把這個畫面導回解鎖畫面；等待中上鎖也一樣
        ModuleHost.of(requireContext()).whenEntriesLoaded(requireContext().getMainExecutor(),
                () -> guard.run(AutofillAndroidFeature.ID, "pickList", this::refresh));
    }

    @Override
    public void onDestroyView() {
        rows.show(List.of(), null);
        super.onDestroyView();
    }

    private void refresh() {
        if (!isAdded() || getView() == null) {
            return;
        }
        Optional<UnlockedEntries> entries = ModuleHost.of(requireContext()).unlockedEntries();
        if (entries.isEmpty() || !entries.get().isOpen()) {
            // 通知之後又上鎖：外殼會關掉這一頁，這裡只是不顯示舊的內容
            return;
        }
        String query = search.getText() == null ? "" : search.getText().toString().trim();
        List<EntryView> found = PickCheck.fillable(entries.get().search(query));
        loading.setVisibility(View.GONE);
        rows.show(found, query.isEmpty() ? null : query);
        list.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
        empty.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
        if (found.isEmpty() && query.isEmpty()) {
            empty.show(R.drawable.sv_ic_search, getString(R.string.af_pick_empty_title),
                    getString(R.string.af_pick_empty_body), null, null);
        } else if (found.isEmpty()) {
            empty.show(R.drawable.sv_ic_search, getString(R.string.af_pick_none_title), null, null, null);
        }
    }

    private void onPick(EntryView entry) {
        if (check.matches(entry)) {
            deliver(entry, true);
            return;
        }
        // AC-10-5：不符的條目先警告；確認前不建任何帶帳密的結果
        warning = ConfirmDialogs.show(requireContext(), getString(R.string.af_pick_warn_title), warningText(entry),
                getString(R.string.af_pick_warn_confirm), true,
                (d, which) -> guard.run(AutofillAndroidFeature.ID, "pickConfirm", () -> deliver(entry, false)));
    }

    private String warningText(EntryView entry) {
        if (args.fields().webDomain() == null && Browsers.isBrowser(args.packageName())) {
            return getString(R.string.af_pick_warn_no_domain, args.packageName());
        }
        String entrySource = PickCheck.entrySource(entry);
        return entrySource == null ? getString(R.string.af_pick_warn_no_url, check.requestSource())
                : getString(R.string.af_pick_warn_message, entrySource, check.requestSource());
    }

    private void deliver(EntryView entry, boolean matched) {
        if (!isAdded() || getView() == null) {
            return;
        }
        Optional<UnlockedEntries> entries = ModuleHost.of(requireContext()).unlockedEntries();
        if (entries.isEmpty() || !entries.get().isOpen()) {
            // 挑選期間被上鎖：不交出先前查到的帳密
            finish(Activity.RESULT_CANCELED, null);
            return;
        }
        Dataset dataset = FillResponses.dataset(requireContext(), args.fields(), entry);
        if (dataset == null) {
            Toasts.show(requireView(), getString(R.string.af_pick_nothing_to_fill), Toasts.Kind.ERROR);
            return;
        }
        // 只記來源套件與是否符合
        LOG.log(Level.INFO, "自動填入手動挑選：package={0} matched={1}", new Object[] {args.packageName(), matched});
        finish(Activity.RESULT_OK, new Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset));
    }

    private void finish(int resultCode, @Nullable Intent data) {
        FragmentActivity activity = getActivity();
        if (activity != null && !activity.isFinishing()) {
            activity.setResult(resultCode, data);
            activity.finish();
        }
    }

    /** 條目清單（EntryRow 的一般／搜尋結果變體，不顯示複製鈕：這一頁只用來挑要填入的那一筆）。 */
    private final class Rows extends RecyclerView.Adapter<Rows.Holder> {

        private final List<EntryView> items = new ArrayList<>();
        private String query;

        // 每次都是整份換成新的搜尋結果（還有關鍵字標示），沒有可以局部更新的部分
        @SuppressLint("NotifyDataSetChanged")
        void show(List<EntryView> entries, @Nullable String keyword) {
            items.clear();
            items.addAll(entries);
            query = keyword;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            EntryRow row = new EntryRow(parent.getContext());
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOnCopyPasswordListener(null);
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            EntryView entry = items.get(position);
            String title = entry.title() == null || entry.title().isBlank()
                    ? getString(R.string.af_dataset_untitled) : entry.title();
            holder.row.bind(title, entry.username() == null ? "" : entry.username(), query);
            holder.row.setOnClickListener(v -> guard.run(AutofillAndroidFeature.ID, "pick", () -> onPick(entry)));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {

            final EntryRow row;

            Holder(EntryRow row) {
                super(row);
                this.row = row;
            }
        }
    }
}
