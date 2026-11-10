package app.svault.android.testmodules;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.PasswordFieldAction;
import app.svault.android.shell.ext.PasswordFill;
import app.svault.android.ui.SecureWindows;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import java.util.List;

/**
 * 儀器測試用的密碼欄動作模組（T-37 的 {@code PasswordFillScreensTest}）：真的 GEN 底部 sheet 是 T-44，這裡用最小的
 * sheet 走一遍「按下 → 開 sheet → 使用這個密碼 → 回填」。按鈕文字用系統字串（測試 APK 的字串資源不在 App 的資源表裡）。
 * 測試讀得到的狀態放在靜態欄位（只在測試裡；正式模組把回填存在 sheet 自己的欄位）。
 */
public final class FillModules {

    /** sheet 裡的密碼（假資料）。 */
    public static final String SHEET_VALUE = "Demo-Gen-2027!q";
    /** 同步動作回傳的密碼（假資料）。 */
    public static final String SYNC_VALUE = "Sync-Gen-0001";
    /** sheet 的 tag。 */
    public static final String SHEET_TAG = "testfill-sheet";
    /** sheet 的按鈕文字。 */
    public static final String USE_TEXT = "使用這個密碼";
    /** sheet 的取消文字。 */
    public static final String CANCEL_TEXT = "取消（測試）";

    /** 最近一次交給非同步模組的回填。 */
    public static volatile PasswordFill lastFill;
    /** 最近一次交出去的陣列（測試確認外殼清零）。 */
    public static volatile char[] lastValue;

    private FillModules() {
    }

    /** 清掉上一個測試留下的狀態。 */
    public static void reset() {
        lastFill = null;
        lastValue = null;
    }

    /** 開底部 sheet、使用者按「使用這個密碼」才回填。 */
    public static final class AsyncSheet implements AndroidFeature {
        /** 代號。 */
        public static final String ID = "testfill";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String displayName() {
            return "測試回填";
        }

        @Override
        public List<PasswordFieldAction> passwordFieldActions() {
            return List.of(new PasswordFieldAction() {
                @Override
                public int labelRes() {
                    return android.R.string.paste;
                }

                @Override
                public char[] perform(Context context) {
                    // 只用非同步版
                    return null;
                }

                @Override
                public void perform(Context context, PasswordFill fill) {
                    lastFill = fill;
                    Sheet sheet = new Sheet();
                    sheet.fill = fill;
                    sheet.show(((FragmentActivity) context).getSupportFragmentManager(), SHEET_TAG);
                }
            });
        }
    }

    /** 只實作同步版（既有的行為）。 */
    public static final class Sync implements AndroidFeature {
        @Override
        public String id() {
            return "testsync";
        }

        @Override
        public String displayName() {
            return "測試同步";
        }

        @Override
        public List<PasswordFieldAction> passwordFieldActions() {
            return List.of(new PasswordFieldAction() {
                @Override
                public int labelRes() {
                    return android.R.string.copy;
                }

                @Override
                public char[] perform(Context context) {
                    char[] v = SYNC_VALUE.toCharArray();
                    lastValue = v;
                    return v;
                }
            });
        }
    }

    /** 按下就丟例外。 */
    public static final class Crashing implements AndroidFeature {
        /** 代號。 */
        public static final String ID = "testfillcrash";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String displayName() {
            return "測試回填出錯";
        }

        @Override
        public List<PasswordFieldAction> passwordFieldActions() {
            return List.of(new PasswordFieldAction() {
                @Override
                public int labelRes() {
                    return android.R.string.cut;
                }

                @Override
                public char[] perform(Context context) {
                    throw new IllegalStateException("測試：動作失敗");
                }
            });
        }
    }

    /** 按鈕文字的資源是 0：外殼不顯示它的按鈕。 */
    public static final class NoLabel implements AndroidFeature {
        /** 代號。 */
        public static final String ID = "testnolabel";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String displayName() {
            return "測試沒有文字";
        }

        @Override
        public List<PasswordFieldAction> passwordFieldActions() {
            return List.of(new PasswordFieldAction() {
                @Override
                public int labelRes() {
                    return 0;
                }

                @Override
                public char[] perform(Context context) {
                    return null;
                }
            });
        }
    }

    /**
     * 最小的底部 sheet。被 FragmentManager 以儲存的狀態還原時丟例外：外殼沒有在重建時換成佔位的話，App 會結束。
     */
    public static final class Sheet extends BottomSheetDialogFragment {
        private static final String KEY = "restored";
        @Nullable PasswordFill fill;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (savedInstanceState != null && savedInstanceState.getBoolean(KEY)) {
                throw new IllegalStateException("測試：sheet 被還原");
            }
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            // 對話框是另一個視窗，不繼承 Activity 的 FLAG_SECURE（SecureWindows 的說明）
            return SecureWindows.inherit(super.onCreateDialog(savedInstanceState));
        }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            Context c = inflater.getContext();
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(24 * c.getResources().getDisplayMetrics().density);
            box.setPadding(pad, pad, pad, pad);
            TextView label = new TextView(c);
            label.setText("產生的密碼（測試模組）");
            TextView value = new TextView(c);
            value.setText(SHEET_VALUE);
            MaterialButton use = new MaterialButton(c);
            use.setText(USE_TEXT);
            use.setOnClickListener(v -> {
                char[] chars = SHEET_VALUE.toCharArray();
                lastValue = chars;
                if (fill != null) {
                    fill.fill(chars);
                }
                dismiss();
            });
            MaterialButton cancel = new MaterialButton(c);
            cancel.setText(CANCEL_TEXT);
            cancel.setOnClickListener(v -> dismiss());
            box.addView(label);
            box.addView(value);
            box.addView(use);
            box.addView(cancel);
            return box;
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putBoolean(KEY, true);
        }
    }
}
