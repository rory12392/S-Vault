package app.svault.desktop.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * 上鎖前關掉主視窗以外的視窗（T-18）：模態對話框（衝突對照、關於、確認、備份還原…）開著時換畫面或結束程式，JavaFX 會在
 * glass.dll 當掉（P-57）；選單、下拉清單等彈出視窗留著也會在上鎖後還能按到舊畫面的動作。
 *
 * <p>對話框用「關閉要求」關（同按標題列的 ✕ 或 Esc）：JavaFX 的 {@code Dialog} 以取消的按鈕結束，{@code showAndWait()}
 * 回傳「沒有選擇」，呼叫端照取消處理，不會寫入任何東西。只在 JavaFX 執行緒呼叫。
 */
public final class OpenWindows {

    private OpenWindows() {
    }

    /**
     * @param main 主視窗（不關）；{@code null} 時關掉所有有擁有者的視窗與彈出視窗
     * @return 關掉的視窗數
     */
    public static int closeAll(Window main) {
        // 關閉會改動 Window.getWindows()：先複製。從最後開的關起：對話框裡再開的確認框（巢狀的 showAndWait）要先結束
        List<Window> open = new ArrayList<>(Window.getWindows());
        Collections.reverse(open);
        int closed = 0;
        for (Window w : open) {
            if (w == main || !w.isShowing()) {
                continue;
            }
            if (w instanceof PopupWindow popup) {
                popup.hide();
                closed++;
            } else if (w instanceof Stage stage && stage.getOwner() != null) {
                stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
                if (stage.isShowing()) {
                    // 對話框擋下關閉要求（例如沒有取消按鈕）：直接收起來，showAndWait 照樣結束
                    stage.hide();
                }
                closed++;
            }
        }
        return closed;
    }
}
