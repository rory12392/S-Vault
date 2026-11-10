package app.svault.core.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 「關於／開源授權」頁的內容（T-23 desktop、T-24 android 共用）。
 *
 * <p>來源是 repo 的 {@code docs/NOTICES.md}：它在 core 的資源檔裡有一份相同的複本（{@link #RESOURCE}），
 * 因為建置時複製要改共用熱點 {@code build.gradle.kts}；兩份不同時 {@code NoticesTest} 會失敗。
 * 只讀 {@code ## 標題} 底下的表格，沒有表格的段落（不隨程式發佈的開發工具）不列出。
 * 授權全文是 {@code licenses/<id>.txt}，取自相依套件 jar 與 JDK 內附的授權檔（CC BY 3.0 取自 creativecommons.org 的 legalcode.txt，MPL 2.0 取自 mozilla.org/MPL/2.0；
 * Boost 1.0 取自 jackson-core 內附的 FastDoubleParser-ThirdParty-LICENSE）。
 */
public final class Notices {

    /** {@code NOTICES.md} 複本的資源路徑。 */
    public static final String RESOURCE = "/app/svault/core/shell/NOTICES.md";

    private static final String LICENSE_DIR = "/app/svault/core/shell/licenses/";
    private static final List<String> LICENSE_IDS = List.of("bouncy-castle", "mit", "apache-2.0", "bsd-2-clause",
            "bsd-3-clause", "gpl-2.0-classpath-exception", "cc-by-3.0", "mpl-2.0", "boost-1.0");

    /** 顯示在哪個平台的關於頁。 */
    public enum Platform { DESKTOP, ANDROID }

    /**
     * 一個開源元件。
     *
     * @param name       名稱（已去掉 Markdown 記號）
     * @param version    版本；表格沒有版本欄時是空字串
     * @param license    授權名稱（照 NOTICES.md）
     * @param purpose    用途
     * @param licenseIds 授權全文的代號（{@link #licenseText}），依出現順序
     */
    public record Item(String name, String version, String license, String purpose, List<String> licenseIds) {

        /** 不可變。 */
        public Item {
            licenseIds = List.copyOf(licenseIds);
        }
    }

    /**
     * NOTICES.md 的一個段落。
     *
     * @param title 段落標題（例：「兩平台共用（core）」）
     * @param items 元件
     */
    public record Section(String title, List<Item> items) {

        /** 不可變。 */
        public Section {
            items = List.copyOf(items);
        }
    }

    private Notices() {
    }

    /**
     * @param platform 平台
     * @return 該平台的關於頁要列的段落：共用段落與該平台的段落，依 NOTICES.md 的順序
     * @throws IllegalStateException 資源檔不存在（打包錯誤）
     */
    public static List<Section> forPlatform(Platform platform) {
        List<Section> result = new ArrayList<>();
        for (Section s : parse(read(RESOURCE))) {
            if (belongsTo(s.title(), platform)) {
                result.add(s);
            }
        }
        return List.copyOf(result);
    }

    /**
     * @param id {@link Item#licenseIds()} 中的代號
     * @return 授權全文
     * @throws IllegalArgumentException 不認得的代號
     * @throws IllegalStateException    資源檔不存在（打包錯誤）
     */
    public static String licenseText(String id) {
        if (!LICENSE_IDS.contains(id)) {
            throw new IllegalArgumentException("不認得的授權代號：" + id);
        }
        return read(LICENSE_DIR + id + ".txt");
    }

    /**
     * @param license NOTICES.md 的授權欄
     * @return 對應的全文代號；不認得時是空清單（{@code NoticesTest} 會擋下）
     */
    static List<String> licenseIds(String license) {
        List<String> ids = new ArrayList<>();
        // Bouncy Castle 的授權寫成「（MIT 型）」，全文帶它自己的版權聲明，不和 zxcvbn4j 的 MIT 混在一起
        if (license.contains("Bouncy Castle")) {
            ids.add("bouncy-castle");
        } else if (license.contains("MIT")) {
            ids.add("mit");
        }
        if (license.contains("Apache")) {
            ids.add("apache-2.0");
        }
        if (license.contains("BSD 2")) {
            ids.add("bsd-2-clause");
        }
        if (license.contains("BSD 3")) {
            ids.add("bsd-3-clause");
        }
        if (license.contains("GPL")) {
            ids.add("gpl-2.0-classpath-exception");
        }
        if (license.contains("CC BY 3.0")) {
            ids.add("cc-by-3.0");
        }
        if (license.contains("Mozilla Public License 2.0")) {
            ids.add("mpl-2.0");
        }
        if (license.contains("Boost")) {
            ids.add("boost-1.0");
        }
        return ids;
    }

    static List<Section> parse(String markdown) {
        List<Section> sections = new ArrayList<>();
        String title = null;
        List<List<String>> table = new ArrayList<>();
        for (String line : markdown.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                addSection(sections, title, table);
                title = trimmed.substring(3).trim();
                table = new ArrayList<>();
            } else if (title != null && trimmed.startsWith("|")) {
                table.add(cells(trimmed));
            }
        }
        addSection(sections, title, table);
        return sections;
    }

    private static void addSection(List<Section> sections, String title, List<List<String>> table) {
        // 表格至少要有標題列、分隔列與一列內容
        if (title == null || table.size() < 3) {
            return;
        }
        List<String> header = table.get(0);
        int version = header.indexOf("版本");
        int license = header.indexOf("授權");
        int purpose = header.indexOf("用途");
        List<Item> items = new ArrayList<>();
        for (List<String> row : table.subList(2, table.size())) {
            String lic = cell(row, license);
            items.add(new Item(cell(row, 0), cell(row, version), lic, cell(row, purpose), licenseIds(lic)));
        }
        sections.add(new Section(title, items));
    }

    private static List<String> cells(String row) {
        String inner = row.substring(1, row.endsWith("|") && row.length() > 1 ? row.length() - 1 : row.length());
        List<String> cells = new ArrayList<>();
        for (String c : inner.split("\\|", -1)) {
            cells.add(c.replace("`", "").replace("**", "").trim());
        }
        return cells;
    }

    private static String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : "";
    }

    private static boolean belongsTo(String title, Platform platform) {
        boolean android = title.contains("Android");
        boolean desktop = title.contains("desktop") || title.contains("Windows");
        if (!android && !desktop) {
            return true;
        }
        return platform == Platform.ANDROID ? android : desktop;
    }

    private static String read(String resource) {
        try (InputStream in = Notices.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少資源檔：" + resource);
            }
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // InputStream.readAllBytes 在 Android 要到 API 33 才有（STACK §7 第 1 點）
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
