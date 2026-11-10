package app.svault.android.gallery;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import app.svault.android.R;
import app.svault.android.shell.FeatureHostActivity;

/**
 * 展示用的模組頁面：由 {@link FeatureHostActivity} 載入，按鈕回傳一個結果。
 */
public class GalleryHostPage extends Fragment {

    static final String RESULT_VALUE = "value";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.gallery_host_page, container, false);
        view.findViewById(R.id.gallery_host_return).setOnClickListener(v -> {
            Bundle result = new Bundle();
            result.putString(RESULT_VALUE, "ok");
            FeatureHostActivity.deliverResult(this, result);
        });
        return view;
    }

    /** 建立畫面時就出錯的頁面，用來確認外殼顯示錯誤狀態、不會整個 App 當掉。 */
    public static class Broken extends Fragment {

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            throw new IllegalStateException("展示用的故障");
        }
    }
}
