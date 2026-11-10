package app.svault.android.feature.autofill;

import static app.svault.android.feature.autofill.FakeNode.field;
import static org.junit.Assert.assertEquals;

import app.svault.android.feature.autofill.FieldClassifier.Kind;
import org.junit.Test;

/**
 * AC-10-3（欄位辨識）：帳號、密碼欄位的判斷。線索的優先順序：autofillHints → inputType → 網頁 type → id 與提示文字。
 */
public class FieldClassifierTest {

    @Test
    public void ac10_3_autofillHintsComeFirst() {
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").hints("password")));
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").hints("current-password")));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").hints("username")));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").hints("emailAddress")));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").hints("email")));
        // hint 說是帳號，id 寫 password 也照 hint
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").hints("username").idEntry("password")));
        // 標了其他用途：不再看別的線索
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").hints("postalCode").idEntry("user_zip")));
        // autocomplete=off／on 不是用途（T-83 實機：momo 的密碼欄標 off，只是不讓瀏覽器記密碼）：改看其他線索
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").hints("off").idEntry("password")));
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").hints("off").html("password", "password")));
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").hints("off").idEntry("nickname")));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").hints("on", "username")));
        // 新密碼欄也填（改密碼頁通常有舊密碼欄）；新帳號註冊頁另由 T-83 實機確認
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").hints("new-password")));
    }

    @Test
    public void ac10_3_passwordAutoIsPasswordClueButIdDecides() {
        // P-AF-03：passwordAuto 不是「別的用途」；欄位自己或外層的 id 說是帳號時是帳號欄（T-83）
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").hints("passwordAuto")));
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").hints("passwordAuto").inputType(0x80091)));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").hints("passwordAuto").idEntry("et_account")));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").hints("passwordAuto").inputType(0x80091),
                "AccountEditText"));
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").hints("passwordAuto"), "PasswordEditText"));
        // 欄位自己的 id 先於外層
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").hints("passwordAuto").idEntry("pwd"),
                "AccountBox"));
        // 和標準的 hint 並存時照標準的
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").hints("passwordAuto", "username"),
                "PasswordEditText"));
        // 搜尋、驗證碼仍然排除
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").hints("passwordAuto").idEntry("otp_code")));
    }

    @Test
    public void ac10_3_inputType() {
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").inputType(FakeNode.TEXT_PASSWORD)));
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").inputType(FakeNode.TEXT_VISIBLE_PASSWORD)));
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").inputType(FakeNode.TEXT_WEB_PASSWORD)));
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").inputType(FakeNode.NUMBER_PASSWORD)));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").inputType(FakeNode.TEXT_EMAIL)));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").inputType(FakeNode.TEXT_WEB_EMAIL)));
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").inputType(FakeNode.TEXT)));
    }

    @Test
    public void ac10_3_htmlType() {
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").html("password", "pw")));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").html("email", null)));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").html("text", "login")));
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").html("search", "q")));
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").html("hidden", "password")));
    }

    @Test
    public void ac10_3_keywordsInIdAndHint() {
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").idEntry("et_password")));
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").idEntry("loginPwd")));
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").hint("請輸入密碼")));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").idEntry("username_input")));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").hint("電子郵件或手機號碼")));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").hint("帳號")));
        assertEquals(Kind.USERNAME, FieldClassifier.classify(field("a").hint("Phone, email or username")));
        // 密碼的關鍵字優先：「帳號密碼」這類提示是密碼欄
        assertEquals(Kind.PASSWORD, FieldClassifier.classify(field("a").hint("帳號密碼")));
        // 搜尋欄就算提到帳號也不是登入欄位
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").idEntry("search_user")));
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").hint("搜尋使用者")));
        // 「passport」「compass」不是密碼
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").idEntry("passport_no")));
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").idEntry("compass")));
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").hint("留言")));
    }

    @Test
    public void ac10_3_notFillableOrHidden() {
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").notFillable().hints("password")));
        assertEquals(Kind.NONE, FieldClassifier.classify(field("a").hidden().hints("password")));
        assertEquals(Kind.NONE, FieldClassifier.classify(FakeNode.group().hints("password")));
    }
}
