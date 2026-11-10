package app.svault.desktop.shell.gallery;

import app.svault.desktop.shell.start.FileDialogs;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.stage.Window;

/**
 * 開發版畫面腳本用的選檔對話框（{@link ScreenDriver} 的 {@code pick=} 步驟）：依序回傳預先指定的路徑，
 * 沒有指定時當成使用者按了取消。不開原生對話框，截圖與鍵盤檢查不受作業系統視窗影響。
 */
public final class ScriptedDialogs implements FileDialogs {

    private static final Logger LOG = Logger.getLogger(ScriptedDialogs.class.getName());

    private final Deque<Path> answers = new ArrayDeque<>();

    /** @param path 下一次對話框的結果 */
    void enqueue(Path path) {
        answers.addLast(path);
    }

    @Override
    public Optional<Path> open(Window owner, Path initialDir) {
        return next("開啟", initialDir);
    }

    @Override
    public Optional<Path> save(Window owner, Path initialDir, String suggestedName) {
        LOG.log(Level.INFO, "畫面腳本｜另存新檔的預設檔名：{0}", suggestedName);
        return next("另存新檔", initialDir);
    }

    private Optional<Path> next(String kind, Path initialDir) {
        Path answer = answers.pollFirst();
        LOG.log(Level.INFO, "畫面腳本｜{0}對話框，起始資料夾 {1} → {2}", new Object[] {kind,
                initialDir == null ? "（無）" : initialDir, answer == null ? "取消" : answer.getFileName()});
        return Optional.ofNullable(answer);
    }
}
