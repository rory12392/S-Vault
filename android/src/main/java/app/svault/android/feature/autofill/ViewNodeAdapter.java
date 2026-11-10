package app.svault.android.feature.autofill;

import android.app.assist.AssistStructure;
import android.util.Pair;
import android.view.View;
import android.view.ViewStructure.HtmlInfo;
import android.view.autofill.AutofillId;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 把系統給的 {@link AssistStructure.ViewNode} 轉成 {@link AfNode}：辨識與比對的邏輯都在純 Java 那邊。
 * 只讀辨識欄位需要的屬性，不讀欄位的文字；節點不保存（請求處理完就丟掉）。
 */
final class ViewNodeAdapter implements AfNode<AutofillId> {

    private final AssistStructure.ViewNode node;

    private ViewNodeAdapter(AssistStructure.ViewNode node) {
        this.node = node;
    }

    /**
     * @param structure 請求的畫面（最後一個 {@code FillContext}）
     * @return 各視窗的根節點
     */
    static List<ViewNodeAdapter> roots(AssistStructure structure) {
        List<ViewNodeAdapter> roots = new ArrayList<>();
        for (int i = 0; i < structure.getWindowNodeCount(); i++) {
            roots.add(new ViewNodeAdapter(structure.getWindowNodeAt(i).getRootViewNode()));
        }
        return roots;
    }

    @Override
    public AutofillId autofillId() {
        return node.getAutofillId();
    }

    @Override
    public List<String> autofillHints() {
        List<String> hints = new ArrayList<>();
        String[] declared = node.getAutofillHints();
        if (declared != null) {
            hints.addAll(Arrays.asList(declared));
        }
        // 網頁的 autocomplete（例如 "username"、"section-login current-password"）：瀏覽器不一定轉成 autofillHints
        String autocomplete = htmlAttribute("autocomplete");
        if (autocomplete != null && !autocomplete.isBlank()) {
            hints.addAll(Arrays.asList(autocomplete.trim().toLowerCase(Locale.ROOT).split("\\s+")));
        }
        return hints;
    }

    @Override
    public int inputType() {
        return node.getInputType();
    }

    @Override
    public int autofillType() {
        return node.getAutofillType();
    }

    @Override
    public String idEntry() {
        return node.getIdEntry();
    }

    @Override
    public String hint() {
        return node.getHint();
    }

    @Override
    public String htmlInputType() {
        HtmlInfo html = node.getHtmlInfo();
        return html != null && "input".equalsIgnoreCase(html.getTag()) ? htmlAttribute("type") : null;
    }

    @Override
    public String htmlName() {
        String name = htmlAttribute("name");
        String id = htmlAttribute("id");
        if (name == null) {
            return id;
        }
        return id == null ? name : name + " " + id;
    }

    @Override
    public String webDomain() {
        return node.getWebDomain();
    }

    @Override
    public String webScheme() {
        return node.getWebScheme();
    }

    @Override
    public boolean visible() {
        return node.getVisibility() == View.VISIBLE;
    }

    @Override
    public List<ViewNodeAdapter> children() {
        int count = node.getChildCount();
        return new AbstractList<>() {
            @Override
            public ViewNodeAdapter get(int index) {
                return new ViewNodeAdapter(node.getChildAt(index));
            }

            @Override
            public int size() {
                return count;
            }
        };
    }

    private String htmlAttribute(String name) {
        HtmlInfo html = node.getHtmlInfo();
        List<Pair<String, String>> attributes = html == null ? null : html.getAttributes();
        if (attributes == null) {
            return null;
        }
        for (Pair<String, String> a : attributes) {
            if (name.equalsIgnoreCase(a.first)) {
                return a.second;
            }
        }
        return null;
    }
}
