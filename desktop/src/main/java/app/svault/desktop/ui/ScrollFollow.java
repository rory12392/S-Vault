package app.svault.desktop.ui;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;

/**
 * 用 Tab 把焦點移到捲動區裡、但在可視範圍外的控制項時捲過去（ScrollPane 不會自己跟；T-61 在匯入頁發現，T-112 改由
 * 模組頁外框統一處理，健檢、產生器的長頁面也適用）。等版面排好再算位置：換步驟時新內容可能還沒排版。
 */
public final class ScrollFollow {

    private ScrollFollow() {
    }

    /**
     * 在 {@code root} 加入場景後開始跟著焦點；移出場景時拿掉監聽。
     *
     * @param root 頁面（例如模組頁外框）；只處理焦點在它裡面、而且外層有 ScrollPane 的控制項
     */
    public static void install(Parent root) {
        ChangeListener<Node> follow = (obs, before, after) -> {
            ScrollPane scroll = after == null ? null : nearestScroll(after, root);
            if (scroll != null) {
                Platform.runLater(() -> ensureVisible(scroll, after));
            }
        };
        root.sceneProperty().addListener((obs, before, after) -> {
            if (before != null) {
                before.focusOwnerProperty().removeListener(follow);
            }
            if (after != null) {
                after.focusOwnerProperty().addListener(follow);
            }
        });
        Scene now = root.getScene();
        if (now != null) {
            now.focusOwnerProperty().addListener(follow);
        }
    }

    /**
     * @param scroll 捲動區
     * @param node   捲動區內容裡的控制項
     */
    public static void ensureVisible(ScrollPane scroll, Node node) {
        Node body = scroll.getContent();
        if (node.getScene() == null || body == null) {
            return;
        }
        scroll.applyCss();
        scroll.layout();
        double content = body.getLayoutBounds().getHeight();
        double view = scroll.getViewportBounds().getHeight();
        if (content <= view) {
            return;
        }
        Bounds b = body.sceneToLocal(node.localToScene(node.getLayoutBounds()));
        double top = scroll.getVvalue() * (content - view);
        double target;
        if (b.getMinY() < top) {
            target = b.getMinY();
        } else if (b.getMaxY() > top + view) {
            target = b.getMaxY() - view;
        } else {
            return;
        }
        scroll.setVvalue(Math.max(0, Math.min(1, target / (content - view))));
    }

    // 從控制項往上找，到 root 為止最近的 ScrollPane；不在 root 裡面時為 null
    private static ScrollPane nearestScroll(Node node, Parent root) {
        ScrollPane found = null;
        for (Node n = node.getParent(); n != null; n = n.getParent()) {
            if (found == null && n instanceof ScrollPane sp && sp.getContent() != null
                    && isInside(node, sp.getContent())) {
                found = sp;
            }
            if (n == root) {
                return found;
            }
        }
        return null;
    }

    private static boolean isInside(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }
}
