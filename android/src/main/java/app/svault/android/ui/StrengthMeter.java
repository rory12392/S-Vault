package app.svault.android.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import app.svault.android.R;

/**
 * 密碼強度（AC-04-4）：弱 danger、中 warning、強 success，一律附文字，不只靠顏色。
 * 不可聚焦；TalkBack 讀「密碼強度：弱」。
 */
public class StrengthMeter extends LinearLayout {

    /** 強度等級。 */
    public enum Level {
        /** 還沒有輸入：不顯示等級。 */
        NONE(0, 0, 0),
        WEAK(1, R.color.sv_danger, R.string.sv_strength_weak),
        MEDIUM(2, R.color.sv_warning, R.string.sv_strength_medium),
        STRONG(3, R.color.sv_success, R.string.sv_strength_strong);

        final int segments;
        @ColorRes final int color;
        @StringRes final int label;

        Level(int segments, int color, int label) {
            this.segments = segments;
            this.color = color;
            this.label = label;
        }
    }

    private final View[] segments;
    private final TextView label;
    private Level level = Level.NONE;

    public StrengthMeter(Context context) {
        this(context, null);
    }

    public StrengthMeter(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setFocusable(false);
        LayoutInflater.from(context).inflate(R.layout.sv_strength_meter, this, true);
        segments = new View[] {findViewById(R.id.sv_meter_1), findViewById(R.id.sv_meter_2),
                findViewById(R.id.sv_meter_3)};
        label = findViewById(R.id.sv_meter_label);
        setLevel(Level.NONE);
    }

    /** @param newLevel 目前的強度 */
    public void setLevel(Level newLevel) {
        level = newLevel;
        ColorStateList on = newLevel == Level.NONE ? null
                : ColorStateList.valueOf(ContextCompat.getColor(getContext(), newLevel.color));
        for (int i = 0; i < segments.length; i++) {
            // 未達到的段保留 drawable 本身的 surface-alt
            ViewCompat.setBackgroundTintList(segments[i], i < newLevel.segments ? on : null);
        }
        if (newLevel == Level.NONE) {
            label.setText("");
            label.setVisibility(INVISIBLE);
            setContentDescription(null);
            return;
        }
        String text = getResources().getString(newLevel.label);
        label.setText(text);
        label.setTextColor(on);
        label.setVisibility(VISIBLE);
        setContentDescription(getResources().getString(R.string.sv_strength_accessible, text));
        // 強度隨輸入改變時讀出新的等級，但不打斷正在讀的內容
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    /** @return 目前的強度 */
    public Level getLevel() {
        return level;
    }
}
