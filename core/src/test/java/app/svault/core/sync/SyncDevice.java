package app.svault.core.sync;

import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import app.svault.core.vault.VaultException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 同步測試的一台裝置：自己的本機資料夾（{@code base/}、{@code pending/}、{@code backups/}）與 {@link VaultSession}，
 * 和其他裝置共用同一個遠端（{@link MemoryLocation}，模擬 Google Drive 上的檔案）。只用假資料，密碼 probe1234。
 */
final class SyncDevice {

    final LocalStore store;
    final VaultSession session;
    final SaveCoordinator saver;
    private final MemoryLocation remote;
    private final MutableClock clock;

    SyncDevice(Path dir, VaultCodec codec, MemoryLocation remote, MutableClock clock) throws IOException {
        this.store = LocalStore.open(dir, key -> null, key -> null);
        TestSessions.Parts parts = TestSessions.assemble(codec, store, clock);
        this.session = parts.session();
        this.saver = parts.saver();
        this.remote = remote;
        this.clock = clock;
    }

    void unlock() throws VaultException {
        session.unlock(remote, TestVaults.password());
    }

    /** 在記憶體中改（還沒存檔）；每次改之前時間往前 1 分鐘，兩台的修改時間才分得出先後。 */
    void edit(Consumer<VaultEditor> change) {
        clock.advance(java.time.Duration.ofMinutes(1));
        change.accept(new VaultEditor(session.vault(), clock));
    }

    /** 改完存檔。 */
    SyncReport editAndSave(Consumer<VaultEditor> change) throws VaultException {
        edit(change);
        return session.save();
    }

    UUID add(String title, String password) throws VaultException {
        UUID[] id = new UUID[1];
        editAndSave(e -> id[0] = e.addEntry(session.vault().id(), new EntryDraft(title, "rory", password, "", "")));
        return id[0];
    }

    EntryView entry(String title) {
        for (EntryView e : session.vault().entries()) {
            if (e.title().equals(title)) {
                return e;
            }
        }
        throw new AssertionError("找不到條目：" + title);
    }

    List<String> titles() {
        List<String> out = new ArrayList<>();
        for (EntryView e : session.vault().entries()) {
            out.add(e.title());
        }
        out.sort(null);
        return out;
    }

    Path baseFile() {
        return store.baseFile(session.vault().id(), remote.persistentId());
    }

    Path pendingFile(UUID vaultId) {
        return store.pendingFile(vaultId, remote.persistentId());
    }

    byte[] base() throws IOException {
        return Files.readAllBytes(baseFile());
    }
}
