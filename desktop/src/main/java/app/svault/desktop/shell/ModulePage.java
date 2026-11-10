package app.svault.desktop.shell;

import app.svault.desktop.ui.EmptyState;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.Icons;
import app.svault.desktop.ui.PageHeader;
import app.svault.desktop.ui.ScrollFollow;
import app.svault.desktop.ui.SvButton;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 功能模組頁面的外框（T-30；DESIGN §4「佔用右側兩欄的頁面」）：PageHeader＝模組給的標題＋ghost「返回」（Esc 同），
 * 下面是模組的內容（填滿剩下的高度；要捲動時模組自己放 ScrollPane）。內容建立失敗時是錯誤狀態。
 */
final class ModulePage extends VBox {

    private final SvButton close;
    private final Node content;

    /**
     * @param title   頁首標題
     * @param content 模組的內容；建立失敗時為空
     * @param onClose 回到條目清單與詳細
     */
    ModulePage(String title, Optional<Node> content, Runnable onClose) {
        ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.shell.messages");
        getStyleClass().add("sv-module-page");
        setId("module-page");
        close = SvButton.ghost(text.getString("module.page.close"));
        close.setGraphic(Icons.of(IconName.CHEVRON_LEFT));
        close.setId("module-page-close");
        close.setOnAction(e -> onClose.run());
        PageHeader header = new PageHeader(title, close);
        this.content = content.orElse(null);
        Node body = content.orElseGet(() -> {
            EmptyState error = new EmptyState(IconName.ERROR, text.getString("module.page.error.title"),
                    text.getString("module.page.error.body"), null, null);
            error.setId("module-page-error");
            return error;
        });
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(header, body);
        // 模組自己的 ScrollPane 裡，Tab 到可視範圍外的控制項時捲過去（T-112，原本只有匯入頁自己處理）
        ScrollFollow.install(this);
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            // 模組的控制項自己處理過 Esc（例如關掉下拉）時不關頁面
            if (e.getCode() == KeyCode.ESCAPE && !e.isConsumed()) {
                e.consume();
                onClose.run();
            }
        });
    }

    /** 開啟時的焦點：模組內容裡第一個可以 Tab 到的控制項；沒有（或錯誤狀態）時是「返回」。 */
    void focusDefault() {
        Node first = content == null ? null : firstFocusable(content);
        (first != null ? first : close).requestFocus();
    }

    private static Node firstFocusable(Node node) {
        if (!node.isVisible() || node.isDisabled()) {
            return null;
        }
        if (node.isFocusTraversable()) {
            return node;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = firstFocusable(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
