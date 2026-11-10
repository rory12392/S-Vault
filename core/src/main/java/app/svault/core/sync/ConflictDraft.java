package app.svault.core.sync;

import app.svault.core.storage.Hashes;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 衝突沒解決時被上鎖的選擇（AC-08-5，T-18）：對照畫面上每個已選欄位的「哪一邊＋兩邊的值的指紋」，寫成文字交給
 * {@code SaveCoordinator.writeConflictDraft} 加密保存。下次解鎖、重新合併後只沿用兩邊的值都沒變的欄位（同
 * {@code ConflictChoices} 帶入上一次選擇的規則，D-105）。
 *
 * <p>文字裡沒有欄位的值，只有 SHA-256 指紋；欄位鍵（可能是自訂欄位名稱）以 Base64 編碼。整份文字只存在加密的檔案裡，
 * 不寫日誌。一行一個欄位：{@code 條目 UUID \t LOCAL|REMOTE \t 指紋 \t Base64(欄位鍵)}。
 */
public final class ConflictDraft {

    private ConflictDraft() {
    }

    /**
     * @param view    目前對照畫面的內容
     * @param choices 使用者的選擇（可能還沒選完）
     * @return 要加密保存的文字；沒有任何選擇時是空字串（檔案仍會寫，表示「上鎖時有衝突」）
     */
    public static String encode(ConflictView view, Map<UUID, Map<String, Side>> choices) {
        StringBuilder out = new StringBuilder();
        for (ConflictView.Item item : view.items()) {
            Map<String, Side> chosen = choices.get(item.entryId());
            if (chosen == null) {
                continue;
            }
            for (ConflictView.Row row : item.rows()) {
                Side side = chosen.get(row.key());
                if (side == null) {
                    continue;
                }
                out.append(item.entryId()).append('\t').append(side.name()).append('\t').append(fingerprint(row))
                        .append('\t').append(Base64.getEncoder().encodeToString(utf8(row.key()))).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * @param content {@link #encode} 的文字
     * @param now     重新合併後對照畫面的內容
     * @return 可以沿用的選擇（條目與欄位都還在、兩邊的值都沒變）；格式不對的行略過
     */
    public static Map<UUID, Map<String, Side>> decode(String content, ConflictView now) {
        Map<UUID, Map<String, String>> saved = new LinkedHashMap<>();
        Map<UUID, Map<String, Side>> sides = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 4) {
                continue;
            }
            try {
                UUID id = UUID.fromString(parts[0]);
                Side side = Side.valueOf(parts[1]);
                String key = new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8);
                saved.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(key, parts[2]);
                sides.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(key, side);
            } catch (IllegalArgumentException e) {
                // 壞掉的一行：那個欄位重新選
            }
        }
        Map<UUID, Map<String, Side>> out = new LinkedHashMap<>();
        for (ConflictView.Item item : now.items()) {
            Map<String, String> prints = saved.get(item.entryId());
            if (prints == null) {
                continue;
            }
            Map<String, Side> kept = new LinkedHashMap<>();
            for (ConflictView.Row row : item.rows()) {
                if (fingerprint(row).equals(prints.get(row.key()))) {
                    kept.put(row.key(), sides.get(item.entryId()).get(row.key()));
                }
            }
            if (!kept.isEmpty()) {
                out.put(item.entryId(), Collections.unmodifiableMap(kept));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    // 兩邊的值（說法＋文字＋時間）的 SHA-256：值變了指紋就不同，檔案裡不必存值本身
    private static String fingerprint(ConflictView.Row row) {
        StringBuilder s = new StringBuilder(row.key());
        for (ConflictView.Shown v : new ConflictView.Shown[] {row.local(), row.remote()}) {
            s.append('\u0000').append(v.form().name()).append('\u0000').append(v.text() == null ? "" : v.text())
                    .append('\u0000').append(v.time() == null ? "" : v.time().toString());
        }
        return Hashes.sha256(utf8(s.toString()));
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
