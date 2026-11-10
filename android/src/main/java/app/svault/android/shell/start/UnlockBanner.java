package app.svault.android.shell.start;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import app.svault.android.R;
import app.svault.android.platform.OpenFailure;
import app.svault.android.ui.Banner;
import app.svault.core.storage.BackupStore;
import com.google.android.material.button.MaterialButton;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 解鎖畫面的 Banner：開啟失敗的原因與行動按鈕（AC-01-7、AC-06-4、AC-09-5），以及還原模式（T-12）。
 *
 * <p>還原模式（檔案損壞時）：「從備份還原」開 {@link BackupActivity} 選一份並確認 →
 * info Banner「將還原到 … 的版本，輸入主密碼後還原」＋「選擇其他版本」，解鎖按鈕改成「還原並解鎖」。
 * 選定的備份跟著畫面狀態保存（旋轉、深色模式重建後仍在還原模式）。
 */
final class UnlockBanner {

    private static final String STATE_FILE = "restore_file";
    private static final String STATE_TIME = "restore_time";

    private final AppCompatActivity activity;
    private final Banner banner;
    private final MaterialButton submit;
    private final Supplier<String> uri;
    private final Runnable focusPassword;
    private final Runnable pickFile;
    private final ActivityResultLauncher<Intent> picker;
    @Nullable private BackupStore.Backup target;

    /**
     * 在 {@code onCreate} 建立（要在畫面啟動前註冊結果回呼）。
     *
     * @param activity      解鎖畫面
     * @param banner        畫面的 Banner
     * @param submit        「解鎖」按鈕
     * @param uri           目前保管庫的 {@code persistentId}；還不知道時 {@code null}
     * @param focusPassword 把焦點放回主密碼欄
     * @param pickFile      「重新選擇檔案」
     */
    UnlockBanner(AppCompatActivity activity, Banner banner, MaterialButton submit, Supplier<String> uri,
            Runnable focusPassword, Runnable pickFile) {
        this.activity = activity;
        this.banner = banner;
        this.submit = submit;
        this.uri = uri;
        this.focusPassword = focusPassword;
        this.pickFile = pickFile;
        picker = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    BackupStore.Backup chosen = BackupActivity.chosen(result.getData());
                    if (result.getResultCode() == AppCompatActivity.RESULT_OK && chosen != null) {
                        target = chosen;
                        show();
                    }
                });
    }

    /**
     * error Banner＋行動按鈕：「重新選擇檔案」、「從備份還原」（損壞且有備份，AC-09-5），或還原失敗後的「選擇其他版本」
     * （同時離開還原模式）。
     *
     * @param f 開啟或還原失敗的說明
     */
    void showFailure(OpenFailure f) {
        CharSequence message = f.messageArg() == null ? activity.getString(f.messageRes())
                : activity.getString(f.messageRes(), f.messageArg());
        switch (f.action()) {
            case RESTORE_BACKUP:
                banner.show(Banner.Kind.ERROR, message, activity.getString(R.string.sv_open_restore), v -> choose());
                break;
            case OTHER_BACKUP:
                clear();
                banner.show(Banner.Kind.ERROR, message, activity.getString(R.string.restore_other), v -> choose());
                break;
            case RESELECT_FILE:
                banner.show(Banner.Kind.ERROR, message, activity.getString(R.string.sv_open_reselect),
                        v -> pickFile.run());
                break;
            default:
                banner.show(Banner.Kind.ERROR, message, null, null);
                break;
        }
        banner.setVisibility(View.VISIBLE);
    }

    /** 開備份清單（「從備份還原」「選擇其他版本」）。 */
    void choose() {
        String where = uri.get();
        if (where != null) {
            picker.launch(BackupActivity.forLocked(activity, where));
        }
    }

    /** @return 選定要還原的備份；一般解鎖時 {@code null} */
    @Nullable
    BackupStore.Backup target() {
        return target;
    }

    /** 離開還原模式（還原失敗後）：按鈕改回「解鎖」。Banner 由呼叫端換成錯誤訊息。 */
    void clear() {
        target = null;
        submit.setText(R.string.unlock_submit);
    }

    /**
     * 還原模式時重新顯示提示（畫面重建後的準備工作會收掉 Banner）。
     *
     * @return 在還原模式
     */
    boolean reshow() {
        if (target == null) {
            return false;
        }
        show();
        return true;
    }

    /** @param saved 重建前保存的狀態；沒有時 {@code null} */
    void restoreState(@Nullable Bundle saved) {
        if (saved != null && saved.getString(STATE_FILE) != null) {
            target = new BackupStore.Backup(Paths.get(saved.getString(STATE_FILE)),
                    LocalDateTime.parse(saved.getString(STATE_TIME)));
            submit.setText(R.string.restore_unlock);
        }
    }

    /** @param out 要保存的狀態 */
    void saveState(Bundle out) {
        if (target != null) {
            out.putString(STATE_FILE, target.file().toString());
            out.putString(STATE_TIME, target.time().toString());
        }
    }

    private void show() {
        banner.show(Banner.Kind.INFO,
                activity.getString(R.string.restore_pending, target.time().format(BackupActivity.TIME)),
                activity.getString(R.string.restore_other), v -> choose());
        banner.setVisibility(View.VISIBLE);
        submit.setText(R.string.restore_unlock);
        focusPassword.run();
    }
}
