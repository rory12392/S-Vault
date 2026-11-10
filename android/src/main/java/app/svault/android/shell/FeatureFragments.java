package app.svault.android.shell;

import androidx.fragment.app.Fragment;
import java.lang.reflect.Modifier;
import java.util.Optional;

/**
 * 把 Intent 中的類別名稱轉成模組頁面的 Fragment 類別。只接受能建立實例的 Fragment 子類別，
 * 其他一律拒絕，避免 {@link FeatureHostActivity} 被拿來載入任意類別。
 */
final class FeatureFragments {

    private FeatureFragments() {
    }

    /**
     * @param className Fragment 的完整類別名稱
     * @param loader    App 的類別載入器
     * @return 類別；不存在、不是 Fragment、抽象或沒有 public 無參數建構子時為空
     */
    static Optional<Class<? extends Fragment>> resolve(String className, ClassLoader loader) {
        if (className == null || className.isEmpty()) {
            return Optional.empty();
        }
        try {
            // initialize=false：拒絕之前不執行該類別的 static 區塊
            Class<?> c = Class.forName(className, false, loader);
            if (!Fragment.class.isAssignableFrom(c) || Modifier.isAbstract(c.getModifiers())
                    || !Modifier.isPublic(c.getModifiers())) {
                return Optional.empty();
            }
            c.getConstructor();
            return Optional.of(c.asSubclass(Fragment.class));
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
            return Optional.empty();
        }
    }
}
