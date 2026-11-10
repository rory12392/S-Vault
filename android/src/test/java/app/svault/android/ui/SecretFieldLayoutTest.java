package app.svault.android.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * 版面裡的密碼欄（{@code textPassword} 或密碼欄樣式）一律用 {@link SecretEditText}（T-19）：一般的
 * {@code TextInputEditText} 會把明文交給無障礙服務（{@code speak_password} 預設為 1 時）。
 */
public class SecretFieldLayoutTest {

    /** 一個元素的開頭標籤（到 {@code >} 或 {@code />} 為止）。 */
    private static final Pattern TAG = Pattern.compile("<([A-Za-z0-9_.]+)(\\s[^>]*?)?/?>", Pattern.DOTALL);

    @Test
    public void passwordFieldsUseSecretEditText() throws IOException {
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (Path layout : layouts()) {
            String xml = new String(Files.readAllBytes(layout), StandardCharsets.UTF_8);
            Matcher m = TAG.matcher(xml);
            while (m.find()) {
                String attrs = m.group(2) == null ? "" : m.group(2);
                if (!attrs.contains("TextInputEditText.Password") && !attrs.matches("(?s).*inputType=\"[^\"]*Password.*")) {
                    continue;
                }
                checked++;
                if (!m.group(1).equals(SecretEditText.class.getName())) {
                    wrong.add(layout.getFileName() + "：" + m.group(1));
                }
            }
        }
        assertEquals("密碼欄要用 SecretEditText：" + wrong, List.of(), wrong);
        // 解鎖、建立（兩個）、條目編輯、展示頁（兩個）：規則有在檢查東西
        assertTrue("找到的密碼欄太少：" + checked, checked >= 6);
    }

    @Test
    public void ruleCatchesPlainEditText() {
        String bad = "<com.google.android.material.textfield.TextInputEditText\n"
                + "    style=\"@style/Widget.SVault.TextInputEditText.Password\" />";
        Matcher m = TAG.matcher(bad);
        assertTrue(m.find());
        assertTrue(m.group(2).contains("TextInputEditText.Password"));
        assertTrue(!m.group(1).equals(SecretEditText.class.getName()));
    }

    private static List<Path> layouts() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String set : new String[] {"main", "debug"}) {
            Path dir = Paths.get("src", set, "res", "layout");
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(p -> p.toString().endsWith(".xml")).forEach(out::add);
                }
            }
        }
        return out;
    }
}
