package app.svault.android.shell.entries;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.svault.android.R;
import app.svault.android.ui.EmptyState;
import app.svault.android.ui.SystemBars;
import app.svault.core.shell.ScreenData;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import java.util.Optional;
import java.util.UUID;

/**
 * 條目清單：首頁點了「全部條目」、一個群組或「資源回收桶」之後。每列＝EntryRow（整列開詳細、
 * 複製密碼鈕 48dp）；空狀態（AC-02-6）；頂部 SearchBar 搜尋全部條目；右下 FAB「新增條目」（回收桶裡不顯示）。
 */
public class EntryListActivity extends EntriesActivity {

    private static final String EXTRA_KIND = "app.svault.android.extra.SCOPE_KIND";
    private static final String EXTRA_GROUP = "app.svault.android.extra.GROUP";

    private View root;
    private MaterialToolbar toolbar;
    private RecyclerView list;
    private View emptyScroll;
    private EmptyState empty;
    private View loading;
    private ExtendedFloatingActionButton add;
    private EntryAdapter adapter;
    private SearchPanel search;
    private Listing.Scope scope;
    @Nullable private ScreenData data;
    private boolean canAdd;

    /**
     * @param context 目前的畫面
     * @param scope   要列出的範圍
     * @return 開啟條目清單的 Intent
     */
    static Intent intent(Context context, Listing.Scope scope) {
        Intent i = new Intent(context, EntryListActivity.class).putExtra(EXTRA_KIND, scope.kind().name());
        if (scope.groupId() != null) {
            i.putExtra(EXTRA_GROUP, scope.groupId().toString());
        }
        return i;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entries_page);
        root = findViewById(R.id.page_root);
        SystemBars.applyInsets(root);
        toolbar = findViewById(R.id.page_toolbar);
        setUpToolbar(toolbar, true);
        EntriesModel model = model();
        if (model == null) {
            return;
        }
        String group = getIntent().getStringExtra(EXTRA_GROUP);
        scope = new Listing.Scope(Listing.Scope.Kind.valueOf(getIntent().getStringExtra(EXTRA_KIND)),
                group == null ? null : UUID.fromString(group));
        EntryAdapter.Actions actions = new EntryAdapter.Actions() {
            @Override
            public void open(EntryView entry) {
                startActivity(EntryDetailActivity.intent(EntryListActivity.this, entry.uuid()));
            }

            @Override
            public void copyPassword(EntryView entry) {
                EntryListActivity.this.copyPassword(entry);
            }
        };
        adapter = new EntryAdapter(actions);
        list = findViewById(R.id.page_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        emptyScroll = findViewById(R.id.page_empty_scroll);
        empty = findViewById(R.id.page_empty);
        loading = findViewById(R.id.page_loading);
        add = findViewById(R.id.page_add);
        add.setOnClickListener(v -> startNew());
        Pages.padForFab(list, add);
        search = new SearchPanel(this, root, actions);
        bindStatus(findViewById(R.id.page_banner), findViewById(R.id.page_saving));
        // 還沒有內容時 LiveData 不會通知：先畫一次「正在載入條目…」
        show(model.data().getValue());
        model.data().observe(this, this::show);
        model.ensureLoaded();
    }

    @Override
    protected View snackbarAnchor() {
        return root;
    }

    @Override
    protected void onSavingChanged(boolean saving) {
        add.setEnabled(!saving);
    }

    private void show(@Nullable ScreenData current) {
        data = current;
        loading.setVisibility(current == null ? View.VISIBLE : View.GONE);
        if (current == null) {
            list.setVisibility(View.GONE);
            emptyScroll.setVisibility(View.GONE);
            return;
        }
        Optional<GroupView> group = current.group(scope.groupId());
        if (scope.kind() == Listing.Scope.Kind.GROUP && !group.isPresent()) {
            // 群組已被永久刪除
            finish();
            return;
        }
        toolbar.setTitle(switch (scope.kind()) {
            case ALL -> getString(R.string.entries_all);
            case BIN -> getString(R.string.entries_recycle_bin);
            default -> group.get().name();
        });
        boolean inBin = scope.kind() == Listing.Scope.Kind.BIN
                || scope.kind() == Listing.Scope.Kind.GROUP && current.inRecycleBin(scope.groupId());
        canAdd = !inBin;
        add.setVisibility(canAdd ? View.VISIBLE : View.GONE);
        Listing listing = Listing.of(current, scope, "");
        adapter.submit(listing, current, null);
        boolean none = listing.empty() != Listing.Empty.NONE;
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        emptyScroll.setVisibility(none ? View.VISIBLE : View.GONE);
        if (none) {
            showEmpty(listing.empty());
        }
        search.setData(current);
    }

    private void showEmpty(Listing.Empty kind) {
        switch (kind) {
            case VAULT -> empty.show(R.drawable.sv_ic_key, getString(R.string.entries_empty_vault_title),
                    getString(R.string.entries_empty_vault_body), getString(R.string.entries_add), v -> startNew());
            case RECYCLE_BIN -> empty.show(R.drawable.sv_ic_delete, getString(R.string.entries_empty_bin_title),
                    getString(R.string.entries_empty_bin_body), null, null);
            default -> empty.show(R.drawable.sv_ic_folder, getString(R.string.entries_empty_group_title), null,
                    getString(R.string.entries_add), v -> startNew());
        }
    }

    /** 新增條目：放在目前的群組；「全部條目」時放根群組。 */
    private void startNew() {
        EntriesModel model = model();
        if (data == null || !canAdd || model == null || model.busy()) {
            return;
        }
        UUID target = scope.kind() == Listing.Scope.Kind.GROUP ? scope.groupId() : data.root().uuid();
        startActivity(EntryEditActivity.newEntry(this, target));
    }
}
