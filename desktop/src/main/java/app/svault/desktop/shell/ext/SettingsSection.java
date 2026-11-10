package app.svault.desktop.shell.ext;

import java.util.function.Supplier;
import javafx.scene.Node;

/**
 * 設定畫面中的模組段落。讀寫自己的設定用 {@link DesktopFeature#attach(ModuleHost)} 拿到的
 * {@link ModuleHost#preferences()}，改了就生效、沒有「儲存」按鈕（T-22）。
 *
 * @param title   段落標題
 * @param factory 建立段落內容
 */
public record SettingsSection(String title, Supplier<Node> factory) { }
