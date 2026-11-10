package app.svault.desktop.shell;

import app.svault.core.storage.LocalStore;
import app.svault.core.storage.LogSetup;
import app.svault.core.vault.VaultRuntimeCheck;
import app.svault.desktop.platform.DesktopPaths;
import app.svault.desktop.platform.WindowsSessionLock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 打包後的自我檢查（{@code --self-check}，Architecture §10）。不開視窗，結果寫到
 * {@code <資料夾>/selfcheck.log}，以結束碼回報：0＝全部通過、1＝有檢查失敗、2＝無法執行檢查。
 *
 * <p>jpackage 產生的是 GUI 程式，主控台看不到輸出，所以一定要寫檔（STACK §7 第 5 點）。
 */
public final class SelfCheck {

    /** 命令列旗標。 */
    public static final String FLAG = "--self-check";

    static final int OK = 0;
    static final int FAILED = 1;
    static final int ERROR = 2;

    private SelfCheck() {
    }

    /**
     * 在預設的本機資料夾執行自我檢查。
     *
     * @return 結束碼
     */
    public static int run() {
        try {
            return run(LocalStore.open(DesktopPaths.defaultDataDir()), Clock.systemUTC());
        } catch (IOException | RuntimeException e) {
            System.err.println("自我檢查無法執行：" + e.getClass().getName());
            return ERROR;
        }
    }

    /**
     * 在指定的本機資料夾執行自我檢查。
     *
     * @param store 本機資料夾
     * @param clock 時鐘（記在日誌開頭）
     * @return 結束碼
     * @throws IOException 無法寫入 {@code selfcheck.log} 時
     */
    static int run(LocalStore store, Clock clock) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("S-Vault 自我檢查 " + Instant.now(clock));
        lines.add("資料夾 " + store.root());
        lines.add("Java " + System.getProperty("java.version") + " " + System.getProperty("java.vendor"));

        boolean logOk;
        try {
            LogSetup.install(store);
            logOk = true;
        } catch (IOException e) {
            logOk = false;
        }
        lines.add((logOk ? "OK   " : "FAIL ") + "日誌 " + store.logsDir());

        List<VaultRuntimeCheck.Result> results = VaultRuntimeCheck.run();
        for (VaultRuntimeCheck.Result r : results) {
            lines.add((r.ok() ? "OK   " : "FAIL ") + r.name() + " " + r.detail());
        }
        // JNA（T-18）：打包後原生程式庫要能載入；鎖定通知的註冊在沒有互動式工作階段時（例如 CI）可能失敗，只記錄
        boolean jnaOk = WindowsSessionLock.nativeLoads();
        lines.add((jnaOk ? "OK   " : "FAIL ") + "JNA 原生程式庫");
        if (jnaOk) {
            WindowsSessionLock watch = new WindowsSessionLock();
            lines.add((watch.start(() -> { }) ? "OK   " : "INFO ") + "Windows 鎖定通知的註冊");
            watch.stop();
        }
        boolean passed = logOk && jnaOk && VaultRuntimeCheck.allPassed(results);
        lines.add(passed ? "結果 通過" : "結果 失敗");

        Files.write(store.root().resolve("selfcheck.log"), lines, StandardCharsets.UTF_8);
        LogSetup.uninstall();
        return passed ? OK : FAILED;
    }
}
