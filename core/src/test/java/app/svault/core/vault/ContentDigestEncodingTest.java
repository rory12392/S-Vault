package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 內容摘要的編碼沒有歧義（Codex M-04 審查 B2）。 */
class ContentDigestEncodingTest {

    @Test
    @DisplayName("B2：名稱 a、值 b=c 和名稱 a=b、值 c 編出來不同")
    void namesAndValuesCannotShiftIntoEachOther() {
        assertNotEquals(encode("a", "b=c"), encode("a=b", "c"));
        assertNotEquals(encode("a", "b\u0000c"), encode("a\u0000b", "c"));
        assertNotEquals(encode("1", "2:x"), encode("1:2", "x"));
    }

    private static String encode(String k, String v) {
        StringBuilder sb = new StringBuilder();
        SnapshotReader.field(sb, k);
        SnapshotReader.field(sb, v);
        return sb.toString();
    }
}
