package app.svault.android.gallery;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageView;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.ui.Banner;
import app.svault.android.ui.Buttons;
import app.svault.android.ui.ConfirmDialogs;
import app.svault.android.ui.DiffRow;
import app.svault.android.ui.EmptyState;
import app.svault.android.ui.EntryRow;
import app.svault.android.ui.FieldRow;
import app.svault.android.ui.ProgressOverlay;
import app.svault.android.ui.StrengthMeter;
import app.svault.android.ui.SystemBars;
import app.svault.android.ui.ThemeMode;
import app.svault.android.ui.Toasts;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

/**
 * 元件展示頁（只在 debug 版）：每個 android 元件的變體與狀態，只用假資料。
 * 這個畫面刻意不設 {@code FLAG_SECURE}，才能截圖驗收。
 *
 * <p>截圖用的參數（{@code adb shell am start -n app.svault.android/.gallery.UiGalleryActivity ...}）：
 * {@code --es theme light|dark|system}、{@code --es section <區塊 tag>}（捲到該區塊）、
 * {@code --es state overlay|percent|dialog|danger|toast|sticky}（開啟後直接進入該狀態）。
 */
public class UiGalleryActivity extends AppCompatActivity {

    private static final long DEMO_MS = 2000;

    /** 與 desktop 的 IconName 同一組，再加 android 用到的 language、arrow_back。 */
    private static final int[] ICONS = {
        R.drawable.sv_ic_add, R.drawable.sv_ic_arrow_back, R.drawable.sv_ic_check_circle,
        R.drawable.sv_ic_chevron_left, R.drawable.sv_ic_chevron_right, R.drawable.sv_ic_close,
        R.drawable.sv_ic_content_copy, R.drawable.sv_ic_dark_mode, R.drawable.sv_ic_delete, R.drawable.sv_ic_edit,
        R.drawable.sv_ic_error, R.drawable.sv_ic_folder, R.drawable.sv_ic_info, R.drawable.sv_ic_key,
        R.drawable.sv_ic_language, R.drawable.sv_ic_light_mode, R.drawable.sv_ic_lock, R.drawable.sv_ic_lock_open,
        R.drawable.sv_ic_menu, R.drawable.sv_ic_more_vert, R.drawable.sv_ic_open_in_new, R.drawable.sv_ic_search,
        R.drawable.sv_ic_settings, R.drawable.sv_ic_sync, R.drawable.sv_ic_visibility,
        R.drawable.sv_ic_visibility_off, R.drawable.sv_ic_warning,
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProgressOverlay overlay;
    private ActivityResultLauncher<Intent> hostLauncher;
    private boolean started;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // 只改這個畫面的模式，不影響 App 的設定（setDefaultNightMode）
        String theme = getIntent().getStringExtra("theme");
        if (theme != null) {
            getDelegate().setLocalNightMode(ThemeMode.fromSetting(theme).nightMode());
        }
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ui_gallery);
        SystemBars.applyInsets(findViewById(R.id.gallery_root));
        overlay = findViewById(R.id.gallery_overlay);
        hostLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                Toasts.show(overlay, getString(R.string.gallery_host_result,
                        r.getData().getStringExtra(GalleryHostPage.RESULT_VALUE)), Toasts.Kind.SUCCESS);
            }
        });

        MaterialToolbar toolbar = findViewById(R.id.gallery_toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            ThemeMode mode = id == R.id.gallery_theme_light ? ThemeMode.LIGHT
                    : id == R.id.gallery_theme_dark ? ThemeMode.DARK : ThemeMode.SYSTEM;
            getDelegate().setLocalNightMode(mode.nightMode());
            return true;
        });

        bindButtons();
        bindFields();
        bindRows();
        bindFeedback();
        bindHost();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // theme 參數與系統模式不同時 AppCompat 會重建畫面，所以等這個實例真的顯示後才套用 section／state
        if (!started) {
            started = true;
            applyStartState();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void bindButtons() {
        ViewGroup icons = findViewById(R.id.gallery_icons);
        int size = getResources().getDimensionPixelSize(R.dimen.sv_icon);
        for (int icon : ICONS) {
            ImageView v = new ImageView(this);
            v.setImageResource(icon);
            icons.addView(v, new ViewGroup.LayoutParams(size, size));
        }
        MaterialButton saving = findViewById(R.id.gallery_saving);
        findViewById(R.id.gallery_toggle_loading).setOnClickListener(
                v -> Buttons.setLoading(saving, !Buttons.isLoading(saving)));
        Buttons.setLoading(saving, true);
    }

    private void bindFields() {
        TextInputLayout url = findViewById(R.id.gallery_url_layout);
        url.setError(getString(R.string.gallery_field_url_error));
        TextInputLayout master = findViewById(R.id.gallery_master_layout);
        master.setError(getString(R.string.gallery_password_wrong));

        StrengthMeter live = findViewById(R.id.gallery_password_meter);
        EditText input = findViewById(R.id.gallery_password_meter_input);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                // 展示用的粗略分級；正式的強度評估是 core 的 StrengthEstimator（T-09）
                int n = s.length();
                live.setLevel(n == 0 ? StrengthMeter.Level.NONE : n < 8 ? StrengthMeter.Level.WEAK
                        : n < 14 ? StrengthMeter.Level.MEDIUM : StrengthMeter.Level.STRONG);
            }
        });
        input.setText(R.string.gallery_sample_password);
        ((StrengthMeter) findViewById(R.id.gallery_meter_weak)).setLevel(StrengthMeter.Level.WEAK);
        ((StrengthMeter) findViewById(R.id.gallery_meter_medium)).setLevel(StrengthMeter.Level.MEDIUM);
        ((StrengthMeter) findViewById(R.id.gallery_meter_strong)).setLevel(StrengthMeter.Level.STRONG);
    }

    private void bindRows() {
        View.OnClickListener copied = v -> Toasts.show(v, getString(R.string.gallery_copied_toast), Toasts.Kind.INFO);
        EntryRow bank = findViewById(R.id.gallery_entry_bank);
        bank.bind(getString(R.string.gallery_sample_bank), getString(R.string.gallery_sample_bank_user), null);
        bank.setOnCopyPasswordListener(copied);
        bank.setOnClickListener(v -> { });
        EntryRow school = findViewById(R.id.gallery_entry_school);
        // 搜尋結果變體：關鍵字標示＋第二行「帳號 ・ 群組路徑」（T-11）
        school.bind(getString(R.string.gallery_sample_school), getString(R.string.gallery_sample_school_user),
                getString(R.string.gallery_sample_query), getString(R.string.gallery_sample_group_path));
        school.setOnCopyPasswordListener(copied);
        school.setOnClickListener(v -> { });
        EntryRow wifi = findViewById(R.id.gallery_entry_wifi);
        wifi.bind(getString(R.string.gallery_sample_wifi), "", null);
        wifi.setOnCopyPasswordListener(copied);
        wifi.setOnClickListener(v -> { });

        FieldRow user = findViewById(R.id.gallery_field_username);
        user.bind(FieldRow.Kind.TEXT, getString(R.string.gallery_field_username),
                getString(R.string.gallery_sample_bank_user));
        user.setCopied(true);
        FieldRow password = findViewById(R.id.gallery_field_password);
        password.bind(FieldRow.Kind.PASSWORD, getString(R.string.gallery_password),
                getString(R.string.gallery_sample_password));
        password.setOnCopyListener(copied);
        FieldRow revealed = findViewById(R.id.gallery_field_password_revealed);
        revealed.bind(FieldRow.Kind.PASSWORD, getString(R.string.gallery_field_password_revealed),
                getString(R.string.gallery_sample_password));
        revealed.setRevealed(true);
        FieldRow url = findViewById(R.id.gallery_field_url);
        url.bind(FieldRow.Kind.URL, getString(R.string.gallery_field_url), getString(R.string.gallery_sample_url));
        FieldRow notes = findViewById(R.id.gallery_field_notes);
        notes.bind(FieldRow.Kind.NOTES, getString(R.string.gallery_field_notes), getString(R.string.gallery_sample_notes));

        DiffRow diffUser = findViewById(R.id.gallery_diff_username);
        diffUser.bind(getString(R.string.gallery_diff_username), getString(R.string.gallery_sample_bank_user),
                getString(R.string.gallery_diff_time_this), getString(R.string.gallery_diff_username_other),
                getString(R.string.gallery_diff_time_other), false);
        DiffRow diffPassword = findViewById(R.id.gallery_diff_password);
        diffPassword.bind(getString(R.string.gallery_diff_password), getString(R.string.gallery_sample_password),
                getString(R.string.gallery_diff_time_this), getString(R.string.gallery_diff_password_other),
                getString(R.string.gallery_diff_time_other), true);
        diffPassword.setChoice(DiffRow.Choice.THIS_DEVICE);
        DiffRow diffNotes = findViewById(R.id.gallery_diff_notes);
        diffNotes.bind(getString(R.string.gallery_diff_notes), "", null,
                getString(R.string.gallery_diff_notes_other), getString(R.string.gallery_diff_time_other), false);
        diffNotes.setChoice(DiffRow.Choice.OTHER_DEVICE);
    }

    private void bindFeedback() {
        findViewById(R.id.gallery_dialog_default).setOnClickListener(v -> showDialog(false));
        findViewById(R.id.gallery_dialog_danger).setOnClickListener(v -> showDialog(true));
        findViewById(R.id.gallery_toast_info).setOnClickListener(
                v -> Toasts.show(v, getString(R.string.gallery_toast_info), Toasts.Kind.INFO));
        findViewById(R.id.gallery_toast_success).setOnClickListener(
                v -> Toasts.show(v, getString(R.string.gallery_toast_success), Toasts.Kind.SUCCESS));
        findViewById(R.id.gallery_toast_error).setOnClickListener(
                v -> Toasts.show(v, getString(R.string.gallery_toast_error), Toasts.Kind.ERROR));
        findViewById(R.id.gallery_toast_sticky).setOnClickListener(
                v -> Toasts.sticky(v, getString(R.string.gallery_copied_toast), Toasts.Kind.INFO));

        ((Banner) findViewById(R.id.gallery_banner_info)).show(Banner.Kind.INFO,
                getString(R.string.gallery_banner_info), null, null);
        ((Banner) findViewById(R.id.gallery_banner_warning)).show(Banner.Kind.WARNING,
                getString(R.string.gallery_banner_warning), null, null);
        ((Banner) findViewById(R.id.gallery_banner_error)).show(Banner.Kind.ERROR,
                getString(R.string.gallery_banner_error), getString(R.string.gallery_banner_action), v -> { });

        findViewById(R.id.gallery_progress_spinner).setOnClickListener(v -> {
            overlay.show(getString(R.string.gallery_progress_unlocking));
            handler.postDelayed(overlay::hide, DEMO_MS);
        });
        findViewById(R.id.gallery_progress_percent).setOnClickListener(v -> runPercentDemo(true));

        ((EmptyState) findViewById(R.id.gallery_empty_action)).show(R.drawable.sv_ic_folder,
                getString(R.string.gallery_empty_title), getString(R.string.gallery_empty_body),
                getString(R.string.gallery_empty_action), v -> { });
        ((EmptyState) findViewById(R.id.gallery_empty_plain)).show(R.drawable.sv_ic_search,
                getString(R.string.gallery_empty_not_found), null, null, null);
    }

    private void bindHost() {
        SvaultApplication app = (SvaultApplication) getApplication();
        findViewById(R.id.gallery_host_open).setOnClickListener(v -> {
            // 展示用：假裝已解鎖，才看得到頁面（正式流程由解鎖畫面呼叫 markUnlocked，T-08）
            app.lockGate().markUnlocked();
            hostLauncher.launch(FeatureHostActivity.intent(this, "gallery", R.string.gallery_host_page_title,
                    GalleryHostPage.class, null));
        });
        findViewById(R.id.gallery_host_broken).setOnClickListener(v -> {
            app.lockGate().markUnlocked();
            startActivity(FeatureHostActivity.intent(this, "gallery", R.string.gallery_host_page_title,
                    GalleryHostPage.Broken.class, null));
        });
        findViewById(R.id.gallery_host_locked).setOnClickListener(v -> {
            app.lockGate().lock();
            startActivity(FeatureHostActivity.intent(this, "gallery", R.string.gallery_host_page_title,
                    GalleryHostPage.class, null));
        });
    }

    private void showDialog(boolean danger) {
        if (danger) {
            ConfirmDialogs.show(this, getString(R.string.gallery_dialog_danger_title),
                    getString(R.string.gallery_dialog_danger_body), getString(R.string.gallery_dialog_danger_confirm),
                    true, (d, w) -> { });
        } else {
            ConfirmDialogs.show(this, getString(R.string.gallery_dialog_default_title),
                    getString(R.string.gallery_dialog_default_body), getString(R.string.gallery_dialog_default_confirm),
                    false, (d, w) -> { });
        }
    }

    private void runPercentDemo(boolean autoHide) {
        String text = getString(R.string.gallery_progress_importing);
        for (int step = 0; step <= 10; step++) {
            int value = step * 10;
            handler.postDelayed(() -> overlay.showProgress(text, value), DEMO_MS * step / 10);
        }
        if (autoHide) {
            handler.postDelayed(overlay::hide, DEMO_MS + DEMO_MS / 4);
        }
    }

    /** 截圖用：捲到指定區塊、直接進入指定狀態。 */
    private void applyStartState() {
        String section = getIntent().getStringExtra("section");
        if (section != null) {
            NestedScrollView scroll = findViewById(R.id.gallery_scroll);
            View target = findViewById(R.id.gallery_content).findViewWithTag(section);
            if (target != null && target.getTop() > 0) {
                scroll.post(() -> scroll.scrollTo(0, target.getTop()));
            } else if (target != null) {
                // 等版面排好（target 有位置）才捲動；只捲一次
                scroll.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (target.getTop() > 0) {
                            scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            scroll.scrollTo(0, target.getTop());
                        }
                    }
                });
            }
        }
        String state = getIntent().getStringExtra("state");
        if (state == null) {
            return;
        }
        View anchor = findViewById(R.id.gallery_root);
        switch (state) {
            case "overlay" -> overlay.show(getString(R.string.gallery_progress_unlocking));
            case "percent" -> overlay.showProgress(getString(R.string.gallery_progress_importing), 60);
            case "dialog" -> anchor.post(() -> showDialog(false));
            case "danger" -> anchor.post(() -> showDialog(true));
            case "toast" -> anchor.post(() -> Toasts.sticky(anchor, getString(R.string.gallery_toast_error),
                    Toasts.Kind.ERROR));
            case "sticky" -> anchor.post(() -> Toasts.sticky(anchor, getString(R.string.gallery_copied_toast),
                    Toasts.Kind.INFO));
            case "success" -> anchor.post(() -> Toasts.sticky(anchor, getString(R.string.gallery_toast_success),
                    Toasts.Kind.SUCCESS));
            default -> { }
        }
    }
}
