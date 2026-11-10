package app.svault.fixture.feature.alpha;

import app.svault.fixture.feature.beta.BetaService;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;

/**
 * LayeringTest 的違規範例：假模組 alpha 故意越界，確認每條規則都會擋（不會被執行）。
 */
public final class AlphaViolations {

    private AlphaViolations() {
    }

    /** 違規：功能模組 import 另一個功能模組。 */
    static String usesOtherFeature() {
        return new BetaService().name();
    }

    /** 違規：core.vault 以外使用 KeePassJava2。 */
    static Object usesKeePassJava2() throws IOException {
        return new JacksonDatabase();
    }

    /** 違規：health 以外使用 Socket。 */
    static Object usesSocket() {
        return new Socket();
    }

    /** 違規：health 以外開啟 URL。 */
    static InputStream opensUrl(URL url) throws IOException {
        return url.openStream();
    }

    /** 允許：URI 只是解析網址。 */
    static String parsesUri(String s) {
        return URI.create(s).getHost();
    }
}
