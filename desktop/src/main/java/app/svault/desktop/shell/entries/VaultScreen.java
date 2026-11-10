package app.svault.desktop.shell.entries;

import app.svault.core.module.ModuleRegistry;
import app.svault.core.security.PasswordStrength;
import app.svault.core.session.ClipboardGuard;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.PendingEdit;
import app.svault.core.shell.ScreenData;
import app.svault.core.shell.VaultOps;
import app.svault.core.sync.Side;
import app.svault.core.vault.EntryView;
import app.svault.desktop.shell.AppShell;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.VaultWorker;
import app.svault.desktop.ui.GroupTree;
import app.svault.desktop.ui.Shortcut;
import app.svault.desktop.ui.ShortcutRegistry;
import app.svault.desktop.ui.Toast;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.StackPane;

/**
 * 解鎖後的三欄（US-02、US-03）：群組樹｜條目清單＋搜尋｜詳細／編輯。
 *
 * <p>畫面只讀 {@link ScreenData} 快照；所有寫入經 {@link VaultOps} 排進同一個 {@link VaultWorker}（和上鎖同一條
 * 背景執行緒），完成後以新的快照重畫。存檔中可以繼續瀏覽與搜尋，但不能再新增、編輯、刪除（DESIGN §6）。
 * 編輯流程在 {@link EditFlow}，條目的刪除、還原、複製在 {@link EntryActions}，群組在 {@link GroupActions}。
 */
public final class VaultScreen {

    /**
     * 畫面需要的其他依賴。
     *
     * @param clock     修改時間的來源與顯示時區
     * @param estimator 密碼強度
     * @param modules   desktop 功能模組（編輯畫面密碼欄旁的按鈕）
     * @param clipboard 剪貼簿（複製密碼後依設定的秒數清除，T-19）
     * @param browser   用系統瀏覽器開網址
     */
    public record Env(Clock clock, Function<CharSequence, PasswordStrength> estimator,
                      ModuleRegistry<DesktopFeature> modules, ClipboardGuard clipboard,
                      Consumer<String> browser) { }

    private final AppShell shell;
    private final VaultWorker worker;
    private final VaultOps ops;
    private final String vaultName;
    private final ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.shell.entries.messages");
    private final GroupTree tree = new GroupTree();
    private final EntryListPane list;
    private final EntryDetailPane detail;
    private final StackPane right = new StackPane();
    private final EditFlow editFlow;
    private final EntryActions entries;
    private final GroupActions groups;
    private final SyncStatus sync;
    private final ConflictFlow conflicts;
    private final Set<String> collapsed = new HashSet<>();
    private final Map<Shortcut, Runnable> shortcuts;

    private ScreenData data;
    /** 每次換新的快照也交給模組的唯讀保管庫（T-32，{@link #publishTo}）。 */
    private Consumer<ScreenData> feed = d -> { };
    private Listing.Scope scope;
    private boolean busy;
    /** 程式設定群組樹的選取時不當成使用者的選擇。 */
    private boolean updatingTree;

