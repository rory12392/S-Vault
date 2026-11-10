package app.svault.android.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * App 的每個畫面（合併後 manifest 的 Activity）都要分類（AC-12-4，T-19）：顯示保管庫內容的繼承
 * {@link SecureActivity}（{@code FLAG_SECURE}）；不顯示內容、刻意可以截圖的列在 {@link #NOT_VAULT_CONTENT}，各自寫理由。
 * 之後新增的畫面沒有分類就失敗。對話框另由 {@code SecureWindowRulesTest} 檢查，實際的視窗旗標由儀器測試
 * {@code SecureWindowsScreensTest} 在手機上確認。
 */
public class SecureActivityManifestTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** 不顯示保管庫內容的畫面（D-58）。 */
    private static final Set<String> NOT_VAULT_CONTENT = Set.of(
            // 歡迎：最近開啟的檔名（不是保管庫內容）
            "app.svault.android.shell.start.WelcomeActivity",
            // 建立、解鎖：只有主密碼欄（遮罩、API 33 起不產生最近使用縮圖，D-58；無障礙只拿到遮罩字元，T-19）
            "app.svault.android.shell.start.CreateVaultActivity",
            "app.svault.android.shell.start.UnlockActivity",
            // 備份還原：只顯示備份時間（T-12）
            "app.svault.android.shell.start.BackupActivity",
            // 元件展示頁：只在 debug 版、只有假資料，刻意可以截圖（DESIGN §8）
            "app.svault.android.gallery.UiGalleryActivity");

    @Test
    public void everyActivityIsClassified() throws Exception {
        String path = System.getProperty("svault.mergedManifest");
        assertNotNull("沒有傳入合併後的 manifest 路徑（svault.mergedManifest）", path);
        List<String> activities = activities(new File(path));
        List<String> unclassified = new ArrayList<>();
        int secure = 0;
        for (String name : activities) {
            if (!name.startsWith("app.svault.android.")) {
                continue;
            }
            Class<?> c = Class.forName(name, false, getClass().getClassLoader());
            if (SecureActivity.class.isAssignableFrom(c)) {
                secure++;
            } else if (!NOT_VAULT_CONTENT.contains(name)) {
                unclassified.add(name);
            }
        }
        assertEquals("沒有 FLAG_SECURE、也不在「不顯示內容」清單的畫面", List.of(), unclassified);
        // 首頁、條目清單、詳細、編輯、FeatureHost（設定、關於、模組頁）
        assertTrue("顯示保管庫內容的畫面太少：" + secure, secure >= 5);
    }

    private static List<String> activities(File manifest) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc;
        try (InputStream in = Files.newInputStream(manifest.toPath())) {
            doc = factory.newDocumentBuilder().parse(in);
        }
        String pkg = doc.getDocumentElement().getAttribute("package");
        NodeList nodes = doc.getElementsByTagName("activity");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String name = ((Element) nodes.item(i)).getAttributeNS(ANDROID_NS, "name");
            out.add(name.startsWith(".") ? pkg + name : name);
        }
        return out;
    }
}
