package app.svault.core.sync;

import app.svault.core.vault.TestVaults;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 三方合併的資料驅動案例（Architecture §4、AC-07-6）：新增／修改／刪除／移動群組／附件／歷史的組合、衝突與解決、
 * 沒有基準的退回規則、還原舊備份後的合併。core 的 {@code ThreeWayMergerCasesTest}（JUnit 6）與 android 的
 * {@code MergeCasesOnDeviceTest}（儀器測試，JUnit 4）跑同一組。
 *
 * <p>時間：基準之後本機先改、遠端後改（遠端的修改時間比本機晚 60 秒），所以「較新的」預設是遠端。
 */
public final class MergeCases {

    private MergeCases() {
    }

    /** @return 全部案例（每次都是新的物件；群組在 {@link GroupMergeCases}，衝突、歷史與沒有基準在 {@link ConflictMergeCases}） */
    public static List<MergeCase> all() {
        List<MergeCase> out = new ArrayList<>();
        out.addAll(additionsAndEdits());
        out.addAll(deletions());
        out.addAll(GroupMergeCases.all());
        out.addAll(ConflictMergeCases.conflicts());
        out.addAll(ConflictMergeCases.history());
        out.addAll(ConflictMergeCases.withoutBase());
        out.addAll(ReplacedMergeCases.all());
        return Collections.unmodifiableList(out);
    }

    private static List<MergeCase> additionsAndEdits() {
        List<MergeCase> out = new ArrayList<>();
        out.add(MergeCase.named("AC-07-2：兩邊各自新增條目，合併後兩筆都在")
                .local(d -> d.entry("A"))
                .remote(d -> d.entry("B"))
                .expect(o -> {
                    o.noConflicts();
                    o.present("A");
                    o.present("B");
                    o.noNotices();
                    o.changed(1);
                }));
        out.add(MergeCase.named("AC-07-3：兩邊修改不同的條目，兩邊的修改都保留")
                .base(d -> {
                    d.entry("X");
                    d.entry("Y");
                })
                .local(d -> d.edit("X", "password", "本機的新密碼"))
                .remote(d -> d.edit("Y", "notes", "遠端的備註"))
                .expect(o -> {
                    o.noConflicts();
                    o.field("X", "password", "本機的新密碼");
                    o.field("Y", "notes", "遠端的備註");
                    o.changed(1);
                }));
        out.add(MergeCase.named("AC-07-3：同一筆的不同欄位（本機改密碼、遠端改備註），不需要使用者介入")
                .base(d -> d.entry("X"))
                .local(d -> d.edit("X", "password", "本機的新密碼"))
                .remote(d -> d.edit("X", "notes", "遠端的備註"))
                .expect(o -> {
                    o.noConflicts();
                    o.field("X", "password", "本機的新密碼");
                    o.field("X", "notes", "遠端的備註");
                    o.noNotices();
                }));
        out.add(MergeCase.named("AC-07-3：只有一邊改了，另一邊沒動，結果就是改過的那一邊")
                .base(d -> d.entry("X"))
                .remote(d -> d.edit("X", "url", "https://example.test"))
                .expect(o -> {
                    o.noConflicts();
                    o.field("X", "url", "https://example.test");
                    o.changed(1);
                }));
        out.add(MergeCase.named("AC-01-2：KeePassXC 檔的附件與自訂欄位，經合併從本機複製到遠端後原樣保留")
                .fixture(TestVaults.KXC_ATTACHMENTS_FIELDS)
                .base(d -> d.nameEntryWithAttachments("附件"))
                .local(d -> d.edit("附件", "notes", "本機改了備註"))
                .remote(d -> d.purge("附件"))
                .expect(o -> {
                    o.noConflicts();
                    o.present("附件");
                    o.field("附件", "notes", "本機改了備註");
                    o.notice(MergeNotice.Kind.KEPT_LOCAL_EDIT, "附件");
                    o.deletedRecorded("附件", false);
                    o.attachmentsAndCustomFieldsSameAsLocal("附件");
                }));
        return out;
    }

