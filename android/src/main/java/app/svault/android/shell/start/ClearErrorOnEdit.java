package app.svault.android.shell.start;

import android.text.Editable;
import android.text.TextWatcher;
import com.google.android.material.textfield.TextInputLayout;

/**
 * 重新輸入時收掉欄位下方的錯誤（例：「主密碼錯誤」），連同保留給錯誤文字的空間——不然下面按鈕的位置會跟著跳。
 * T-36 從 {@code UnlockActivity} 拆出。
 */
final class ClearErrorOnEdit implements TextWatcher {

    private final TextInputLayout layout;

    /**
     * @param layout 欄位的外框（錯誤文字在它上面）
     */
    ClearErrorOnEdit(TextInputLayout layout) {
        this.layout = layout;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (layout.getError() != null) {
            layout.setError(null);
            layout.setErrorEnabled(false);
        }
    }

    @Override
    public void afterTextChanged(Editable s) {
    }
}
