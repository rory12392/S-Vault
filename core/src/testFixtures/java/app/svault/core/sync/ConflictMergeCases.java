package app.svault.core.sync;

import app.svault.core.vault.EntrySnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 衝突與解決、歷史紀錄、沒有基準的退回規則的合併案例（AC-08-1、AC-08-2、Architecture §4 第 5、6 點；
 * {@link MergeCases#all()} 的一部分）。
 */
final class ConflictMergeCases {

    private static final String PASSWORD = EntrySnapshot.stringKey("Password");
    private static final String TITLE = EntrySnapshot.stringKey("Title");

    private ConflictMergeCases() {
    }

    static List<MergeCase> conflicts() {
        List<MergeCase> out = new ArrayList<>();
        out.add(MergeCase.named("AC-08-1：兩邊把同一個欄位改成不同的值才是衝突，其他欄位照常合併")
                .base(d -> d.entry("X"))
                .local(d -> {
                    d.edit("X", "password", "本機的密碼");
                    d.edit("X", "notes", "只有本機改的備註");
                })
                .remote(d -> d.edit("X", "password", "遠端的密碼"))
                .expect(o -> {
                    o.conflict("X", PASSWORD);
                    o.check(o.merged() == null, "有衝突時不該套用（AC-08-3）");
                    o.check(throwsOnPlan(o.result()), "有衝突時 plan() 要拒絕");
                }));
        out.add(MergeCase.named("AC-08-1：兩邊改成相同的值不算衝突")
                .base(d -> d.entry("X"))
                .local(d -> d.edit("X", "password", "一樣的"))
                .remote(d -> d.edit("X", "password", "一樣的"))
                .expect(o -> {
                    o.noConflicts();
                    o.field("X", "password", "一樣的");
                }));
        out.add(MergeCase.named("AC-08-2：衝突選「這台裝置」，另一台的版本存入歷史紀錄")
                .base(d -> d.entry("X"))
                .local(d -> {
                    d.edit("X", "password", "本機的密碼");
                    d.edit("X", "notes", "本機的備註");
                })
                .remote(d -> d.edit("X", "password", "遠端的密碼"))
                .resolve(all(Side.LOCAL))
                .expect(o -> {
                    o.field("X", "password", "本機的密碼");
                    o.field("X", "notes", "本機的備註");
                    // 基準版本（兩邊歷史都有，去重）＋本機的第一次修改＋遠端被取代的版本
                    o.history("X", 3);
                    o.hasHistoryWithPassword("X", "遠端的密碼");
                }));
        out.add(MergeCase.named("AC-08-2：衝突選「另一台裝置」，這台的版本存入歷史紀錄、只有本機改的欄位照常合併")
                .base(d -> d.entry("X"))
                .local(d -> {
                    d.edit("X", "password", "本機的密碼");
                    d.edit("X", "notes", "本機的備註");
                })
                .remote(d -> d.edit("X", "password", "遠端的密碼"))
                .resolve(all(Side.REMOTE))
                .expect(o -> {
                    o.field("X", "password", "遠端的密碼");
                    o.field("X", "notes", "本機的備註");
                    o.hasHistoryWithPassword("X", "本機的密碼");
                }));
        out.add(MergeCase.named("AC-08-2：逐欄挑選（名稱選這台、密碼選另一台），兩邊的版本都存入歷史紀錄")
                .base(d -> d.entry("X"))
                .local(d -> {
                    d.edit("X", "title", "本機的名稱");
                    d.edit("X", "password", "本機的密碼");
                })
                .remote(d -> {
                    d.edit("X", "title", "遠端的名稱");
                    d.edit("X", "password", "遠端的密碼");
                })
                .resolve((result, ids) -> {
                    Map<String, Side> fields = new HashMap<>();
                    fields.put(TITLE, Side.LOCAL);
                    fields.put(PASSWORD, Side.REMOTE);
                    Map<UUID, Map<String, Side>> out2 = new HashMap<>();
                    out2.put(ids.get("X"), fields);
                    return out2;
                })
                .expect(o -> {
                    o.conflict("X", PASSWORD, TITLE);
                    o.field("X", "title", "本機的名稱");
                    o.field("X", "password", "遠端的密碼");
                    o.hasHistoryWithPassword("X", "本機的密碼");
                    o.hasHistoryWithPassword("X", "遠端的密碼");
                }));
        return out;
    }

    static List<MergeCase> history() {
        List<MergeCase> out = new ArrayList<>();
        out.add(MergeCase.named("歷史紀錄：兩邊的歷史以修改時間聯集、去重")
                .base(d -> d.entry("X"))
                .local(d -> {
                    d.edit("X", "password", "本機第一次");
                    d.later(5);
                    d.edit("X", "password", "本機第二次");
                })
                .remote(d -> d.edit("X", "notes", "遠端改的"))
                .expect(o -> {
                    o.noConflicts();
                    o.field("X", "password", "本機第二次");
                    o.field("X", "notes", "遠端改的");
                    // 基準版本（兩邊都有，算一次）＋本機第一次修改後的版本
                    o.history("X", 2);
                }));
        return out;
    }

    static List<MergeCase> withoutBase() {
        List<MergeCase> out = new ArrayList<>();
        out.add(MergeCase.named("沒有基準：同一筆兩邊不同，以較新的為準、較舊的存入歷史紀錄、不產生衝突")
                .base(d -> d.entry("X"))
                .withoutBase()
                .local(d -> d.edit("X", "password", "較舊的"))
                .remote(d -> d.edit("X", "password", "較新的"))
                .expect(o -> {
                    o.noConflicts();
                    o.field("X", "password", "較新的");
                    o.hasHistoryWithPassword("X", "較舊的");
                    o.notice(MergeNotice.Kind.FIRST_SYNC, null);
                }));
        out.add(MergeCase.named("沒有基準：本機移動（LocationChanged 較新）、遠端改密碼（修改時間較新），兩者都保留")
                .base(d -> {
                    d.group("工作");
                    d.entry("X");
                })
                .withoutBase()
                .local(d -> d.move("X", "工作"))
                .remote(d -> d.edit("X", "password", "遠端的密碼"))
                .expect(o -> {
                    o.noConflicts();
                    o.inGroup("X", "工作");
                    o.field("X", "password", "遠端的密碼");
                }));
        out.add(MergeCase.named("沒有基準：只在一邊的條目保留；另一邊的刪除紀錄比它晚的才刪除（T-99，D-190 取代 T-13 的「一律保留」）")
                .base(d -> {
                    d.entry("X");
                    d.entry("Y");
                })
                .withoutBase()
                .local(d -> {
                    d.later(1);
                    d.purge("X");
                })
                .remote(d -> d.entry("Z"))
                .expect(o -> {
                    o.noConflicts();
                    o.absent("X");
                    o.present("Y");
                    o.present("Z");
                    o.deletedRecorded("X", true);
                    o.notice(MergeNotice.Kind.DELETED_BY_RECORD, "X");
                    o.notice(MergeNotice.Kind.FIRST_SYNC, null);
                }));
        out.add(MergeCase.named("沒有基準：兩邊內容相同時不通知")
                .base(d -> d.entry("X"))
                .withoutBase()
                .expect(o -> {
                    o.noConflicts();
                    o.noNotices();
                    o.check(o.result().plan().isEmpty(), "內容相同時不需要改遠端");
                }));
        return out;
    }

    private static MergeCase.Resolver all(Side side) {
        return (result, ids) -> {
            Map<UUID, Map<String, Side>> out = new HashMap<>();
            for (Conflict c : result.conflicts()) {
                out.put(c.entryId(), c.choose(side));
            }
            return out;
        };
    }

    private static boolean throwsOnPlan(MergeResult r) {
        try {
            r.plan();
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }
}