    /**
     * @param shell     主視窗外框
     * @param worker    保管庫的背景執行緒
     * @param session   解鎖狀態（已解鎖）
     * @param vaultName 工具列上的保管庫名稱
     * @param env       其他依賴
     */
    public VaultScreen(AppShell shell, VaultWorker worker, VaultSession session, String vaultName, Env env) {
        this.shell = shell;
        this.worker = worker;
        this.ops = new VaultOps(session, env.clock());
        this.vaultName = vaultName;
        list = new EntryListPane(text, new ListActions(this));
        entries = new EntryActions(this, text, env.clipboard(), env.browser());
        detail = new EntryDetailPane(text, entries, env.clock().getZone());
        editFlow = new EditFlow(this, text, env.estimator(), PasswordButtons.from(env.modules()),
                g -> data == null ? g.name() : data.query().groupPath(g.uuid()));
        groups = new GroupActions(this, text);
        sync = new SyncStatus(this, text, vaultName);
        conflicts = new ConflictFlow(this, text, env.clock().getZone());
        tree.setId("group-tree");
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (!updatingTree && now != null && old != now) {
                chooseGroup(old, now);
            }
        });
        tree.setMenuFactory(groups::menu);
        tree.setOnDropEntry(groups::drop);
        tree.setOnRename(groups::renameSelected);
        list.search().textProperty().addListener((obs, old, now) -> relist(currentEntryId()));
        shortcuts = VaultShortcuts.of(this, groups);
    }

    /** 顯示三欄並在背景載入內容（載入中顯示在清單欄）。 */
    public void show() {
        right.getChildren().setAll(detail);
        shell.showThreeColumns(tree, list, right);
        shell.setVaultStatus(vaultName, "");
        list.showLoading();
        ShortcutRegistry registry = registry();
        if (registry != null) {
            shortcuts.forEach(registry::bind);
        }
        worker.supply(ops::load, loaded -> {
            scope = Listing.Scope.group(loaded.root().uuid());
            refresh(loaded, null);
            // 清單或空狀態剛放進場景；先排版，requestFocus 才有效（STACK §13）
            shell.applyCss();
            shell.layout();
            list.focusDefault();
            // AC-07-5：解鎖後讀一次遠端的最新版本，之後視窗取得焦點時再讀
            sync.start(shell.getScene() == null ? null : shell.getScene().getWindow());
        }, e -> shell.toasts().show(Toast.Kind.ERROR, text.getString("toast.failed")));
    }

    /** @param sink 載入與每次編輯、存檔、同步後的快照（JavaFX 執行緒；{@link #show()} 之前設定） */
    public void publishTo(Consumer<ScreenData> sink) {
        feed = sink;
    }

    /** 上鎖：解除快捷鍵、停止同步計時、清掉畫面上的內容參照。 */
    public void dispose() {
        sync.dispose();
        ShortcutRegistry registry = registry();
        if (registry != null) {
            shortcuts.keySet().forEach(registry::unbind);
        }
        editFlow.clear();
        conflicts.clear();
        data = null;
    }

    /** 還原前（在背景執行緒）：把沒寫進檔案的修改存一次，讓「還原前的備份」包含它（同上鎖前，AC-12-3）。 */
    public void saveBeforeLock() {
        ops.saveBeforeLock();
    }

    /**
     * 上鎖時要先處理的畫面狀態（JavaFX 執行緒上取得，交給背景執行緒的 {@link #lock}）。
     *
     * @param edit    編輯畫面上還沒儲存的內容；沒有時 {@code null}
     * @param choices 衝突對照畫面上的選擇
     */
    public record LockState(PendingEdit edit, Map<UUID, Map<String, Side>> choices) { }

    /** @return 上鎖前（JavaFX 執行緒，對話框已關掉）的畫面狀態（AC-12-3、AC-08-5） */
    public LockState prepareLock() {
        return new LockState(editFlow.pendingEdit(), conflicts.choicesForLock());
    }

    /**
     * 上鎖（背景執行緒）：先存編輯中的內容與存檔失敗的修改，有衝突時連同選擇加密保存，然後上鎖（{@code VaultOps.lock}）。
     *
     * @param state {@link #prepareLock()} 的結果
     */
    public void lock(LockState state) {
        ops.lock(state.edit(), state.choices());
    }

    /**
     * 以 UUID 開啟條目詳細（T-30 的 {@code EntryNavigator}，見 {@link EntryJump}；JavaFX 執行緒）。
     *
     * @param entryId 條目
     */
    public void openEntry(UUID entryId) {
        EntryJump.open(this, entryId);
    }

    // ---------- 給 EditFlow、EntryActions、GroupActions ----------

    ResourceBundle text() {
        return text;
    }

    ScreenData data() {
        return data;
    }

    VaultOps ops() {
        return ops;
    }

    Listing.Scope scope() {
        return scope;
    }

    GroupTree tree() {
        return tree;
    }

    EntryListPane list() {
        return list;
    }

    EntryDetailPane detail() {
        return detail;
    }

    StackPane right() {
        return right;
    }

    EditFlow editFlow() {
        return editFlow;
    }

    SyncStatus sync() {
        return sync;
    }

    ConflictFlow conflicts() {
        return conflicts;
    }

    EntryActions entryActions() {
        return entries;
    }

    AppShell shell() {
        return shell;
    }

    boolean busy() {
        return busy || data == null;
    }

    /** @return 搜尋框是空的（不是搜尋結果） */
    boolean notSearching() {
        return list.search().textProperty().get().isEmpty();
    }

    /**
     * 執行一個寫入操作：排進背景執行緒，完成後以新的內容重畫。
     *
     * @param op        操作
     * @param select    完成後要選取的條目（{@code null}：維持目前的）
     * @param onSuccess 完成後（重畫之後，JavaFX 執行緒）
     * @param onFailure 操作失敗（沒有改到保管庫）後
     */
    void apply(Supplier<VaultOps.Outcome> op, Function<VaultOps.Outcome, UUID> select,
            Consumer<VaultOps.Outcome> onSuccess, Runnable onFailure) {
        if (busy()) {
            return;
        }
        setBusy(true);
        worker.supply(op::get, out -> {
            setBusy(false);
            if (out.data() != null) {
                refresh(out.data(), select == null ? currentEntryId() : select.apply(out));
            }
            sync.show(out);
            onSuccess.accept(out);
        }, e -> {
            setBusy(false);
            onFailure.run();
            shell.toasts().show(Toast.Kind.ERROR, text.getString("toast.failed"));
        });
    }

    /**
     * 在背景讀一次（不改保管庫，例如取得衝突），完成後在 JavaFX 執行緒處理結果；寫入進行中時不做。
     *
     * @param query 讀取
     * @param then  結果
     * @param <T>   結果型別
     */
    <T> void read(Supplier<T> query, Consumer<T> then) {
        if (busy()) {
            return;
        }
        worker.supply(query::get, then, e -> shell.toasts().show(Toast.Kind.ERROR, text.getString("toast.failed")));
    }

    /**
     * @param target 群組 id
     * @param notify 是否當成使用者的選擇（換清單內容）
     */
    void selectGroup(String target, boolean notify) {
        TreeItem<GroupTree.Item> item = GroupItems.find(tree.getRoot(), target);
        if (item == null) {
            return;
        }
        updatingTree = !notify;
        try {
            tree.getSelectionModel().select(item);
            if (!notify) {
                scope = GroupItems.scopeOf(item.getValue());
            }
        } finally {
            updatingTree = false;
        }
    }

    /** 回到詳細（離開編輯後）。 */
    void showDetailPane() {
        right.getChildren().setAll(detail);
        showDetail(list.selected());
    }

    // ---------- 重畫 ----------

    void refresh(ScreenData fresh, UUID selectEntry) {
        data = fresh;
        feed.accept(fresh);
        String selectedGroup = scope == null || scope.groupId() == null ? "" : scope.groupId().toString();
        updatingTree = true;
        try {
            tree.setRoot(GroupItems.build(fresh, text.getString("groups.recycleBin"), collapsed));
            TreeItem<GroupTree.Item> item = GroupItems.find(tree.getRoot(), selectedGroup);
            if (item == null) {
                item = tree.getRoot();
            }
            tree.getSelectionModel().select(item);
            scope = GroupItems.scopeOf(item.getValue());
        } finally {
            updatingTree = false;
        }
        relist(selectEntry);
    }

    void relist(UUID selectEntry) {
        if (data == null) {
            return;
        }
        Listing listing = Listing.of(data, scope, list.search().textProperty().get());
        list.show(listing, e -> data.query().groupPath(e.groupId()), selectEntry, editFlow::startNew);
        if (!editFlow.editing()) {
            showDetail(list.selected());
        }
    }

    void showDetail(EntryView entry) {
        if (entry == null) {
            detail.showNone();
        } else {
            detail.show(entry, data.inRecycleBin(entry.groupId()));
            detail.setBusy(busy);
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        list.setBusy(value);
        detail.setBusy(value);
        sync.working(value);
    }

    // ---------- 選取 ----------

    private void chooseGroup(TreeItem<GroupTree.Item> old, TreeItem<GroupTree.Item> now) {
        if (!editFlow.leaveEdit()) {
            Platform.runLater(() -> {
                updatingTree = true;
                tree.getSelectionModel().select(old);
                updatingTree = false;
            });
            return;
        }
        scope = GroupItems.scopeOf(now.getValue());
        if (!notSearching()) {
            // 清空搜尋會觸發 relist；選群組就是要看那個群組
            list.search().textProperty().set("");
        } else {
            relist(null);
        }
    }

    UUID currentEntryId() {
        EntryView e = editFlow.editing() ? editFlow.editingEntry() : detail.entry();
        return e == null ? null : e.uuid();
    }

    private ShortcutRegistry registry() {
        return shell.getScene() == null ? null : ShortcutRegistry.of(shell.getScene());
    }
}
