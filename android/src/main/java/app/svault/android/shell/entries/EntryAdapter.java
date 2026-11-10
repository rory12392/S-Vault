package app.svault.android.shell.entries;

import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import app.svault.android.ui.EntryRow;
import app.svault.core.shell.ScreenData;
import app.svault.core.vault.EntryView;
import java.util.ArrayList;
import java.util.List;

/**
 * 條目清單與搜尋結果的 {@code RecyclerView} 內容：每列一個 {@link EntryRow}（整列開詳細，複製鈕複製密碼）。
 * 搜尋結果時第二行多顯示群組路徑、標示符合的關鍵字。
 */
final class EntryAdapter extends RecyclerView.Adapter<EntryAdapter.Holder> {

    /** 列上的動作。 */
    interface Actions {

        /** @param entry 點了這一列 */
        void open(EntryView entry);

        /** @param entry 按了這一列的「複製密碼」 */
        void copyPassword(EntryView entry);
    }

    private final Actions actions;
    private final List<EntryView> items = new ArrayList<>();
    private ScreenData data;
    private String query;
    private boolean search;

    EntryAdapter(Actions actions) {
        this.actions = actions;
        setHasStableIds(true);
    }

    /**
     * @param listing 要列出的條目
     * @param source  目前的內容（搜尋結果的群組路徑）
     * @param keyword 搜尋關鍵字（標示用）；不是搜尋時 {@code null}
     */
    void submit(Listing listing, ScreenData source, String keyword) {
        items.clear();
        items.addAll(listing.entries());
        data = source;
        search = listing.search();
        query = search ? keyword : null;
        notifyDataSetChanged();
    }

    /** 清空（搜尋欄沒有字時）。 */
    void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        EntryView e = items.get(position);
        return e.uuid().getMostSignificantBits() ^ e.uuid().getLeastSignificantBits();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        EntryRow row = new EntryRow(parent.getContext());
        row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Holder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        EntryView e = items.get(position);
        holder.row.bind(e.title(), e.username(), query, search ? data.query().groupPath(e.groupId()) : null);
        holder.row.setOnClickListener(v -> actions.open(e));
        // 沒有密碼的條目不顯示複製鈕
        holder.row.setOnCopyPasswordListener(e.password().isEmpty() ? null : v -> actions.copyPassword(e));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {

        final EntryRow row;

        Holder(EntryRow row) {
            super(row);
            this.row = row;
        }
    }
}
