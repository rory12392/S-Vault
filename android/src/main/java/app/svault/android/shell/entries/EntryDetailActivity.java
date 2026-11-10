package app.svault.android.shell.entries;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.Nullable;
import app.svault.android.R;
import app.svault.android.ui.ConfirmDialogs;
import app.svault.android.ui.FieldRow;
import app.svault.android.ui.SystemBars;
import app.svault.android.ui.Toasts;
import app.svault.core.shell.Links;
import app.svault.core.shell.ScreenData;
import app.svault.core.shell.VaultOps;
import app.svault.core.vault.EntryView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import java.util.Optional;
import java.util.UUID;

/**
 * 條目詳細：帳號（複製）、密碼（預設遮罩＋顯示＋複製，AC-02-5）、網址（開啟＋複製）、備註（多行、
 * 可選取＋複製）、最後修改；「編輯」、「刪除」（確認後移到資源回收桶，AC-02-3）；回收桶裡是「還原」「永久刪除」（確認）。
 */
public class EntryDetailActivity extends EntriesActivity {

    private static final String EXTRA_ENTRY = "app.svault.android.extra.ENTRY";
    private static final String JOB_DELETE = "delete";
    private static final String JOB_RESTORE = "restore";
    private static final String JOB_PURGE = "purge";

    private View root;
    private MaterialToolbar toolbar;
    private FieldRow username;
    private FieldRow password;
    private FieldRow url;
    private FieldRow notes;
    private MaterialButton edit;
    private MaterialButton delete;
    private MaterialButton restore;
    private MaterialButton purge;
    private UUID entryId;
    @Nullable private EntryView shown;

