package app.svault.android.feature.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import app.svault.android.R;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.feature.importer.CsvSource;
import app.svault.core.feature.importer.ImportWizardModel;
import app.svault.core.feature.importer.ImportWizardModel.Busy;
import app.svault.core.feature.importer.ImportWizardModel.Problem;
import app.svault.core.feature.importer.ImportWizardModel.Step;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.UnlockedEntries;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 匯入精靈的錯誤情況（T-62，同 desktop 的 T-61）：存檔失敗不留半批、上鎖、整個檔案不能匯入、讀不到（含權限被收回）、
 * 頁面結束後晚到的結果、非預期例外由錯誤邊界接住、每種錯誤都有文字。
 */
public class ImportWizardProblemsTest extends ImportModelFixture {

    /** 存檔失敗時一筆都不留、停在預覽並說明，可以再試一次。 */
    @Test
    public void saveFailureLeavesNothing() {
        model.open(csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n"));
        remote.failWrite(true);

        model.importNow();

        assertEquals(Step.PREVIEW, model.step());
        assertEquals(Optional.of(Problem.SAVE_FAILED), model.problem());
        assertEquals(Busy.NONE, model.busy());
        assertTrue(imported().isEmpty());
        assertFalse("記憶體裡也沒有半批", session.hasUnsavedChanges());

        remote.failWrite(false);
        model.importNow();
        assertEquals(Step.DONE, model.step());
        assertEquals(1, imported().size());
    }

    /** 已上鎖時不讀檔也不寫入：選檔時與按下匯入時都顯示「已上鎖」。 */
    @Test
    public void lockedIsReported() {
        CsvSource file = csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n");
        model.open(file);
        access.revoke();

        model.importNow();
        assertEquals(Optional.of(Problem.LOCKED), model.problem());
        assertEquals(Step.CHOOSE, model.step());

        model.open(file);
        assertEquals(Optional.of(Problem.LOCKED), model.problem());
        assertEquals(Step.CHOOSE, model.step());
        assertTrue(imported().isEmpty());
    }

    /** 整個檔案不能匯入時留在第 1 步並說明原因（空檔、缺欄位、不是 UTF-8、讀不到、權限被收回）；日誌不含檔名。 */
    @Test
    public void fileProblems() {
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        Logger log = Logger.getLogger(ImportWizardModel.class.getName());
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logs.add(r.getMessage() + " " + java.util.Arrays.toString(r.getParameters()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(h);
        try {
            model.open(csv(""));
            assertEquals(Optional.of(Problem.EMPTY), model.problem());
            model.open(csv("title,login\nx,y\n"));
            assertEquals(Optional.of(Problem.MISSING_COLUMNS), model.problem());
            model.open(bytes((HEADER + "café,https://a.example.com,u,p,\n")
                    .getBytes(StandardCharsets.ISO_8859_1)));
            assertEquals(Optional.of(Problem.NOT_UTF8), model.problem());
            model.open(unreadable(null));
            assertEquals(Optional.of(Problem.READ_FAILED), model.problem());
            model.open(unreadable(new SecurityException("Permission Denial: content://秘密的路徑/x.csv")));
            assertEquals(Optional.of(Problem.READ_FAILED), model.problem());
            assertEquals(Step.CHOOSE, model.step());
            assertEquals(Busy.NONE, model.busy());
        } finally {
            log.removeHandler(h);
        }
        assertEquals(logs.toString(), 2, logs.size());
        for (String line : logs) {
            assertFalse(line, line.contains("秘密") || line.contains("不存在") || line.contains("content://"));
        }
    }

    /** 選檔中（讀檔還沒回來）頁面所在的 Activity 結束：晚到的結果丟掉，不寫入、不通知。 */
    @Test
    public void clearedModelDropsLateResults() {
        List<Runnable> queued = new ArrayList<>();
        ImportWizardModel m = new ImportWizardModel(ImportViewModel.importHost(host),
                new ModuleGuard(ModuleFailureListener.NONE), queued::add, sync);
        int[] notified = {0};
        m.setListener(() -> notified[0]++);
        m.open(csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n"));
        assertEquals(Busy.READING, m.busy());
        int before = notified[0];

        m.clear();
        queued.forEach(Runnable::run);

        assertEquals(Step.CHOOSE, m.step());
        assertEquals("", m.name());
        assertEquals(0, m.total());
        assertEquals("clear 後不再通知", before, notified[0]);
    }

    /** 讀檔時的非預期例外由模組的錯誤邊界接住，頁面顯示「無法讀取」。 */
    @Test
    public void unexpectedFailureIsContained() {
        ModuleHost broken = new ModuleHost() {
            @Override
            public ModulePreferences preferences(String moduleId) {
                return host.preferences(moduleId);
            }

            @Override
            public EntryNavigator navigator(Context context) {
                return host.navigator(context);
            }

            @Override
            public boolean whenEntriesLoaded(Executor executor, Runnable callback) {
                return host.whenEntriesLoaded(executor, callback);
            }

            @Override
            public Optional<UnlockedEntries> unlockedEntries() {
                throw new IllegalStateException("故障注入");
            }
        };
        ImportWizardModel m = newModel(broken);

        m.open(csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n"));

        assertEquals(Optional.of(Problem.READ_FAILED), m.problem());
        assertEquals(Busy.NONE, m.busy());
    }

    /** 只實作舊方法的 host（預設的 addEntries 一律上鎖）：停在第 1 步說明已上鎖，不留半批。 */
    @Test
    public void defaultHostReportsLocked() {
        ModuleHost old = new ModuleHost() {
            @Override
            public ModulePreferences preferences(String moduleId) {
                return host.preferences(moduleId);
            }

            @Override
            public EntryNavigator navigator(Context context) {
                return host.navigator(context);
            }

            @Override
            public boolean whenEntriesLoaded(Executor executor, Runnable callback) {
                return host.whenEntriesLoaded(executor, callback);
            }

            @Override
            public Optional<UnlockedEntries> unlockedEntries() {
                return host.unlockedEntries();
            }
        };
        ImportWizardModel m = newModel(old);
        m.open(csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n"));

        m.importNow();

        assertEquals(Optional.of(Problem.LOCKED), m.problem());
        assertTrue(imported().isEmpty());
    }

    /** 每種錯誤都有自己的繁體中文訊息（字串資源存在、不是空的、互不相同）。 */
    @Test
    public void everyProblemHasText() throws Exception {
        String xml = Files.readString(Path.of("src/main/res/values/imp_strings.xml"), StandardCharsets.UTF_8);
        Set<Integer> seen = new HashSet<>();
        for (Problem p : Problem.values()) {
            int id = ImportWizardFragment.problemText(p);
            assertTrue(p.name(), seen.add(id));
            String name = nameOf(id);
            Matcher m = Pattern.compile("<string name=\"" + name + "\">([^<]+)</string>").matcher(xml);
            assertTrue(name, m.find());
            assertFalse(name, m.group(1).isBlank());
        }
    }

    private static String nameOf(int id) throws IllegalAccessException {
        for (Field f : R.string.class.getFields()) {
            if (f.getInt(null) == id) {
                return f.getName();
            }
        }
        throw new AssertionError("找不到字串資源 " + id);
    }
}
