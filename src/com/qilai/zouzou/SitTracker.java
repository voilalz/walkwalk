package com.qilai.zouzou;

import java.util.ArrayDeque;

/**
 * 久坐判断状态机。纯 Java，不依赖 Android，方便在电脑上测试。
 *
 * 后台服务每隔一段时间（默认 30 秒）调用一次 {@link #tick}，传入：
 *  - 这段时间内的步数增量（来自手机计步传感器）
 *  - 摄像头最近一次的判断：看到人 / 没看到人 / 没有可用数据
 *
 * 判断规则：
 *  - 一段时间内走够一定步数（默认 5 分钟内 50 步），算起身活动过，计时清零
 *  - 摄像头之前看到过人、随后连续一段时间（默认 3 分钟）看不到人，算离开座位，计时清零
 *  - 摄像头没有可用数据（关闭、手机平放、在兜里、被占用）时，只要没在走动就当作坐着
 *  - 连续坐满设定时间（默认 45 分钟）提醒；没起身的话每隔一段时间（默认 5 分钟）再提醒
 */
public final class SitTracker {

    public static final int PRESENCE_NO_DATA = 0;
    public static final int PRESENCE_PRESENT = 1;
    public static final int PRESENCE_ABSENT = 2;

    public static final int EVENT_NONE = 0;
    /** 该提醒了（第一次或重复提醒）。 */
    public static final int EVENT_REMIND = 1;
    /** 提醒之后完成了一次休息。 */
    public static final int EVENT_BREAK_DONE = 2;
    /** 还没到提醒时间就起身活动了，计时清零。 */
    public static final int EVENT_RESET = 3;

    public static final int STATE_SITTING = 0;
    public static final int STATE_MOVING = 1;
    public static final int STATE_AWAY = 2;

    public static final int BREAK_NONE = 0;
    public static final int BREAK_WALK = 1;
    public static final int BREAK_AWAY = 2;
    public static final int BREAK_MANUAL = 3;

    // ---------- 参数 ----------
    public long sitLimitMs = 45 * 60_000L;
    public long remindRepeatMs = 5 * 60_000L;
    /** 在 breakStepWindowMs 内走够这么多步，算起身活动过。 */
    public int breakSteps = 50;
    public long breakStepWindowMs = 5 * 60_000L;
    /** 一个周期内超过这么多步，视为正在走动（这段时间不算坐着）。 */
    public int movingStepsPerTick = 8;
    /** 摄像头连续这么久看不到人，算离开座位。 */
    public long awayForBreakMs = 3 * 60_000L;
    /** 超过这么久没看到过人脸，就不再相信“没看到人”（多半是摄像头没对准）。 */
    public long faceValidityMs = 2 * 60 * 60_000L;
    /** 两次 tick 间隔过长时（例如系统暂停了程序），最多只按这么长时间累计。 */
    public long maxTickGapMs = 5 * 60_000L;
    /** 连续坐了至少这么久再起身，才计入“起身次数”。 */
    public long minSitToCountBreakMs = 5 * 60_000L;

    // ---------- 运行状态 ----------
    private long lastTickMs = -1;
    private long sittingMs = 0;
    private boolean reminding = false;
    private long lastRemindMs = 0;
    private long lastFaceSeenMs = -1;
    private long absentSinceMs = -1;
    private final ArrayDeque<long[]> stepLog = new ArrayDeque<long[]>();
    private int state = STATE_SITTING;
    private int effectivePresence = PRESENCE_NO_DATA;
    private int lastBreakReason = BREAK_NONE;

    // ---------- 当天统计 ----------
    private String dayKey = "";
    private long todaySitMs = 0;
    private long todayLongestMs = 0;
    private int todayBreaks = 0;
    private int todayReminders = 0;

    /** 开始新一轮监测时调用：清空计时，但保留当天统计。 */
    public void reset() {
        lastTickMs = -1;
        sittingMs = 0;
        reminding = false;
        lastRemindMs = 0;
        lastFaceSeenMs = -1;
        absentSinceMs = -1;
        stepLog.clear();
        state = STATE_SITTING;
        effectivePresence = PRESENCE_NO_DATA;
        lastBreakReason = BREAK_NONE;
    }

    /** 恢复之前保存的当天统计。 */
    public void restoreDay(String day, long sitMs, long longestMs, int breaks, int reminders) {
        dayKey = day == null ? "" : day;
        todaySitMs = Math.max(0, sitMs);
        todayLongestMs = Math.max(0, longestMs);
        todayBreaks = Math.max(0, breaks);
        todayReminders = Math.max(0, reminders);
    }

