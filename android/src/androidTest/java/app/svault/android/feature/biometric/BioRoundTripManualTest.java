package app.svault.android.feature.biometric;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.gallery.UiGalleryActivity;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 實機的完整流程，需要人手按指紋（AC-11-2、AC-11-4）。平常的 {@code connectedDebugAndroidTest} 會略過；
 * 手動執行：
 * <pre>
 * .\gradlew :android:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.bioManual=true" "-Pandroid.testInstrumentationRunnerArguments.class=app.svault.android.feature.biometric.BioRoundTripManualTest"
 * </pre>
 * {@code ac11_2_…} 出現兩次提示框都按指紋（啟用、解鎖）；{@code ac11_4_…} 出現一次，按「使用主密碼」（取消）。
 * 每次等 60 秒。只跑其中一個時在 class 參數後面加 {@code #方法名稱}。
 */
@RunWith(AndroidJUnit4.class)
public class BioRoundTripManualTest {

    private static final String VAULT = "00000000-0000-4000-8000-0000000b1071";

    private Context context;
    private BioVault bio;

    private boolean manual;

    @Before
    public void setUp() throws Exception {
        // 一般的 connectedDebugAndroidTest 不跑（AGP 會把 Assume 的略過算成失敗，同 DriveReadProbeTest）
        manual = "true".equals(InstrumentationRegistry.getArguments().getString("bioManual"));
        if (!manual) {
            return;
        }
        context = ApplicationProvider.getApplicationContext();
        bio = BioVault.of(context);
        bio.disable(VAULT);
    }

    @After
    public void tearDown() throws Exception {
        if (bio != null) {
            bio.disable(VAULT);
        }
    }

    /** 顯示提示框並等結果：成功回傳加密器，失敗回傳 {@link BioException}。 */
    private Object prompt(ActivityScenario<UiGalleryActivity> scenario, Cipher cipher, int titleRes)
            throws Exception {
        CompletableFuture<Object> result = new CompletableFuture<>();
        scenario.onActivity(activity -> BioPrompt.authenticate(activity, cipher, titleRes, new BioPrompt.Callback() {
            @Override
            public void onAuthenticated(Cipher authenticated) {
                result.complete(authenticated);
            }

            @Override
            public void onError(BioException error) {
                result.complete(error);
            }
        }));
        return result.get(60, TimeUnit.SECONDS);
    }

    @Test
    public void ac11_2_enableAndUnlockOnDevice() throws Exception {
        if (!manual) {
            return;
        }
        byte[] password = "probe1234".getBytes(StandardCharsets.UTF_8);
        try (ActivityScenario<UiGalleryActivity> scenario = ActivityScenario.launch(UiGalleryActivity.class)) {
            // ① 啟用：按指紋
            Object enabled = prompt(scenario, bio.prepareEnable(VAULT), R.string.bio_prompt_title_enable);
            assertTrue("啟用時應按指紋：" + enabled, enabled instanceof Cipher);
            bio.completeEnable(VAULT, (Cipher) enabled, password);
            assertTrue(bio.isEnabled(VAULT));

            // ② 解鎖：按指紋，取回同一組主密碼
            Object unlocked = prompt(scenario, bio.prepareUnlock(VAULT), R.string.bio_prompt_title_unlock);
            assertTrue("解鎖時應按指紋：" + unlocked, unlocked instanceof Cipher);
            byte[] recovered = bio.completeUnlock(VAULT, (Cipher) unlocked);
            assertArrayEquals(password, recovered);
            java.util.Arrays.fill(recovered, (byte) 0);
        } finally {
            java.util.Arrays.fill(password, (byte) 0);
        }
    }

    @Test
    public void ac11_4_cancelFallsBackWithReason() throws Exception {
        if (!manual) {
            return;
        }
        try (ActivityScenario<UiGalleryActivity> scenario = ActivityScenario.launch(UiGalleryActivity.class)) {
            // 取消只看提示框的回呼，用啟用的加密器就好，不必先按指紋啟用：按「使用主密碼」→ CANCELED（AC-11-4）
            Object canceled = prompt(scenario, bio.prepareEnable(VAULT), R.string.bio_prompt_title_unlock);
            assertTrue("應按「使用主密碼」：" + canceled, canceled instanceof BioException);
            assertEquals(BioReason.CANCELED, ((BioException) canceled).reason());
        }
    }
}
