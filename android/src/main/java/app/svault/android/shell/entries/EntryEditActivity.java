package app.svault.android.shell.entries;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import app.svault.android.R;
import app.svault.android.shell.ModuleRestoreFactory;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.PasswordFieldAction;
import app.svault.android.ui.Buttons;
import app.svault.android.ui.ConfirmDialogs;
import app.svault.android.ui.StrengthMeter;
import app.svault.android.ui.SystemBars;
import app.svault.android.ui.Toasts;
import app.svault.core.module.Contribution;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.security.PasswordStrength;
import app.svault.core.shell.PendingEdit;
import app.svault.core.shell.ScreenData;
import app.svault.core.shell.VaultOps;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.nio.CharBuffer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 新增／編輯條目（AC-02-1、AC-02-2）：名稱必填；「儲存」經 {@link VaultOps}（VaultEditor →
 * SaveCoordinator）寫入，儲存中按鈕 loading、不能離開；完成後回到詳細（新增時開新條目的詳細）並顯示「已儲存」。
 * 有修改時離開先確認「捨棄修改？」。群組下拉可以把條目移到其他群組（AC-02-4）。
 */
public class EntryEditActivity extends EntriesActivity {

    private static final String EXTRA_ENTRY = "app.svault.android.extra.ENTRY";
    private static final String EXTRA_GROUP = "app.svault.android.extra.GROUP";
    private static final String JOB_SAVE = "save";

    /** 畫面重建（旋轉、深色模式）時帶過去的欄位值；不放 Bundle（{@link EntryForm} 的說明）。 */
    public static final class State extends ViewModel {
        @Nullable EntryForm initial;
        @Nullable EntryForm pending;
    }

    private View root;
    private State state;
    private TextInputLayout nameLayout;
    private TextInputEditText name;
    private TextInputEditText username;
    private TextInputEditText password;
    private TextInputEditText url;
    private TextInputEditText notes;
    private TextInputLayout groupLayout;
    private MaterialAutoCompleteTextView groupField;
    private StrengthMeter meter;
    private MaterialButton save;
    private MaterialButton cancel;
    @Nullable private UUID entryId;
    private List<GroupView> groups = List.of();
    @Nullable private UUID group;
    private boolean filled;
    private final Supplier<PendingEdit> editSource = this::pendingEdit;
    private final PasswordFills fills = new PasswordFills(new PasswordFills.Target() {
        @Override
        public boolean accepting() {
            return acceptsFill();
        }

        @Override
        public void fill(char[] value) {
            fillPassword(value);
        }
    }, () -> Looper.myLooper() == Looper.getMainLooper());

    /**
     * @param context 目前的畫面
     * @param groupId 新條目預設的群組
     * @return 開啟「新增條目」的 Intent
     */
    static Intent newEntry(Context context, UUID groupId) {
        return new Intent(context, EntryEditActivity.class).putExtra(EXTRA_GROUP, groupId.toString());
    }

