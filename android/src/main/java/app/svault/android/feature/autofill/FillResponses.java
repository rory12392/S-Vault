package app.svault.android.feature.autofill;

import android.content.Context;
import android.content.IntentSender;
import android.os.Build;
import android.service.autofill.Dataset;
import android.service.autofill.Field;
import android.service.autofill.FillResponse;
import android.service.autofill.Presentations;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;
import app.svault.android.R;
import app.svault.core.vault.EntryView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把 {@link FillPlan} 轉成系統的 {@link FillResponse}。
 *
 * <p>{@link FillPlan.Outcome#ENTRIES}：每筆條目一個項目，不帶值、選了之後經驗證頁（T-110，D-197）。{@link FillPlan.Outcome#LOCKED}：一個需要驗證的項目（T-81，
 * AC-10-2），選了之後開驗證頁、解鎖後交回 {@link #build} 的結果；沒有驗證頁可開（{@link AuthPages#NONE}）時不提供建議。
 * {@link FillPlan.Outcome#SEARCH_ONLY}：一個「搜尋保管庫」項目（{@link #searchOnly}，T-83），選了之後開條目選擇頁。
 */
final class FillResponses {

    private FillResponses() {
    }

    /** 一次回應最多幾筆建議（每筆各自一個 {@code PendingIntent}）。 */
    static final int MAX_DATASETS = 256;

    /** 每筆建議的驗證頁。 */
    @FunctionalInterface
    interface EntrySenders {

        /**
         * @param entry 條目
         * @param index 第幾筆（0 起）
         * @return 開這一筆驗證頁的 {@link IntentSender}；沒有時為空（這一筆不提供，不退回帶值的建議）
         */
        Optional<IntentSender> forEntry(EntryView entry, int index);
    }

    /**
     * 符合的條目，每筆一個建議（T-110，D-197）：建議只有名稱與帳號的顯示，**不帶帳密的值**，選了之後系統開那一筆的驗證頁，
     * 確認仍解鎖、仍符合才交回帶值的項目（{@link #dataset}）。帶值的建議交給系統後收不回來，S-Vault 上鎖後仍可點選填入
     * （T-110 實測），所以一律經驗證頁。
     *
     * @param context 服務
     * @param plan    處理結果（解鎖後才比對時，這裡只會是條目）
     * @param senders 每筆建議的驗證頁
     * @return 回應；沒有要提供的建議時為 {@code null}
     */
    static FillResponse build(Context context, FillPlan<AutofillId> plan, EntrySenders senders) {
        if (plan.outcome() != FillPlan.Outcome.ENTRIES) {
            return null;
        }
        FillResponse.Builder response = new FillResponse.Builder();
        int datasets = 0;
        int index = 0;
        for (EntryView entry : plan.entries()) {
            if (index >= MAX_DATASETS) {
                break;
            }
            Optional<IntentSender> sender = senders.forEntry(entry, index++);
            Dataset dataset = sender.isPresent() ? gated(context, plan.fields(), entry, sender.get()) : null;
            if (dataset != null) {
                response.addDataset(dataset);
                datasets++;
            }
        }
        // FillResponse 至少要有一個項目才建得起來
        return datasets == 0 ? null : response.build();
    }

    /** 不帶值、要先驗證的一筆建議；畫面上的欄位它都沒有值可填時為 {@code null}。 */
    private static Dataset gated(Context context, FieldScan<AutofillId> fields, EntryView entry, IntentSender sender) {
        Values values = new Values(row(context, entry), false);
        values.put(fields.usernames(), entry.username());
        values.put(fields.passwords(), entry.password());
        if (values.count == 0) {
            return null;
        }
        values.builder.setAuthentication(sender);
        return values.build();
    }

    /**
     * 上鎖時（AC-10-2）：整個回應要先驗證。建議清單只有一列「S-Vault 已上鎖／點一下解鎖後填入」，選了之後系統開
     * {@code sender}；驗證頁解鎖、重新比對後把 {@link #build} 的結果交回系統，系統再顯示符合的條目。
     *
     * @param context 服務
     * @param plan    處理結果（{@link FillPlan.Outcome#LOCKED}）
     * @param sender  開驗證頁的 {@link IntentSender}
     * @return 回應；畫面上沒有欄位時為 {@code null}
     */
    static FillResponse locked(Context context, FillPlan<AutofillId> plan, IntentSender sender) {
        List<AutofillId> ids = new ArrayList<>(plan.fields().usernames());
        ids.addAll(plan.fields().passwords());
        if (plan.outcome() != FillPlan.Outcome.LOCKED || ids.isEmpty()) {
            return null;
        }
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.af_dataset_item);
        row.setTextViewText(R.id.af_dataset_title, context.getString(R.string.af_locked_title));
        row.setTextViewText(R.id.af_dataset_username, context.getString(R.string.af_locked_hint));
        AutofillId[] fields = ids.toArray(new AutofillId[0]);
        FillResponse.Builder response = new FillResponse.Builder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            response.setAuthentication(fields, sender, new Presentations.Builder().setMenuPresentation(row).build());
        } else {
            legacyAuthentication(response, fields, sender, row);
        }
        return response.build();
    }

    @SuppressWarnings("deprecation")
    private static void legacyAuthentication(FillResponse.Builder response, AutofillId[] fields, IntentSender sender,
            RemoteViews row) {
        response.setAuthentication(fields, sender, row);
    }

    /**
     * 沒有符合的條目、或瀏覽器拿不到網域時（AC-10-3、AC-10-5，T-83）：一個「搜尋保管庫」項目，不含任何帳密。選了之後系統開
     * {@code sender}（條目選擇頁 {@link AfPickFragment}），使用者挑好、必要時確認不符的警告之後，頁面交回那一筆的
     * {@link Dataset}，系統直接填入。
     *
     * @param context 服務
     * @param plan    處理結果（{@link FillPlan.Outcome#SEARCH_ONLY}）
     * @param sender  開條目選擇頁的 {@link IntentSender}
     * @return 回應；畫面上沒有欄位時為 {@code null}
     */
    static FillResponse searchOnly(Context context, FillPlan<AutofillId> plan, IntentSender sender) {
        List<AutofillId> ids = new ArrayList<>(plan.fields().usernames());
        ids.addAll(plan.fields().passwords());
        if (plan.outcome() != FillPlan.Outcome.SEARCH_ONLY || ids.isEmpty()) {
            return null;
        }
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.af_dataset_item);
        row.setTextViewText(R.id.af_dataset_title, context.getString(R.string.af_search_title));
        row.setTextViewText(R.id.af_dataset_username, context.getString(R.string.af_search_hint));
        Dataset.Builder dataset;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            dataset = new Dataset.Builder(new Presentations.Builder().setMenuPresentation(row).build());
            for (AutofillId id : ids) {
                // 沒有值：這個項目只是入口，帳密等使用者挑好才由條目選擇頁交回
                dataset.setField(id, new Field.Builder().build());
            }
        } else {
            dataset = Values.legacyBuilder(row);
            for (AutofillId id : ids) {
                Values.legacySetValue(dataset, id, null);
            }
        }
        dataset.setAuthentication(sender);
        return new FillResponse.Builder().addDataset(dataset.build()).build();
    }

    /**
     * 一筆條目的項目：建議清單的每一列，也是條目選擇頁交回的那一筆（使用者已挑好；不符時已確認警告）。
     *
     * @return 項目；畫面上的欄位它都沒有值可填時為 {@code null}（例如只有帳號欄、條目只有密碼）
     */
    static Dataset dataset(Context context, FieldScan<AutofillId> fields, EntryView entry) {
        Values values = new Values(row(context, entry), true);
        values.put(fields.usernames(), entry.username());
        values.put(fields.passwords(), entry.password());
        return values.count == 0 ? null : values.build();
    }

    private static RemoteViews row(Context context, EntryView entry) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.af_dataset_item);
        String title = entry.title() == null || entry.title().isBlank()
                ? context.getString(R.string.af_dataset_untitled) : entry.title();
        row.setTextViewText(R.id.af_dataset_title, title);
        row.setTextViewText(R.id.af_dataset_username, entry.username() == null ? "" : entry.username());
        return row;
    }

    /** 依 API 版本選建構方式：33 起 {@code setValue(…, RemoteViews)} 系列已棄用，改用 {@link Field}。 */
    private static final class Values {

        private final Dataset.Builder builder;
        /** {@code false}：只標出要填的欄位、不放值（要先驗證的建議）。 */
        private final boolean withValues;
        private int count;

        Values(RemoteViews row, boolean withValues) {
            this.withValues = withValues;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder = new Dataset.Builder(new Presentations.Builder().setMenuPresentation(row).build());
            } else {
                builder = legacyBuilder(row);
            }
        }

        void put(List<AutofillId> ids, String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            AutofillValue value = withValues ? AutofillValue.forText(text) : null;
            for (AutofillId id : ids) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.setField(id, value == null ? new Field.Builder().build()
                            : new Field.Builder().setValue(value).build());
                } else {
                    legacySetValue(builder, id, value);
                }
                count++;
            }
        }

        Dataset build() {
            return builder.build();
        }

        @SuppressWarnings("deprecation")
        private static Dataset.Builder legacyBuilder(RemoteViews row) {
            return new Dataset.Builder(row);
        }

        @SuppressWarnings("deprecation")
        private static void legacySetValue(Dataset.Builder builder, AutofillId id, AutofillValue value) {
            builder.setValue(id, value);
        }
    }
}
