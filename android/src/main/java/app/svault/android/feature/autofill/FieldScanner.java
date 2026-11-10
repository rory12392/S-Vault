package app.svault.android.feature.autofill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 走訪請求畫面的所有節點，找出帳號、密碼欄與網頁網域（AC-10-3，{@code onFillRequest} 用）。
 */
final class FieldScanner {

    /** 外層 id 看最近的幾層（有 id 的才算）：長榮航空的欄位在 RelativeLayout 裡，中間可能還有沒有 id 的容器。 */
    private static final int OUTER_IDS = 2;

    private FieldScanner() {
    }

    /**
     * 走訪中的節點、它外層的 id（近的在前，最多 {@link #OUTER_IDS} 個），以及最近一個帶網域的祖先（含自己）的網域。
     */
    private record Visit<I>(AfNode<I> node, List<String> outerIds, String domain) { }

    /**
     * 依畫面順序走訪（每個視窗一個根節點，對話框與底下的畫面都看）。沒有任何帳號線索、但有密碼欄時，取第一個密碼欄之前
     * 最近的一般文字欄當帳號欄：很多 App 的帳號欄沒有 hint 也沒有 inputType。沒有這種欄位、但有兩個以上密碼欄，而且
     * 第一個沒有密碼字樣、第二個有時，第一個是遮罩的帳號欄。
     *
     * <p>網域以第一個帶網域的節點為準（頂層網頁）；網域不同的節點（跨網域 iframe）裡的欄位一律略過，
     * 帳密不會填進第三方內容（2026-11-03 資安總檢 M-1）。
     *
     * @param roots 各視窗的根節點
     * @param <I>   欄位代號的型別
     * @return 找到的欄位
     */
    static <I> FieldScan<I> scan(List<? extends AfNode<I>> roots) {
        List<I> usernames = new ArrayList<>();
        List<I> passwords = new ArrayList<>();
        String webDomain = null;
        String webScheme = null;
        I plainBeforePassword = null;
        List<AfNode<I>> passwordNodes = new ArrayList<>();
        // 不用遞迴：網頁的節點樹可能很深
        Deque<Visit<I>> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.push(new Visit<>(roots.get(i), List.of(), null));
        }
        while (!stack.isEmpty()) {
            Visit<I> visit = stack.pop();
            AfNode<I> node = visit.node();
            String own = node.webDomain();
            String domain = own != null && !own.isBlank() ? own : visit.domain();
            if (webDomain == null && domain != null) {
                // 由上往下走，第一個有網域的一定是自己帶網域的節點：scheme 也取它的
                webDomain = domain;
                webScheme = node.webScheme();
            }
            List<? extends AfNode<I>> children = node.children();
            List<String> childOuter = outerIdsFor(node, visit.outerIds());
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(new Visit<>(children.get(i), childOuter, domain));
            }
            if (domain != null && !domain.equalsIgnoreCase(webDomain)) {
                continue;
            }
            switch (FieldClassifier.classify(node, String.join(" ", visit.outerIds()))) {
                case USERNAME -> usernames.add(node.autofillId());
                case PASSWORD -> {
                    passwords.add(node.autofillId());
                    passwordNodes.add(node);
                }
                default -> {
                    if (passwords.isEmpty() && FieldClassifier.isPlainText(node)) {
                        plainBeforePassword = node.autofillId();
                    }
                }
            }
        }
        if (usernames.isEmpty() && !passwords.isEmpty() && plainBeforePassword != null) {
            usernames.add(plainBeforePassword);
        } else if (usernames.isEmpty() && passwordNodes.size() >= 2
                && !FieldClassifier.namedPassword(passwordNodes.get(0))
                && FieldClassifier.namedPassword(passwordNodes.get(1))) {
            // 遮罩的帳號欄（T-83 實機：momo 的帳號欄是 type=password、id=residentNo）：第一個密碼欄沒有密碼字樣、
            // 下一個有 → 第一個是帳號欄。註冊頁的「密碼／確認密碼」都有密碼字樣，不受影響
            usernames.add(passwords.remove(0));
        }
        return new FieldScan<>(usernames, passwords, webDomain, webScheme);
    }

    private static List<String> outerIdsFor(AfNode<?> parent, List<String> parentOuter) {
        String id = parent.idEntry();
        if (id == null || id.isBlank()) {
            return parentOuter;
        }
        List<String> out = new ArrayList<>(OUTER_IDS);
        out.add(id);
        for (int i = 0; i < parentOuter.size() && out.size() < OUTER_IDS; i++) {
            out.add(parentOuter.get(i));
        }
        return out;
    }
}
