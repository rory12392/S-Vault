package app.svault.android.feature.biometric;

import static app.svault.android.feature.biometric.BioScreens.app;
import static app.svault.android.feature.biometric.BioScreens.clickText;
import static app.svault.android.feature.biometric.BioScreens.hasText;
import static app.svault.android.feature.biometric.BioScreens.onMain;
import static app.svault.android.feature.biometric.BioScreens.resumed;
import static app.svault.android.feature.biometric.BioScreens.run;
import static app.svault.android.feature.biometric.BioScreens.shot;
import static app.svault.android.feature.biometric.BioScreens.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.settings.SettingsFragment;
import app.svault.android.shell.start.UnlockActivity;
import app.svault.android.ui.SecretEditText;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.RecentVault;
import app.svault.core.vault.TestVaults;
import com.google.android.material.button.MaterialButton;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * BIO 在真的 App 裡的畫面（T-71；AC-11-1、AC-11-4）：解鎖畫面只在這個保管庫有金鑰時出現
 * 「使用生物辨識解鎖」，按下後失敗時顯示原因並退回主密碼輸入；設定頁的段落顯示狀態，「停用」刪除所有金鑰與密文。
 * 不需要按指紋：「已啟用」用一把真的 Keystore 金鑰＋假密文模擬（按下按鈕會出現提示框的流程在 {@link BioUnlockManualTest}）。
 * T-72：設定段落依目前解鎖的保管庫顯示啟用／停用；「啟用」走真的外殼確認主密碼，出現提示框後按返回取消。
 *
 * <p>測試開始時這台裝置不能有 BIO 的資料（結束時刪掉全部）：在手機上啟用過的話先到設定頁停用。
 * 帶 {@code -e shots <前綴>} 時另外截圖（同外殼的畫面測試）。
 */
@RunWith(AndroidJUnit4.class)
public class BioScreensTest {

    private static final String VAULT = "00000000-0000-4000-8000-0000000b1071";
    /** 解鎖畫面用的位置：不存在的文件，畫面會顯示讀不到的原因，但替代解鎖方式照常依保管庫 ID 決定。 */
    private static final String URI = "content://app.svault.android.test.none/document/bio";
    private static final String NAME = "生物辨識測試.kdbx";

    private SvaultApplication app;
    private BioVault bio;
    private KeystoreBioKeys keys;
    private File out;

    @Before
    public void setUp() throws Exception {
        app = app();
        bio = BioVault.of(app);
        keys = new KeystoreBioKeys();
        assertFalse("測試前這台裝置不能有生物辨識解鎖的資料", bio.hasAnyData());
        String prefix = InstrumentationRegistry.getArguments().getString("shots");
        out = prefix == null ? null : new File(app.getExternalFilesDir(null), "shots/" + prefix);
    }

    @After
    public void tearDown() throws Exception {
        // 測試前這台裝置沒有資料（setUp 檢查），所以全部清掉不會刪到別人的
        bio.disableAll();
        new RecentStore(app.localStore().recentFile()).remove(URI);
    }

    /** 模擬「已啟用」：真的 Keystore 金鑰（產生不需要生物辨識）＋格式正確的假密文。 */
    private void fakeEnabled(String vaultId) throws BioException {
        keys.newEncryptCipher(BioVault.alias(vaultId));
        new PrefsBioSecretStore(app).put(vaultId, new BioSecretStore.Sealed(new byte[12], new byte[32]));
    }

