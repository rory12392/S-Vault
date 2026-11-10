package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.click;
import static app.svault.android.shell.entries.ScreenShots.clickText;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.FrameMetrics;
import android.view.ViewTreeObserver;
import android.view.Window;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import com.google.android.material.search.SearchView;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * AC-03-2 的手機實測：2,000 筆的保管庫，在搜尋欄逐字輸入時「輸入 → 結果畫出來」的時間（每字元 300 ms 內），
 * 以及全部條目清單捲動的每格畫面時間（FrameMetrics）。只在 {@code -e shots <前綴>} 時執行，結果寫到
 * {@code shots/<前綴>/perf.txt} 並印在 logcat（tag {@code SVaultPerf}）。
 */
@RunWith(AndroidJUnit4.class)
public class EntryPerfTest {

    private static final String TAG = "SVaultPerf";
    private SvaultApplication app;
    private TestVaultFiles files;
    private File out;

    @Before
    public void setUp() {
        String prefix = InstrumentationRegistry.getArguments().getString("shots");
        if (prefix == null) {
            // 一般的 connectedDebugAndroidTest 不跑（AGP 會把 Assume 的略過算成失敗）
            return;
        }
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        out = new File(app.getExternalFilesDir(null), "shots/" + prefix);
        out.mkdirs();
    }

    @After
    public void tearDown() {
        if (files != null) {
            files.close();
        }
    }

    @Test
    public void ac03_2_twoThousandEntriesSearchAndScroll() throws Exception {
        if (files == null) {
            return;
        }
        files.unlock(big(), "兩千筆.kdbx");
        long loadStart = SystemClock.elapsedRealtime();
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        settle();
        long loaded = SystemClock.elapsedRealtime() - loadStart;

        click(R.id.page_search_bar);
        String typed = "site19.example";
        List<Long> perChar = new ArrayList<>();
        for (int n = 1; n <= typed.length(); n++) {
            perChar.add(typeAndWaitForDraw(typed.substring(0, n)));
        }
        instr().runOnMainSync(() -> ((SearchView) resumed().findViewById(R.id.page_search_view)).hide());
        settle();

        clickText("全部條目");
        List<Double> frames = scrollFrames();

        long worstChar = Collections.max(perChar);
        StringBuilder r = new StringBuilder();
        r.append("loadToSettledMs=").append(loaded).append('\n');
        r.append("searchPerCharMs=").append(perChar).append(" worst=").append(worstChar).append('\n');
        Collections.sort(frames);
        r.append("scrollFrames=").append(frames.size())
                .append(" p50=").append(pct(frames, 50)).append(" p90=").append(pct(frames, 90))
                .append(" p99=").append(pct(frames, 99)).append(" max=").append(frames.get(frames.size() - 1))
                .append(" over16.7=").append(frames.stream().filter(f -> f > 16.7).count())
                .append(" over33.3=").append(frames.stream().filter(f -> f > 33.3).count()).append('\n');
        Log.i(TAG, r.toString());
        try (Writer w = new FileWriter(new File(out, "perf.txt"))) {
            w.write(r.toString());
        }
        assertTrue(r.toString(), worstChar < 300);
    }

    /** @return 從設定搜尋文字到下一次畫面畫完的毫秒數 */
    private static long typeAndWaitForDraw(String text) throws InterruptedException {
        CountDownLatch drawn = new CountDownLatch(1);
        long[] took = new long[1];
        instr().runOnMainSync(() -> {
            Activity a = resumed();
            ViewTreeObserver vto = a.getWindow().getDecorView().getViewTreeObserver();
            long start = SystemClock.elapsedRealtimeNanos();
            ViewTreeObserver.OnDrawListener[] l = new ViewTreeObserver.OnDrawListener[1];
            l[0] = () -> {
                took[0] = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000;
                drawn.countDown();
                // 畫面繪製中不能移除監聽者
                new Handler(Looper.getMainLooper()).post(() -> vto.removeOnDrawListener(l[0]));
            };
            vto.addOnDrawListener(l[0]);
            ((SearchView) a.findViewById(R.id.page_search_view)).setText(text);
            a.getWindow().getDecorView().invalidate();
        });
        assertTrue("畫面沒有更新", drawn.await(5, TimeUnit.SECONDS));
        SystemClock.sleep(150);
        return took[0];
    }

    private static List<Double> scrollFrames() {
        List<Double> frames = Collections.synchronizedList(new ArrayList<>());
        Window.OnFrameMetricsAvailableListener listener = (window, metrics, dropped) ->
                frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0);
        Activity a = resumed();
        instr().runOnMainSync(() -> a.getWindow().addOnFrameMetricsAvailableListener(listener,
                new Handler(Looper.getMainLooper())));
        RecyclerView list = a.findViewById(R.id.page_list);
        for (int i = 0; i < 6; i++) {
            int v = i % 2 == 0 ? 20000 : -20000;
            instr().runOnMainSync(() -> list.fling(0, v));
            SystemClock.sleep(1500);
        }
        instr().runOnMainSync(() -> a.getWindow().removeOnFrameMetricsAvailableListener(listener));
        return new ArrayList<>(frames);
    }

    private static double pct(List<Double> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, sorted.size() * p / 100));
    }

    /** 2,000 筆、6 個群組（與 T-10 的 big.kdbx 相同的假資料）。 */
    private static Vault big() throws Exception {
        Vault v = new VaultCodec(TestVaults.fastParams()).create("兩千筆", TestVaults.password());
        VaultEditor ed = new VaultEditor(v, Clock.systemUTC());
        List<UUID> groups = new ArrayList<>();
        groups.add(v.groups().get(0).uuid());
        for (String n : new String[] {"工作", "購物", "社群", "金融", "遊戲"}) {
            groups.add(ed.addGroup(groups.get(0), n));
        }
        for (int i = 0; i < 2000; i++) {
            ed.addEntry(groups.get(i % groups.size()), new EntryDraft(String.format("網站 %04d", i),
                    "user" + i + "@mail.test", "pw-" + i, "https://site" + i + ".example.com/login", ""));
        }
        return v;
    }
}
