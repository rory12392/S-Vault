package app.svault.core.vault;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.PropertyValue;

/**
 * 只為合併暫時開啟的基準用的受保護欄位值（T-29，D-101 修訂）：標為受保護、但不經 {@code SealedStore} 封存，
 * 以字串保存。快照本來就把每個欄位攤成明文字串，基準封存只是多一次封存與解封（手機 2,000 筆約 0.3 秒）。
 *
 * <p>範圍：只由 {@link VaultCodec#openForMerge} 產生；從這種保管庫複製到別的保管庫時一律依目標的策略重新封存
 * （{@link KdbxCopy#resealed}）。{@link #toString()} 不含值。
 */
final class UnsealedValue implements PropertyValue {

    /** 讀檔時用的策略：受保護的欄位用 {@link UnsealedValue}，其他照函式庫預設。 */
    static final Strategy STRATEGY = new Strategy() {
        @Override
        public List<String> getProtectedProperties() {
            List<String> names = new ArrayList<>();
            names.add(Entry.STANDARD_PROPERTY_NAME_PASSWORD);
            return names;
        }

        @Override
        public Factory<? extends PropertyValue> newProtected() {
            return FACTORY;
        }

        @Override
        public Factory<? extends PropertyValue> newUnprotected() {
            return BytesStore.getFactory();
        }
    };

    private static final Factory<UnsealedValue> FACTORY = new Factory<>() {
        @Override
        public UnsealedValue of(CharSequence value) {
            return new UnsealedValue(value.toString());
        }

        @Override
        public UnsealedValue of(char[] value) {
            return new UnsealedValue(new String(value));
        }

        @Override
        public UnsealedValue of(byte[] value) {
            return new UnsealedValue(new String(value, StandardCharsets.UTF_8));
        }
    };

    private final String value;

    private UnsealedValue(String value) {
        this.value = value;
    }

    @Override
    public CharSequence getValue() {
        return value;
    }

    @Override
    public char[] getValueAsChars() {
        return value.toCharArray();
    }

    @Override
    public byte[] getValueAsBytes() {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean isProtected() {
        return true;
    }

    @Override
    public String getValueAsString() {
        return value;
    }

    @Override
    public String toString() {
        return "UnsealedValue[受保護]";
    }
}
