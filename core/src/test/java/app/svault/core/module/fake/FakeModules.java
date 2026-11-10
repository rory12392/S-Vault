package app.svault.core.module.fake;

import java.util.List;

/**
 * ModuleDisableTest、ModuleFaultTest 用的假模組。每個巢狀類別都要能被 ServiceLoader 建立（public、無參數建構子）。
 */
public final class FakeModules {

    /** 「秘密」字串：故障例外的訊息含這段，測試確認它不會進日誌。 */
    public static final String SECRET = "probe1234-secret-field";

    private FakeModules() {
    }

    /** 正常模組。 */
    public static final class Alpha implements TestFeature {
        @Override
        public String id() {
            return "alpha";
        }

        @Override
        public String displayName() {
            return "Alpha";
        }

        @Override
        public List<String> pages() {
            return List.of("alpha-page");
        }
    }

    /** 正常模組。 */
    public static final class Beta implements TestFeature {
        @Override
        public String id() {
            return "beta";
        }

        @Override
        public String displayName() {
            return "Beta";
        }

        @Override
        public List<String> pages() {
            return List.of("beta-page-1", "beta-page-2");
        }
    }

    /** 預設停用的模組。 */
    public static final class OffByDefault implements TestFeature {
        @Override
        public String id() {
            return "off";
        }

        @Override
        public String displayName() {
            return "Off";
        }

        @Override
        public boolean enabledByDefault() {
            return false;
        }

        @Override
        public List<String> pages() {
            return List.of("off-page");
        }
    }

    /** 擴充點丟例外的模組（例外訊息含機密）。 */
    public static final class Faulty implements TestFeature {
        @Override
        public String id() {
            return "faulty";
        }

        @Override
        public String displayName() {
            return "Faulty";
        }

        @Override
        public List<String> pages() {
            throw new IllegalStateException("壞掉了：" + SECRET);
        }
    }

    /** 擴充點缺類別的模組（模擬模組 jar 不完整）。 */
    public static final class MissingClass implements TestFeature {
        @Override
        public String id() {
            return "missing";
        }

        @Override
        public String displayName() {
            return "Missing";
        }

        @Override
        public List<String> pages() {
            throw new NoClassDefFoundError("app/svault/core/feature/missing/Gone");
        }
    }

    /** 建構子就失敗的模組：ServiceLoader 會丟 ServiceConfigurationError。 */
    public static final class BrokenConstructor implements TestFeature {
        /** 永遠失敗。 */
        public BrokenConstructor() {
            throw new IllegalStateException("建構失敗");
        }

        @Override
        public String id() {
            return "broken";
        }

        @Override
        public String displayName() {
            return "Broken";
        }
    }

    /** 和 Alpha 同代號的模組：應該被略過。 */
    public static final class DuplicateAlpha implements TestFeature {
        @Override
        public String id() {
            return "alpha";
        }

        @Override
        public String displayName() {
            return "Alpha 2";
        }

        @Override
        public List<String> pages() {
            return List.of("duplicate-page");
        }
    }
}