    private void openUnlock() throws Exception {
        new RecentStore(app.localStore().recentFile())
                .record(new RecentVault(URI, NAME, UUID.fromString(VAULT), Instant.now(), null));
        app.startActivity(UnlockActivity.intent(app, URI, NAME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        // 背景準備完成（讀不到檔案的 Banner 出現）後才決定要不要顯示按鈕
        waitUntil(() -> resumed() instanceof UnlockActivity
                && resumed().findViewById(R.id.unlock_banner).getVisibility() == View.VISIBLE, "解鎖畫面準備完成");
    }

    private static int providerCount() {
        ViewGroup box = resumed().findViewById(R.id.unlock_providers);
        return box.getVisibility() == View.VISIBLE ? box.getChildCount() : 0;
    }

    @Test
    public void ac11_1_unlockButtonOnlyWhenEnabled() throws Exception {
        openUnlock();
        assertEquals("沒有啟用時不顯示按鈕", 0, (int) onMain(BioScreensTest::providerCount));

        fakeEnabled(VAULT);
        openUnlock();
        assertEquals(1, (int) onMain(BioScreensTest::providerCount));
        MaterialButton button = onMain(() -> (MaterialButton) ((ViewGroup) resumed()
                .findViewById(R.id.unlock_providers)).getChildAt(0));
        assertEquals(app.getString(R.string.bio_unlock_button), onMain(() -> button.getText().toString()));
        // 截圖只看按鈕的位置與樣式：收掉「讀不到檔案」的 Banner（測試用的位置不存在，正常使用不會有）
        run(() -> resumed().findViewById(R.id.unlock_banner).setVisibility(View.GONE));
        shot(out, "bio-10-unlock-button");
    }

    @Test
    public void ac11_4_failureShowsReasonAndFallsBack() throws Exception {
        // 只有金鑰、沒有密文（啟用做到一半）：按鈕出現，按下後不出現提示框，顯示原因、退回主密碼並清掉金鑰
        keys.newEncryptCipher(BioVault.alias(VAULT));
        openUnlock();
        run(() -> resumed().findViewById(R.id.unlock_banner).setVisibility(View.GONE));
        String reason = app.getString(R.string.bio_reason_not_enabled);
        clickText(app.getString(R.string.bio_unlock_button));
        waitUntil(() -> hasText(reason), "顯示失敗原因");
        assertTrue("退回主密碼輸入", onMain(() -> resumed().findViewById(R.id.unlock_password).hasFocus()));
        assertTrue(resumed() instanceof UnlockActivity);
        assertFalse("金鑰已清掉", BioVault.hasKey(keys, VAULT));
        shot(out, "bio-11-unlock-failure");
    }

    @Test
    public void ac11_1_settingsFollowsCurrentVaultAndDisables() throws Exception {
        try (BioScreens.TestVault vault = new BioScreens.TestVault(app, "生物辨識設定測試")) {
            openSettings();
            assertEquals(app.getString(R.string.bio_settings_status_off), text(R.id.bio_settings_status));
            assertEquals(View.GONE, visibility(R.id.bio_settings_disable));
            assertEquals(View.GONE, visibility(R.id.bio_settings_disable_all));
            boolean supported = BioPrompt.strongUnavailableReason(app) == null;
            assertEquals("裝置支援時才有「啟用」（AC-11-4）", supported ? View.VISIBLE : View.GONE,
                    visibility(R.id.bio_settings_enable));
            // TalkBack：段落標題是標題；狀態與說明合成一個可聚焦的區塊
            assertTrue(onMain(() -> hasHeading(app.getString(R.string.bio_settings_title))));
            assertTrue(onMain(() -> resumed().findViewById(R.id.bio_settings_summary).isScreenReaderFocusable()));
            shot(out, "bio-20-settings-off");

            fakeEnabled(vault.id());
            openSettings();
            assertEquals(app.getString(R.string.bio_settings_status_on), text(R.id.bio_settings_status));
            assertEquals(View.GONE, visibility(R.id.bio_settings_enable));
            assertEquals(View.VISIBLE, visibility(R.id.bio_settings_disable));
            shot(out, "bio-21-settings-on");

            clickText(app.getString(R.string.bio_settings_disable));
            waitUntil(() -> hasText(app.getString(R.string.bio_settings_disable_current_message)), "停用確認對話框");
            shot(out, "bio-22-settings-confirm");
            clickText(app.getString(R.string.bio_settings_disable_confirm));
            waitUntil(() -> hasText(app.getString(R.string.bio_settings_disabled)), "停用成功的 Toast");
            assertEquals(app.getString(R.string.bio_settings_status_off), text(R.id.bio_settings_status));
            assertFalse("金鑰與密文都已刪除", bio.hasAnyData());
            // TalkBack：狀態改變時自動讀出（live region），不搶無障礙焦點
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, (int) onMain(() -> resumed()
                    .findViewById(R.id.bio_settings_status).getAccessibilityLiveRegion()));
            shot(out, "bio-23-settings-disabled");
        }
    }

    @Test
    public void otherVaultsCanBeDisabledTogether() throws Exception {
        try (BioScreens.TestVault vault = new BioScreens.TestVault(app, "生物辨識設定測試")) {
            fakeEnabled(VAULT);
            openSettings();
            assertEquals(app.getString(R.string.bio_settings_status_others,
                    app.getString(R.string.bio_settings_status_off), 1), text(R.id.bio_settings_status));
            assertEquals(View.VISIBLE, visibility(R.id.bio_settings_disable_all));
            shot(out, "bio-25-settings-others");
            clickText(app.getString(R.string.bio_settings_disable_all));
            waitUntil(() -> hasText(app.getString(R.string.bio_settings_disable_all_title)), "停用所有的確認對話框");
            clickText(app.getString(R.string.bio_settings_disable_confirm));
            waitUntil(() -> hasText(app.getString(R.string.bio_settings_disabled)), "停用成功的 Toast");
            assertFalse(bio.hasAnyData());
            assertEquals(View.GONE, visibility(R.id.bio_settings_disable_all));
        }
    }

    @Test
    public void ac11_1_enableAsksMasterPasswordThenPrompt() throws Exception {
        if (BioPrompt.strongUnavailableReason(app) != null) {
            // 沒有登錄指紋的裝置沒有「啟用」（上一個測試已檢查）；提示框的部分只能在有指紋的手機上跑
            return;
        }
        try (BioScreens.TestVault vault = new BioScreens.TestVault(app, "生物辨識設定測試")) {
            openSettings();
            clickText(app.getString(R.string.bio_settings_enable));
            waitUntil(() -> hasText(app.getString(R.string.bio_enable_message)), "輸入主密碼的對話框");
            assertTrue("對話框防截圖", onMain(BioScreensTest::secureDialog));
            shot(out, "bio-24-enable-dialog");

            // 空白、錯誤的主密碼：對話框不關，欄位顯示錯誤，沒有出現提示框
            clickText(app.getString(R.string.bio_enable_next));
            waitUntil(() -> hasText(app.getString(R.string.bio_enable_empty)), "空白的錯誤");
            run(() -> dialogField().setText("wrong-password"));
            clickText(app.getString(R.string.bio_enable_next));
            waitUntil(() -> hasText(app.getString(R.string.bio_enable_mismatch)), "主密碼不正確");
            assertEquals("欄位已清空", 0, (int) onMain(() -> dialogField().length()));
            assertFalse(BioVault.hasKey(keys, vault.id()));
            shot(out, "bio-26-enable-mismatch");

            // 正確的主密碼（外殼真的確認，T-35）：對話框關掉、出現系統的生物辨識提示框；按返回取消後不留金鑰
            run(() -> dialogField().setText(new String(TestVaults.password(), StandardCharsets.UTF_8)));
            clickText(app.getString(R.string.bio_enable_next));
            waitUntil(() -> !hasText(app.getString(R.string.bio_enable_message)), "對話框關掉");
            assertTrue("提示框出現前已產生金鑰", BioVault.hasKey(keys, vault.id()));
            BioScreens.pressBack();
            waitUntil(() -> hasText(app.getString(R.string.bio_enable_canceled)), "取消的 Toast");
            assertFalse("取消後不留下金鑰", bio.hasAnyData());
            assertEquals(app.getString(R.string.bio_settings_status_off), text(R.id.bio_settings_status));
        }
    }

    private static int visibility(int id) {
        return onMain(() -> resumed().findViewById(id).getVisibility());
    }

    /** @return 啟用對話框的主密碼欄（主執行緒） */
    private static SecretEditText dialogField() {
        for (View root : BioScreens.rootViews()) {
            View v = root.findViewById(R.id.bio_enable_password);
            if (v != null) {
                return (SecretEditText) v;
            }
        }
        throw new AssertionError("找不到主密碼欄");
    }

    /** @return 啟用對話框的視窗有 {@code FLAG_SECURE}（主執行緒） */
    private static boolean secureDialog() {
        View root = dialogField().getRootView();
        return root.getLayoutParams() instanceof android.view.WindowManager.LayoutParams lp
                && (lp.flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    private void openSettings() {
        app.startActivity(FeatureHostActivity.intent(app, SettingsFragment.PAGE_ID, R.string.settings_title,
                SettingsFragment.class, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof FeatureHostActivity
                && resumed().findViewById(R.id.bio_settings_status) != null
                && ((TextView) resumed().findViewById(R.id.bio_settings_status)).length() > 0, "設定頁的生物辨識段落");
        // 段落在設定頁下方：捲到看得到（截圖用）
        run(() -> {
            View section = resumed().findViewById(R.id.bio_settings_summary);
            for (View v = section; v.getParent() instanceof View; v = (View) v.getParent()) {
                if (v.getParent() instanceof NestedScrollView scroll) {
                    scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                    break;
                }
            }
        });
        BioScreens.settle();
    }

    private static String text(int id) {
        return onMain(() -> ((TextView) resumed().findViewById(id)).getText().toString());
    }

    private static boolean hasHeading(String title) {
        View v = BioScreens.findText(resumed().getWindow().getDecorView(), title);
        return v != null && v.isAccessibilityHeading();
    }
}
