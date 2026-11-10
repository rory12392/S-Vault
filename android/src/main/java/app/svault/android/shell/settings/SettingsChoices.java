package app.svault.android.shell.settings;

import android.content.Context;
import androidx.annotation.StringRes;
import app.svault.android.R;
import app.svault.core.storage.IntSetting;
import app.svault.core.storage.Settings;
import java.util.ArrayList;
import java.util.List;

/**
 * 設定畫面的選項與文字。範圍與預設值只定義在 core 的 {@link Settings}（D-122）；這裡只挑畫面上
 * 列出的幾個值，全部在範圍內、含預設值。檔案裡是清單以外的合法值（例如手動改過、或之後的版本加了選項）時，
 * 那個值也列出來並保持選取，不會因為開了設定畫面就被改掉。
 */
final class SettingsChoices {

    /** 主題的選項，依畫面順序。 */
    static final List<String> THEMES = List.of(Settings.THEME_LIGHT, Settings.THEME_DARK, Settings.THEME_SYSTEM);

    private static final int[] BACKGROUND_LOCK = {0, 15, 30, 60, 120, 300, 600, 1800, 3600};
    private static final int[] CLIPBOARD = {10, 15, 30, 45, 60, 90, 120, 180, 300};
    private static final int MINUTE = 60;
    private static final int HOUR = 3600;

    /**
     * 一段文字：字串資源與它的參數。
     *
     * @param res  字串資源
     * @param args 格式參數
     */
    record Label(@StringRes int res, List<Object> args) {

        Label {
            args = List.copyOf(args);
        }

        /**
         * @param context 字串資源
         * @return 顯示的文字
         */
        String text(Context context) {
            return context.getString(res, args.toArray());
        }
    }

    private SettingsChoices() {
    }

    /**
     * @param current 目前的秒數
     * @return 「切到背景後自動上鎖」的選項（秒，0＝立即），由小到大
     */
    static List<Integer> backgroundLock(int current) {
        return options(BACKGROUND_LOCK, Settings.AUTO_LOCK_ANDROID_SECONDS, current);
    }

    /**
     * @param current 目前的秒數
     * @return 「剪貼簿清除秒數」的選項，由小到大
     */
    static List<Integer> clipboard(int current) {
        return options(CLIPBOARD, Settings.CLIPBOARD_CLEAR_SECONDS, current);
    }

    private static List<Integer> options(int[] base, IntSetting setting, int current) {
        List<Integer> out = new ArrayList<>();
        boolean added = !setting.accepts(current);
        for (int s : base) {
            if (!added && current <= s) {
                if (current < s) {
                    out.add(current);
                }
                added = true;
            }
            out.add(s);
        }
        if (!added) {
            out.add(current);
        }
        return List.copyOf(out);
    }

    /**
     * @param seconds 秒數（0＝立即）
     * @return 「立即」「15 秒」「1 分鐘」「1 分 30 秒」「1 小時」
     */
    static Label duration(int seconds) {
        if (seconds == 0) {
            return new Label(R.string.settings_immediately, List.of());
        }
        if (seconds % HOUR == 0) {
            return new Label(R.string.settings_hours, List.of(seconds / HOUR));
        }
        if (seconds < MINUTE) {
            return new Label(R.string.settings_seconds, List.of(seconds));
        }
        if (seconds % MINUTE == 0) {
            return new Label(R.string.settings_minutes, List.of(seconds / MINUTE));
        }
        return new Label(R.string.settings_minutes_seconds, List.of(seconds / MINUTE, seconds % MINUTE));
    }

    /**
     * @param theme {@link Settings#theme()} 的值
     * @return 選項的文字
     */
    @StringRes
    static int themeLabel(String theme) {
        if (Settings.THEME_LIGHT.equals(theme)) {
            return R.string.settings_theme_light;
        }
        if (Settings.THEME_DARK.equals(theme)) {
            return R.string.settings_theme_dark;
        }
        return R.string.settings_theme_system;
    }
}
