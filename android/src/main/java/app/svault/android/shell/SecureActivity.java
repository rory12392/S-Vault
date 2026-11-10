package app.svault.android.shell;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.start.UnlockActivity;

/**
 * 所有顯示保管庫內容的 Activity 的基底：
 * <ul>
 *   <li>{@code FLAG_SECURE}：不能截圖、錄影，最近使用的 App 清單中也不顯示內容（AC-12-4）。</li>
 *   <li>上鎖時導回解鎖畫面：開啟時已經上鎖、或顯示中被上鎖（{@link LockGate}），都清掉整個工作並開解鎖畫面。
 *       例外是模組自己開的頁面（{@link FeatureHostActivity#onLocked()} 的回程，T-36）。</li>
 *   <li>edge-to-edge：子類別在 {@code setContentView} 後對根部呼叫 {@code SystemBars.applyInsets}。</li>
 * </ul>
 */
public abstract class SecureActivity extends AppCompatActivity implements LockGate.Listener {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        // 在任何內容畫出來之前設定，第一個畫面也受保護
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        LockGate gate = lockGate();
        gate.addListener(this);
        if (gate.isLocked()) {
            onLocked();
        }
    }

    @Override
    protected void onStop() {
        lockGate().removeListener(this);
        super.onStop();
    }

    @Override
    public void onLocked() {
        if (isFinishing()) {
            return;
        }
        Intent unlock = unlockIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(unlock);
        finish();
    }

    /** @return App 共用的上鎖狀態 */
    protected final LockGate lockGate() {
        return ((SvaultApplication) getApplication()).lockGate();
    }

    /**
     * 解鎖畫面：回到剛才開著的保管庫（最近開啟的第一筆），顯示「已上鎖」。
     *
     * @param context 目前的畫面
     * @return 開啟解鎖畫面的 Intent
     */
    static Intent unlockIntent(Context context) {
        return UnlockActivity.lockedIntent(context);
    }
}
