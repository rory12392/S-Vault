package app.svault.desktop;

import app.svault.desktop.shell.SelfCheck;
import app.svault.desktop.shell.SvaultApp;
import java.util.Arrays;
import javafx.application.Application;

/**
 * desktop 的進入點。
 *
 * <p>主類別不繼承 {@link Application}：JavaFX 放在 classpath（非模組）時，繼承 Application 的主類別會被
 * 啟動器拒絕。{@code --self-check} 也必須在這裡分流，才能完全不初始化 JavaFX、不開視窗。
 */
public final class Launcher {

    private Launcher() {
    }

    /**
     * @param args {@code --self-check}：自我檢查後以結束碼回報（0＝正常）；其他參數交給 JavaFX
     */
    public static void main(String[] args) {
        if (Arrays.asList(args).contains(SelfCheck.FLAG)) {
            System.exit(SelfCheck.run());
            return;
        }
        Application.launch(SvaultApp.class, args);
    }
}
