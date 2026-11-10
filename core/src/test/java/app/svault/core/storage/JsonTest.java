package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 最小 JSON 讀寫。
 */
class JsonTest {

    @Test
    @DisplayName("各型別解析")
    void parsesAllTypes() {
        Object v = Json.parse(" {\"s\":\"中文\\n\\\"引號\\\" \\u00e9\",\"i\":-12,\"d\":1.5e2,\"t\":true,\"f\":false,"
                + "\"n\":null,\"a\":[1,[],{}]} ");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("s", "中文\n\"引號\" é");
        expected.put("i", -12L);
        expected.put("d", 150.0);
        expected.put("t", true);
        expected.put("f", false);
        expected.put("n", null);
        expected.put("a", Arrays.asList(1L, new ArrayList<>(), new LinkedHashMap<>()));
        assertEquals(expected, v);
    }

    @Test
    @DisplayName("寫出再讀回相同，順序保留，控制字元跳脫")
    void roundTrip() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("z", "最後\t一個\u0001");
        doc.put("a", List.of(1L, "x", true));
        doc.put("nested", new LinkedHashMap<>(Map.of("k", 2.25)));
        doc.put("empty", new LinkedHashMap<>());
        String text = Json.write(doc);
        assertEquals(doc, Json.parse(text));
        assertEquals(List.of("z", "a", "nested", "empty"), new ArrayList<>(((Map<?, ?>) Json.parse(text)).keySet()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"a\"}", "{\"a\":1,}", "[1 2]", "tru", "\"abc", "{a:1}", "1 2", "\"\\x\"",
        "\"\u0001\"", "-", "1.2.3"})
    @DisplayName("不合法的 JSON 丟 IllegalArgumentException")
    void rejectsInvalid(String text) {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(text));
    }

    @Test
    @DisplayName("巢狀太深時拒絕，不會堆疊溢位")
    void limitsDepth() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(10_000)));
    }
}
