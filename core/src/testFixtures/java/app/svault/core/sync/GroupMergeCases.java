package app.svault.core.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * 群組相關的合併案例（Architecture §4 第 4 點；{@link MergeCases#all()} 的一部分）。
 */
final class GroupMergeCases {

    private GroupMergeCases() {
    }

    static List<MergeCase> all() {
        List<MergeCase> out = new ArrayList<>();
        out.add(MergeCase.named("AC-07-6：本機把條目移到別的群組、遠端改密碼，兩者都保留")
                .base(d -> {
                    d.group("工作");
                    d.entry("X");
                })
                .local(d -> d.move("X", "工作"))
                .remote(d -> d.edit("X", "password", "遠端的密碼"))
                .expect(o -> {
                    o.noConflicts();
                    o.inGroup("X", "工作");
                    o.field("X", "password", "遠端的密碼");
                }));
        out.add(MergeCase.named("AC-07-6：兩邊把同一筆移到不同群組，是衝突（所在群組）")
                .base(d -> {
                    d.group("工作");
                    d.group("家庭");
                    d.entry("X");
                })
                .local(d -> d.move("X", "工作"))
                .remote(d -> d.move("X", "家庭"))
                .expect(o -> o.conflict("X", Conflict.GROUP)));
        out.add(MergeCase.named("AC-07-6：本機改群組名稱、遠端在群組裡新增條目")
                .base(d -> d.group("工作"))
                .local(d -> d.renameGroup("工作", "公司"))
                .remote(d -> d.entryIn("工作", "N"))
                .expect(o -> {
                    o.noConflicts();
                    o.groupName("工作", "公司");
                    o.inGroup("N", "工作");
                }));
        out.add(MergeCase.named("AC-07-6：兩邊各自新增群組並放進條目")
                .local(d -> {
                    d.group("本機群組");
                    d.entryIn("本機群組", "L");
                })
                .remote(d -> {
                    d.group("遠端群組");
                    d.entryIn("遠端群組", "R");
                })
                .expect(o -> {
                    o.noConflicts();
                    o.inGroup("L", "本機群組");
                    o.inGroup("R", "遠端群組");
                }));
        out.add(MergeCase.named("AC-07-6：遠端永久刪除群組、本機修改了其中一筆，保留群組與那一筆，其餘照刪除")
                .base(d -> {
                    d.group("工作");
                    d.entryIn("工作", "X");
                    d.entryIn("工作", "Y");
                })
                .local(d -> d.edit("X", "notes", "本機改的"))
                .remote(d -> d.purgeGroup("工作"))
                .expect(o -> {
                    o.noConflicts();
                    o.groupPresent("工作", true);
                    o.inGroup("X", "工作");
                    o.field("X", "notes", "本機改的");
                    o.absent("Y");
                    o.notice(MergeNotice.Kind.GROUP_KEPT, "工作");
                    o.notice(MergeNotice.Kind.KEPT_LOCAL_EDIT, "X");
                }));
        out.add(MergeCase.named("AC-07-6：本機把群組移到回收桶、遠端在裡面新增條目，群組回到原位、沒動的條目留在回收桶")
                .base(d -> {
                    d.group("工作");
                    d.entryIn("工作", "舊的");
                })
                .local(d -> d.recycleGroup("工作"))
                .remote(d -> d.entryIn("工作", "新的"))
                .expect(o -> {
                    o.noConflicts();
                    o.groupInRecycleBin("工作", false);
                    o.inGroup("新的", "工作");
                    o.inRecycleBin("舊的", true);
                    o.notice(MergeNotice.Kind.GROUP_KEPT, "工作");
                }));
        out.add(MergeCase.named("AC-07-6：本機把群組移到回收桶、遠端沒動，整個群組在回收桶裡")
                .base(d -> {
                    d.group("工作");
                    d.entryIn("工作", "X");
                })
                .local(d -> d.recycleGroup("工作"))
                .expect(o -> {
                    o.noConflicts();
                    o.groupInRecycleBin("工作", true);
                    o.inGroup("X", "工作");
                    o.noNotices();
                }));
        out.add(MergeCase.named("群組：兩邊把同一個群組改成不同的名稱，以較新的為準並通知")
                .base(d -> d.group("工作"))
                .local(d -> d.renameGroup("工作", "本機的名稱"))
                .remote(d -> d.renameGroup("工作", "遠端的名稱"))
                .expect(o -> {
                    o.noConflicts();
                    o.groupName("工作", "遠端的名稱");
                    o.notice(MergeNotice.Kind.GROUP_NEWER_KEPT, "工作");
                }));
        return out;
    }
}
