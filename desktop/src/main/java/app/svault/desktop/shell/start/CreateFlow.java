package app.svault.desktop.shell.start;

import app.svault.core.shell.Secrets;
import app.svault.desktop.shell.VaultWorker;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.stage.Window;

/**
 * 建立保管庫畫面的動作（US-01）：選位置、驗證並在背景建立。T-23 從 {@link StartFlow} 拆出（行為不變，
 * CONVENTIONS §10 的 400 行上限）。
 */
final class CreateFlow implements CreateScreen.Actions {

    private final VaultWorker worker;
    private final VaultActions actions;
    private final FileDialogs dialogs;
    private final ResourceBundle text;
    private final Supplier<CreateScreen> screen;
    private final Supplier<Window> window;
    private final Supplier<Path> defaultFolder;
    private final Consumer<Path> onCreated;
    private final Runnable onBack;

    /**
     * @param worker        保管庫的背景執行緒
     * @param actions       背後的操作
     * @param dialogs       選檔對話框
     * @param text          文字
     * @param screen        目前的建立畫面
     * @param window        對話框的擁有者
     * @param defaultFolder 沒有選過位置時從這個資料夾開始（Drive 電腦版資料夾）
     * @param onCreated     建立成功（JavaFX 執行緒），參數是新檔案
     * @param onBack        回歡迎畫面
     */
    CreateFlow(VaultWorker worker, VaultActions actions, FileDialogs dialogs, ResourceBundle text,
            Supplier<CreateScreen> screen, Supplier<Window> window, Supplier<Path> defaultFolder,
            Consumer<Path> onCreated, Runnable onBack) {
        this.worker = worker;
        this.actions = actions;
        this.dialogs = dialogs;
        this.text = text;
        this.screen = screen;
        this.window = window;
        this.defaultFolder = defaultFolder;
        this.onCreated = onCreated;
        this.onBack = onBack;
    }

    @Override
    public void chooseLocation() {
        CreateScreen create = screen.get();
        Path start = create.file() != null ? create.file().getParent() : defaultFolder.get();
        String suggested = create.vaultName().strip().isEmpty() ? text.getString("create.name.default")
                : create.vaultName().strip();
        dialogs.save(window.get(), start, CreateForm.suggestedFileName(suggested))
                .map(CreateForm::withExtension)
                .ifPresent(create::setFile);
    }

    @Override
    public void submit() {
        CreateScreen create = screen.get();
        CreateForm form = create.form();
        List<CreateForm.Problem> problems;
        byte[] password = null;
        try {
            problems = form.validate();
            if (problems.isEmpty()) {
                password = Secrets.utf8(form.password());
            }
        } finally {
            Secrets.wipe(form.password());
            Secrets.wipe(form.confirm());
        }
        create.clearProblems();
        if (!problems.isEmpty()) {
            create.showProblems(problems);
            return;
        }
        String name = form.name();
        Path file = form.file();
        byte[] pw = password;
        create.setBusy(true);
        worker.run(() -> {
            try {
                actions.create(name, file, pw);
            } finally {
                Secrets.wipe(pw);
            }
        }, () -> {
            create.clearPasswords();
            onCreated.accept(file);
        }, e -> {
            create.setBusy(false);
            create.showFailure("create.error.failed");
        });
    }

    @Override
    public void back() {
        screen.get().clearPasswords();
        onBack.run();
    }
}
