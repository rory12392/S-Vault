package app.svault.fixture.feature.health;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

/**
 * LayeringTest 的範例：health 模組可以連網（合規）。
 */
public final class HealthClient {

    private HealthClient() {
    }

    /** 允許：只有 health 能開啟連線。 */
    static URLConnection open(URL url) throws IOException {
        return url.openConnection();
    }
}
