package app.svault.desktop.ui;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * 條目清單的一列（EntryRow）：圖示＋名稱＋帳號＋複製按鈕，列高 48。
 * 搜尋時名稱與帳號中的關鍵字以粗體 primary 標示。放在 {@code ListView} 時用 {@link #cell}。
 */
public final class EntryRow extends HBox {

    private final TextFlow title = new TextFlow();
    private final TextFlow username = new TextFlow();
    private final IconButton copy;

    /**
     * @param onCopyPassword 複製密碼的動作
     */
    public EntryRow(Runnable onCopyPassword) {
        getStyleClass().add("sv-entry-row");
        StackPane badge = new StackPane(Icons.of(IconName.KEY));
        badge.getStyleClass().add("sv-entry-badge");
        title.getStyleClass().add("sv-entry-title");
        username.getStyleClass().add("sv-entry-username");
        VBox texts = new VBox(title, username);
        texts.getStyleClass().add("sv-entry-texts");
        HBox.setHgrow(texts, Priority.ALWAYS);
        // 長的帳號或群組路徑不把複製鈕擠出列外：文字區可以縮到 0，超出的部分裁掉（TextFlow 不會自己加刪節號）
        texts.setMinWidth(0);
        texts.setPrefWidth(0);
        // TextFlow 被壓窄時會換行、超出固定的列高；讓它維持一行的寬度，由上面的 clip 裁掉
        texts.setFillWidth(false);
        title.setMinWidth(Region.USE_PREF_SIZE);
        username.setMinWidth(Region.USE_PREF_SIZE);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(texts.widthProperty());
        clip.heightProperty().bind(texts.heightProperty());
        texts.setClip(clip);
        copy = new IconButton(IconName.CONTENT_COPY, UiText.get("entry.copyPassword"));
        // 清單裡的複製鈕不進 Tab 順序：鍵盤在清單上用 Ctrl+C，避免每列都多一個 Tab 停留點
        copy.setFocusTraversable(false);
        copy.setOnAction(e -> onCopyPassword.run());
        getChildren().addAll(badge, texts, copy);
    }

    /**
     * @param titleText    名稱
     * @param usernameText 帳號（可為空）
     * @param query        搜尋關鍵字；不是搜尋結果時傳空字串
     */
    public void show(String titleText, String usernameText, String query) {
        show(titleText, usernameText, query, null);
    }

    /**
     * 搜尋結果的變體：第二行在帳號後面接群組路徑（列高維持 48）。
     *
     * @param titleText    名稱
     * @param usernameText 帳號（可為空）
     * @param query        搜尋關鍵字；不是搜尋結果時傳空字串
     * @param groupPath    群組路徑；不是搜尋結果時 {@code null}
     */
    public void show(String titleText, String usernameText, String query, String groupPath) {
        String user = usernameText == null ? "" : usernameText;
        boolean hasPath = groupPath != null && !groupPath.isEmpty();
        fill(title, titleText, query);
        fill(username, user, query);
        if (hasPath) {
            Text path = new Text(user.isEmpty() ? groupPath : UiText.format("entry.groupPath", groupPath));
            path.getStyleClass().add("sv-text");
            username.getChildren().add(path);
        }
        username.setVisible(!user.isEmpty() || hasPath);
        username.setManaged(username.isVisible());
        copy.setAccessibleText(UiText.format("entry.copyPasswordOf", titleText));
        String accessible = user.isEmpty() ? titleText : UiText.format("entry.accessible", titleText, user);
        setAccessibleText(hasPath ? UiText.format("entry.accessibleInGroup", accessible, groupPath) : accessible);
    }

    private static void fill(TextFlow flow, String text, String query) {
        flow.getChildren().clear();
        for (Highlight.Segment s : Highlight.split(text, query)) {
            Text t = new Text(s.text());
            t.getStyleClass().add(s.match() ? "sv-highlight" : "sv-text");
            flow.getChildren().add(t);
        }
    }

    /**
     * {@code ListView} 用的 cell：每個 cell 重用一個 EntryRow。
     *
     * @param <T>      清單項目型別
     * @param titleOf  取名稱
     * @param userOf   取帳號
     * @param query    目前的搜尋關鍵字
     * @param onCopy   複製某一項的密碼
     * @return cell factory 可用的 cell
     */
    public static <T> ListCell<T> cell(Function<T, String> titleOf, Function<T, String> userOf,
            Supplier<String> query, Consumer<T> onCopy) {
        return cell(titleOf, userOf, item -> null, query, onCopy);
    }

    /**
     * 同 {@link #cell(Function, Function, Supplier, Consumer)}，搜尋結果時每列多顯示群組路徑。
     *
     * @param <T>      清單項目型別
     * @param titleOf  取名稱
     * @param userOf   取帳號
     * @param pathOf   取群組路徑；不是搜尋結果時回傳 {@code null}
     * @param query    目前的搜尋關鍵字
     * @param onCopy   複製某一項的密碼
     * @return cell factory 可用的 cell
     */
    public static <T> ListCell<T> cell(Function<T, String> titleOf, Function<T, String> userOf,
            Function<T, String> pathOf, Supplier<String> query, Consumer<T> onCopy) {
        return new ListCell<>() {
            private final EntryRow row = new EntryRow(() -> {
                if (getItem() != null) {
                    onCopy.accept(getItem());
                }
            });

            {
                getStyleClass().add("sv-entry-cell");
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setPrefWidth(Region.USE_COMPUTED_SIZE);
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setAccessibleText(null);
                } else {
                    row.show(titleOf.apply(item), userOf.apply(item), query.get(), pathOf.apply(item));
                    setGraphic(row);
                    setAccessibleText(row.getAccessibleText());
                }
            }
        };
    }
}
