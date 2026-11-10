package app.svault.desktop.platform;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 把本機資料夾（{@code %APPDATA%\S-Vault}：加密的基準、暫存、備份，與明文的設定、最近開啟清單）的權限收緊成只有擁有者與
 * SYSTEM（T-116）。%APPDATA% 繼承下來的權限可能多給了別的帳號（2026-11-10 實測：Codex CLI 的 Windows 沙箱在
 * {@code AppData\Roaming} 加了 {@code CodexSandboxUsers} 的讀取權）。資料夾本身設成可繼承，之後新增的檔案自動套用；
 * 既有的檔案逐一改。只用 Java 的 ACL API（不呼叫 icacls），沒有 ACL 的檔案系統不做事。
 */
public final class OwnerOnlyAcl {

    /** Windows 的 {@code FILE_ATTRIBUTE_REPARSE_POINT}。 */
    private static final int REPARSE_POINT = 0x400;

    private OwnerOnlyAcl() {
    }

    /**
     * @param dir 資料夾
     * @return 這個檔案系統支援 ACL（Windows 的 NTFS）
     */
    public static boolean supported(Path dir) {
        return Files.getFileAttributeView(dir, AclFileAttributeView.class) != null;
    }

    /**
     * @param dir 本機資料夾（已存在）
     * @throws IOException 無法讀寫權限
     */
    public static void apply(Path dir) throws IOException {
        if (!supported(dir)) {
            return;
        }
        UserPrincipal owner = Files.getOwner(dir);
        UserPrincipal system = FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName("SYSTEM");
        // 不跟連結：Files.walk 會走進 Windows 的 junction，連結指向的外部檔案權限也被改掉（Codex M-04 審查待辦 3，實測）
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes attrs) throws IOException {
                if (!p.equals(dir) && isLink(p, attrs)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                restrict(p, true);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) throws IOException {
                if (!isLink(p, attrs)) {
                    restrict(p, false);
                }
                return FileVisitResult.CONTINUE;
            }

            private void restrict(Path p, boolean folder) throws IOException {
                Set<AclEntryFlag> inherit = folder
                        ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) : Set.of();
                Files.getFileAttributeView(p, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .setAcl(List.of(full(owner, inherit), full(system, inherit)));
            }
        });
    }

    /** 符號連結、junction 等重解析點（Windows 的 {@code FILE_ATTRIBUTE_REPARSE_POINT}）。 */
    private static boolean isLink(Path p, BasicFileAttributes attrs) throws IOException {
        if (attrs.isSymbolicLink() || attrs.isOther()) {
            return true;
        }
        Object dos = Files.getAttribute(p, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
        return dos instanceof Integer bits && (bits & REPARSE_POINT) != 0;
    }

    private static AclEntry full(UserPrincipal who, Set<AclEntryFlag> flags) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build();
    }
}
