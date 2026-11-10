package app.svault.android.feature.autofill;

import static app.svault.android.feature.autofill.FakeNode.field;
import static app.svault.android.feature.autofill.FakeNode.group;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * AC-10-3（欄位辨識）：走訪整個畫面找出帳號、密碼欄位與網頁網域。
 */
public class FieldScannerTest {

    @Test
    public void ac10_3_findsFieldsInNestedLayout() {
        FakeNode root = group(
                group(field("title").hint("登入")),
                group(group(field("user").hints("username")), field("pass").inputType(FakeNode.TEXT_PASSWORD)),
                field("remember").inputType(FakeNode.TEXT));
        FieldScan<String> scan = FieldScanner.scan(List.of(root));
        assertEquals(List.of("user"), scan.usernames());
        assertEquals(List.of("pass"), scan.passwords());
        assertNull(scan.webDomain());
        assertTrue(scan.hasFields());
    }

    @Test
    public void ac10_3_webDomainFromAnyNode() {
        FakeNode web = group(field("u").html("email", "login"), field("p").html("password", "pw"))
                .webDomain("accounts.example.com");
        FieldScan<String> scan = FieldScanner.scan(List.of(group(field("url_bar").idEntry("url_bar")), group(web)));
        assertEquals("accounts.example.com", scan.webDomain());
        assertEquals(List.of("u"), scan.usernames());
        assertEquals(List.of("p"), scan.passwords());
    }

    @Test
    public void ac10_3_unlabeledTextBeforePasswordIsUsername() {
        // 很多 App 的帳號欄沒有任何線索：取密碼欄之前最近的一般文字欄
        FakeNode root = group(field("search").idEntry("search_box"), field("first").inputType(FakeNode.TEXT),
                field("second").inputType(FakeNode.TEXT), field("pass").inputType(FakeNode.TEXT_PASSWORD));
        FieldScan<String> scan = FieldScanner.scan(List.of(root));
        assertEquals(List.of("second"), scan.usernames());
        assertEquals(List.of("pass"), scan.passwords());
    }

    @Test
    public void ac10_3_noFields() {
        FieldScan<String> scan = FieldScanner.scan(List.of(group(field("a").inputType(FakeNode.TEXT),
                field("b").hints("password").hidden())));
        assertTrue(scan.usernames().isEmpty());
        assertTrue(scan.passwords().isEmpty());
        assertTrue(!scan.hasFields());
    }

    @Test
    public void ac10_3_multipleWindows() {
        // 對話框與底下的畫面是不同的視窗：每個視窗一個根節點，都要看
        FieldScan<String> scan = FieldScanner.scan(List.of(group(field("u").hints("username")),
                group(field("p").hints("password"))));
        assertEquals(List.of("u"), scan.usernames());
        assertEquals(List.of("p"), scan.passwords());
    }

    @Test
    public void ac10_3_passwordAutoTellsApartByOuterId() {
        // P-AF-03 長榮航空（com.evaair.android）：兩個欄位都標 passwordAuto、帳號欄是 visible password，
        // 欄位本身沒有 id，外層的 RelativeLayout 才有 AccountEditText／PasswordEditText
        FakeNode root = group(
                group(field("title").hint("會員登入")),
                group(field("account").hints("passwordAuto").inputType(0x80091)).idEntry("AccountEditText"),
                group(field("password").hints("passwordAuto").inputType(FakeNode.TEXT_PASSWORD))
                        .idEntry("PasswordEditText"),
                field("login").notFillable().idEntry("LoginButton"));
        FieldScan<String> scan = FieldScanner.scan(List.of(root));
        assertEquals(List.of("account"), scan.usernames());
        assertEquals(List.of("password"), scan.passwords());
    }

    @Test
    public void ac10_3_maskedAccountBeforeNamedPassword() {
        // T-83 實機（momo 購物的 WebView 登入頁）：帳號欄是 <input type=password id=residentNo>（遮罩的帳號），
        // 密碼欄 id=password。第一個密碼欄沒有密碼字樣、後面的有 → 第一個是帳號欄
        // 實際的密碼欄另外標了 autocomplete=off
        FakeNode web = group(field("acct").html("password", "residentNo"),
                field("pw").hints("off").html("password", "password")).webDomain("www.momoshop.com.tw");
        FieldScan<String> scan = FieldScanner.scan(List.of(web));
        assertEquals(List.of("acct"), scan.usernames());
        assertEquals(List.of("pw"), scan.passwords());
        // 註冊頁的「密碼／確認密碼」兩欄都有密碼字樣：照舊都是密碼欄
        FieldScan<String> signup = FieldScanner.scan(List.of(group(field("p1").html("password", "password"),
                field("p2").html("password", "password_confirm"))));
        assertTrue(signup.usernames().isEmpty());
        assertEquals(List.of("p1", "p2"), signup.passwords());
        // 前面已有一般文字欄當帳號時不套用（那一欄比較可能是帳號）
        FieldScan<String> plain = FieldScanner.scan(List.of(group(field("u").inputType(FakeNode.TEXT),
                field("pin").html("password", "pin"), field("pw").html("password", "password"))));
        assertEquals(List.of("u"), plain.usernames());
        assertEquals(List.of("pin", "pw"), plain.passwords());
    }

    @Test
    public void ac10_3_outerIdOnlyForPasswordAuto() {
        // 外層 id 只在 passwordAuto 這種模稜兩可的情況才看：外層叫 login_form 不會讓裡面的一般文字欄都變成帳號
        FakeNode root = group(field("nick").inputType(FakeNode.TEXT).idEntry("nickname"),
                field("note").inputType(FakeNode.TEXT).idEntry("note"),
                field("pass").inputType(FakeNode.TEXT_PASSWORD)).idEntry("login_form");
        FieldScan<String> scan = FieldScanner.scan(List.of(root));
        assertEquals(List.of("note"), scan.usernames());
        assertEquals(List.of("pass"), scan.passwords());
        // 外層沒有線索時 passwordAuto 照密碼處理
        FieldScan<String> plain = FieldScanner.scan(List.of(group(group(field("p").hints("passwordAuto"))
                .idEntry("container"))));
        assertEquals(List.of("p"), plain.passwords());
    }

    @Test
    public void ac10_5_crossOriginIframeFieldsAreSkipped() {
        // 資安總檢 M-1：頂層是 bank.example 時，第三方 iframe（含巢狀在 iframe 裡、沒有自己網域的節點）裡的欄位不收
        FakeNode iframe = group(field("evil_pw").html("password", "pw"),
                group(field("evil_user").html("email", "login"))).webDomain("ads.evil.example");
        FakeNode page = group(field("u").html("email", "login"), field("p").html("password", "pw"), iframe,
                group(field("p2").html("password", "pw2")).webDomain("BANK.example"))
                .webDomain("bank.example");
        FieldScan<String> scan = FieldScanner.scan(List.of(group(page)));
        assertEquals("bank.example", scan.webDomain());
        assertEquals(List.of("u"), scan.usernames());
        assertEquals(List.of("p", "p2"), scan.passwords());
    }
}
