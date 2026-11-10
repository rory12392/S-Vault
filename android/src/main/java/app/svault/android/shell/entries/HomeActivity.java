package app.svault.android.shell.entries;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.svault.android.R;
import app.svault.android.shell.AfterUnlockPages;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.ModuleMenus;
import app.svault.android.shell.settings.AboutFragment;
import app.svault.android.shell.settings.SettingsFragment;
import app.svault.android.shell.start.BackupActivity;
import app.svault.android.shell.start.StartActions;
import app.svault.android.ui.ConfirmDialogs;
import app.svault.android.ui.SystemBars;
import app.svault.android.ui.Toasts;
import app.svault.core.shell.ScreenData;
import app.svault.core.shell.VaultOps;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 解鎖後的首頁＝群組清單（D-20）：「全部條目」＋各群組（條目數）＋「資源回收桶」；
 * 點群組進條目清單；長按群組：新增群組、重新命名、刪除（回收桶裡的群組：還原群組、永久刪除）。頂部 SearchBar 搜尋
 * 全部條目，右下 FAB「新增條目」（放在根群組）。
 */
public class HomeActivity extends EntriesActivity {

    /** {@code true}：剛建立保管庫，顯示「已建立保管庫」。 */
    public static final String EXTRA_CREATED = "app.svault.android.extra.CREATED";
    /** {@code true}：剛從備份還原（T-12），顯示「已還原」。 */
    public static final String EXTRA_RESTORED = "app.svault.android.extra.RESTORED";

    private static final String JOB_ADD_GROUP = "addGroup";
    private static final String JOB_RENAME = "rename";
    private static final String JOB_DELETE_GROUP = "deleteGroup";
    private static final String JOB_RESTORE_GROUP = "restoreGroup";
    private static final String JOB_PURGE_GROUP = "purgeGroup";
    /** 模組頁面的選單項目 id（資源 id 都是 0x7f…，不會撞到）與排序（entries_menu.xml 留的 200 起）。 */
    private static final int MODULE_ITEM = 1000;
    private static final int MODULE_ORDER = 200;

