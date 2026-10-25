import com.qilai.zouzou.SitTracker;

/** 在电脑上跑的场景测试：javac + java 直接运行，失败时退出码为 1。 */
public class SitTrackerTest {
    static int fails = 0, passes = 0;
    static final long MIN = 60_000L, TICK = 30_000L;
    static final String DAY = "2026-10-25";

    static final int NO = SitTracker.PRESENCE_NO_DATA;
    static final int IN = SitTracker.PRESENCE_PRESENT;
    static final int OUT = SitTracker.PRESENCE_ABSENT;

    static void check(boolean cond, String name) {
        if (cond) { passes++; System.out.println("  ok   " + name); }
        else { fails++; System.out.println("  FAIL " + name); }
    }

    /** 每 30 秒 tick 一次的小模拟器。 */
    static final class Sim {
        final SitTracker t = new SitTracker();
        long now = 1_000_000L;
        boolean first = true;

        int tick(int steps, int presence) {
            if (!first) now += TICK;
            first = false;
            return t.tick(now, DAY, steps, presence);
        }

        int[] run(int n, int steps, int presence) {
            int[] ev = new int[4];
            for (int i = 0; i < n; i++) ev[tick(steps, presence)]++;
            return ev;
        }

        long sitMin() { return t.getSittingMs() / MIN; }
    }