    /**
     * @param context 目前的畫面
     * @param entryId 要編輯的條目
     * @return 開啟「編輯條目」的 Intent
     */
    static Intent edit(Context context, UUID entryId) {
        return new Intent(context, EntryEditActivity.class).putExtra(EXTRA_ENTRY, entryId.toString());
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // 模組開在這個畫面上的 sheet（PasswordFieldAction，T-37）重建時不由 FragmentManager 還原（不在錯誤邊界內）：
        // 換成空白佔位、onStart 拿掉；重建前發出的回填已經失效，sheet 跟著消失
        ModuleRestoreFactory restore = new ModuleRestoreFactory();
        getSupportFragmentManager().setFragmentFactory(restore);
        restore.restoring(savedInstanceState != null);
        try {
            super.onCreate(savedInstanceState);
        } finally {
            restore.restoring(false);
        }
        setContentView(R.layout.activity_entry_edit);
        root = findViewById(R.id.edit_root);
        SystemBars.applyInsets(root);
        EntriesModel model = model();
        if (model == null) {
            return;
        }
        state = new ViewModelProvider(this).get(State.class);
        String entry = getIntent().getStringExtra(EXTRA_ENTRY);
        entryId = entry == null ? null : UUID.fromString(entry);
        MaterialToolbar toolbar = findViewById(R.id.edit_toolbar);
        toolbar.setTitle(entryId == null ? R.string.entries_edit_title_new : R.string.entries_edit_title_edit);
        toolbar.setNavigationOnClickListener(v -> leave());
        nameLayout = findViewById(R.id.edit_name_layout);
        name = findViewById(R.id.edit_name);
        username = findViewById(R.id.edit_username);
        password = findViewById(R.id.edit_password);
        url = findViewById(R.id.edit_url);
        notes = findViewById(R.id.edit_notes);
        groupLayout = findViewById(R.id.edit_group_layout);
        groupField = findViewById(R.id.edit_group);
        meter = findViewById(R.id.edit_strength);
        save = findViewById(R.id.edit_save);
        cancel = findViewById(R.id.edit_cancel);
        save.setOnClickListener(v -> save());
        cancel.setOnClickListener(v -> leave());
        name.addTextChangedListener(new GroupDialogs.SimpleWatcher(() -> nameLayout.setError(null)));
        password.addTextChangedListener(new GroupDialogs.SimpleWatcher(this::updateStrength));
        groupField.setOnItemClickListener((parent, view, position, id) -> group = groups.get(position).uuid());
        addPasswordActions(app().modules());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                leave();
            }
        });
        // 編輯畫面自己用「儲存」的 loading 表示存檔中；存檔失敗的 Banner 顯示在回到的詳細頁
        bindStatus(null, null);
        if (opRunning()) {
            setBusy(true);
        }
        // zxcvbn 的字典在第一次評估時載入：先在背景暖機（同建立保管庫畫面）
        app().worker().execute(() -> app().strength().estimate(""));
        model.data().observe(this, this::fill);
        model.ensureLoaded();
        // AC-12-3：上鎖時（切到背景、螢幕關閉）先存畫面上還沒按「儲存」的內容（DESIGN §6：不詢問）
        model.setEditSource(editSource);
    }

    /** @return 有修改時畫面上的內容（上鎖時先存）；還沒填入或沒有修改時 {@code null} */
    @Nullable
    private PendingEdit pendingEdit() {
        if (!filled || state.initial == null) {
            return null;
        }
        EntryForm now = current();
        if (!state.initial.dirtyComparedTo(now)) {
            return null;
        }
        return new PendingEdit(entryId, now.draft(), state.initial.group(), now.group());
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleRestoreFactory.removePlaceholders(getSupportFragmentManager());
    }

    @Override
    protected void onDestroy() {
        // 重建、離開：已經交給模組的回填一律失效（不會填到新的畫面）
        fills.close();
        EntriesModel model = model();
        if (model != null) {
            model.clearEditSource(editSource);
        }
        // 旋轉、深色模式切換：欄位值留在 ViewModel（記憶體），新的畫面接著用
        if (state != null && filled && isChangingConfigurations()) {
            state.pending = current();
        }
        super.onDestroy();
    }

    @Override
    protected View snackbarAnchor() {
        return root;
    }

    /** 第一次拿到內容時填入欄位；之後內容再變（例如自己存檔）不覆寫正在編輯的值。 */
    private void fill(@Nullable ScreenData data) {
        if (data == null || filled) {
            return;
        }
        EntryForm start;
        if (state.initial != null) {
            start = state.initial;
        } else if (entryId != null) {
            Optional<EntryView> e = data.entry(entryId);
            if (!e.isPresent()) {
                finish();
                return;
            }
            start = EntryForm.of(e.get());
        } else {
            String g = getIntent().getStringExtra(EXTRA_GROUP);
            start = EntryForm.blank(g == null ? data.root().uuid() : UUID.fromString(g));
        }
        state.initial = start;
        EntryForm shown = state.pending != null ? state.pending : start;
        state.pending = null;
        name.setText(shown.title());
        username.setText(shown.username());
        password.setText(shown.password());
        url.setText(shown.url());
        notes.setText(shown.notes());
        groups = data.activeGroups();
        String[] paths = new String[groups.size()];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = data.query().groupPath(groups.get(i).uuid());
        }
        groupField.setSimpleItems(paths);
        group = shown.group();
        if (data.group(group).isPresent() && !data.inRecycleBin(group)) {
            groupField.setText(data.query().groupPath(group), false);
        } else {
            // 群組已被刪除：放根群組
            group = data.root().uuid();
            groupField.setText(data.query().groupPath(group), false);
        }
        filled = true;
        updateStrength();
        if (entryId == null) {
            name.requestFocus();
        }
    }

    private EntryForm current() {
        return new EntryForm(text(name), text(username), text(password), text(url), text(notes), group);
    }

    private static String text(EditText field) {
        Editable e = field.getText();
        return e == null ? "" : e.toString();
    }

    private void updateStrength() {
        Editable text = password.getText();
        if (text == null || text.length() == 0) {
            meter.setLevel(StrengthMeter.Level.NONE);
            return;
        }
        PasswordStrength s = app().strength().estimate(text);
        meter.setLevel(s == PasswordStrength.WEAK ? StrengthMeter.Level.WEAK
                : s == PasswordStrength.MEDIUM ? StrengthMeter.Level.MEDIUM : StrengthMeter.Level.STRONG);
    }

    /**
     * 功能模組的密碼欄按鈕（GEN 的「產生」，M-03）。每次呼叫模組都包在 {@link ModuleGuard} 裡：模組出錯時不顯示或不填入，
     * 編輯與存檔照常。按下時呼叫非同步的 {@code perform(Context, PasswordFill)}（T-37；預設轉呼叫同步版並立即回填）。
     */
    private void addPasswordActions(ModuleRegistry<AndroidFeature> modules) {
        LinearLayout container = findViewById(R.id.edit_password_actions);
        ModuleGuard guard = modules.guard();
        for (Contribution<PasswordFieldAction> c
                : modules.collect("passwordFieldActions", AndroidFeature::passwordFieldActions)) {
            // 字串資源是 0 或讀不到時不顯示這個按鈕（以前 setText 會在錯誤邊界外丟例外）
            Optional<String> label = guard.call(c.moduleId(), "labelRes", () -> getString(c.value().labelRes()));
            if (!label.isPresent()) {
                continue;
            }
            MaterialButton b = (MaterialButton) LayoutInflater.from(this)
                    .inflate(R.layout.entries_password_action, container, false);
            b.setText(label.get());
            b.setOnClickListener(v -> guard.run(c.moduleId(), "perform",
                    () -> c.value().perform(this, fills.handle(c.moduleId()))));
            container.addView(b);
        }
        container.setVisibility(container.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    /** @return 模組現在可以回填密碼欄：沒有上鎖、內容已載入、不在儲存、不是正在離開 */
    private boolean acceptsFill() {
        EntriesModel model = model();
        return filled && !isFinishing() && !isDestroyed() && !lockGate().isLocked() && model != null
                && !model.busy() && password.isEnabled();
    }

    private void fillPassword(char[] generated) {
        Editable e = password.getText();
        if (e != null) {
            e.replace(0, e.length(), CharBuffer.wrap(generated));
        }
        // 清零由 PasswordFills 負責（PasswordFill 的約定）
    }

    private void save() {
        EntriesModel model = model();
        if (!filled || model == null || model.busy()) {
            return;
        }
        EntryForm form = current();
        if (form.nameMissing()) {
            // 送出時焦點移到第一個錯誤欄位（DESIGN §6）
            nameLayout.setError(getString(R.string.entries_error_name));
            name.requestFocus();
            return;
        }
        UUID from = state.initial.group();
        boolean started = runOp(JOB_SAVE, ops -> entryId == null ? ops.addEntry(form.group(), form.draft())
                : ops.updateEntry(entryId, form.draft(), from, form.group()));
        if (started) {
            setBusy(true);
        }
    }

    private void setBusy(boolean busy) {
        Buttons.setLoading(save, busy);
        cancel.setEnabled(!busy);
        for (View v : new View[] {name, username, password, url, notes, groupField, groupLayout}) {
            v.setEnabled(!busy);
        }
    }

    @Override
    protected boolean syncOnResume() {
        // 改到一半時不讀遠端：存檔時遠端有變更會先合併（AC-07-1）
        return false;
    }

    @Override
    protected void onOpDone(String tag, VaultOps.Outcome out) {
        EntriesModel model = model();
        if (model != null && out.saved()) {
            model.notice(getString(DriveLagHint.takeFirst(model) ? R.string.entries_toast_saved_drive_lag
                    : R.string.entries_toast_saved), Toasts.Kind.SUCCESS);
        }
        if (entryId == null && out.subject() != null) {
            startActivity(EntryDetailActivity.intent(this, out.subject()));
        }
        finish();
    }

    @Override
    protected void onOpFailed(String tag) {
        setBusy(false);
    }

    /** 取消、返回：儲存中不離開；有修改時確認「捨棄修改？」（DESIGN §6）。 */
    private void leave() {
        if (opRunning()) {
            return;
        }
        if (!filled || state.initial == null || !state.initial.dirtyComparedTo(current())) {
            finish();
            return;
        }
        ConfirmDialogs.show(this, getString(R.string.entries_discard_title), getString(R.string.entries_discard_body),
                getString(R.string.entries_discard_confirm), getString(R.string.entries_discard_keep), true,
                (d, w) -> finish());
    }
}
