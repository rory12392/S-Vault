package app.svault.desktop.feature.generator;

import app.svault.core.feature.generator.GeneratorModel;
import app.svault.core.module.ModuleClipboard;
import app.svault.desktop.ui.Icons;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.SvButton;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * 選單「密碼產生器」的獨立頁（{@code MenuPage}）：產生器內容＋「複製」。外殼給頁首與「返回」，
 * 這裡排邊距與捲動。離開頁面（從場景移除）時清掉結果。
 *
 * <p>「複製」走核心的剪貼簿政策（host 的剪貼簿＝外殼的 {@code ClipboardGuard}，依設定的秒數清除、上鎖時立即清除；
 * T-43）。host 沒有剪貼簿時（{@code copier} 為空，例如測試用的假 host）不顯示「複製」：不自己寫剪貼簿，避免繞過清除規則。
 */
final class GeneratorPage {

    private GeneratorPage() {
    }

    /**
     * @param model  狀態
     * @param text   模組的文字
     * @param copier 核心的剪貼簿；沒有時不提供「複製」
     * @return 頁面內容
     */
    static Node create(GeneratorModel model, ResourceBundle text, Optional<ModuleClipboard> copier) {
        GeneratorPanel panel = new GeneratorPanel(model, text);
        VBox body = new VBox(panel);
        body.getStyleClass().add("sv-gen-page");
        body.getStylesheets().addAll(panel.getStylesheets());
        copier.ifPresent(c -> body.getChildren().add(copyRow(model, text, c)));

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        // 開頁時焦點要落在內容裡的控制項，不是捲動區本身（外殼找第一個可 Tab 到的元件）
        scroll.setFocusTraversable(false);
        scroll.getStyleClass().add("sv-settings-scroll");
        scroll.setId("gen-page");
        scroll.sceneProperty().addListener((obs, old, now) -> {
            if (now == null) {
                model.clear();
            }
        });
        return scroll;
    }

    private static HBox copyRow(GeneratorModel model, ResourceBundle text, ModuleClipboard copier) {
        SvButton copy = SvButton.primary(text.getString("page.copy"));
        copy.setId("gen-copy");
        copy.setGraphic(Icons.of(IconName.CONTENT_COPY));
        Label copied = new Label();
        copied.getStyleClass().add("sv-gen-copied");
        copied.setId("gen-copied");
        long[] copiedId = {-1};
        copy.setOnAction(e -> {
            Optional<Duration> after = model.copy(copier);
            copiedId[0] = model.resultId();
            copied.setText(after.map(d -> MessageFormat.format(text.getString("page.copied"), d.toSeconds()))
                    .orElse(""));
        });
        copy.setDisable(model.invalid());
        model.addListener(() -> {
            copy.setDisable(model.invalid());
            // 換了新的結果，剪貼簿裡的已不是畫面上這一個（強度算完也會通知，那時結果沒變，提示留著）
            if (model.resultId() != copiedId[0]) {
                copied.setText("");
            }
        });
        HBox row = new HBox(copy, copied);
        row.getStyleClass().add("sv-button-row");
        return row;
    }
}
