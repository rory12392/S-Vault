package app.svault.core.storage;

import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import app.svault.core.vault.VaultException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 衝突暫存檔（AC-08-5，T-18）的加密讀寫，只由 {@link SaveCoordinator} 使用（它超過 400 行，拆出來）。
 *
 * <p>內容放在另一個 KDBX（{@link VaultCodec#createWithKeyOf}：和開啟中的保管庫同一把金鑰與 KDF 參數）唯一一筆條目的
 * 備註裡，整個檔案由函式庫以 AES-256 加密；不另外發明檔案格式，也不寫任何明文。
 */
final class ConflictDraftFile {

    private static final Logger LOG = Logger.getLogger(ConflictDraftFile.class.getName());
    /** 暫存保管庫與唯一一筆條目的名稱。 */
    private static final String NAME = "S-Vault conflict draft";

    private final VaultCodec codec;
    private final SaveCoordinator.Serializer serializer;
    private final Clock clock;

    ConflictDraftFile(VaultCodec codec, SaveCoordinator.Serializer serializer, Clock clock) {
        this.codec = codec;
        this.serializer = serializer;
        this.clock = clock;
    }

    boolean write(Vault keySource, Path file, String content) {
        try (Vault draft = codec.createWithKeyOf(NAME, keySource)) {
            new VaultEditor(draft, clock).addEntry(draft.id(), new EntryDraft(NAME, "", "", "", content));
            AtomicFiles.write(file, serializer.serialize(draft));
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "無法寫入衝突暫存：{0}", e.getClass().getName());
            return false;
        }
    }

    String read(Vault keySource, Path file) {
        byte[] bytes = SyncFiles.read(file, "衝突暫存");
        if (bytes == null) {
            return null;
        }
        try (Vault draft = codec.openWithKeyOf(bytes, keySource)) {
            for (EntryView e : draft.entries()) {
                if (NAME.equals(e.title())) {
                    return e.notes();
                }
            }
            return null;
        } catch (VaultException | RuntimeException e) {
            LOG.log(Level.WARNING, "衝突暫存解不開，刪除：{0}", e.getClass().getName());
            try {
                Files.deleteIfExists(file);
            } catch (IOException io) {
                LOG.log(Level.WARNING, "無法刪除衝突暫存：{0}", io.getClass().getName());
            }
            return null;
        }
    }
}
