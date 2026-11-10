package app.svault.desktop.shell.settings;

import app.svault.core.shell.Notices;
import app.svault.desktop.ui.IconButton;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.ThemeManager;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

/**
 * 關於／開源授權：App 名稱、版本、一行說明、資料位置（可複製）、
 * 開源授權清單（{@code NOTICES.md}，每一列可展開看授權全文）。
 *
 * <p>鍵盤：Tab 到每一列的標題，空白鍵或 Enter 展開；Esc 或「關閉」關閉。
 */
final class AboutDialog {

    private static final double SCROLL_WIDTH = 480;
    private static final double SCROLL_HEIGHT = 360;
    private static final int LICENSE_ROWS = 12;
    /** 標題列放得下的大約字數；更長的名稱展開後另外完整顯示一次。 */
    private static final int TITLE_MAX = 40;

    private final Dialog<ButtonType> dialog = new Dialog<>();

    /**
     * @param owner   觸發的元件（決定擁有者視窗與主題）
     * @param text    文字
     * @param version {@link AboutInfo#VERSION_PROPERTY} 的值；開發執行時 {@code null}
     * @param dataDir 本機資料夾；沒有時 {@code null}
     * @param copy    複製資料位置（一般文字，不清除）並顯示提示
     */
    AboutDialog(Node owner, ResourceBundle text, String version, Path dataDir, Consumer<String> copy) {
        String title = text.getString("about.title");
        dialog.setTitle(title);
        dialog.initModality(Modality.WINDOW_MODAL);
        if (owner != null && owner.getScene() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            ThemeManager theme = ThemeManager.of(owner.getScene());
            if (theme != null) {
                theme.track(dialog.getDialogPane());
            }
        }

        Label name = new Label(text.getString("about.appName"));
        name.getStyleClass().add("sv-page-title");
        Label versionLabel = new Label(AboutInfo.version(version, text));
        versionLabel.setId("about-version");
        versionLabel.getStyleClass().add("sv-caption");
        Label tagline = new Label(text.getString("about.tagline"));
        tagline.setWrapText(true);

        VBox body = new VBox(name, versionLabel, tagline);
        body.getStyleClass().add("sv-about-body");
        if (dataDir != null) {
            body.getChildren().add(dataDirRow(text, dataDir.toString(), copy));
        }
        Label licenses = new Label(text.getString("about.licenses"));
        licenses.getStyleClass().add("sv-section-title");
        Label licensesBody = new Label(text.getString("about.licenses.description"));
        licensesBody.getStyleClass().add("sv-caption");
        licensesBody.setWrapText(true);
        body.getChildren().addAll(licenses, licensesBody);
        for (Notices.Section section : Notices.forPlatform(Notices.Platform.DESKTOP)) {
            Label heading = new Label(section.title());
            heading.getStyleClass().add("sv-about-group");
            body.getChildren().add(heading);
            for (Notices.Item item : section.items()) {
                body.getChildren().add(itemPane(text, item));
            }
        }

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(SCROLL_WIDTH);
        scroll.setPrefViewportHeight(SCROLL_HEIGHT);
        scroll.getStyleClass().add("sv-about-scroll");
        VBox content = new VBox(scroll);
        content.getStyleClass().add("sv-dialog-content");
        dialog.getDialogPane().getStyleClass().add("sv-dialog");
        dialog.getDialogPane().setContent(content);

        ButtonType closeType = new ButtonType(text.getString("about.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);
        Button close = (Button) dialog.getDialogPane().lookupButton(closeType);
        close.getStyleClass().addAll("sv-button", "sv-button-secondary");
        close.setId("about-close");
    }

    /** 顯示並等它關閉。 */
    void showAndWait() {
        dialog.showAndWait();
    }

    private static Node dataDirRow(ResourceBundle text, String path, Consumer<String> copy) {
        Label label = new Label(text.getString("about.dataDir"));
        label.getStyleClass().add("sv-field-row-label");
        Label value = new Label(path);
        value.getStyleClass().add("sv-field-row-value");
        value.setWrapText(true);
        value.setId("about-data-dir");
        VBox texts = new VBox(label, value);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        IconButton copyButton = new IconButton(IconName.CONTENT_COPY, text.getString("about.copyDataDir"));
        copyButton.setId("about-copy-data-dir");
        copyButton.setOnAction(e -> copy.accept(path));
        label.setLabelFor(copyButton);
        HBox row = new HBox(texts, copyButton);
        row.getStyleClass().add("sv-about-data-dir");
        return row;
    }

    private static TitledPane itemPane(ResourceBundle text, Notices.Item item) {
        VBox details = new VBox();
        details.getStyleClass().add("sv-about-item");
        for (String line : AboutInfo.details(item, text)) {
            Label l = new Label(line);
            l.getStyleClass().add("sv-caption");
            l.setWrapText(true);
            details.getChildren().add(l);
        }
        TitledPane pane = new TitledPane(item.name(), details);
        // 名稱很長的列（Material Symbols 的圖示清單）在標題列以刪節號截斷，完整名稱在展開後的內容裡；
        // 不然它會把整個清單撐寬，出現橫向捲軸、資料位置的複製鈕被擠到看不到的地方
        pane.setMinWidth(0);
        pane.setMaxWidth(Double.MAX_VALUE);
        if (item.name().length() > TITLE_MAX) {
            Label fullName = new Label(item.name());
            fullName.setWrapText(true);
            details.getChildren().add(0, fullName);
        }
        pane.setExpanded(false);
        pane.setAnimated(false);
        pane.getStyleClass().add("sv-about-license");
        // 全文（GPL 約 19 KB）展開時才建立，開對話框不必一次排版二十幾份
        pane.expandedProperty().addListener((obs, old, expanded) -> {
            if (expanded && details.getChildren().stream().noneMatch(n -> n instanceof TextArea)) {
                TextArea full = new TextArea(AboutInfo.licenseText(item));
                full.setEditable(false);
                full.setWrapText(true);
                full.setPrefRowCount(LICENSE_ROWS);
                full.getStyleClass().add("sv-mono");
                full.setAccessibleText(MessageFormat.format(text.getString("about.licenseText"), item.name()));
                details.getChildren().add(full);
            }
        });
        return pane;
    }
}
