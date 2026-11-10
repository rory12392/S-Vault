package app.svault.desktop.shell.gallery;

import app.svault.desktop.shell.AppShell;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.Duration;

/**
 * 開發用的鍵盤檢查（STACK §7 第 7 點）：在展示頁送出按鍵事件，把焦點移動與快捷鍵的結果寫進日誌後結束。
 * 只在程式內部派送事件，不用作業系統層級的輸入（不會干擾使用者正在用的其他視窗）。
 */
final class KeyboardCheck {

    private static final Logger LOG = Logger.getLogger(KeyboardCheck.class.getName());
    private static final int TABS = 12;

    private final Scene scene;
    private final AppShell shell;
    private final List<String> lines = new ArrayList<>();

    KeyboardCheck(Scene scene, AppShell shell) {
        this.scene = scene;
        this.shell = shell;
    }

    void runAndExit() {
        PauseTransition wait = new PauseTransition(Duration.millis(800));
        wait.setOnFinished(e -> {
            try {
                run();
            } catch (RuntimeException ex) {
                lines.add("例外：" + ex.getClass().getName());
            }
            lines.forEach(l -> LOG.log(Level.INFO, "鍵盤檢查｜{0}", l));
            Platform.exit();
        });
        wait.play();
    }

    private void run() {
        press(KeyCode.F, true, false);
        lines.add("Ctrl+F → " + describe(scene.getFocusOwner()));
        if (scene.getFocusOwner() instanceof TextInputControl t) {
            t.setText("git");
            lines.add("輸入 git 後清單篩選；Esc → 搜尋文字「" + afterEsc(t) + "」");
        }
        for (int i = 1; i <= TABS; i++) {
            press(KeyCode.TAB, false, false);
            lines.add("Tab " + i + " → " + describe(scene.getFocusOwner()));
        }
        boolean before = shell.groupsVisible();
        press(KeyCode.G, true, false);
        lines.add("Ctrl+G：群組樹 " + before + " → " + shell.groupsVisible());
        press(KeyCode.G, true, false);
        lines.add("再按 Ctrl+G：群組樹 → " + shell.groupsVisible());
    }

    private String afterEsc(TextInputControl t) {
        Event.fireEvent(t, key(KeyCode.ESCAPE, false, false));
        return t.getText();
    }

    private void press(KeyCode code, boolean ctrl, boolean alt) {
        Node target = scene.getFocusOwner() != null ? scene.getFocusOwner() : scene.getRoot();
        Event.fireEvent(target, key(code, ctrl, alt));
    }

    private static KeyEvent key(KeyCode code, boolean ctrl, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, alt, false);
    }

    private static String describe(Node n) {
        if (n == null) {
            return "（沒有焦點）";
        }
        String name = n.getAccessibleText();
        if (name == null && n instanceof Labeled l) {
            name = l.getText();
        }
        if (name == null && n instanceof TextInputControl t) {
            name = t.getPromptText();
        }
        return n.getClass().getSimpleName() + "「" + (name == null ? "" : name) + "」";
    }
}
