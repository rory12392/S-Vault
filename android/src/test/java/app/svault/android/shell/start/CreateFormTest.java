package app.svault.android.shell.start;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.android.R;
import app.svault.core.shell.Secrets;
import java.util.List;
import org.junit.Test;

/**
 * 建立保管庫表單的驗證（AC-01-4、AC-01-5；文字與 desktop 相同）與主密碼轉換。
 */
public class CreateFormTest {

    private static final char[] PW = "probe1234".toCharArray();

    private static List<CreateForm.Problem> validate(String name, char[] pw, char[] confirm, boolean ack) {
        return new CreateForm(name, pw, confirm, ack).validate();
    }

    @Test
    public void ac01_4_emptyPasswordCannotCreate() {
        List<CreateForm.Problem> p = validate("我的密碼", new char[0], new char[0], true);
        assertEquals(List.of(new CreateForm.Problem(CreateForm.Field.PASSWORD, R.string.create_error_empty)), p);
    }

    @Test
    public void ac01_4_mismatchCannotCreate() {
        List<CreateForm.Problem> p = validate("我的密碼", PW, "probe12345".toCharArray(), true);
        assertEquals(List.of(new CreateForm.Problem(CreateForm.Field.CONFIRM, R.string.create_error_mismatch)), p);
    }

    @Test
    public void ac01_5_mustAcknowledge() {
        List<CreateForm.Problem> p = validate("我的密碼", PW, PW.clone(), false);
        assertEquals(List.of(new CreateForm.Problem(CreateForm.Field.ACKNOWLEDGE,
                R.string.create_error_acknowledge)), p);
    }

    @Test
    public void allProblemsInFieldOrder() {
        List<CreateForm.Problem> p = validate("  ", new char[0], new char[0], false);
        assertEquals(List.of(CreateForm.Field.NAME, CreateForm.Field.PASSWORD, CreateForm.Field.ACKNOWLEDGE),
                p.stream().map(CreateForm.Problem::field).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    public void validFormHasNoProblemsAndFileNameGetsExtension() {
        assertTrue(validate("我的密碼", PW, PW.clone(), true).isEmpty());
        assertEquals("我的密碼.kdbx", CreateForm.fileName(" 我的密碼 "));
        assertEquals("家用.KDBX", CreateForm.fileName("家用.KDBX"));
    }

    @Test
    public void ac01_4_takePasswordValidatesBeforeWiping() {
        char[] pw = "probe1234".toCharArray();
        char[] confirm = "probe1234".toCharArray();
        byte[] bytes = new CreateForm("我的密碼", pw, confirm, true).takePassword();
        assertArrayEquals("probe1234".getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes);
        assertArrayEquals("取出後兩個字元陣列都清零", new char[pw.length], pw);
        assertArrayEquals(new char[confirm.length], confirm);

        char[] other = "probe12345".toCharArray();
        assertEquals(null, new CreateForm("我的密碼", "probe1234".toCharArray(), other, true).takePassword());
        assertArrayEquals(new char[other.length], other);
    }

    @Test
    public void toStringDoesNotContainPassword() {
        String s = new CreateForm("我的密碼", PW, PW.clone(), true).toString();
        assertFalse(s.contains("probe1234"));
    }

    @Test
    public void utf8ConversionWithoutStringAndWipe() {
        char[] chars = "密碼probe".toCharArray();
        byte[] bytes = Secrets.utf8(chars);
        assertArrayEquals("密碼probe".getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes);
        Secrets.wipe(bytes);
        Secrets.wipe(chars);
        assertArrayEquals(new byte[bytes.length], bytes);
        assertArrayEquals(new char[chars.length], chars);
    }
}
