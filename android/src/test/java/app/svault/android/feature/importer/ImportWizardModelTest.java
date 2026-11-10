package app.svault.android.feature.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.core.feature.importer.ImportWizardModel.Busy;
import app.svault.core.feature.importer.ImportWizardModel.PreviewRow;
import app.svault.core.feature.importer.ImportWizardModel.Step;
import app.svault.core.shell.ScreenData;
import app.svault.core.shell.VaultOps;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * 匯入精靈的狀態與寫入（T-62，AC-14-1～AC-14-4 的 android 部分；案例同 desktop 的 T-61）。錯誤與上鎖的情況在
 * {@link ImportWizardProblemsTest}。
 */
public class ImportWizardModelTest extends ImportModelFixture {

    /** AC-14-1：選檔後到預覽：共 n 筆、前 10 筆的名稱／帳號／網址；預覽的列沒有密碼欄位（畫面只能顯示遮罩）。 */
    @Test
    public void ac14_1_previewShowsCountAndFirstTen() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 1; i <= 12; i++) {
            sb.append("站台 ").append(i).append(",https://s").append(i).append(".example.com/,user").append(i)
                    .append(",Fake-Pass-").append(i).append(",\n");
        }
        model.open(csv(sb.toString()));

        assertEquals(Step.PREVIEW, model.step());
        assertEquals(Busy.NONE, model.busy());
        assertEquals(12, model.total());
        assertEquals("Chrome 密碼.csv", model.name());
        List<PreviewRow> preview = model.preview();
        assertEquals(10, preview.size());
        assertEquals(new PreviewRow(2, "站台 1", "user1", "https://s1.example.com/", false), preview.get(0));
        assertEquals("站台 10", preview.get(9).name());
        assertEquals(List.of("line", "name", "username", "url", "duplicate"),
                Arrays.stream(PreviewRow.class.getRecordComponents()).map(RecordComponent::getName).toList());
        assertFalse("toString 不含內容", preview.get(0).toString().contains("user1"));
        assertEquals(12, model.toImport());
        assertEquals("固定 8 個點，不透露長度", 8, ImportWizardFragment.MASK.length());
    }

    /** AC-14-1、AC-14-2：100 筆全部匯入新的「Chrome 匯入」群組，五個欄位與原檔一致；存檔一次。 */
    @Test
    public void ac14_2_importsHundredRowsIntoNewGroup() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 1; i <= 100; i++) {
            sb.append("站台 ").append(i).append(",https://s").append(i).append(".example.com/login,user").append(i)
                    .append(",\"Fake,Pass\"\"").append(i).append("\",\"備註 ").append(i).append("\n第二行\"\n");
        }
        int writesBefore = remote.writes();
        model.open(csv(sb.toString()));
        assertEquals(100, model.toImport());

        model.importNow();

        assertEquals(Step.DONE, model.step());
        assertEquals(100, model.imported());
        assertEquals("整批只存檔一次", 1, remote.writes() - writesBefore);
        assertEquals("外殼換新內容一次", 1, redraws.size());
        List<EntryView> got = imported();
        assertEquals(100, got.size());
        EntryView e42 = got.stream().filter(e -> e.username().equals("user42")).findFirst().orElseThrow();
        assertEquals("站台 42", e42.title());
        assertEquals("https://s42.example.com/login", e42.url());
        assertEquals("Fake,Pass\"42", e42.password());
        assertEquals("備註 42\n第二行", e42.notes());
        GroupView group = session.vault().groups().stream().filter(g -> g.uuid().equals(e42.groupId()))
                .findFirst().orElseThrow();
        assertEquals("群組在根群組底下", session.vault().id(), group.parentId());
    }

    /** AC-14-2：格式錯誤的列跳過並列出行號，其餘照常匯入。 */
    @Test
    public void ac14_2_skippedLinesAreListed() {
        String content = HEADER
                + "好的 1,https://a.example.com,alice,Fake-1,\n"
                + "壞的,只有兩欄\n"
                + "好的 2,https://b.example.com,bob,Fake-2,\n"
                + ",,,,\n"
                + "好的 3,https://c.example.com,carol,Fake-3,\n";
        model.open(csv(content));

        assertEquals(List.of(3, 5), model.skippedFormatLines());
        assertEquals("3、5", ImportWizardFragment.lines(model.skippedFormatLines()));
        assertEquals(3, model.total());
        model.importNow();
        assertEquals(3, imported().size());
    }

    /** AC-14-2：行號清單超過 20 個時只列前 20 個再加「…」。 */
    @Test
    public void ac14_2_longLineListIsTruncated() {
        List<Integer> many = new ArrayList<>();
        for (int i = 2; i <= 40; i++) {
            many.add(i);
        }
        String s = ImportWizardFragment.lines(many);
        assertTrue(s.startsWith("2、3、"));
        assertTrue(s, s.endsWith("21、…"));
    }

    /** AC-14-3：匯入完成後顯示原本 CSV 的位置（提示刪除），不替使用者刪；明文的解析結果已放掉。 */
    @Test
    public void ac14_3_doneKeepsLocation() {
        model.open(csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n"));
        model.importNow();

        assertEquals(Step.DONE, model.step());
        assertEquals(1, model.imported());
        assertEquals("內部儲存空間/Download/Chrome 密碼.csv", model.location());
        assertTrue("完成後不再持有 CSV 的內容", model.preview().isEmpty());
        assertEquals(0, model.total());

        model.restart();
        assertEquals(Step.CHOOSE, model.step());
        assertEquals("", model.location());
        assertEquals(0, model.imported());
    }

    /** AC-14-4：網址網域與帳號都和既有條目相同的列標示可能重複；預設略過，選「照樣匯入」時全部匯入。 */
    @Test
    public void ac14_4_duplicatesSkipOrKeep() {
        addExisting("舊的", "https://www.Shop.example.org/cart", "Bob");
        addExisting("回收桶裡的", "https://forum.example.net/", "carol");
        UUID recycledId = session.vault().entries().stream().filter(e -> e.title().equals("回收桶裡的"))
                .findFirst().orElseThrow().uuid();
        new VaultOps(session, Clock.systemUTC()).recycleEntry(recycledId);
        lease.publish(ScreenData.of(session.vault()));
        String content = HEADER
                + "商店,https://shop.example.org/login,bob,Fake-1,\n"
                + "論壇,https://forum.example.net/,carol,Fake-2,\n"
                + "另一個商店帳號,https://shop.example.org/,alice,Fake-3,\n";

        model.open(csv(content));
        assertEquals("回收桶裡的不算", List.of(2), model.duplicateLines());
        assertTrue(model.preview().get(0).duplicate());
        assertTrue(model.skipDuplicates());
        assertEquals(2, model.toImport());
        model.importNow();
        assertEquals(Set.of("論壇", "另一個商店帳號"), imported().stream().map(EntryView::title)
                .collect(Collectors.toSet()));

        model.restart();
        model.open(csv(content));
        int before = changes;
        model.setSkipDuplicates(false);
        assertEquals("改選擇時通知畫面", before + 1, changes);
        model.setSkipDuplicates(false);
        assertEquals("沒有改變時不通知（單選按鈕重畫時會再設一次）", before + 1, changes);
        assertEquals(3, model.toImport());
        model.importNow();
        assertEquals("第二次匯入另建一個群組、照樣匯入 3 筆", 5, imported().size());
    }

    /** AC-14-4：全部都是可能重複又選略過時沒有要匯入的，按「匯入」不寫入。 */
    @Test
    public void ac14_4_nothingToImport() {
        addExisting("舊的", "https://a.example.com", "alice");
        int writes = remote.writes();
        model.open(csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n"));

        assertEquals(0, model.toImport());
        model.importNow();
        assertEquals(Step.PREVIEW, model.step());
        assertEquals(writes, remote.writes());
        assertTrue(imported().isEmpty());
    }

    /** T-62：頁面重建時換監聽，舊頁面不再收到通知；狀態留在模型（ViewModel）裡。 */
    @Test
    public void recreatedPageTakesOverListener() {
        int[] fresh = {0};
        int old = changes;
        model.setListener(() -> fresh[0]++);

        model.open(csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n"));

        assertEquals(old, changes);
        assertTrue(fresh[0] > 0);
        assertEquals(Step.PREVIEW, model.step());
        model.setListener(null);
        model.restart();
        assertEquals("頁面不在時改狀態不會出錯", Step.CHOOSE, model.step());
    }
}