    private static List<MergeCase> deletions() {
        List<MergeCase> out = new ArrayList<>();
        out.add(MergeCase.named("AC-07-4：本機修改、遠端永久刪除，保留修改並通知")
                .base(d -> d.entry("X"))
                .local(d -> d.edit("X", "password", "改過的"))
                .remote(d -> d.purge("X"))
                .expect(o -> {
                    o.noConflicts();
                    o.field("X", "password", "改過的");
                    o.inRecycleBin("X", false);
                    o.notice(MergeNotice.Kind.KEPT_LOCAL_EDIT, "X");
                    o.deletedRecorded("X", false);
                }));
        out.add(MergeCase.named("AC-07-4：本機修改、遠端移到資源回收桶，保留修改、不放進回收桶")
                .base(d -> {
                    d.group("工作");
                    d.entryIn("工作", "X");
                })
                .local(d -> d.edit("X", "password", "改過的"))
                .remote(d -> d.recycle("X"))
                .expect(o -> {
                    o.noConflicts();
                    o.field("X", "password", "改過的");
                    o.inGroup("X", "工作");
                    o.notice(MergeNotice.Kind.KEPT_LOCAL_EDIT, "X");
                }));
        out.add(MergeCase.named("AC-07-4：本機刪除（回收桶）、遠端修改，保留遠端修改後的版本")
                .base(d -> d.entry("X"))
                .local(d -> d.recycle("X"))
                .remote(d -> d.edit("X", "notes", "遠端改的"))
                .expect(o -> {
                    o.noConflicts();
                    o.field("X", "notes", "遠端改的");
                    o.inRecycleBin("X", false);
                    o.notice(MergeNotice.Kind.KEPT_REMOTE_EDIT, "X");
                }));
        out.add(MergeCase.named("AC-07-4：本機永久刪除、遠端修改，保留遠端修改後的版本、刪除紀錄移除")
                .base(d -> d.entry("X"))
                .local(d -> d.purge("X"))
                .remote(d -> d.edit("X", "notes", "遠端改的"))
                .expect(o -> {
                    o.noConflicts();
                    o.field("X", "notes", "遠端改的");
                    o.notice(MergeNotice.Kind.KEPT_REMOTE_EDIT, "X");
                    o.deletedRecorded("X", false);
                }));
        out.add(MergeCase.named("刪除：一邊永久刪除、另一邊沒動，條目刪除、刪除紀錄保留")
                .base(d -> {
                    d.entry("X");
                    d.entry("Y");
                })
                .local(d -> d.purge("X"))
                .remote(d -> d.edit("Y", "notes", "無關的修改"))
                .expect(o -> {
                    o.noConflicts();
                    o.absent("X");
                    o.deletedRecorded("X", true);
                    o.field("Y", "notes", "無關的修改");
                    o.noNotices();
                }));
        out.add(MergeCase.named("刪除：遠端移到回收桶、本機沒動，合併後在回收桶裡")
                .base(d -> d.entry("X"))
                .remote(d -> d.recycle("X"))
                .expect(o -> {
                    o.noConflicts();
                    o.inRecycleBin("X", true);
                    o.changed(1);
                }));
        out.add(MergeCase.named("刪除：兩邊都刪除（一邊回收桶、一邊永久刪除），結果是刪除")
                .base(d -> d.entry("X"))
                .local(d -> d.recycle("X"))
                .remote(d -> d.purge("X"))
                .expect(o -> {
                    o.noConflicts();
                    o.absent("X");
                    o.noNotices();
                }));
        out.add(MergeCase.named("刪除：兩邊各自第一次刪除、各自建立回收桶，合併後只有一個回收桶")
                .base(d -> {
                    d.entry("X");
                    d.entry("Y");
                })
                .local(d -> d.recycle("X"))
                .remote(d -> d.recycle("Y"))
                .expect(o -> {
                    o.noConflicts();
                    o.inRecycleBin("X", true);
                    o.inRecycleBin("Y", true);
                    o.groupsNamed("資源回收桶", 1);
                }));
        out.add(MergeCase.named("還原舊備份後的合併：遠端還原成舊版本，本機沒動的跟著消失、有修改的保留")
                .before(d -> d.entry("A"))
                .base(d -> {
                    d.entry("X");
                    d.entry("Y");
                })
                .remoteRestored()
                .local(d -> d.edit("Y", "password", "還原後才改的"))
                .expect(o -> {
                    o.noConflicts();
                    o.present("A");
                    o.absent("X");
                    o.field("Y", "password", "還原後才改的");
                    o.notice(MergeNotice.Kind.KEPT_LOCAL_EDIT, "Y");
                }));
        return out;
    }
}