    /**
     * @param now        单调递增的时间（毫秒），Android 上用 SystemClock.elapsedRealtime()
     * @param day        当天日期，例如 "2026-10-25"，用于按天统计
     * @param stepsDelta 自上次 tick 以来的步数
     * @param presence   摄像头判断，PRESENCE_*
     * @return EVENT_*
     */
    public int tick(long now, String day, int stepsDelta, int presence) {
        rollDay(day);

        long dt = lastTickMs < 0 ? 0 : now - lastTickMs;
        if (dt < 0) dt = 0;
        if (dt > maxTickGapMs) dt = maxTickGapMs;
        lastTickMs = now;

        // 计步
        if (stepsDelta < 0) stepsDelta = 0;
        if (stepsDelta > 0) stepLog.addLast(new long[] {now, stepsDelta});
        while (!stepLog.isEmpty() && now - stepLog.peekFirst()[0] > breakStepWindowMs) {
            stepLog.removeFirst();
        }
        int windowSteps = windowSteps();
        boolean moving = stepsDelta >= movingStepsPerTick;

        // 摄像头
        int eff = presence;
        if (presence == PRESENCE_PRESENT) {
            lastFaceSeenMs = now;
            absentSinceMs = -1;
        } else if (presence == PRESENCE_ABSENT) {
            if (lastFaceSeenMs < 0 || now - lastFaceSeenMs > faceValidityMs) {
                // 很久没看到过人脸：多半是摄像头没对准人，不当作“离开”
                eff = PRESENCE_NO_DATA;
                absentSinceMs = -1;
            } else if (absentSinceMs < 0) {
                absentSinceMs = now;
            }
        } else {
            absentSinceMs = -1;
        }
        effectivePresence = eff;
        boolean away = eff == PRESENCE_ABSENT;

        if (moving) {
            state = STATE_MOVING;
        } else if (away) {
            state = STATE_AWAY;
        } else {
            state = STATE_SITTING;
        }

        // 是否完成了一次休息
        boolean walkBreak = windowSteps >= breakSteps;
        boolean awayBreak = away && absentSinceMs >= 0 && now - absentSinceMs >= awayForBreakMs;
        if (walkBreak || awayBreak) {
            if (walkBreak) stepLog.clear();
            if (sittingMs > 0 || reminding) {
                return finishSession(walkBreak ? BREAK_WALK : BREAK_AWAY);
            }
            return EVENT_NONE;
        }

        // 累计坐着的时间
        int event = EVENT_NONE;
        if (state == STATE_SITTING) {
            sittingMs += dt;
            todaySitMs += dt;
            if (sittingMs > todayLongestMs) todayLongestMs = sittingMs;
            if (sittingMs >= sitLimitMs) {
                if (!reminding) {
                    reminding = true;
                    lastRemindMs = now;
                    todayReminders++;
                    event = EVENT_REMIND;
                } else if (now - lastRemindMs >= remindRepeatMs) {
                    lastRemindMs = now;
                    event = EVENT_REMIND;
                }
            }
        }
        return event;
    }

    /** 用户手动表示“我刚活动过”。 */
    public int manualBreak(long now) {
        stepLog.clear();
        return finishSession(BREAK_MANUAL);
    }

    private int finishSession(int reason) {
        boolean wasReminding = reminding;
        if (sittingMs >= minSitToCountBreakMs || wasReminding) todayBreaks++;
        sittingMs = 0;
        reminding = false;
        lastBreakReason = reason;
        return wasReminding ? EVENT_BREAK_DONE : EVENT_RESET;
    }

    private void rollDay(String day) {
        if (day == null) return;
        if (!day.equals(dayKey)) {
            dayKey = day;
            todaySitMs = 0;
            todayLongestMs = 0;
            todayBreaks = 0;
            todayReminders = 0;
        }
    }

    private int windowSteps() {
        int sum = 0;
        for (long[] e : stepLog) sum += (int) e[1];
        return sum;
    }

    // ---------- 读取 ----------
    public long getSittingMs() { return sittingMs; }
    public boolean isReminding() { return reminding; }
    public int getState() { return state; }
    public int getEffectivePresence() { return effectivePresence; }
    public int getLastBreakReason() { return lastBreakReason; }
    public int getWindowSteps() { return windowSteps(); }
    public long getAbsentMs(long now) { return absentSinceMs < 0 ? 0 : Math.max(0, now - absentSinceMs); }
    public long getLastTickMs() { return lastTickMs; }
    public String getDayKey() { return dayKey; }
    public long getTodaySitMs() { return todaySitMs; }
    public long getTodayLongestMs() { return todayLongestMs; }
    public int getTodayBreaks() { return todayBreaks; }
    public int getTodayReminders() { return todayReminders; }
}
