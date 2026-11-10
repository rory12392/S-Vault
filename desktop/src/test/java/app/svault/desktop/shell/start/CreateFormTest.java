package app.svault.desktop.shell.start;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.desktop.shell.start.CreateForm.Field;
import app.svault.desktop.shell.start.CreateForm.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 建立保管庫表單的驗證。
 */
class CreateFormTest {

    private static final char[] PW = "correct horse battery".toCharArray();

    @TempDir
    Path dir;

    @Test
    @DisplayName("AC-01-4：主密碼為空時無法建立，提示「主密碼不能是空的」")
    void emptyPassword() {
        assertEquals(List.of(new Problem(Field.PASSWORD, "create.error.empty")),
                form(new char[0], new char[0], true).validate());
    }

    @Test
    @DisplayName("AC-01-4：兩次輸入不一致時無法建立，提示「兩次輸入的主密碼不一樣」")
    void mismatch() {
        assertEquals(List.of(new Problem(Field.CONFIRM, "create.error.mismatch")),
                form(PW, "correct horse batterY".toCharArray(), true).validate());
    }

    @Test
    @DisplayName("AC-01-5：沒勾選「我了解」就無法建立")
    void mustAcknowledge() {
        assertEquals(List.of(new Problem(Field.ACKNOWLEDGE, "create.error.acknowledge")),
                form(PW, PW.clone(), false).validate());
    }

    @Test
    @DisplayName("全部正確時沒有錯誤")
    void valid() {
        assertTrue(form(PW, PW.clone(), true).validate().isEmpty());
    }

    @Test
    @DisplayName("名稱空白、沒選位置、位置已有檔案：各自提示，錯誤依欄位順序（送出時焦點移到第一個）")
    void otherFieldsInOrder() throws IOException {
        List<Problem> all = new CreateForm(" ", null, new char[0], new char[0], false).validate();
        assertEquals(List.of(Field.NAME, Field.LOCATION, Field.PASSWORD, Field.ACKNOWLEDGE),
                all.stream().map(Problem::field).toList());

        Path existing = Files.write(dir.resolve("已存在.kdbx"), new byte[] {1});
        assertEquals(List.of(new Problem(Field.LOCATION, "create.error.exists")),
                new CreateForm("x", existing, PW, PW.clone(), true).validate());
    }

    @Test
    @DisplayName("另存新檔沒有副檔名時補上 .kdbx（大小寫不同視為已有）")
    void extension() {
        assertEquals(Paths.get("a", "我的密碼.kdbx"), CreateForm.withExtension(Paths.get("a", "我的密碼")));
        assertEquals(Paths.get("a", "X.KDBX"), CreateForm.withExtension(Paths.get("a", "X.KDBX")));
    }

    @Test
    @DisplayName("P-85：名稱已經以 .kdbx 結尾時，預設檔名不再多補一次")
    void suggestedFileName() {
        assertEquals("我的密碼.kdbx", CreateForm.suggestedFileName("我的密碼"));
        assertEquals("我的密碼.kdbx", CreateForm.suggestedFileName("我的密碼.kdbx"));
        assertEquals("家用.kdbx", CreateForm.suggestedFileName("家用.KDBX"));
        assertEquals("a.kdbx.kdbx", CreateForm.suggestedFileName("a.kdbx.kdbx"), "只去掉最後一個副檔名");
    }

    @Test
    @DisplayName("toString 不含主密碼")
    void toStringHidesPassword() {
        assertFalse(form(PW, PW.clone(), true).toString().contains("correct"));
    }

    private CreateForm form(char[] password, char[] confirm, boolean ack) {
        return new CreateForm("我的密碼", dir.resolve("new.kdbx"), password, confirm, ack);
    }
}
