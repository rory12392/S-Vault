package app.svault.desktop.shell;

import app.svault.desktop.shell.WindowStateStore.Bounds;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * 主視窗的大小與位置：最小 900×600，第一次開啟用常用的 1280×800，之後還原上次關閉時的位置。
 * 上次的位置已不在任何螢幕上（例如拔掉外接螢幕）時改回預設位置。
 */
public final class WindowPlacement {

    /** 第一次開啟的寬。 */
    public static final double DEFAULT_WIDTH = 1280;
    /** 第一次開啟的高。 */
    public static final double DEFAULT_HEIGHT = 800;

    private final WindowStateStore store;
    private Bounds normal;

    /**
     * @param store 紀錄檔；不記錄時傳 {@code null}（展示頁的截圖模式）
     */
    public WindowPlacement(WindowStateStore store) {
        this.store = store;
    }

    /**
     * 在 {@code stage.show()} 之前呼叫：設定最小大小、還原位置，並在視窗關閉時記錄。
     *
     * @param stage 主視窗
     */
    public void attach(Stage stage) {
        stage.setMinWidth(AppShell.MIN_WIDTH);
        stage.setMinHeight(AppShell.MIN_HEIGHT);
        Optional<Bounds> saved = store == null ? Optional.empty() : store.load();
        List<Rectangle2D> screens = Screen.getScreens().stream().map(Screen::getVisualBounds).toList();
        Bounds b = choose(saved, screens, Screen.getPrimary().getVisualBounds());
        stage.setX(b.x());
        stage.setY(b.y());
        stage.setWidth(b.width());
        stage.setHeight(b.height());
        stage.setMaximized(b.maximized());
        normal = b;
        // 最大化時的大小不是使用者要記的「一般大小」，只在非最大化時更新
        Runnable track = () -> {
            if (!stage.isMaximized() && !stage.isIconified()) {
                normal = new Bounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), false);
            }
        };
        stage.xProperty().addListener((o, a, v) -> track.run());
        stage.yProperty().addListener((o, a, v) -> track.run());
        stage.widthProperty().addListener((o, a, v) -> track.run());
        stage.heightProperty().addListener((o, a, v) -> track.run());
        if (store != null) {
            stage.addEventHandler(WindowEvent.WINDOW_HIDING, e -> store.save(
                    new Bounds(normal.x(), normal.y(), normal.width(), normal.height(), stage.isMaximized())));
        }
    }

    /**
     * 決定開啟時的位置（純計算，可測試）。
     *
     * @param saved   上次的紀錄
     * @param screens 各螢幕的可用範圍
     * @param primary 主螢幕的可用範圍
     * @return 要套用的位置與大小
     */
    static Bounds choose(Optional<Bounds> saved, List<Rectangle2D> screens, Rectangle2D primary) {
        if (saved.isPresent()) {
            Bounds b = saved.get().atLeast(AppShell.MIN_WIDTH, AppShell.MIN_HEIGHT);
            // 標題列附近（左上角往內 48px）要落在某個螢幕上，使用者才拖得動視窗
            boolean reachable = screens.stream().anyMatch(s -> s.contains(b.x() + 48, b.y() + 16));
            if (reachable) {
                return b;
            }
        }
        double w = Math.max(AppShell.MIN_WIDTH, Math.min(DEFAULT_WIDTH, primary.getWidth()));
        double h = Math.max(AppShell.MIN_HEIGHT, Math.min(DEFAULT_HEIGHT, primary.getHeight()));
        double x = primary.getMinX() + Math.max(0, (primary.getWidth() - w) / 2);
        double y = primary.getMinY() + Math.max(0, (primary.getHeight() - h) / 2);
        return new Bounds(x, y, w, h, false);
    }
}
