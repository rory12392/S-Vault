package app.svault.android.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import app.svault.android.R;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import java.util.ArrayList;
import java.util.List;

/**
 * 解鎖、背景合併、匯入中的覆蓋層：顯示期間吃掉下方的觸控與 TalkBack 焦點，文字說明在做什麼。
 * 放在畫面根部（FrameLayout 或 CoordinatorLayout）的最後一個子元素，寬高 match_parent，預設隱藏。
 */
public class ProgressOverlay extends FrameLayout {

    private final View card;
    private final CircularProgressIndicator indicator;
    private final TextView percent;
    private final TextView text;
    private final List<View> hiddenSiblings = new ArrayList<>();

    public ProgressOverlay(Context context) {
        this(context, null);
    }

    public ProgressOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setBackgroundResource(R.color.sv_shadow);
        // 遮罩本身可點：吃掉下方的觸控，「鎖住下方操作」
        setClickable(true);
        setVisibility(GONE);
        LayoutInflater.from(context).inflate(R.layout.sv_progress_overlay, this, true);
        card = findViewById(R.id.sv_overlay_card);
        indicator = findViewById(R.id.sv_overlay_indicator);
        percent = findViewById(R.id.sv_overlay_percent);
        text = findViewById(R.id.sv_overlay_text);
        // 出現時由 TalkBack 讀出在做什麼（announceForAccessibility 在 API 36 已不建議使用）
        card.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    /**
     * 顯示不確定進度。
     *
     * @param message 在做什麼（「正在解鎖…」）
     */
    public void show(CharSequence message) {
        indicator.setIndeterminate(true);
        percent.setVisibility(GONE);
        open(message);
    }

    /**
     * 顯示或更新百分比進度。
     *
     * @param message 在做什麼
     * @param value   0–100
     */
    public void showProgress(CharSequence message, int value) {
        int v = Math.max(0, Math.min(100, value));
        indicator.setIndeterminate(false);
        indicator.setProgressCompat(v, true);
        percent.setText(getResources().getString(R.string.sv_progress_percent, v));
        percent.setVisibility(VISIBLE);
        open(message);
        card.setContentDescription(message + "，" + percent.getText());
    }

    /** 隱藏並把 TalkBack 焦點還給下方畫面。 */
    public void hide() {
        setVisibility(GONE);
        for (View v : hiddenSiblings) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
        hiddenSiblings.clear();
    }

    /** @return 目前是否顯示中 */
    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    private void open(CharSequence message) {
        text.setText(message);
        card.setContentDescription(message);
        if (!isShowing()) {
            setVisibility(VISIBLE);
            hideSiblingsFromAccessibility();
            card.requestFocus();
        }
    }

    private void hideSiblingsFromAccessibility() {
        if (!(getParent() instanceof ViewGroup parent)) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child != this && child.getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
                child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                hiddenSiblings.add(child);
            }
        }
    }
}
