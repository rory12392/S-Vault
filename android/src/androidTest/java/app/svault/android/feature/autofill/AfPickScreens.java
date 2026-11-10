package app.svault.android.feature.autofill;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * {@link AutofillPickOnDeviceTest} 的輔助：找畫面上的文字、截圖、刪測試留下的資料夾（從測試類別拆出，單檔 400 行內）。
 */
final class AfPickScreens {

    private AfPickScreens() {
    }

    static boolean hasText(View v, String text) {
        if (v instanceof TextView t && text.contentEquals(t.getText())) {
            return true;
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasText(g.getChildAt(i), text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 帶 {@code -e shots <前綴>} 時，在 App 行程內把畫面畫成 PNG（FLAG_SECURE 的畫面用 adb 截會是全黑；同 shell 的
     * ScreenShots），圖在 {@code files/shots/<前綴>/}。有對話框時把它的視窗疊在它在螢幕上的位置。
     *
     * @param s      畫面
     * @param dialog 疊在上面的對話框；沒有時為 {@code null}
     * @param name   檔名（不含副檔名）
     * @param <A>    Activity 的型別
     */
    static <A extends Activity> void shot(ActivityScenario<A> s, AlertDialog dialog, String name) {
        Bundle args = InstrumentationRegistry.getArguments();
        String prefix = args.getString("shots");
        if (prefix == null) {
            return;
        }
        SystemClock.sleep(700);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        s.onActivity(a -> {
            File dir = new File(a.getExternalFilesDir(null), "shots/" + prefix);
            View root = a.getWindow().getDecorView();
            Bitmap out = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            root.draw(canvas);
            if (dialog != null) {
                View window = dialog.getWindow().getDecorView();
                int[] at = new int[2];
                window.getLocationOnScreen(at);
                int[] base = new int[2];
                root.getLocationOnScreen(base);
                // 對話框底下的遮罩（dim）不是 View，畫一層半透明代替
                canvas.drawARGB(0x66, 0, 0, 0);
                canvas.save();
                canvas.translate(at[0] - base[0], at[1] - base[1]);
                window.draw(canvas);
                canvas.restore();
            }
            dir.mkdirs();
            try (OutputStream o = new FileOutputStream(new File(dir, name + ".png"))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, o);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
