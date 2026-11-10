package app.svault.android;

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
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 合併後的 manifest 只能有白名單內的權限（Architecture §3、STACK §4）。
 * 路徑由 build.gradle.kts 以系統屬性 {@code svault.mergedManifest} 傳入。
 */
public class ManifestPermissionTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** Architecture §3：App 申請的兩項＋函式庫自動加入、可接受的兩項。 */
    private static final Set<String> ALLOWED = Set.of(
            "android.permission.INTERNET",
            "android.permission.USE_BIOMETRIC",
            "app.svault.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "android.permission.USE_FINGERPRINT");

    @Test
    public void mergedManifestOnlyHasAllowedPermissions() throws Exception {
        String path = System.getProperty("svault.mergedManifest");
        assertNotNull("沒有傳入合併後的 manifest 路徑（svault.mergedManifest）", path);
        Set<String> requested;
        try (InputStream in = Files.newInputStream(new File(path).toPath())) {
            requested = permissions(in);
        }
        assertEquals("出現白名單以外的權限", List.of(), disallowed(requested));
        assertTrue(requested.contains("android.permission.INTERNET"));
        assertTrue(requested.contains("android.permission.USE_BIOMETRIC"));
    }

    @Test
    public void extraPermissionIsCaught() throws Exception {
        String manifest = "<manifest xmlns:android=\"" + ANDROID_NS + "\">"
                + "<uses-permission android:name=\"android.permission.INTERNET\"/>"
                + "<uses-permission android:name=\"android.permission.READ_CONTACTS\"/>"
                + "<uses-permission-sdk-23 android:name=\"android.permission.CAMERA\"/>"
                + "</manifest>";
        Set<String> requested = permissions(new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("android.permission.CAMERA", "android.permission.READ_CONTACTS"), disallowed(requested));
    }

    private static Set<String> permissions(InputStream manifest) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(manifest);
        Set<String> names = new TreeSet<>();
        for (String tag : List.of("uses-permission", "uses-permission-sdk-23")) {
            NodeList nodes = doc.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                names.add(((Element) nodes.item(i)).getAttributeNS(ANDROID_NS, "name"));
            }
        }
        return names;
    }

    private static List<String> disallowed(Set<String> requested) {
        List<String> out = new ArrayList<>();
        for (String name : requested) {
            if (!ALLOWED.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }
}
