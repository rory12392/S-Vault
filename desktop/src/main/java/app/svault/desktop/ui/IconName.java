package app.svault.desktop.ui;

import java.util.Locale;

/**
 * {@code icons.css} 中有路徑的 Material Symbols 圖示。新增圖示時兩邊一起加（{@code IconsCssTest} 會檢查）。
 */
public enum IconName {
    ADD, CHECK_CIRCLE, CHEVRON_LEFT, CHEVRON_RIGHT, CLOSE, CONTENT_COPY, DARK_MODE, DELETE, EDIT, ERROR, FOLDER,
    INFO, KEY, LIGHT_MODE, LOCK, LOCK_OPEN, MENU, MORE_VERT, OPEN_IN_NEW, SEARCH, SETTINGS, SYNC, VISIBILITY,
    VISIBILITY_OFF, WARNING;

    /** @return style class，例如 {@code sv-icon-content-copy} */
    public String styleClass() {
        return "sv-icon-" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
