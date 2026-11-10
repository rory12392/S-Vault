package app.svault.desktop.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本機資料夾只有自己與 SYSTEM 讀得到（T-116）：%APPDATA% 繼承下來的權限可能多給了別的帳號（例如 Codex CLI 沙箱的
 * CodexSandboxUsers 有讀取權）。
 */
class OwnerOnlyAclTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("T-116：資料夾與裡面既有的檔案都改成只有擁有者與 SYSTEM，之後新增的檔案也照樣繼承")
    void restrictsFolderAndExistingFiles() throws Exception {
        assumeTrue(OwnerOnlyAcl.supported(tmp), "只有 Windows（NTFS）有 ACL");
        Path dir = Files.createDirectories(tmp.resolve("S-Vault"));
        Path old = Files.writeString(Files.createDirectories(dir.resolve("base")).resolve("x.kdbx"), "x");

        OwnerOnlyAcl.apply(dir);

        Path later = Files.writeString(dir.resolve("recent.json"), "{}");
        for (Path p : List.of(dir, dir.resolve("base"), old, later)) {
            Set<String> who = principals(p);
            String owner = Files.getOwner(p).getName();
            assertTrue(who.contains(owner), p + " 要有擁有者：" + who);
            assertTrue(who.stream().allMatch(n -> n.equals(owner) || n.endsWith("SYSTEM")), p + " 不該有其他人：" + who);
        }
        assertEquals("x", Files.readString(old), "內容不受影響");
    }

    @Test
    @DisplayName("Codex M-04 審查待辦 3：資料夾裡的連結（Windows junction）不跟過去，連結指向的資料夾權限不變")
    void doesNotFollowLinks() throws Exception {
        assumeTrue(OwnerOnlyAcl.supported(tmp), "只有 Windows（NTFS）有 ACL");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path target = Files.writeString(outside.resolve("other.txt"), "y");
        List<AclEntry> before = Files.getFileAttributeView(target, AclFileAttributeView.class).getAcl();
        List<AclEntry> folderBefore = Files.getFileAttributeView(outside, AclFileAttributeView.class).getAcl();
        Path dir = Files.createDirectories(tmp.resolve("S-Vault"));
        // junction 不需要系統管理員權限（符號連結要）
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", dir.resolve("link").toString(), outside.toString())
                .redirectErrorStream(true).start();
        assumeTrue(p.waitFor() == 0 && Files.exists(dir.resolve("link")), "建不出 junction");

        OwnerOnlyAcl.apply(dir);

        assertEquals(before, Files.getFileAttributeView(target, AclFileAttributeView.class).getAcl(),
                "連結指向的檔案權限不變");
        assertEquals(folderBefore, Files.getFileAttributeView(outside, AclFileAttributeView.class).getAcl(),
                "連結指向的資料夾權限不變");
    }

    private static Set<String> principals(Path p) throws Exception {
        return Files.getFileAttributeView(p, AclFileAttributeView.class).getAcl().stream()
                .map(AclEntry::principal).map(java.security.Principal::getName).collect(Collectors.toSet());
    }
}
