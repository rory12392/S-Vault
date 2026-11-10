package app.svault.desktop.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.application.ColorScheme;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * 主題。
 *
 * <p>每個場景的樣式表依序是：主題（{@code theme-light.css}／{@code theme-dark.css}，只有這份會被替換）、
 * {@code icons.css}、{@code base.css}。{@link #track(Scene)} 要在 {@code stage.show()} 之前呼叫，畫面才不會先閃淺色。
 * 同時設定 {@code Scene.Preferences} 的色彩配置，Windows 的原生標題列跟著變深色（D-18）。
 */
public final class ThemeManager {

    private static final String BASE = "/app/svault/desktop/ui/";
    private static final String KEY = ThemeManager.class.getName();

    private final ObjectProperty<ThemeMode> mode = new SimpleObjectProperty<>(this, "mode");
    private final ReadOnlyObjectWrapper<ColorScheme> effective = new ReadOnlyObjectWrapper<>(this, "effective");
    private final ObservableValue<ColorScheme> system;
    private final List<Scene> scenes = new ArrayList<>();

    /**
     * @param initial 初始模式（之後由 {@code settings.json} 提供）
     * @param system  系統的色彩配置，通常是 {@code Platform.getPreferences().colorSchemeProperty()}
     */
    public ThemeManager(ThemeMode initial, ObservableValue<ColorScheme> system) {
        this.system = Objects.requireNonNull(system, "system");
        mode.set(Objects.requireNonNull(initial, "initial"));
        mode.addListener((obs, old, now) -> refresh());
        system.addListener((obs, old, now) -> refresh());
        effective.set(initial.resolve(system.getValue()));
    }

    /**
     * 套用主題並在之後的切換中持續更新。要在場景所在的視窗顯示之前呼叫。
     *
     * @param scene 場景
     */
    public void track(Scene scene) {
        if (scenes.contains(scene)) {
            return;
        }
        scenes.add(scene);
        if (scene.getProperties().put(KEY, this) == null) {
            // 視窗關閉後不再更新（避免一直持有已關閉的對話框）；再次顯示時重新套用目前的主題
            scene.windowProperty().addListener((obs, old, window) -> hook(scene, window));
            hook(scene, scene.getWindow());
        }
        apply(scene);
    }

    private void hook(Scene scene, Window window) {
        if (window != null) {
            window.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> scenes.remove(scene));
            window.addEventHandler(WindowEvent.WINDOW_SHOWING, e -> track(scene));
        }
    }

    private void apply(Scene scene) {
        scene.getStylesheets().removeAll(allSheets());
        scene.getStylesheets().addAll(0, List.of(themeSheet(effective.get()), sheet("icons.css"), sheet("base.css")));
        scene.getPreferences().setColorScheme(effective.get());
    }

    /**
     * 對話框的場景在顯示時才建立；建立後自動套用主題。
     *
     * @param pane 對話框內容
     */
    public void track(DialogPane pane) {
        if (pane.getScene() != null) {
            track(pane.getScene());
        }
        pane.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                track(scene);
            }
        });
    }

    /**
     * @param scene 場景
     * @return 管理這個場景的主題管理員；沒有時 {@code null}
     */
    public static ThemeManager of(Scene scene) {
        return scene == null ? null : (ThemeManager) scene.getProperties().get(KEY);
    }

    /** @return 使用者選的模式 */
    public ObjectProperty<ThemeMode> modeProperty() {
        return mode;
    }

    /** @return 實際套用的配置（跟隨系統時隨系統變動） */
    public ReadOnlyObjectProperty<ColorScheme> effectiveProperty() {
        return effective.getReadOnlyProperty();
    }

    /**
     * @param scheme 色彩配置
     * @return 對應主題樣式表的資源網址
     */
    public static String themeSheet(ColorScheme scheme) {
        return sheet(scheme == ColorScheme.DARK ? "theme-dark.css" : "theme-light.css");
    }

    private void refresh() {
        ColorScheme now = mode.get().resolve(system.getValue());
        effective.set(now);
        String wanted = themeSheet(now);
        String other = themeSheet(now == ColorScheme.DARK ? ColorScheme.LIGHT : ColorScheme.DARK);
        for (Scene scene : List.copyOf(scenes)) {
            int index = scene.getStylesheets().indexOf(other);
            if (index >= 0) {
                scene.getStylesheets().set(index, wanted);
            }
            scene.getPreferences().setColorScheme(now);
        }
    }

    private static List<String> allSheets() {
        return List.of(themeSheet(ColorScheme.LIGHT), themeSheet(ColorScheme.DARK), sheet("icons.css"),
                sheet("base.css"));
    }

    private static String sheet(String name) {
        return Objects.requireNonNull(ThemeManager.class.getResource(BASE + name), name).toExternalForm();
    }
}
