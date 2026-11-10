package app.svault.desktop.shell;

import app.svault.desktop.ui.ThemeMode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 開發用的啟動參數。只有系統屬性 {@code svault.dev=true}（{@code :desktop:run} 設定）時才生效；
 * jpackage 打包版沒有這個屬性，傳入時當成未知參數忽略。
 *
 * <ul>
 *   <li>{@code --ui-gallery}：元件展示頁</li>
 *   <li>{@code --theme=light|dark|system}：覆寫主題</li>
 *   <li>{@code --size=1280x800}：視窗內容大小（截圖用；不讀寫視窗位置紀錄）</li>
 *   <li>{@code --snapshot=<png>}：畫面穩定後把場景存成 PNG 並結束（截圖用）</li>
 *   <li>{@code --gallery-state=<狀態>}：展示頁開啟時的狀態（{@code centered}、{@code toasts}、{@code overlay}、
 *       {@code overlay-spin}、{@code dialog}、{@code narrow-groups}、{@code scroll-1}～{@code scroll-5}；
 *       {@code keys}＝鍵盤檢查，結果寫進日誌後結束）</li>
 *   <li>{@code --drive=<步驟|步驟|…>}：在歡迎、建立、解鎖畫面上執行畫面腳本（{@code gallery.ScreenDriver}），
 *       選檔對話框改用腳本指定的結果</li>
 *   <li>{@code --demo-modules}：加上兩個展示模組（{@code gallery.DemoModules}：正常的模組頁面與建立失敗的頁面），
 *       截模組選單與 {@code EntryNavigator} 用（T-30）</li>
 * </ul>
 *
 * @param gallery      是否開啟展示頁
 * @param theme        主題覆寫；沒有時 {@code null}
 * @param width        內容寬；沒有時 0
 * @param height       內容高；沒有時 0
 * @param snapshot     截圖輸出檔；沒有時 {@code null}
 * @param galleryState 展示頁狀態；沒有時空字串
 * @param drive        畫面腳本；沒有時空字串
 * @param demoModules  是否加上展示模組
 */
public record DevOptions(boolean gallery, ThemeMode theme, double width, double height, Path snapshot,
        String galleryState, String drive, boolean demoModules) {

    /** 開發屬性名稱。 */
    public static final String DEV_PROPERTY = "svault.dev";

    /** 一般啟動（沒有任何開發參數）。 */
    public static final DevOptions NONE = new DevOptions(false, null, 0, 0, null, "", "", false);

    /**
     * @param args    命令列參數
     * @param devMode {@code svault.dev} 是否為 {@code true}
     * @return 解析結果；非開發模式一律 {@link #NONE}
     */
    public static DevOptions parse(List<String> args, boolean devMode) {
        if (!devMode) {
            return NONE;
        }
        boolean gallery = false;
        ThemeMode theme = null;
        double w = 0;
        double h = 0;
        Path snapshot = null;
        String state = "";
        String drive = "";
        boolean demo = false;
        for (String a : args) {
            if (a.equals("--ui-gallery")) {
                gallery = true;
            } else if (a.startsWith("--theme=")) {
                theme = ThemeMode.fromSetting(a.substring("--theme=".length()));
            } else if (a.startsWith("--size=")) {
                String[] parts = a.substring("--size=".length()).split("x");
                if (parts.length == 2) {
                    w = Double.parseDouble(parts[0]);
                    h = Double.parseDouble(parts[1]);
                }
            } else if (a.startsWith("--snapshot=")) {
                snapshot = Paths.get(a.substring("--snapshot=".length()));
            } else if (a.startsWith("--gallery-state=")) {
                state = a.substring("--gallery-state=".length());
            } else if (a.startsWith("--drive=")) {
                drive = a.substring("--drive=".length());
            } else if (a.equals("--demo-modules")) {
                demo = true;
            }
        }
        return new DevOptions(gallery, theme, w, h, snapshot, state, drive, demo);
    }

    /** @return 是否指定了固定的視窗大小 */
    public boolean fixedSize() {
        return width > 0 && height > 0;
    }
}