    public static void main(String[] args) {
        System.out.println("1. 只靠计步：一直坐着");
        {
            Sim s = new Sim();
            int[] ev = s.run(90, 0, NO);
            check(ev[SitTracker.EVENT_REMIND] == 0, "44.5 分钟内不提醒");
            check(s.tick(0, NO) == SitTracker.EVENT_REMIND, "满 45 分钟提醒");
            ev = s.run(9, 0, NO);
            check(ev[SitTracker.EVENT_REMIND] == 0, "接下来 5 分钟内不重复提醒");
            check(s.tick(0, NO) == SitTracker.EVENT_REMIND, "5 分钟后还没起身，再提醒一次");
            int e1 = s.tick(30, NO);
            check(e1 == SitTracker.EVENT_NONE && s.t.getState() == SitTracker.STATE_MOVING, "开始走动，步数还不够 50");
            int e2 = s.tick(30, NO);
            check(e2 == SitTracker.EVENT_BREAK_DONE, "走够 50 步，完成休息");
            check(s.t.getSittingMs() == 0 && !s.t.isReminding(), "计时清零、提醒解除");
            check(s.t.getTodayBreaks() == 1 && s.t.getTodayReminders() == 1, "统计：起身 1 次、提醒 1 次");
            check(s.t.getLastBreakReason() == SitTracker.BREAK_WALK, "休息原因记为走动");
        }

        System.out.println("2. 零星几步（抖腿、挪椅子）");
        {
            Sim s = new Sim();
            s.run(21, 3, NO);
            check(s.t.getSittingMs() == 20 * TICK, "每 30 秒 3 步：不打断计时");
            check(s.t.getState() == SitTracker.STATE_SITTING, "仍判定为坐着");
        }

        System.out.println("3. 摄像头：在座位 / 短暂离开 / 离开满 3 分钟");
        {
            Sim s = new Sim();
            s.run(41, 0, IN);
            check(s.sitMin() == 20, "摄像头看到人：累计 20 分钟");
            s.run(5, 0, OUT);
            check(s.t.getState() == SitTracker.STATE_AWAY && s.sitMin() == 20, "离开 2 分钟：暂停计时，不清零");
            s.tick(0, IN);
            check(s.t.getSittingMs() == 20 * MIN + TICK, "回到座位：接着累计");
            int[] ev = s.run(7, 0, OUT);
            check(ev[SitTracker.EVENT_RESET] == 1 && s.t.getSittingMs() == 0, "离开满 3 分钟：计时清零");
            check(s.t.getTodayBreaks() == 1 && s.t.getLastBreakReason() == SitTracker.BREAK_AWAY, "记一次起身，原因是离开座位");
            ev = s.run(60, 0, OUT);
            check(ev[SitTracker.EVENT_REMIND] == 0 && s.t.getSittingMs() == 0, "离开 30 分钟（开会）：不累计、不提醒");
            s.run(10, 0, IN);
            check(s.t.getSittingMs() == 5 * MIN, "回来后从 0 开始累计");
        }

        System.out.println("4. 摄像头从没看到过人脸（没对准）");
        {
            Sim s = new Sim();
            int[] ev = s.run(91, 0, OUT);
            check(ev[SitTracker.EVENT_REMIND] == 1, "不当作离开，照常在 45 分钟提醒");
            check(s.t.getEffectivePresence() == SitTracker.PRESENCE_NO_DATA, "摄像头结果按“无数据”处理");
        }

        System.out.println("5. 看到过人脸，之后 2 小时以上都没看到");
        {
            Sim s = new Sim();
            s.tick(0, IN);
            int[] ev = s.run(240, 0, OUT);
            check(ev[SitTracker.EVENT_REMIND] == 0 && s.t.getSittingMs() == 0, "2 小时内一直当作离开");
            ev = s.run(91, 0, OUT);
            check(ev[SitTracker.EVENT_REMIND] == 1, "超过 2 小时后改为只靠计步判断，照常提醒");
        }

        System.out.println("6. 提醒后离开座位");
        {
            Sim s = new Sim();
            s.run(91, 0, IN);
            check(s.t.isReminding(), "坐满 45 分钟进入提醒");
            int[] ev = s.run(7, 0, OUT);
            check(ev[SitTracker.EVENT_BREAK_DONE] == 1 && !s.t.isReminding(), "离开 3 分钟：完成休息");
        }

        System.out.println("7. 手动“我刚活动过”");
        {
            Sim s = new Sim();
            s.run(95, 0, NO);
            check(s.t.manualBreak(s.now) == SitTracker.EVENT_BREAK_DONE && s.t.getSittingMs() == 0, "提醒中点按钮：完成休息、清零");
            check(s.t.getLastBreakReason() == SitTracker.BREAK_MANUAL, "原因记为手动");
        }

        System.out.println("8. 跨天");
        {
            Sim s = new Sim();
            s.run(21, 0, NO);
            s.now += TICK;
            s.t.tick(s.now, "2026-10-26", 0, NO);
            check(s.t.getTodaySitMs() == TICK && s.t.getSittingMs() == 21 * TICK, "新的一天统计归零，连续计时不受影响");
        }

        System.out.println("9. 程序被系统暂停很久");
        {
            SitTracker t = new SitTracker();
            t.tick(0, DAY, 0, NO);
            t.tick(20 * MIN, DAY, 0, NO);
            check(t.getSittingMs() == 5 * MIN, "间隔 20 分钟最多按 5 分钟累计");
        }

        System.out.println("10. 短暂走动");
        {
            Sim s = new Sim();
            s.run(21, 0, NO);
            s.run(2, 10, NO);
            check(s.sitMin() == 10 && s.t.getState() == SitTracker.STATE_MOVING, "走了 20 步：暂停计时但不清零");
        }

        System.out.println("11. 没到提醒时间就起身");
        {
            Sim s = new Sim();
            s.run(21, 0, NO);
            int e = s.tick(60, NO);
            check(e == SitTracker.EVENT_RESET && s.t.getTodayBreaks() == 1, "坐了 10 分钟后走 60 步：清零并记一次起身");
            s.run(4, 0, NO);
            e = s.tick(60, NO);
            check(e == SitTracker.EVENT_RESET && s.t.getTodayBreaks() == 1, "坐不到 5 分钟又走动：清零但不重复计数");
        }

        System.out.println("12. 重启后恢复当天统计");
        {
            SitTracker t = new SitTracker();
            t.restoreDay(DAY, 30 * MIN, 20 * MIN, 2, 1);
            t.reset();
            t.tick(0, DAY, 0, NO);
            check(t.getTodaySitMs() == 30 * MIN && t.getTodayBreaks() == 2 && t.getTodayReminders() == 1, "统计保留");
        }

        System.out.println();
        System.out.println(passes + " 通过, " + fails + " 失败");
        if (fails > 0) System.exit(1);
    }
}
