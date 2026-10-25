package com.qilai.zouzou;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 设置和当天统计，全部存在本机。 */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("zouzou", Context.MODE_PRIVATE);
    }

    static int sitLimitMin(Context c) { return sp(c).getInt("sit_limit_min", 45); }
    static void setSitLimitMin(Context c, int v) { sp(c).edit().putInt("sit_limit_min", v).apply(); }

    static boolean camera(Context c) { return sp(c).getBoolean("camera", false); }
    static void setCamera(Context c, boolean v) { sp(c).edit().putBoolean("camera", v).apply(); }

    static boolean deskMode(Context c) { return sp(c).getBoolean("desk_mode", false); }
    static void setDeskMode(Context c, boolean v) { sp(c).edit().putBoolean("desk_mode", v).apply(); }

    static boolean wantRunning(Context c) { return sp(c).getBoolean("want_running", false); }
    static void setWantRunning(Context c, boolean v) { sp(c).edit().putBoolean("want_running", v).apply(); }

    static boolean askedBattery(Context c) { return sp(c).getBoolean("asked_battery", false); }
    static void setAskedBattery(Context c, boolean v) { sp(c).edit().putBoolean("asked_battery", v).apply(); }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static void saveStats(Context c, String day, long sitMs, long longestMs, int breaks, int reminders, int steps) {
        sp(c).edit()
                .putString("st_day", day)
                .putLong("st_sit", sitMs)
                .putLong("st_longest", longestMs)
                .putInt("st_breaks", breaks)
                .putInt("st_rem", reminders)
                .putInt("st_steps", steps)
                .apply();
    }

    /** 返回 {久坐总计ms, 最长连续ms, 起身次数, 提醒次数, 步数}；不是这一天的数据则全为 0。 */
    static long[] loadStats(Context c, String day) {
        SharedPreferences p = sp(c);
        if (!day.equals(p.getString("st_day", ""))) return new long[5];
        return new long[] {
                p.getLong("st_sit", 0),
                p.getLong("st_longest", 0),
                p.getInt("st_breaks", 0),
                p.getInt("st_rem", 0),
                p.getInt("st_steps", 0),
        };
    }

    static String minutes(long ms) {
        long m = Math.max(0, ms) / 60000L;
        if (m < 60) return m + " 分钟";
        long h = m / 60, r = m % 60;
        return r == 0 ? h + " 小时" : h + " 小时 " + r + " 分";
    }
}
