package app.svault.android.shell.entries;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.svault.android.R;
import app.svault.android.ui.EmptyState;
import app.svault.core.shell.ScreenData;
import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;

/**
 * 首頁與條目清單頂部的搜尋（SearchBar 在兩頁頂部都有，搜尋全部條目）。按下 SearchBar 展開
 * M3 SearchView，每輸入一個字就在主執行緒重算結果（{@code EntryQuery} 2,000 筆每字元 &lt; 1 ms，T-09），
 * 不加延遲（AC-03-2）。沒有結果時「找不到符合的條目」（AC-03-3）。
 */
final class SearchPanel {

    private final SearchView view;
    private final EntryAdapter adapter;
    private final RecyclerView results;
    private final EmptyState empty;
    @Nullable private ScreenData data;

    /**
     * @param activity 畫面
     * @param root     含 {@code activity_entries_page} 的根部
     * @param actions  結果列的動作
     */
    SearchPanel(AppCompatActivity activity, View root, EntryAdapter.Actions actions) {
        SearchBar bar = root.findViewById(R.id.page_search_bar);
        view = root.findViewById(R.id.page_search_view);
        results = root.findViewById(R.id.page_results);
        empty = root.findViewById(R.id.page_results_empty);
        adapter = new EntryAdapter(actions);
        view.setupWithSearchBar(bar);
        results.setLayoutManager(new LinearLayoutManager(activity));
        results.setAdapter(adapter);
        view.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh();
            }
        });
        // 展開時按返回先收起搜尋
        OnBackPressedCallback back = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                view.hide();
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, back);
        view.addTransitionListener((v, previous, now) -> back.setEnabled(
                now == SearchView.TransitionState.SHOWING || now == SearchView.TransitionState.SHOWN));
    }

    /** @param current 目前的內容（存檔、上一頁改了條目之後重算） */
    void setData(ScreenData current) {
        data = current;
        refresh();
    }

    /** @return 搜尋畫面展開中 */
    boolean isShowing() {
        return view.isShowing();
    }

    private void refresh() {
        String q = view.getText() == null ? "" : view.getText().toString();
        if (data == null || q.trim().isEmpty()) {
            adapter.clear();
            empty.setVisibility(View.GONE);
            return;
        }
        Listing found = Listing.of(data, Listing.Scope.all(), q);
        adapter.submit(found, data, q);
        boolean none = found.empty() == Listing.Empty.NO_RESULTS;
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        results.setVisibility(none ? View.GONE : View.VISIBLE);
        if (none) {
            empty.show(R.drawable.sv_ic_search, empty.getResources().getString(R.string.entries_empty_search_title),
                    null, null, null);
        }
    }
}
