package app.svault.android.feature.biometric;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.gallery.UiGalleryActivity;
import app.svault.android.shell.ext.UnlockProvider;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 解鎖畫面按鈕背後的完整流程（{@link BiometricAndroidFeature} 註冊的 {@link UnlockProvider}，T-71），要人手按指紋。
 * 平常的 {@code connectedDebugAndroidTest} 會略過；手動執行（一次一個方法）：
 * <pre>
 * .\gradlew :android:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.bioManual=true" "-Pandroid.testInstrumentationRunnerArguments.class=app.svault.android.feature.biometric.BioUnlockManualTest#方法名稱"
 * </pre>
 * <ul>
 *   <li>{@code ac11_2_unlockThroughProvider}：兩次提示框都按指紋（啟用、解鎖），外殼收到同一組主密碼。</li>
 *   <li>{@code ac11_4_lockoutThroughProvider}：第一次按指紋（啟用）；第二次一直用沒登錄的手指，直到系統鎖定。</li>
 *   <li>{@code ac11_3_enableBeforeNewFingerprint}：按指紋啟用並保留；接著到系統設定新增一枚指紋，再跑
 *       {@code ac11_3_unlockAfterNewFingerprint}（不會出現提示框，顯示「生物特徵已變更…」）。</li>
 * </ul>
 * 每個提示框等 120 秒。
 */
@RunWith(AndroidJUnit4.class)
public class BioUnlockManualTest {

    private static final String VAULT = "00000000-0000-4000-8000-0000000b1072";

    private Context context;
    private BioVault bio;
    private boolean manual;
    private boolean keep;

    @Before
    public void setUp() {
        // 一般的 connectedDebugAndroidTest 不跑（AGP 會把 Assume 的略過算成失敗，R-BIO-01）
        manual = "true".equals(InstrumentationRegistry.getArguments().getString("bioManual"));
        if (!manual) {
            return;
        }
        context = ApplicationProvider.getApplicationContext();
        bio = BioVault.of(context);
    }

    @After
    public void tearDown() throws Exception {
        if (bio != null && !keep) {
            bio.disable(VAULT);
        }
    }

    private static byte[] password() {
        return "probe1234".getBytes(StandardCharsets.UTF_8);
    }

    /** 外殼收到的結果：主密碼、取消（{@code "cancel"}）或失敗的例外。 */
    private static final class Result implements UnlockProvider.Callback {
        final CompletableFuture<Object> done = new CompletableFuture<>();

        @Override
        public void onSuccess(byte[] masterPassword) {
            done.complete(masterPassword.clone());
            // 同外殼：用完清零
            Arrays.fill(masterPassword, (byte) 0);
        }

        @Override
        public void onCancel() {
            done.complete("cancel");
        }

        @Override
        public void onFailure(Throwable error) {
            done.complete(error);
        }
    }

    private void enable(ActivityScenario<UiGalleryActivity> scenario) throws Exception {
        bio.disable(VAULT);
        Cipher cipher = bio.prepareEnable(VAULT);
        CompletableFuture<Object> result = new CompletableFuture<>();
        scenario.onActivity(a -> BioPrompt.authenticate(a, cipher, R.string.bio_prompt_title_enable,
                new BioPrompt.Callback() {
                    @Override
                    public void onAuthenticated(Cipher authenticated) {
                        result.complete(authenticated);
                    }

                    @Override
                    public void onError(BioException error) {
                        result.complete(error);
                    }
                }));
        Object enabled = result.get(120, TimeUnit.SECONDS);
        assertTrue("啟用時應按指紋：" + enabled, enabled instanceof Cipher);
        bio.completeEnable(VAULT, (Cipher) enabled, password());
    }

    /** 透過模組註冊的 UnlockProvider（外殼按下按鈕時呼叫的同一個物件）開始解鎖。 */
    private Object unlock(ActivityScenario<UiGalleryActivity> scenario) throws Exception {
        UnlockProvider provider = new BiometricAndroidFeature().unlockProviders().get(0);
        assertTrue("已啟用時應顯示按鈕", provider.isAvailable(VAULT));
        Result result = new Result();
        scenario.onActivity(a -> provider.start(a, VAULT, result));
        return result.done.get(120, TimeUnit.SECONDS);
    }

    private static BioReason reason(Object outcome) {
        assertTrue("應退回主密碼輸入：" + outcome, outcome instanceof BioException);
        return ((BioException) outcome).reason();
    }

    @Test
    public void ac11_2_unlockThroughProvider() throws Exception {
        if (!manual) {
            return;
        }
        try (ActivityScenario<UiGalleryActivity> scenario = ActivityScenario.launch(UiGalleryActivity.class)) {
            enable(scenario);
            Object outcome = unlock(scenario);
            assertTrue("解鎖時應按指紋：" + outcome, outcome instanceof byte[]);
            assertArrayEquals(password(), (byte[]) outcome);
        }
    }

    @Test
    public void ac11_4_lockoutThroughProvider() throws Exception {
        if (!manual) {
            return;
        }
        try (ActivityScenario<UiGalleryActivity> scenario = ActivityScenario.launch(UiGalleryActivity.class)) {
            enable(scenario);
            BioReason r = reason(unlock(scenario));
            // 有指紋與臉部兩種 Class 3 時，鎖定可能以系統中斷（ERROR_CANCELED）回報（T-71 實機）
            assertTrue("連續失敗應鎖定：" + r, r == BioReason.LOCKOUT || r == BioReason.LOCKOUT_PERMANENT
                    || r == BioReason.INTERRUPTED);
            assertTrue("鎖定不是金鑰問題，保留設定", bio.isEnabled(VAULT));
        }
    }

    @Test
    public void ac11_3_enableBeforeNewFingerprint() throws Exception {
        if (!manual) {
            return;
        }
        try (ActivityScenario<UiGalleryActivity> scenario = ActivityScenario.launch(UiGalleryActivity.class)) {
            enable(scenario);
            assertTrue(bio.isEnabled(VAULT));
            // 留著給 ac11_3_unlockAfterNewFingerprint
            keep = true;
        }
    }

    @Test
    public void ac11_3_unlockAfterNewFingerprint() throws Exception {
        if (!manual) {
            return;
        }
        assertTrue("先跑 ac11_3_enableBeforeNewFingerprint", bio.isEnabled(VAULT));
        try (ActivityScenario<UiGalleryActivity> scenario = ActivityScenario.launch(UiGalleryActivity.class)) {
            BioReason r = reason(unlock(scenario));
            assertTrue("新增指紋後金鑰應失效：" + r, r == BioReason.KEY_INVALIDATED);
            assertFalse("金鑰與密文已刪除，按鈕不再出現", BioVault.hasKey(new KeystoreBioKeys(), VAULT));
            assertFalse(bio.isEnabled(VAULT));
        }
    }
}
