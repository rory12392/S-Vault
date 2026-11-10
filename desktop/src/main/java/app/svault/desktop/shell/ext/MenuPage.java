package app.svault.desktop.shell.ext;

import java.util.function.Supplier;
import javafx.scene.Node;

/**
 * 選單中的獨立頁面。外殼在使用者打開頁面時才呼叫 {@code factory}（包在 {@code ModuleGuard} 裡）。
 *
 * @param title   頁面標題
 * @param factory 建立頁面內容
 */
public record MenuPage(String title, Supplier<Node> factory) { }
