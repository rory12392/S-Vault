package app.svault.core.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code settings.json} 與 {@code recent.json} 用的最小 JSON 讀寫（RFC 8259）。
 *
 * <p>自己寫而不引入 JSON 函式庫：這兩個檔案結構很小、不含機密，不值得為此在兩平台多一個相依
 * （Jackson 只是 KeePassJava2 的間接相依，不直接使用）。值的對應：物件 → {@link LinkedHashMap}（保留順序）、
 * 陣列 → {@link ArrayList}、字串 → {@link String}、整數 → {@link Long}、小數 → {@link Double}、
 * {@code true}／{@code false} → {@link Boolean}、{@code null} → {@code null}。
 */
final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /**
     * @param text JSON 文字
     * @return 解析後的值
     * @throws IllegalArgumentException 不是合法的 JSON（訊息只含位置，不含內容）
     */
    static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipSpace();
        Object value = parser.value(0);
        parser.skipSpace();
        if (parser.pos != text.length()) {
            throw parser.error("結尾有多餘的字元");
        }
        return value;
    }

    /**
     * @param value 由 {@link Map}、{@link List}、字串、數字、布林與 {@code null} 組成的值
     * @return 縮排兩格、以 LF 換行的 JSON
     */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.append('\n').toString();
    }

    // ---- 解析 ----

    // 巢狀深度上限：設定檔不會超過幾層，限制深度避免惡意檔案造成堆疊溢位
    private static final int MAX_DEPTH = 64;

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("巢狀太深");
        }
        if (pos >= text.length()) {
            throw error("內容不完整");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("無法辨識的值");
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (!peek('"')) {
                throw error("物件的鍵必須是字串");
            }
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            map.put(key, value(depth + 1));
            skipSpace();
            if (peek(',')) {
                pos++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        pos++;
        skipSpace();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value(depth + 1));
            skipSpace();
            if (peek(',')) {
                pos++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("字串沒有結束");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("字串中有控制字元");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("跳脫字元不完整");
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    sb.append(e);
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > text.length()) {
                        throw error("\\u 跳脫不完整");
                    }
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("\\u 跳脫不是十六進位");
                    }
                    pos += 4;
                    break;
                default:
                    throw error("不認得的跳脫字元");
            }
        }
    }

    private Object number() {
        int start = pos;
        if (peek('-')) {
            pos++;
        }
        boolean fraction = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                fraction = true;
                pos++;
            } else {
                break;
            }
        }
        String token = text.substring(start, pos);
        try {
            if (!fraction) {
                return Long.parseLong(token);
            }
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            pos = start;
            throw error("數字格式錯誤");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("無法辨識的值");
        }
        pos += word.length();
        return value;
    }

    private void skipSpace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private boolean peek(char c) {
        return pos < text.length() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("缺少 '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON 格式錯誤（位置 " + pos + "）：" + what);
    }

    // ---- 輸出 ----

    private static void write(StringBuilder out, Object value, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            quote(out, (String) value);
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
            out.append(value);
        } else if (value instanceof Double) {
            double d = (Double) value;
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON 不能表示 NaN 或無限大");
            }
            out.append(d);
        } else if (value instanceof Map) {
            writeObject(out, (Map<?, ?>) value, indent);
        } else if (value instanceof List) {
            writeArray(out, (List<?>) value, indent);
        } else {
            throw new IllegalArgumentException("不支援的 JSON 型別：" + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            pad(out, indent + 1);
            quote(out, String.valueOf(e.getKey()));
            out.append(": ");
            write(out, e.getValue(), indent + 1);
            out.append(++i < map.size() ? ",\n" : "\n");
        }
        pad(out, indent);
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list, int indent) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            pad(out, indent + 1);
            write(out, list.get(i), indent + 1);
            out.append(i + 1 < list.size() ? ",\n" : "\n");
        }
        pad(out, indent);
        out.append(']');
    }

    private static void pad(StringBuilder out, int indent) {
        for (int i = 0; i < indent; i++) {
            out.append("  ");
        }
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
