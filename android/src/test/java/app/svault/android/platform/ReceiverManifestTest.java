package app.svault.android.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * S-Vault 自己的廣播接收器（T-28 起：剪貼簿的備援清除）只給 App 自己的 {@code PendingIntent} 叫：不匯出、沒有
 * intent-filter，別的 App 不能送廣播進來（例如提早清掉或反覆叫起）。合併後 manifest 的路徑同 ManifestPermissionTest。
 */
public class ReceiverManifestTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String OWN = "app.svault.android.";

    @Test
    public void ownReceiversAreNotReachableFromOtherApps() throws Exception {
        String path = System.getProperty("svault.mergedManifest");
        assertNotNull("沒有傳入合併後的 manifest 路徑（svault.mergedManifest）", path);
        Document doc;
        try (InputStream in = Files.newInputStream(new File(path).toPath())) {
            doc = parse(in);
        }
        assertEquals("匯出或有 intent-filter 的接收器", List.of(), violations(doc));
        assertTrue("剪貼簿的備援清除接收器", receivers(doc).contains(OWN + "platform.ClipboardClearReceiver"));
    }

    @Test
    public void exportedOrFilteredReceiverIsCaught() throws Exception {
        String manifest = "<manifest xmlns:android=\"" + ANDROID_NS + "\"><application>"
                + "<receiver android:name=\"app.svault.android.platform.A\" android:exported=\"true\"/>"
                + "<receiver android:name=\"app.svault.android.platform.B\" android:exported=\"false\">"
                + "<intent-filter><action android:name=\"x\"/></intent-filter></receiver>"
                + "<receiver android:name=\"app.svault.android.platform.C\"/>"
                + "<receiver android:name=\"app.svault.android.platform.D\" android:exported=\"false\"/>"
                + "<receiver android:name=\"androidx.Other\" android:exported=\"true\"/>"
                + "</application></manifest>";
        Document doc = parse(new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));
        // C 沒寫 exported：targetSdk 31 起建置會失敗，這裡也一併擋下
        assertEquals(List.of("app.svault.android.platform.A", "app.svault.android.platform.B",
                "app.svault.android.platform.C"), violations(doc));
    }

    private static Document parse(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(in);
    }

    private static List<String> receivers(Document doc) {
        List<String> out = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("receiver");
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add(((Element) nodes.item(i)).getAttributeNS(ANDROID_NS, "name"));
        }
        return out;
    }

    private static List<String> violations(Document doc) {
        List<String> out = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("receiver");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element r = (Element) nodes.item(i);
            String name = r.getAttributeNS(ANDROID_NS, "name");
            if (!name.startsWith(OWN)) {
                continue;
            }
            if (!"false".equals(r.getAttributeNS(ANDROID_NS, "exported"))
                    || r.getElementsByTagName("intent-filter").getLength() > 0) {
                out.add(name);
            }
        }
        return out;
    }
}
