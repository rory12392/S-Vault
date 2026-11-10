package app.svault.android.shell.fake;

import android.content.Context;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.MenuPage;
import app.svault.android.shell.ext.PasswordFieldAction;
import app.svault.android.shell.ext.SettingsSection;
import app.svault.android.shell.ext.UnlockProvider;
import java.util.List;

/**
 * 故障注入：包住一個真的模組，代號與名稱照舊，每個擴充點都丟例外（T-22：ModuleFaultTest 涵蓋真的模組，
 * 不管模組分支之後在擴充點放了什麼）。
 */
public final class ThrowingAndroidFeature implements AndroidFeature {

    private final AndroidFeature real;

    /** @param real 被包住的模組 */
    public ThrowingAndroidFeature(AndroidFeature real) {
        this.real = real;
    }

    @Override
    public String id() {
        return real.id();
    }

    @Override
    public String displayName() {
        return real.displayName();
    }

    @Override
    public List<MenuPage> menuPages() {
        throw new IllegalStateException("注入：menuPages");
    }

    @Override
    public List<PasswordFieldAction> passwordFieldActions() {
        throw new IllegalStateException("注入：passwordFieldActions");
    }

    @Override
    public List<SettingsSection> settingsSections() {
        throw new IllegalStateException("注入：settingsSections");
    }

    @Override
    public List<UnlockProvider> unlockProviders() {
        // 模擬 BIO 缺 androidx.biometric 類別（LinkageError 也在邊界內）
        throw new NoClassDefFoundError("注入：unlockProviders");
    }

    @Override
    public void onDisabled(Context context) {
        throw new IllegalStateException("注入：onDisabled");
    }
}