    /**
     * @param context 目前的畫面
     * @param entryId 條目
     * @return 開啟詳細的 Intent
     */
    static Intent intent(Context context, UUID entryId) {
        return new Intent(context, EntryDetailActivity.class).putExtra(EXTRA_ENTRY, entryId.toString());
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entry_detail);
        root = findViewById(R.id.detail_root);
        SystemBars.applyInsets(root);
        toolbar = findViewById(R.id.detail_toolbar);
        setUpToolbar(toolbar, true);
        EntriesModel model = model();
        if (model == null) {
            return;
        }
        entryId = UUID.fromString(getIntent().getStringExtra(EXTRA_ENTRY));
        username = findViewById(R.id.detail_username);
        password = findViewById(R.id.detail_password);
        url = findViewById(R.id.detail_url);
        notes = findViewById(R.id.detail_notes);
        edit = findViewById(R.id.detail_edit);
        delete = findViewById(R.id.detail_delete);
        restore = findViewById(R.id.detail_restore);
        purge = findViewById(R.id.detail_purge);
        username.setOnCopyListener(v -> copy(username, shown.username(), R.string.entries_toast_copied_username));
        password.setOnCopyListener(v -> {
            copyPassword(shown);
            password.setCopied(true);
        });
        url.setOnCopyListener(v -> copy(url, shown.url(), R.string.entries_toast_copied_url));
        url.setOnOpenListener(v -> openUrl());
        notes.setOnCopyListener(v -> copy(notes, shown.notes(), R.string.entries_toast_copied_notes));
        edit.setOnClickListener(v -> startActivity(EntryEditActivity.edit(this, entryId)));
        delete.setOnClickListener(v -> confirm(false));
        purge.setOnClickListener(v -> confirm(true));
        restore.setOnClickListener(v -> runOp(JOB_RESTORE, ops -> ops.restoreEntry(entryId)));
        bindStatus(findViewById(R.id.detail_banner), findViewById(R.id.detail_saving));
        model.data().observe(this, this::show);
        model.ensureLoaded();
    }

    @Override
    protected View snackbarAnchor() {
        return root;
    }

    @Override
    protected void onSavingChanged(boolean saving) {
        for (MaterialButton b : new MaterialButton[] {edit, delete, restore, purge}) {
            b.setEnabled(!saving);
        }
    }

    private void show(@Nullable ScreenData data) {
        if (data == null) {
            return;
        }
        Optional<EntryView> found = data.entry(entryId);
        EntriesModel model = model();
        if (!found.isPresent()) {
            // 剛刪除（結果另外處理）或在別處被永久刪除
            if (model != null && !model.busy()) {
                finish();
            }
            return;
        }
        EntryView e = found.get();
        boolean sameEntry = shown != null;
        boolean wasRevealed = sameEntry && password.isRevealed();
        shown = e;
        toolbar.setTitle(e.title());
        username.bind(FieldRow.Kind.TEXT, getString(R.string.entries_username), e.username());
        password.bind(FieldRow.Kind.PASSWORD, getString(R.string.entries_password), e.password());
        // 存檔後重畫時不把使用者剛按的「顯示」改回遮罩；第一次開啟一律遮罩（AC-02-5）
        password.setRevealed(wasRevealed);
        url.bind(FieldRow.Kind.URL, getString(R.string.entries_url), e.url());
        notes.bind(FieldRow.Kind.NOTES, getString(R.string.entries_notes), e.notes());
        // 網址、備註沒有值時不顯示那一列（同 PC）；帳號、密碼一律顯示
        url.setVisibility(e.url().isEmpty() ? View.GONE : View.VISIBLE);
        notes.setVisibility(e.notes().isEmpty() ? View.GONE : View.VISIBLE);
        int extras = e.customFields().size() + e.attachmentNames().size();
        TextView extrasView = findViewById(R.id.detail_extras);
        extrasView.setVisibility(extras > 0 ? View.VISIBLE : View.GONE);
        extrasView.setText(getString(R.string.entries_extras, extras));
        ((TextView) findViewById(R.id.detail_modified)).setText(
                getString(R.string.entries_modified, Pages.time(e.lastModified())));
        boolean inBin = data.inRecycleBin(e.groupId());
        findViewById(R.id.detail_in_bin).setVisibility(inBin ? View.VISIBLE : View.GONE);
        edit.setVisibility(inBin ? View.GONE : View.VISIBLE);
        delete.setVisibility(inBin ? View.GONE : View.VISIBLE);
        restore.setVisibility(inBin ? View.VISIBLE : View.GONE);
        purge.setVisibility(inBin ? View.VISIBLE : View.GONE);
    }

    private void copy(FieldRow row, String value, int toast) {
        app().clipboard().copy(value);
        row.setCopied(true);
        Toasts.show(root, getString(toast), Toasts.Kind.INFO);
    }

    /** 只交給瀏覽器 http／https，其他「無法開啟這個網址」。 */
    private void openUrl() {
        Optional<String> link = Links.browsable(shown.url());
        if (link.isPresent()) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(link.get()))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            try {
                startActivity(view);
                return;
            } catch (ActivityNotFoundException e) {
                // 沒有瀏覽器：照下面的錯誤處理
            }
        }
        Toasts.show(root, getString(R.string.entries_toast_url_failed), Toasts.Kind.ERROR);
    }

    private void confirm(boolean permanently) {
        EntriesModel model = model();
        if (shown == null || model == null || model.busy()) {
            return;
        }
        String name = shown.title();
        if (permanently) {
            ConfirmDialogs.show(this, getString(R.string.entries_confirm_delete_permanently_title),
                    getString(R.string.entries_confirm_delete_permanently_body, name),
                    getString(R.string.entries_confirm_delete_permanently), true,
                    (d, w) -> runOp(JOB_PURGE, ops -> ops.deleteEntryPermanently(entryId)));
        } else {
            ConfirmDialogs.show(this, getString(R.string.entries_confirm_delete_title),
                    getString(R.string.entries_confirm_delete_body, name), getString(R.string.entries_confirm_delete),
                    true, (d, w) -> runOp(JOB_DELETE, ops -> ops.recycleEntry(entryId)));
        }
    }

    @Override
    protected void onOpDone(String tag, VaultOps.Outcome out) {
        EntriesModel model = model();
        if (JOB_RESTORE.equals(tag)) {
            if (out.saved()) {
                Toasts.show(root, getString(R.string.entries_toast_restored), Toasts.Kind.SUCCESS);
            }
            return;
        }
        // 刪除、永久刪除：回到清單，提示顯示在清單上（存檔失敗時由「尚未同步」Banner 說明）
        if (out.saved() && model != null) {
            int message = JOB_PURGE.equals(tag) ? R.string.entries_toast_deleted_permanently
                    : out.recycled() ? R.string.entries_toast_recycled : R.string.entries_toast_deleted_no_bin;
            model.notice(getString(message), Toasts.Kind.SUCCESS);
        }
        finish();
    }
}
