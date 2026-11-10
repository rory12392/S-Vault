package app.svault.android.feature.autofill;

import android.text.InputType;
import android.view.View;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 判斷一個欄位是帳號、密碼還是都不是（AC-10-3）。線索依可信度排序，前面的有結論就不看後面的：
 * <ol>
 * <li>{@code autofillHints}（含網頁的 {@code autocomplete}）：開發者明確標的用途，標了別的用途（地址）就不填；
 * {@code off}／{@code on} 不算用途，改看後面的線索（T-83）。
 * 只標 {@code passwordAuto} 時是密碼的線索，但由 id、外層 id 分出帳號欄（P-AF-03，T-83）。</li>
 * <li>{@code inputType} 的密碼與電子郵件變體。</li>
 * <li>網頁 {@code <input>} 的 {@code type}。</li>
 * <li>資源 id、提示文字、網頁 name 裡的關鍵字（中英日）。先排除搜尋欄，再看密碼、最後看帳號。</li>
 * </ol>
 * 只用到 Android 的常數（編譯時內嵌），可以在 JVM 上測。
 */
final class FieldClassifier {

    /** 欄位種類。 */
    enum Kind { USERNAME, PASSWORD, NONE }

    /** 小寫比對：App 用 {@code View.AUTOFILL_HINT_*}（{@code emailAddress}），網頁用 autocomplete 的值。 */
    private static final Set<String> PASSWORD_HINTS = Set.of("password", "current-password", "new-password");
    private static final Set<String> USERNAME_HINTS = Set.of("username", "emailaddress", "email", "phone", "tel");
    /**
     * 只說「和密碼有關」、沒說是哪一欄的 hint（P-AF-03：長榮航空的帳號欄與密碼欄都標 {@code passwordAuto}）。
     * 當成密碼的線索，但先看 id 與外層 id 能不能分出帳號欄（T-83）。
     */
    private static final Set<String> VAGUE_PASSWORD_HINTS = Set.of("passwordauto");
    private static final Set<String> NOT_PURPOSE_HINTS = Set.of("off", "on");

    /** 整段文字的子字串（不會誤中的長字與 CJK）。 */
    private static final List<String> PASSWORD_WORDS = List.of("password", "passwd", "passcode", "密碼", "密码",
            "パスワード", "비밀번호");
    /** 要整個單字相同才算（「pass」不能中 passport、compass）。 */
    private static final Set<String> PASSWORD_TOKENS = Set.of("pass", "pwd", "pw", "pswd");
    private static final List<String> USERNAME_WORDS = List.of("username", "userid", "email", "login", "account",
            "帳號", "账号", "帳戶", "账户", "使用者", "用戶", "用户", "電子郵件", "电子邮件", "信箱", "郵箱", "邮箱",
            "手機", "手机", "メール", "ユーザー", "아이디");
    private static final Set<String> USERNAME_TOKENS = Set.of("user", "mail", "acct", "phone", "mobile", "uid");
    private static final List<String> EXCLUDED_WORDS = List.of("search", "query", "搜尋", "搜索", "検索", "captcha",
            "驗證碼", "验证码", "otp");
    private static final Set<String> EXCLUDED_HTML_TYPES = Set.of("hidden", "search", "submit", "button", "checkbox",
            "radio", "number", "date", "url", "file");

    private FieldClassifier() {
    }

    /**
     * @param node 畫面節點
     * @return 欄位種類；看不見、不能填文字的一律 {@link Kind#NONE}
     */
    static Kind classify(AfNode<?> node) {
        return classify(node, "");
    }

    /**
     * @param node     畫面節點
     * @param outerIds 外層節點的資源 id（以空白相接，近的在前）；只在 hint 模稜兩可（{@code passwordAuto}）時用來分出帳號欄，
     *                 其他情況不看——外層叫 {@code login_form} 不代表裡面每一欄都是帳號
     * @return 欄位種類；看不見、不能填文字的一律 {@link Kind#NONE}
     */
    static Kind classify(AfNode<?> node, String outerIds) {
        if (node.autofillId() == null || node.autofillType() != View.AUTOFILL_TYPE_TEXT || !node.visible()) {
            return Kind.NONE;
        }
        List<String> hints = purposeHints(node);
        if (!hints.isEmpty()) {
            Kind byHints = fromHints(hints);
            return byHints == Kind.NONE && hints.stream().map(FieldClassifier::lower)
                    .anyMatch(VAGUE_PASSWORD_HINTS::contains) ? vaguePassword(node, outerIds) : byHints;
        }
        Kind byType = fromInputType(node.inputType());
        if (byType != Kind.NONE) {
            return byType;
        }
        String htmlType = lower(node.htmlInputType());
        if ("password".equals(htmlType)) {
            return Kind.PASSWORD;
        }
        if ("email".equals(htmlType)) {
            return Kind.USERNAME;
        }
        if (EXCLUDED_HTML_TYPES.contains(htmlType)) {
            return Kind.NONE;
        }
        return fromWords(lower(node.idEntry()) + " " + lower(node.hint()) + " " + lower(node.htmlName()),
                node.idEntry() + " " + node.htmlName());
    }

