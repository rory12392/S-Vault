package app.svault.desktop.shell.gallery;

import app.svault.core.module.EntryBatch;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.shell.ModuleVaultWriter;
import app.svault.core.vault.EntryDraft;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.MenuPage;
import app.svault.desktop.shell.ext.ModuleHost;
import app.svault.desktop.ui.FormField;
import app.svault.desktop.ui.SvButton;
import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * 開發版的展示模組（{@code --demo-modules}，T-30）：真的模組在 M-03 的模組分支上才註冊 {@code MenuPage}，
 * 外殼的選單項目、模組頁面、錯誤狀態與 {@code EntryNavigator} 要截圖與跑畫面腳本時用這兩個。不在 services 檔裡，
 * 打包版不會出現（{@code svault.dev} 不是 {@code true} 時這個參數被忽略）。
 */
public final class DemoModules {

    private static final Logger LOG = Logger.getLogger(DemoModules.class.getName());

    private DemoModules() {
    }

    /** @return 正常的展示模組與頁面建立失敗的展示模組（沒有「寫入保管庫」） */
    public static List<DesktopFeature> all() {
        return all(null);
    }

    /**
     * @param writer 模組的寫入入口（T-33）；給了才有「寫入保管庫」按鈕
     * @return 正常的展示模組與頁面建立失敗的展示模組
     */
    public static List<DesktopFeature> all(ModuleVaultWriter writer) {
        return List.of(new Healthy(writer), new Faulty());
    }

    /** 頁面正常：輸入條目的 UUID，按「開啟條目」經 {@code EntryNavigator} 開啟。 */
    static final class Healthy implements DesktopFeature {

        private final ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.shell.gallery.messages");
        private final ModuleVaultWriter writer;
        private ModuleHost host;
        private UnlockedEntries held;

        Healthy(ModuleVaultWriter writer) {
            this.writer = writer;
        }

        @Override
        public String id() {
            return "demo";
        }

        @Override
        public String displayName() {
            return text.getString("demo.name");
        }

        @Override
        public void attach(ModuleHost moduleHost) {
            host = moduleHost;
        }

        @Override
        public List<MenuPage> menuPages() {
            return List.of(new MenuPage(text.getString("demo.page"), this::page));
        }

        private VBox page() {
            Label intro = new Label(text.getString("demo.intro"));
            intro.setWrapText(true);
            FormField field = FormField.text(text.getString("demo.entryId"));
            TextField input = (TextField) field.input();
            input.setId("demo-entry-id");
            SvButton open = SvButton.secondary(text.getString("demo.open"));
            open.setId("demo-open");
            open.setOnAction(e -> host.navigator().openEntry(parse(input.getText())));
            SvButton missing = SvButton.ghost(text.getString("demo.openMissing"));
            missing.setId("demo-open-missing");
            missing.setOnAction(e -> host.navigator().openEntry(UUID.randomUUID()));
            Label vault = new Label(text.getString("demo.vaultIdle"));
            vault.setId("demo-vault");
            vault.setWrapText(true);
            SvButton read = SvButton.secondary(text.getString("demo.read"));
            read.setId("demo-read");
            read.setOnAction(e -> readVault(vault));
            HBox actions = new HBox(open, missing, read);
            if (writer != null) {
                SvButton write = SvButton.secondary(text.getString("demo.write"));
                write.setId("demo-write");
                write.setOnAction(e -> writeVault(vault));
                actions.getChildren().add(write);
            }
            // T-34 的剪貼簿：複製一個假密碼，顯示外殼回傳的清除秒數（和 GEN 產生器頁的「複製」同一條路）
            host.clipboard().ifPresent(clipboard -> {
                SvButton copy = SvButton.secondary(text.getString("demo.copy"));
                copy.setId("demo-copy");
                copy.setOnAction(e -> vault.setText(MessageFormat.format(text.getString("demo.copied"),
                        clipboard.copySecret("Demo-Gen-2026!").getSeconds())));
                actions.getChildren().add(copy);
            });
            actions.getStyleClass().add("sv-page-actions");
            VBox page = new VBox(intro, field, actions, vault);
            page.getStyleClass().add("sv-settings-body");
            return page;
        }

        /**
         * T-32 的唯讀保管庫：等內容載入好、在背景執行緒數條目（健檢就是這樣用），結果顯示在頁面上並寫日誌（只有筆數）。
         * 故意留著上一次拿到的物件，上鎖再解鎖後按一次可以看到它已失效（正式模組不要這樣存）。
         */
        private void readVault(Label out) {
            UnlockedEntries before = held;
            boolean waiting = host.whenEntriesLoaded(Platform::runLater, () -> {
                Optional<UnlockedEntries> now = host.unlockedEntries();
                held = now.orElse(null);
                String old = before == null ? "none" : before.isOpen() ? "open" : "closed";
                CompletableFuture.supplyAsync(() -> now.map(q -> q.all().size()).orElse(-1))
                        .thenAcceptAsync(count -> {
                            out.setText(MessageFormat.format(text.getString("demo.vaultRead"), count, old));
                            LOG.log(Level.INFO, "展示模組讀取保管庫：count={0} previous={1}",
                                    new Object[] {count, old});
                        }, Platform::runLater);
            });
            if (!waiting) {
                out.setText(text.getString("demo.vaultLocked"));
            }
        }

        /**
         * T-33 的寫入入口：以匯入模組的身分（開放範圍只有它，D-162；展示模組自己的 host 會被拒）加 3 筆假條目到新群組
         * 「展示匯入」，結果顯示在頁面上並寫日誌（只有狀態與筆數）。成功時外殼重畫三欄，關掉模組頁就看得到新群組。
         */
        private void writeVault(Label out) {
            List<EntryDraft> drafts = List.of(
                    new EntryDraft("展示 A", "demo", "probe1234", "https://a.example.com", ""),
                    new EntryDraft("展示 B", "demo", "probe1234", "https://b.example.com", ""),
                    new EntryDraft("展示 C", "demo", "probe1234", "https://c.example.com", ""));
            writer.addEntries("importer", new EntryBatch(text.getString("demo.writeGroup"), drafts), Platform::runLater,
                    r -> {
                        out.setText(MessageFormat.format(text.getString("demo.written"), r.status(), r.added()));
                        LOG.log(Level.INFO, "展示模組寫入保管庫：status={0} added={1}",
                                new Object[] {r.status(), r.added()});
                    });
        }

        private static UUID parse(String s) {
            try {
                return UUID.fromString(s.strip());
            } catch (IllegalArgumentException e) {
                // 不是 UUID：當成找不到（外殼顯示提示）
                return null;
            }
        }
    }

    /** 頁面建立就丟例外：選單項目照常，頁面是錯誤狀態。 */
    static final class Faulty implements DesktopFeature {

        private final ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.shell.gallery.messages");

        @Override
        public String id() {
            return "demofaulty";
        }

        @Override
        public String displayName() {
            return text.getString("demo.faultyName");
        }

        @Override
        public List<MenuPage> menuPages() {
            return List.of(new MenuPage(text.getString("demo.faultyPage"), () -> {
                throw new IllegalStateException("展示：頁面建立失敗");
            }));
        }
    }
}
