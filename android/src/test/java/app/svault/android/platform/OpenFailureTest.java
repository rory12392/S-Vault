package app.svault.android.platform;

import static org.junit.Assert.assertEquals;

import app.svault.android.R;
import app.svault.core.storage.VaultIoException;
import app.svault.core.vault.CorruptVaultException;
import app.svault.core.vault.UnsupportedKdfException;
import app.svault.core.vault.WrongPasswordException;
import org.junit.Test;

/**
 * 開啟失敗的分類與提示（AC-06-4、AC-09-5 的偵測部分、AC-01-3、AC-01-7）：文字逐字比對。
 */
public class OpenFailureTest {

    private static void assertFailure(OpenFailure f, OpenFailure.Kind kind, int message, OpenFailure.Action action) {
        assertEquals(kind, f.kind());
        assertEquals(message, f.messageRes());
        assertEquals(action, f.action());
    }

    @Test
    public void c4_costlyKdfAsksWithFactor() {
        OpenFailure f = OpenFailure.of(new app.svault.core.vault.CostlyKdfException(15.2), true);
        assertFailure(f, OpenFailure.Kind.COSTLY, R.string.sv_open_costly, OpenFailure.Action.NONE);
        org.junit.Assert.assertEquals("15", f.messageArg());
    }

    @Test
    public void ac06_4_notFoundOffersReselect() {
        assertFailure(OpenFailure.of(new VaultIoException(VaultIoException.Reason.NOT_FOUND, "x"), true),
                OpenFailure.Kind.NOT_FOUND, R.string.sv_open_not_found, OpenFailure.Action.RESELECT_FILE);
    }

    @Test
    public void ac06_4_accessDeniedOffersReselect() {
        assertFailure(OpenFailure.of(new VaultIoException(VaultIoException.Reason.ACCESS_DENIED, "x"), true),
                OpenFailure.Kind.ACCESS_DENIED, R.string.sv_open_access_denied, OpenFailure.Action.RESELECT_FILE);
    }

    @Test
    public void ac06_4_offlineOffersReselect() {
        assertFailure(OpenFailure.of(new VaultIoException(VaultIoException.Reason.IO, "x"), false),
                OpenFailure.Kind.UNAVAILABLE, R.string.sv_open_unavailable, OpenFailure.Action.RESELECT_FILE);
    }

    @Test
    public void ac09_5_corruptWithBackupsOffersRestore() {
        assertFailure(OpenFailure.of(new CorruptVaultException("x"), true),
                OpenFailure.Kind.CORRUPT, R.string.sv_open_corrupt, OpenFailure.Action.RESTORE_BACKUP);
    }

    @Test
    public void ac09_5_corruptWithoutBackupsOffersReselect() {
        assertFailure(OpenFailure.of(new CorruptVaultException("x"), false),
                OpenFailure.Kind.CORRUPT, R.string.sv_open_corrupt, OpenFailure.Action.RESELECT_FILE);
    }

    @Test
    public void ac09_5_wrongPasswordIsNotCorruption() {
        assertFailure(OpenFailure.of(new WrongPasswordException(), true),
                OpenFailure.Kind.WRONG_PASSWORD, R.string.sv_open_wrong_password, OpenFailure.Action.NONE);
    }

    @Test
    public void ac01_7_unsupportedKdf() {
        OpenFailure f = OpenFailure.of(new UnsupportedKdfException("Argon2id"), true);
        assertFailure(f, OpenFailure.Kind.UNSUPPORTED, R.string.sv_open_unsupported_kdf, OpenFailure.Action.NONE);
        assertEquals("Argon2id", f.messageArg());
    }
}