    private final GroupAdapter adapter = new GroupAdapter();
    private View root;
    private RecyclerView list;
    private View loading;
    private ExtendedFloatingActionButton add;
    private SearchPanel search;
    @Nullable private ScreenData data;
    private List<ModuleMenus.Item> modulePages = List.of();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entries_page);
        root = findViewById(R.id.page_root);
        SystemBars.applyInsets(root);
        MaterialToolbar toolbar = findViewById(R.id.page_toolbar);
        setUpToolbar(toolbar, false);
        EntriesModel model = model();
        if (model == null) {
            return;
        }
        toolbar.setTitle(StartActions.title(app().vaultSession().location().displayName()));
        toolbar.getMenu().findItem(R.id.entries_add_group).setVisible(true);
        toolbar.getMenu().findItem(R.id.entries_restore).setVisible(true);
        toolbar.getMenu().findItem(R.id.entries_settings).setVisible(true);
        toolbar.getMenu().findItem(R.id.entries_about).setVisible(true);
        addModulePages(toolbar.getMenu());
        list =findViewById(R.id.page_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        loading = findViewById(R.id.page_loading);
        add = findViewById(R.id.page_add);
        add.setOnClickListener(v -> {
            if (data != null) {
                startActivity(EntryEditActivity.newEntry(this, data.root().uuid()));
            }
        });
        Pages.padForFab(list, add);
        search = new SearchPanel(this, root, new EntryAdapter.Actions() {
            @Override
            public void open(EntryView entry) {
                startActivity(EntryDetailActivity.intent(HomeActivity.this, entry.uuid()));
            }

            @Override
            public void copyPassword(EntryView entry) {
                HomeActivity.this.copyPassword(entry);
            }
        });
        bindStatus(findViewById(R.id.page_banner), findViewById(R.id.page_saving));
        // 還沒有內容時 LiveData 不會通知：先畫一次「正在載入條目…」
        show(model.data().getValue());
        model.data().observe(this, this::show);
        model.ensureLoaded();
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_CREATED, false)) {
            model.notice(getString(R.string.create_created), Toasts.Kind.SUCCESS);
        }
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_RESTORED, false)) {
            model.notice(getString(R.string.restore_done), Toasts.Kind.SUCCESS);
        }
        if (savedInstanceState == null) {
            WeakKdfCheck.maybeShow(this, app().vaultSession(), app().worker(), model);
            AfterUnlockPages.openFirst(this, app().modules());
        }
    }

    @Override
    protected View snackbarAnchor() {
        return root;
    }

    /**
     * 功能模組的頁面（T-30，{@link ModuleMenus}）：排在「新增群組」之後、「備份還原」之前（DESIGN §4 的順序：產生器、
     * 健檢、匯入、備份還原、設定、關於）。
     */
    private void addModulePages(Menu menu) {
        modulePages = ModuleMenus.items(app().modules(), this::getText);
        for (int i = 0; i < modulePages.size(); i++) {
            menu.add(Menu.NONE, MODULE_ITEM + i, MODULE_ORDER + i, modulePages.get(i).title());
        }
    }

    @Override
    protected boolean onToolbarItem(int itemId) {
        int page = itemId - MODULE_ITEM;
        if (page >= 0 && page < modulePages.size()) {
            ModuleMenus.Item item = modulePages.get(page);
            startActivity(FeatureHostActivity.intent(this, item.moduleId(), item.title(), item.pageClass()));
            return true;
        }
        if (itemId == R.id.entries_add_group && data != null && !busy()) {
            addGroup(data.root().uuid());
            return true;
        }
        if (itemId == R.id.entries_restore && !busy()) {
            startActivity(BackupActivity.unlocked(this));
            return true;
        }
        // 設定與關於是外殼的頁面，放在 FeatureHostActivity 裡（不改 manifest；FLAG_SECURE 與上鎖導回同條目畫面）
        if (itemId == R.id.entries_settings) {
            startActivity(FeatureHostActivity.intent(this, SettingsFragment.PAGE_ID, R.string.settings_title,
                    SettingsFragment.class, null));
            return true;
        }
        if (itemId == R.id.entries_about) {
            startActivity(FeatureHostActivity.intent(this, AboutFragment.PAGE_ID, R.string.about_title,
                    AboutFragment.class, null));
            return true;
        }
        return false;
    }

    @Override
    protected void onSavingChanged(boolean saving) {
        add.setEnabled(!saving);
    }

    private boolean busy() {
        EntriesModel m = model();
        return m == null || m.busy();
    }

    private void show(@Nullable ScreenData current) {
        data = current;
        loading.setVisibility(current == null ? View.VISIBLE : View.GONE);
        list.setVisibility(current == null ? View.GONE : View.VISIBLE);
        if (current == null) {
            return;
        }
        adapter.submit(GroupRows.of(current));
        search.setData(current);
    }

    private void openRow(GroupRows.Row row) {
        Listing.Scope scope = switch (row.kind()) {
            case ALL -> Listing.Scope.all();
            case BIN -> Listing.Scope.bin();
            default -> Listing.Scope.group(row.groupId());
        };
        startActivity(EntryListActivity.intent(this, scope));
    }

    private void showMenu(View anchor, GroupRows.Row row) {
        if (data == null || busy() || row.kind() == GroupRows.Kind.ALL || row.kind() == GroupRows.Kind.BIN) {
            return;
        }
        PopupMenu menu = new PopupMenu(this, anchor);
        Menu m = menu.getMenu();
        if (row.kind() == GroupRows.Kind.GROUP) {
            m.add(0, 1, 0, R.string.entries_group_add);
            m.add(0, 2, 1, R.string.entries_group_rename);
            m.add(0, 3, 2, R.string.entries_group_delete);
        } else {
            m.add(0, 4, 0, R.string.entries_group_restore);
            m.add(0, 5, 1, R.string.entries_group_delete_permanently);
        }
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1 -> addGroup(row.groupId());
                case 2 -> GroupDialogs.showName(this, true, row.name(),
                        name -> runOp(JOB_RENAME, ops -> ops.renameGroup(row.groupId(), name)));
                case 3 -> confirmDelete(row, false);
                case 4 -> runOp(JOB_RESTORE_GROUP, ops -> ops.restoreGroup(row.groupId()));
                default -> confirmDelete(row, true);
            }
            return true;
        });
        menu.show();
    }

    private void addGroup(UUID parent) {
        GroupDialogs.showName(this, false, "", name -> runOp(JOB_ADD_GROUP, ops -> ops.addGroup(parent, name)));
    }

    /** AC-02-4：刪除含有條目的群組前提示條目數（含子群組）並確認。 */
    private void confirmDelete(GroupRows.Row row, boolean permanently) {
        int n = data.query().countInGroup(row.groupId());
        CharSequence title = getString(permanently ? R.string.entries_confirm_delete_group_permanently_title
                : R.string.entries_confirm_delete_group_title, row.name());
        CharSequence body;
        if (permanently) {
            body = n == 0 ? getString(R.string.entries_confirm_delete_group_permanently_body_empty)
                    : getString(R.string.entries_confirm_delete_group_permanently_body, n);
        } else {
            body = n == 0 ? getString(R.string.entries_confirm_delete_group_body_empty)
                    : getString(R.string.entries_confirm_delete_group_body, n);
        }
        CharSequence confirm = getString(permanently ? R.string.entries_confirm_delete_permanently
                : R.string.entries_confirm_delete);
        ConfirmDialogs.show(this, title, body, confirm, true, (d, w) -> {
            if (permanently) {
                runOp(JOB_PURGE_GROUP, ops -> ops.deleteGroupPermanently(row.groupId()));
            } else {
                runOp(JOB_DELETE_GROUP, ops -> ops.recycleGroup(row.groupId()));
            }
        });
    }

    @Override
    protected void onOpDone(String tag, VaultOps.Outcome out) {
        if (!out.saved()) {
            // 修改在記憶體，「尚未同步」Banner 會說明
            return;
        }
        String name = out.data().group(out.subject()).map(GroupView::name).orElse("");
        int message = switch (tag) {
            case JOB_ADD_GROUP -> R.string.entries_toast_group_added;
            case JOB_RENAME -> R.string.entries_toast_group_renamed;
            case JOB_RESTORE_GROUP -> R.string.entries_toast_restored;
            case JOB_PURGE_GROUP -> R.string.entries_toast_deleted_permanently;
            default -> out.recycled() ? R.string.entries_toast_recycled : R.string.entries_toast_deleted_no_bin;
        };
        Toasts.show(root, getString(message, name), Toasts.Kind.SUCCESS);
    }

    private final class GroupAdapter extends RecyclerView.Adapter<GroupHolder> {

        private final List<GroupRows.Row> rows = new ArrayList<>();

        void submit(List<GroupRows.Row> next) {
            rows.clear();
            rows.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public GroupHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new GroupHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.entries_group_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull GroupHolder holder, int position) {
            GroupRows.Row row = rows.get(position);
            String name = switch (row.kind()) {
                case ALL -> getString(R.string.entries_all);
                case BIN -> getString(R.string.entries_recycle_bin);
                default -> row.name();
            };
            holder.name.setText(name);
            holder.count.setText(getString(R.string.entries_count, row.count()));
            holder.icon.setImageResource(row.kind() == GroupRows.Kind.ALL ? R.drawable.sv_ic_key
                    : row.kind() == GroupRows.Kind.BIN ? R.drawable.sv_ic_delete : R.drawable.sv_ic_folder);
            int indent = getResources().getDimensionPixelSize(R.dimen.sv_space_xl) * row.depth();
            holder.itemView.setPaddingRelative(getResources().getDimensionPixelSize(R.dimen.sv_space_lg) + indent,
                    0, holder.itemView.getPaddingEnd(), 0);
            holder.itemView.setContentDescription(getString(R.string.entries_group_accessible, name, row.count()));
            holder.itemView.setOnClickListener(v -> openRow(row));
            boolean hasMenu = row.kind() == GroupRows.Kind.GROUP || row.kind() == GroupRows.Kind.RECYCLED;
            if (hasMenu) {
                holder.itemView.setOnLongClickListener(v -> {
                    showMenu(v, row);
                    return true;
                });
                // TalkBack 讀出「點兩下並按住可開啟群組選項」
                ViewCompat.replaceAccessibilityAction(holder.itemView,
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                        getString(R.string.entries_group_options), null);
            } else {
                holder.itemView.setOnLongClickListener(null);
                holder.itemView.setLongClickable(false);
                ViewCompat.replaceAccessibilityAction(holder.itemView,
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK, null, null);
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class GroupHolder extends RecyclerView.ViewHolder {

        final ImageView icon;
        final TextView name;
        final TextView count;

        GroupHolder(View row) {
            super(row);
            icon = row.findViewById(R.id.group_icon);
            name = row.findViewById(R.id.group_name);
            count = row.findViewById(R.id.group_count);
        }
    }
}
