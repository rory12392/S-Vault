package app.svault.android.feature.autofill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 測試用的畫面節點：預設是看得見、可填入文字（{@code AUTOFILL_TYPE_TEXT}）、沒有任何線索的欄位。
 */
final class FakeNode implements AfNode<String> {

    static final int TEXT = 0x01;
    static final int TEXT_PASSWORD = 0x81;
    static final int TEXT_VISIBLE_PASSWORD = 0x91;
    static final int TEXT_WEB_PASSWORD = 0xe1;
    static final int NUMBER_PASSWORD = 0x12;
    static final int TEXT_EMAIL = 0x21;
    static final int TEXT_WEB_EMAIL = 0xd1;

    private final String id;
    private List<String> hints = List.of();
    private int inputType;
    private int autofillType = 1;
    private String idEntry;
    private String hint;
    private String htmlType;
    private String htmlName;
    private String webDomain;
    private boolean visible = true;
    private final List<FakeNode> children = new ArrayList<>();

    private FakeNode(String id) {
        this.id = id;
    }

    /** @return 可填入的欄位 */
    static FakeNode field(String id) {
        return new FakeNode(id);
    }

    /** @return 版面容器（沒有代號、不可填入） */
    static FakeNode group(FakeNode... children) {
        FakeNode n = new FakeNode(null);
        n.autofillType = 0;
        n.children.addAll(Arrays.asList(children));
        return n;
    }

    FakeNode hints(String... values) {
        hints = List.of(values);
        return this;
    }

    FakeNode inputType(int value) {
        inputType = value;
        return this;
    }

    FakeNode notFillable() {
        autofillType = 0;
        return this;
    }

    FakeNode idEntry(String value) {
        idEntry = value;
        return this;
    }

    FakeNode hint(String value) {
        hint = value;
        return this;
    }

    FakeNode html(String type, String name) {
        htmlType = type;
        htmlName = name;
        return this;
    }

    FakeNode webDomain(String value) {
        webDomain = value;
        return this;
    }

    FakeNode hidden() {
        visible = false;
        return this;
    }

    FakeNode add(FakeNode... more) {
        children.addAll(Arrays.asList(more));
        return this;
    }

    @Override
    public String autofillId() {
        return id;
    }

    @Override
    public List<String> autofillHints() {
        return hints;
    }

    @Override
    public int inputType() {
        return inputType;
    }

    @Override
    public int autofillType() {
        return autofillType;
    }

    @Override
    public String idEntry() {
        return idEntry;
    }

    @Override
    public String hint() {
        return hint;
    }

    @Override
    public String htmlInputType() {
        return htmlType;
    }

    @Override
    public String htmlName() {
        return htmlName;
    }

    @Override
    public String webDomain() {
        return webDomain;
    }

    @Override
    public boolean visible() {
        return visible;
    }

    @Override
    public List<FakeNode> children() {
        return children;
    }
}