    /**
     * @param node 畫面節點
     * @return 可以當沒有線索的帳號欄（密碼欄前面的一般文字欄）
     */
    static boolean isPlainText(AfNode<?> node) {
        if (node.autofillId() == null || node.autofillType() != View.AUTOFILL_TYPE_TEXT || !node.visible()
                || !purposeHints(node).isEmpty()) {
            return false;
        }
        String htmlType = lower(node.htmlInputType());
        if (!htmlType.isEmpty() && !"text".equals(htmlType)) {
            return false;
        }
        String words = lower(node.idEntry()) + " " + lower(node.hint()) + " " + lower(node.htmlName());
        return EXCLUDED_WORDS.stream().noneMatch(words::contains);
    }

    /**
     * 網頁的 {@code autocomplete=off}／{@code on} 只是要不要讓瀏覽器記住輸入，不是欄位的用途（T-83 實機：momo 的密碼欄
     * 標 off），不算線索；其他值（含標了別的用途的）照舊。
     */
    private static List<String> purposeHints(AfNode<?> node) {
        return node.autofillHints().stream().filter(h -> !NOT_PURPOSE_HINTS.contains(lower(h))).toList();
    }

    /**
     * @param node 畫面節點
     * @return id、提示文字或網頁 name 裡有密碼字樣（{@link FieldScanner} 用來分出遮罩的帳號欄）
     */
    static boolean namedPassword(AfNode<?> node) {
        String words = lower(node.idEntry()) + " " + lower(node.hint()) + " " + lower(node.htmlName());
        return PASSWORD_WORDS.stream().anyMatch(words::contains)
                || tokens(node.idEntry() + " " + node.htmlName() + " " + words).stream()
                        .anyMatch(PASSWORD_TOKENS::contains);
    }

    private static Kind fromHints(List<String> hints) {
        boolean username = false;
        for (String hint : hints) {
            String h = lower(hint);
            if (PASSWORD_HINTS.contains(h)) {
                return Kind.PASSWORD;
            }
            username |= USERNAME_HINTS.contains(h);
        }
        return username ? Kind.USERNAME : Kind.NONE;
    }

    /**
     * 只標了 {@code passwordAuto}：先看欄位自己的 id、提示文字與網頁 name，再看外層 id；都沒結論時當密碼欄。
     * 不看 inputType：長榮航空的帳號欄是 visible password。
     */
    private static Kind vaguePassword(AfNode<?> node, String outerIds) {
        String own = lower(node.idEntry()) + " " + lower(node.hint()) + " " + lower(node.htmlName());
        if (EXCLUDED_WORDS.stream().anyMatch(own::contains)) {
            return Kind.NONE;
        }
        Kind byOwn = fromWords(own, node.idEntry() + " " + node.htmlName());
        if (byOwn != Kind.NONE) {
            return byOwn;
        }
        // 外層由近到遠一層一層看：近的那層（AccountEditText）比遠的容器（login_form）可信
        for (String outer : (outerIds == null ? "" : outerIds).split(" ")) {
            Kind byOuter = outer.isEmpty() ? Kind.NONE : fromWords(lower(outer), outer);
            if (byOuter != Kind.NONE) {
                return byOuter;
            }
        }
        return Kind.PASSWORD;
    }

    private static Kind fromInputType(int inputType) {
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) {
                return Kind.PASSWORD;
            }
            if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) {
                return Kind.USERNAME;
            }
        } else if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return Kind.PASSWORD;
        }
        return Kind.NONE;
    }

    /**
     * @param words 小寫的 id、提示文字、網頁 name
     * @param ids   原始大小寫的 id 與網頁 name（拆駝峰式單字用）
     */
    private static Kind fromWords(String words, String ids) {
        if (EXCLUDED_WORDS.stream().anyMatch(words::contains)) {
            return Kind.NONE;
        }
        Set<String> tokens = tokens(ids + " " + words);
        if (PASSWORD_WORDS.stream().anyMatch(words::contains) || tokens.stream().anyMatch(PASSWORD_TOKENS::contains)) {
            return Kind.PASSWORD;
        }
        if (USERNAME_WORDS.stream().anyMatch(words::contains) || tokens.stream().anyMatch(USERNAME_TOKENS::contains)) {
            return Kind.USERNAME;
        }
        return Kind.NONE;
    }

    /** 以非英數字元與駝峰式的大寫字母切開，轉小寫（{@code loginPwd} → login、pwd）。 */
    private static Set<String> tokens(String text) {
        String split = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        Set<String> out = new HashSet<>();
        for (String t : split.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
