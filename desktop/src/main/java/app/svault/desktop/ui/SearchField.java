package app.svault.desktop.ui;

import javafx.beans.property.StringProperty;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * 條目搜尋欄（SearchField）：有字時顯示清除鈕，Esc 清空。{@code Ctrl+F} 聚焦由畫面以
 * {@link Shortcut#SEARCH} 註冊後呼叫 {@link #focusField()}。
 */
public final class SearchField extends HBox {

    private final TextField field = new TextField();
    private final IconButton clear = new IconButton(IconName.CLOSE, UiText.get("search.clear"));

    /** 建立空的搜尋欄。 */
    public SearchField() {
        getStyleClass().add("sv-search-field");
        field.getStyleClass().add("sv-search-input");
        field.setPromptText(UiText.get("search.prompt"));
        field.setAccessibleText(UiText.get("search.accessible"));
        HBox.setHgrow(field, Priority.ALWAYS);
        clear.getStyleClass().add("sv-icon-button-small");
        // 清除鈕不放進 Tab 順序：Esc 有同樣的效果
        clear.setFocusTraversable(false);
        clear.setOnAction(e -> {
            field.clear();
            field.requestFocus();
        });
        clear.visibleProperty().bind(field.textProperty().isNotEmpty());
        clear.managedProperty().bind(clear.visibleProperty());
        field.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !field.getText().isEmpty()) {
                field.clear();
                e.consume();
            }
        });
        getChildren().addAll(Icons.of(IconName.SEARCH), field, clear);
    }

    /** @return 搜尋文字 */
    public StringProperty textProperty() {
        return field.textProperty();
    }

    /** @return 內部的文字欄位 */
    public TextField field() {
        return field;
    }

    /** 聚焦並全選，方便直接輸入新的關鍵字。 */
    public void focusField() {
        field.requestFocus();
        field.selectAll();
    }
}
