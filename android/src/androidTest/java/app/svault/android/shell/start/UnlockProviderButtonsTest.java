package app.svault.android.shell.start;

import static org.junit.Assert.assertEquals;

import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import androidx.fragment.app.FragmentActivity;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.UnlockProvider;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.RecentVault;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** 替代解鎖方式失敗後重新問一次能不能用（P-BIO-04）：金鑰失效時按鈕立刻消失，暫時的失敗按鈕留著。 */
@RunWith(AndroidJUnit4.class)
public class UnlockProviderButtonsTest {

    private final AtomicBoolean available = new AtomicBoolean(true);
    private boolean stillAvailableAfterFailure;
    private int failures;

    private final class Provider implements UnlockProvider {
        @Override
        public int labelRes() {
            return R.string.app_name;
        }

        @Override
        public boolean isAvailable(String vaultId) {
            return available.get();
        }

        @Override
        public void start(FragmentActivity activity, String vaultId, Callback callback) {
            available.set(stillAvailableAfterFailure);
            callback.onFailure(new IllegalStateException("測試"));
        }
    }

    private final class Module implements AndroidFeature {
        @Override
        public String id() {
            return "fake-unlock";
        }

        @Override
        public String displayName() {
            return "假解鎖";
        }

        @Override
        public List<UnlockProvider> unlockProviders() {
            return List.of(new Provider());
        }
    }

    @Test
    public void buttonDisappearsWhenFailureMakesItUnavailable() {
        stillAvailableAfterFailure = false;
        assertEquals(0, buttonsAfterFailure());
        assertEquals(1, failures);
    }

    @Test
    public void buttonStaysAfterTemporaryFailure() {
        stillAvailableAfterFailure = true;
        assertEquals(1, buttonsAfterFailure());
        assertEquals(1, failures);
    }

    /**
     * 歡迎畫面只當容器用：最近開啟清單只有一筆時它會直接轉去解鎖畫面並關掉自己，所以先清空（完整跑時前面的測試可能留下
     * 一筆，M-04 完整驗證時這兩個測試因此失敗）。
     */
    @Before
    public void emptyRecentList() throws Exception {
        SvaultApplication app = (SvaultApplication) InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        RecentStore recent = new RecentStore(app.localStore().recentFile());
        for (RecentVault v : recent.list()) {
            recent.remove(v.location());
        }
    }

    private int buttonsAfterFailure() {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                WelcomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int[] left = new int[1];
        try (ActivityScenario<WelcomeActivity> s = ActivityScenario.launch(intent)) {
            s.onActivity(a -> {
                LinearLayout container = new LinearLayout(a);
                ModuleRegistry<AndroidFeature> modules = ModuleRegistry.of(List.of(new Module()),
                        ModuleSettings.DEFAULTS, new ModuleGuard(ModuleFailureListener.NONE));
                UnlockProviderButtons buttons = new UnlockProviderButtons(a, container, modules);
                buttons.show("vault", password -> { }, () -> failures++);
                assertEquals(View.VISIBLE, container.getVisibility());
                assertEquals(1, container.getChildCount());
                container.getChildAt(0).performClick();
                left[0] = container.getChildCount();
            });
        }
        return left[0];
    }
}
