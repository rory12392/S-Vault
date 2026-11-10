package app.svault.core.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * 遠端被不含這台修改的版本整份取代的合併案例（AC-07-9；Architecture §4 第 4、6 點；{@link MergeCases#all()} 的一部分）。
 *
 * <p>T-17 實測的情況：Google Drive 電腦版恢復同步時，以 PC 在離線期間的版本整份取代雲端（不產生衝突副本）。這台的基準是
 * 自己寫上去的那一份，遠端卻是從更早的版本延伸出來的，所以案例用 {@link MergeCase#remoteFromBefore()}：遠端從
 * {@code before} 出發，看不到基準裡這台寫上去的東西。
 */
final class ReplacedMergeCases {

    private ReplacedMergeCases() {
    }

    static List<MergeCase> all() {
        List<MergeCase> out = new ArrayList<>();
        out.add(MergeCase.named("AC-07-9：遠端被 PC 的版本整份取代，這台已上傳的新增與修改都保留，PC 的新增也在")
                .before(d -> {
                    d.entry("舊條目");
                    d.entry("改過的");
                })
                .base(d -> {
                    d.entry("手機新增");
                    d.edit("改過的", "password", "手機改的密碼");
                })
                .remoteFromBefore()
                .remote(d -> d.entry("PC 新增"))
                .expect(o -> {
                    o.noConflicts();
                    o.present("舊條目");
                    o.present("手機新增");
                    o.present("PC 新增");
                    o.field("改過的", "password", "手機改的密碼");
                    o.notice(MergeNotice.Kind.REMOTE_REPLACED, null);
                }));
        out.add(MergeCase.named("T-99（D-190）：整份取代時，PC 離線期間永久刪除的條目依刪除紀錄一併刪除，不再復活")
                .before(d -> {
                    d.entry("舊條目");
                    d.entry("PC 刪的");
                })
                .base(d -> d.entry("手機新增"))
                .remoteFromBefore()
                .remote(d -> {
                    d.later(60);
                    d.purge("PC 刪的");
                })
                .expect(o -> {
                    o.noConflicts();
                    o.present("舊條目");
                    o.present("手機新增");
                    o.absent("PC 刪的");
                    o.deletedRecorded("PC 刪的", true);
                    o.notice(MergeNotice.Kind.DELETED_BY_RECORD, "PC 刪的");
                }));
        out.add(MergeCase.named("T-99：沒有基準時，這台較晚永久刪除的條目也從遠端移除")
                .before(d -> d.entry("X"))
                .withoutBase()
                .local(d -> {
                    d.later(60);
                    d.purge("X");
                })
                .expect(o -> {
                    o.noConflicts();
                    o.absent("X");
                    o.notice(MergeNotice.Kind.DELETED_BY_RECORD, "X");
                }));
        out.add(MergeCase.named("T-99：刪除紀錄比另一邊的修改早（刪了之後另一邊又改了）→ 保留修改，不刪")
                .before(d -> d.entry("X"))
                .withoutBase()
                .local(d -> d.purge("X"))
                .remote(d -> {
                    d.later(60);
                    d.edit("X", "password", "刪除之後才改的");
                })
                .expect(o -> {
                    o.noConflicts();
                    o.present("X");
                    o.field("X", "password", "刪除之後才改的");
                }));
        // Codex M-04 審查待辦 1：下面兩個是 D-190 的時間戳政策「會刪掉」的情況，確認一定留下通知（使用者可以從備份還原）
        // 案例的兩邊共用一個時鐘、本機先跑：用「修改那台記的時間較早、刪除那台記的較晚」表達時鐘偏差（合併只看記下的時間）
        out.add(MergeCase.named("T-99 的限制：刪除那台的時鐘較快 → 另一邊其實較晚的修改也被刪掉，但一定有通知")
                .before(d -> d.entry("X"))
                .withoutBase()
                .local(d -> d.edit("X", "password", "實際上較晚改的（這台時鐘慢）"))
                .remote(d -> {
                    d.later(30);
                    d.purge("X");
                })
                .expect(o -> {
                    o.noConflicts();
                    o.absent("X");
                    o.notice(MergeNotice.Kind.DELETED_BY_RECORD, "X");
                }));
        out.add(MergeCase.named("T-99 的限制：刪除之後另一邊只把條目移到別的群組（移動不改最後修改時間）→ 照刪除紀錄刪掉，一定有通知")
                .before(d -> {
                    d.entry("X");
                    d.group("G");
                })
                .withoutBase()
                .local(d -> {
                    d.later(30);
                    d.purge("X");
                })
                .remote(d -> {
                    d.later(60);
                    d.move("X", "G");
                })
                .expect(o -> {
                    o.noConflicts();
                    o.absent("X");
                    o.notice(MergeNotice.Kind.DELETED_BY_RECORD, "X");
                }));
        out.add(MergeCase.named("AC-07-9：整份取代後這台還有沒寫出去的修改，也都保留")
                .before(d -> d.entry("舊條目"))
                .base(d -> d.entry("手機新增"))
                .remoteFromBefore()
                .local(d -> {
                    d.entry("離線新增");
                    d.edit("手機新增", "notes", "離線改的備註");
                })
                .remote(d -> d.entry("PC 新增"))
                .expect(o -> {
                    o.noConflicts();
                    o.present("舊條目");
                    o.present("PC 新增");
                    o.present("離線新增");
                    o.field("手機新增", "notes", "離線改的備註");
                    o.notice(MergeNotice.Kind.REMOTE_REPLACED, null);
                }));
        out.add(MergeCase.named("AC-07-9：兩邊都改了同一筆，以較新的為準，另一邊的版本存入歷史紀錄（不遺失）")
                .before(d -> d.entry("X"))
                .base(d -> d.edit("X", "password", "手機的密碼"))
                .remoteFromBefore()
                .remote(d -> d.edit("X", "password", "PC 較晚改的密碼"))
                .expect(o -> {
                    o.noConflicts();
                    o.field("X", "password", "PC 較晚改的密碼");
                    o.hasHistoryWithPassword("X", "手機的密碼");
                }));
        out.add(MergeCase.named("AC-07-9：這台新增的群組與裡面的條目、回收桶都保留")
                .before(d -> d.entry("舊條目"))
                .base(d -> {
                    d.group("手機群組");
                    d.entryIn("手機群組", "群組裡的");
                    d.entry("要刪的");
                    d.recycle("要刪的");
                })
                .remoteFromBefore()
                .remote(d -> d.entry("PC 新增"))
                .expect(o -> {
                    o.noConflicts();
                    o.groupPresent("手機群組", true);
                    o.inGroup("群組裡的", "手機群組");
                    o.inRecycleBin("要刪的", true);
                    o.present("PC 新增");
                }));
        out.add(MergeCase.named("AC-07-9：PC 在離線期間刪除到回收桶的，合併後仍在回收桶（較新的位置為準）")
                .before(d -> d.entry("PC 要刪的"))
                .base(d -> d.entry("手機新增"))
                .remoteFromBefore()
                .remote(d -> d.recycle("PC 要刪的"))
                .expect(o -> {
                    o.noConflicts();
                    o.present("手機新增");
                    o.inRecycleBin("PC 要刪的", true);
                }));
        out.addAll(restored());
        return out;
    }

    /** 還原舊備份（{@link MergeCase#remoteRestored()}）和整份取代分得開：還原造成的刪除與改回照常生效。 */
    private static List<MergeCase> restored() {
        List<MergeCase> out = new ArrayList<>();
        out.add(MergeCase.named("AC-07-9：還原舊備份造成的刪除照常生效（不當成整份取代）")
                .before(d -> d.entry("舊條目"))
                .base(d -> {
                    d.entry("備份後新增");
                    d.group("備份後的群組");
                })
                .remoteRestored()
                .expect(o -> {
                    o.noConflicts();
                    o.present("舊條目");
                    o.absent("備份後新增");
                    o.groupPresent("備份後的群組", false);
                    o.deletedRecorded("備份後新增", true);
                    o.noNotices();
                }));
        out.add(MergeCase.named("AC-09-2：還原舊備份把條目改回舊值，其他裝置也改回；被換掉的版本留在歷史紀錄")
                .before(d -> d.entry("M"))
                .base(d -> d.edit("M", "password", "備份後改的"))
                .remoteRestored()
                .expect(o -> {
                    o.noConflicts();
                    o.field("M", "password", "pw-M");
                    o.hasHistoryWithPassword("M", "備份後改的");
                    o.noNotices();
                }));
        out.add(MergeCase.named("AC-07-4：還原舊備份改回的條目，這台在還原前又改了 → 衝突（不是靜默改回）")
                .before(d -> d.entry("M"))
                .base(d -> d.edit("M", "password", "備份後改的"))
                .remoteRestored()
                .local(d -> d.edit("M", "password", "這台又改的"))
                .expect(o -> o.conflict("M", "s:Password")));
        return out;
    }
}
