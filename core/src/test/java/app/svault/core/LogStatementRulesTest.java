package app.svault.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 日誌呼叫的靜態規則（AC-15-1、CONVENTIONS §3「只記類型與非機密的上下文」）：{@link LogRedactionTest} 實際跑過
 * core 的流程，這裡補上它跑不到的 desktop 與 android 外殼——掃描三個子專案的正式程式，每個寫日誌的敘述
 * （{@code LOG.*}、android 的 {@code Log.*}、{@code System.out／err.print*}）不得帶例外訊息、{@code toString()}，
 * 也不得帶名稱像機密的變數（密碼、草稿、條目欄位）。字串常值不算（中文說明裡出現「密碼」沒關係）。
 *
 * <p>這是啟發式的檢查：擋的是最常見的寫法（{@code LOG.warning("…" + e.getMessage())}、把 {@code password}
 * 串進訊息）；換個變數名稱就繞得過，所以和程式審查、{@link LogRedactionTest} 一起用。
 */
class LogStatementRulesTest {

    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java"), Path.of("../desktop/src/main/java"), Path.of("../android/src/main/java"));

    private static final Pattern LOG_CALL = Pattern.compile(
            "\\bLOG\\.(log|info|warning|severe|fine|finer|finest|config)\\s*\\("
                    + "|\\bLog\\.[ewidv]\\s*\\("
                    + "|\\bSystem\\.(out|err)\\.print");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\.getMessage\\(\\)|\\.getLocalizedMessage\\(\\)|\\.toString\\(\\)"
                    + "|\\b(password|passwords|pw|secret|secrets|chars|draft|title|username|notes|value|text|content)\\b");

    /**
     * 審查過、允許的例外（檔名 → 敘述裡的片段）。VaultOps：{@code VaultIoException} 的訊息只有檔名與步驟，
     * 不含條目內容（{@code LogRedactionTest} 走過離線存檔失敗確認）。
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "VaultOps.java", "e instanceof VaultIoException ? e.getMessage()");

    @Test
    @DisplayName("AC-15-1：三個子專案的日誌呼叫都不帶例外訊息、toString() 或像機密的變數")
    void ac15_1_logStatementsCarryNoSecrets() throws IOException {
        List<String> found = new ArrayList<>();
        int statements = 0;
        for (Path root : ROOTS) {
            assertTrue(Files.isDirectory(root), "找不到原始碼：" + root.toAbsolutePath());
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                    statements += logStatements(source).size();
                    found.addAll(violations(f.getFileName().toString(), source));
                }
            }
        }
        assertTrue(statements >= 40, "掃到的日誌呼叫太少，可能是比對寫錯：" + statements);
        assertEquals(List.of(), found);
    }

    @Test
    @DisplayName("違規範例：帶例外訊息、密碼變數、toString() 的日誌會被擋；只記類別名稱與中文說明的不會")
    void catchesViolations() {
        String bad = String.join("\n",
                "LOG.log(Level.WARNING, \"失敗：{0}\", e.getMessage());",
                "LOG.info(\"主密碼 \" + password);",
                "Log.w(TAG, \"條目\" + entry.toString());",
                "System.err.println(draft);");
        String good = String.join("\n",
                "LOG.log(Level.WARNING, \"無法寫入密碼與備註：{0}\", e.getClass().getName());",
                "Log.w(TAG, \"解鎖失敗：\" + outcome.error().getClass().getName());");

        assertEquals(4, violations("Bad.java", bad).size(), String.valueOf(violations("Bad.java", bad)));
        assertEquals(List.of(), violations("Good.java", good));
        assertEquals(1, violations("Other.java",
                "LOG.log(Level.WARNING, \"x\", e instanceof VaultIoException ? e.getMessage() : \"\");").size(),
                "允許清單只對指定的檔案有效");
    }

    private static List<String> violations(String fileName, String source) {
        List<String> out = new ArrayList<>();
        for (String statement : logStatements(source)) {
            String allowed = ALLOWED.get(fileName);
            if (allowed != null && statement.contains(allowed)) {
                continue;
            }
            Matcher m = FORBIDDEN.matcher(STRING_LITERAL.matcher(statement).replaceAll("\"\""));
            if (m.find()) {
                out.add(fileName + "：" + statement.replaceAll("\\s+", " ") + "（" + m.group() + "）");
            }
        }
        return out;
    }

    /** 從日誌呼叫開始到敘述結尾的分號（略過字串常值裡的分號）。 */
    private static List<String> logStatements(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = LOG_CALL.matcher(source);
        while (m.find()) {
            int i = m.start();
            boolean inString = false;
            while (i < source.length()) {
                char c = source.charAt(i);
                if (c == '\\' && inString) {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = !inString;
                } else if (c == ';' && !inString) {
                    break;
                }
                i++;
            }
            out.add(source.substring(m.start(), Math.min(i + 1, source.length())));
        }
        return out;
    }
}
