package com.qilai.zouzou;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final int C_BG = 0xFFF2F4F3;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF1C2421;
    private static final int C_SUB = 0xFF65706B;
    private static final int C_PRIMARY = 0xFF2E7D5B;
    private static final int C_PRIMARY_SOFT = 0xFFE4F2EA;
    private static final int C_WARN = 0xFFD64545;
    private static final int C_WARN_SOFT = 0xFFFDECEC;
    private static final int C_LINE = 0xFFE5E9E7;
    private static final int D_TEXT = 0xFFD9DFDC;
    private static final int D_SUB = 0xFF7D8581;
    private static final int D_WARN = 0xFFFF7070;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int REQ_START = 1;
    private static final int REQ_CAMERA_SWITCH = 2;
    private static final int REQ_CAMERA_TEST = 3;
    private static final int[] LIMITS = {30, 45, 60, 90};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FaceChecker tester;
    private boolean testing;
    private boolean suppress;
    private int renderedRunning = -1;
    private int renderedLimit = -1;

    private LinearLayout deskView;
    private TextView bigMinutes, stateLine, detailLine, noteLine, reminderBanner;
    private ProgressBar progress;
    private TextView startBtn, breakBtn, camTestResult;
    private final TextView[] chips = new TextView[LIMITS.length];
    private Switch camSwitch, deskSwitch;
    private TextView statSit, statLongest, statBreaks, statReminders, statSteps;
    private TextView deskMinutes, deskState, deskReminder;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        MonitorService.ensureChannels(this);
        tester = new FaceChecker(this);
        setContentView(buildUi());
        applyDesk(Prefs.deskMode(this));
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(refresher);
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(refresher);
        tester.release();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (deskView.getVisibility() == View.VISIBLE) {
            setDesk(false);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 界面

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(24), dp(18), dp(32));
        scroll.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        col.addView(text("起来走走", 26, C_TEXT, true));
        col.addView(text("久坐提醒 · 全程离线，不联网", 13, C_SUB, false), lp(MATCH, WRAP, 2));

        // ---- 状态
        LinearLayout status = card();
        status.addView(text("已连续坐", 14, C_SUB, false));
        LinearLayout numRow = new LinearLayout(this);
        numRow.setOrientation(LinearLayout.HORIZONTAL);
        numRow.setGravity(Gravity.BOTTOM);
        bigMinutes = text("0", 56, C_TEXT, true);
        bigMinutes.setIncludeFontPadding(false);
        numRow.addView(bigMinutes);
        TextView unit = text("分钟", 17, C_SUB, false);
        unit.setPadding(dp(6), 0, 0, dp(6));
        numRow.addView(unit);
        status.addView(numRow, lp(WRAP, WRAP, 4));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(C_LINE));
        progress.setProgressTintList(ColorStateList.valueOf(C_PRIMARY));
        status.addView(progress, lp(MATCH, WRAP, 12));
        stateLine = text("", 15, C_TEXT, false);
        status.addView(stateLine, lp(MATCH, WRAP, 12));
        detailLine = text("", 13, C_SUB, false);
        status.addView(detailLine, lp(MATCH, WRAP, 4));
        noteLine = text("", 13, C_WARN, false);
        noteLine.setVisibility(View.GONE);
        status.addView(noteLine, lp(MATCH, WRAP, 6));
        reminderBanner = text("该起来走走了！带着手机走一走，或者离开座位几分钟。", 15, C_WARN, true);
        reminderBanner.setBackground(rounded(C_WARN_SOFT, 12, 0));
        reminderBanner.setPadding(dp(12), dp(10), dp(12), dp(10));
        reminderBanner.setVisibility(View.GONE);
        status.addView(reminderBanner, lp(MATCH, WRAP, 12));
        col.addView(status, lp(MATCH, WRAP, 18));

        // ---- 按钮
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = button();
        breakBtn = button();
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        b2.leftMargin = dp(12);
        btns.addView(startBtn, new LinearLayout.LayoutParams(0, WRAP, 1f));
        btns.addView(breakBtn, b2);
        col.addView(btns, lp(MATCH, WRAP, 14));
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onStartStop(); }
        });
        breakBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onManualBreak(); }
        });

        // ---- 设置
        LinearLayout set = card();
        set.addView(text("设置", 17, C_TEXT, true));
        set.addView(text("提醒间隔", 14, C_SUB, false), lp(MATCH, WRAP, 12));
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < LIMITS.length; i++) {
            final int v = LIMITS[i];
            TextView c = text(v + " 分钟", 14, C_TEXT, false);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, dp(9), 0, dp(9));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            if (i > 0) cp.leftMargin = dp(8);
            chipRow.addView(c, cp);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { setLimit(v); }
            });
            chips[i] = c;
        }
        set.addView(chipRow, lp(MATCH, WRAP, 8));

        set.addView(divider(), lp(MATCH, dp(1), 16));
        camSwitch = makeSwitch("摄像头辅助判断", Prefs.camera(this));
        set.addView(camSwitch, lp(MATCH, WRAP, 12));
        set.addView(text("把手机立在显示器旁、前置摄像头对着你，就能看出你是否离开了座位。手机平放或放在兜里时会自动只用计步。画面只在手机上即时分析，不保存。", 13, C_SUB, false), lp(MATCH, WRAP, 2));
        TextView testBtn = smallButton("测试摄像头位置");
        set.addView(testBtn, lp(WRAP, WRAP, 10));
        camTestResult = text("", 13, C_SUB, false);
        camTestResult.setVisibility(View.GONE);
        set.addView(camTestResult, lp(MATCH, WRAP, 8));
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runCameraTest(); }
        });
        camSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!suppress) onCameraSwitch(on);
            }
        });

        set.addView(divider(), lp(MATCH, dp(1), 16));
        deskSwitch = makeSwitch("桌面常亮模式", Prefs.deskMode(this));
        set.addView(deskSwitch, lp(MATCH, WRAP, 12));
        set.addView(text("屏幕常亮并调暗，大字显示已经坐了多久。适合手机立在桌上、插着充电时用。", 13, C_SUB, false), lp(MATCH, WRAP, 2));
        deskSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!suppress) setDesk(on);
            }
        });
        col.addView(set, lp(MATCH, WRAP, 18));

        // ---- 今天
        LinearLayout stats = card();
        stats.addView(text("今天", 17, C_TEXT, true));
        statSit = statRow(stats, "久坐总计");
        statLongest = statRow(stats, "最长一次连续坐");
        statBreaks = statRow(stats, "起身次数");
        statReminders = statRow(stats, "提醒次数");
        statSteps = statRow(stats, "步数");
        col.addView(stats, lp(MATCH, WRAP, 18));

        // ---- 怎么判断
        LinearLayout how = card();
        how.addView(text("怎么判断的", 17, C_TEXT, true));
        how.addView(text(
                "• 带着手机走动约 50 步，算起身活动过，计时清零\n"
                        + "• 开了摄像头辅助时，连续 3 分钟看不到你，算离开座位\n"
                        + "• 坐满设定时间就振动提醒；没起身的话，每 5 分钟再提醒一次\n"
                        + "• 离开时没带手机、也没开摄像头，App 会以为你还坐着，回来点“我刚活动过”即可",
                14, C_TEXT, false), lp(MATCH, WRAP, 8));
        col.addView(how, lp(MATCH, WRAP, 18));

        // ---- 可靠性
        LinearLayout tips = card();
        tips.addView(text("让提醒更可靠", 17, C_TEXT, true));
        tips.addView(text("国产手机的后台管理比较严格。请在系统设置里把本应用的电池策略设为“不限制”，并允许自启动、后台运行，否则 App 可能会被系统关掉。", 14, C_TEXT, false), lp(MATCH, WRAP, 8));
        LinearLayout tipBtns = new LinearLayout(this);
        tipBtns.setOrientation(LinearLayout.HORIZONTAL);
        TextView batt = button();
        styleButton(batt, "忽略电池优化", false);
        TextView appSet = button();
        styleButton(appSet, "应用设置", false);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        t2.leftMargin = dp(12);
        tipBtns.addView(batt, new LinearLayout.LayoutParams(0, WRAP, 1f));
        tipBtns.addView(appSet, t2);
        tips.addView(tipBtns, lp(MATCH, WRAP, 12));
        batt.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestIgnoreBattery(); }
        });
        appSet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAppSettings(); }
        });
        tips.addView(text("本应用没有联网权限，所有数据只保存在这台手机上。", 13, C_SUB, false), lp(MATCH, WRAP, 12));
        col.addView(tips, lp(MATCH, WRAP, 18));

        // ---- 桌面常亮模式（盖在最上层）
        deskView = new LinearLayout(this);
        deskView.setOrientation(LinearLayout.VERTICAL);
        deskView.setGravity(Gravity.CENTER);
        deskView.setBackgroundColor(Color.BLACK);
        deskView.setPadding(dp(24), dp(24), dp(24), dp(24));
        deskView.setClickable(true);
        deskView.addView(centered(text("已连续坐", 18, D_SUB, false)), lp(MATCH, WRAP, 0));
        deskMinutes = centered(text("0", 120, D_TEXT, true));
        deskMinutes.setIncludeFontPadding(false);
        deskView.addView(deskMinutes, lp(MATCH, WRAP, 8));
        deskView.addView(centered(text("分钟", 20, D_SUB, false)), lp(MATCH, WRAP, 4));
        deskState = centered(text("", 16, D_SUB, false));
        deskView.addView(deskState, lp(MATCH, WRAP, 28));
        deskReminder = centered(text("该起来走走了！", 30, D_WARN, true));
        deskReminder.setVisibility(View.GONE);
        deskView.addView(deskReminder, lp(MATCH, WRAP, 20));
        TextView exit = centered(text("退出桌面模式", 15, D_SUB, false));
        exit.setPadding(dp(16), dp(12), dp(16), dp(12));
        exit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setDesk(false); }
        });
        deskView.addView(exit, lp(MATCH, WRAP, 48));
        deskView.setVisibility(View.GONE);
        root.addView(deskView, new FrameLayout.LayoutParams(MATCH, MATCH));
        return root;
    }

    private void render() {
        MonitorService.Status s = MonitorService.status;
        boolean running = s.running;
        int limit = Prefs.sitLimitMin(this);
        long now = SystemClock.elapsedRealtime();

        long sit = 0;
        if (running) {
            sit = s.sittingMs;
            if (s.state == SitTracker.STATE_SITTING) {
                sit += Math.max(0, Math.min(now - s.asOf, MonitorService.TICK_MS));
            }
        }
        long mins = sit / 60000L;
        boolean warn = running && s.reminding;

        bigMinutes.setText(String.valueOf(mins));
        bigMinutes.setTextColor(warn ? C_WARN : C_TEXT);
        progress.setMax(limit);
        progress.setProgress((int) Math.min(limit, mins));
        progress.setProgressTintList(ColorStateList.valueOf(warn ? C_WARN : C_PRIMARY));

        String state = stateText(s);
        stateLine.setText(state);
        detailLine.setText(detailText(s, limit, now));
        boolean showNote = running && s.note != null && s.note.length() > 0;
        noteLine.setVisibility(showNote ? View.VISIBLE : View.GONE);
        if (showNote) noteLine.setText(s.note);
        reminderBanner.setVisibility(warn ? View.VISIBLE : View.GONE);

        int r = running ? 1 : 0;
        if (r != renderedRunning) {
            renderedRunning = r;
            styleButton(startBtn, running ? "停止监测" : "开始监测", !running);
            styleButton(breakBtn, "我刚活动过", running);
            breakBtn.setEnabled(running);
            breakBtn.setAlpha(running ? 1f : 0.45f);
        }
        if (limit != renderedLimit) {
            renderedLimit = limit;
            for (int i = 0; i < LIMITS.length; i++) {
                boolean sel = LIMITS[i] == limit;
                chips[i].setTextColor(sel ? Color.WHITE : C_TEXT);
                chips[i].setBackground(rounded(sel ? C_PRIMARY : C_PRIMARY_SOFT, 12, 0));
            }
        }

        long sitT, longest;
        int breaks, rem, steps;
        if (running) {
            sitT = s.todaySitMs;
            longest = s.todayLongestMs;
            breaks = s.todayBreaks;
            rem = s.todayReminders;
            steps = s.todaySteps;
        } else {
            long[] st = Prefs.loadStats(this, Prefs.today());
            sitT = st[0];
            longest = st[1];
            breaks = (int) st[2];
            rem = (int) st[3];
            steps = (int) st[4];
        }
        statSit.setText(Prefs.minutes(sitT));
        statLongest.setText(Prefs.minutes(longest));
        statBreaks.setText(breaks + " 次");
        statReminders.setText(rem + " 次");
        statSteps.setText(steps + " 步");

        deskMinutes.setText(String.valueOf(mins));
        deskMinutes.setTextColor(warn ? D_WARN : D_TEXT);
        deskState.setText(running ? state : "还没开始监测");
        deskReminder.setVisibility(warn ? View.VISIBLE : View.GONE);
    }

    private String stateText(MonitorService.Status s) {
        if (!s.running) return "还没开始监测。点“开始监测”后，App 会在后台持续判断。";
        if (s.state == SitTracker.STATE_MOVING) return "正在走动";
        if (s.state == SitTracker.STATE_AWAY) {
            long m = s.absentMs / 60000L;
            if (m >= 3) return "已离开座位 " + m + " 分钟 · 已算作一次休息";
            return "离开座位 " + m + " 分钟（满 3 分钟算休息）";
        }
        if (s.presence == SitTracker.PRESENCE_PRESENT) return "坐着 · 摄像头看到你在座位上";
        return "坐着 · 根据计步判断（最近没检测到走动）";
    }

    private String detailText(MonitorService.Status s, int limit, long now) {
        StringBuilder b = new StringBuilder();
        b.append("每 ").append(limit).append(" 分钟提醒");
        if (!s.running) return b.toString();
        if (!s.stepSensor) b.append(" · 本机没有计步传感器");
        else if (!s.stepsPermission) b.append(" · 没有计步权限");
        else b.append(" · 今天走了 ").append(s.todaySteps).append(" 步");
        if (s.cameraOn) {
            b.append('\n').append(camText(s.camResult, s.camResultAt == 0 ? -1 : now - s.camResultAt));
        }
        return b.toString();
    }

    private static String camText(int r, long ageMs) {
        if (r == FaceChecker.RESULT_NONE || ageMs < 0) return "摄像头：准备中…";
        if (ageMs > MonitorService.CAM_FRESH_MS) return "摄像头：暂时没有新结果（锁屏后可能被系统限制）";
        switch (r) {
            case FaceChecker.RESULT_FACE: return "摄像头：看到你了";
            case FaceChecker.RESULT_NO_FACE: return "摄像头：没看到人";
            case FaceChecker.RESULT_DARK: return "摄像头：画面太暗（手机可能在兜里或扣着放）";
            case FaceChecker.RESULT_FLAT: return "摄像头：手机平放着，暂时只用计步";
            case FaceChecker.RESULT_BUSY: return "摄像头：正被其他应用占用";
            case FaceChecker.RESULT_NO_PERMISSION: return "摄像头：没有权限";
            case FaceChecker.RESULT_NO_CAMERA: return "摄像头：没找到前置摄像头";
            default: return "摄像头：暂时不可用";
        }
    }

    private static String testText(int r) {
        switch (r) {
            case FaceChecker.RESULT_FACE: return "✓ 看到你了，这个位置可以。";
            case FaceChecker.RESULT_NO_FACE: return "没看到人脸。把手机立起来放在显示器旁边，让前置摄像头大致对着你的脸，再试一次。";
            case FaceChecker.RESULT_DARK: return "画面太暗，换个亮一点的位置再试。";
            case FaceChecker.RESULT_BUSY: return "摄像头正被占用（可能后台正在检测），过几秒再试。";
            case FaceChecker.RESULT_NO_CAMERA: return "没找到前置摄像头。";
            case FaceChecker.RESULT_NO_PERMISSION: return "没有摄像头权限。";
            default: return "摄像头暂时不可用，稍后再试。";
        }
    }

    // ------------------------------------------------------------------ 操作

    private void onStartStop() {
        if (MonitorService.status.running) {
            startService(new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP));
            return;
        }
        ArrayList<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            need.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Prefs.camera(this) && !granted(Manifest.permission.CAMERA)) {
            need.add(Manifest.permission.CAMERA);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[need.size()]), REQ_START);
            return;
        }
        launchService();
    }

    private void launchService() {
        Intent i = new Intent(this, MonitorService.class)
                .setAction(MonitorService.ACTION_START)
                .putExtra(MonitorService.EXTRA_FROM_UI, true);
        try {
            startForegroundService(i);
        } catch (RuntimeException e) {
            toast("启动失败：" + e.getClass().getSimpleName());
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() { maybeAskBattery(); }
        }, 1200);
    }

    private void onManualBreak() {
        if (!MonitorService.status.running) return;
        startService(new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_MANUAL_BREAK));
        toast("好的，已重新计时");
    }

    private void onCameraSwitch(boolean on) {
        if (on && !granted(Manifest.permission.CAMERA)) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA_SWITCH);
            return;
        }
        Prefs.setCamera(this, on);
        reloadService();
    }

    private void setLimit(int v) {
        Prefs.setSitLimitMin(this, v);
        reloadService();
        render();
    }

    private void reloadService() {
        if (!MonitorService.status.running) return;
        startService(new Intent(this, MonitorService.class)
                .setAction(MonitorService.ACTION_RELOAD)
                .putExtra(MonitorService.EXTRA_FROM_UI, true));
    }

    private void runCameraTest() {
        if (testing) return;
        if (!granted(Manifest.permission.CAMERA)) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA_TEST);
            return;
        }
        testing = true;
        camTestResult.setVisibility(View.VISIBLE);
        camTestResult.setTextColor(C_SUB);
        camTestResult.setText("检测中…请像平时一样看着电脑屏幕");
        tester.check(new FaceChecker.Callback() {
            @Override public void onResult(final int r) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        testing = false;
                        camTestResult.setTextColor(r == FaceChecker.RESULT_FACE ? C_PRIMARY : C_WARN);
                        camTestResult.setText(testText(r));
                    }
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_START) {
            StringBuilder msg = new StringBuilder();
            for (int i = 0; i < perms.length && i < results.length; i++) {
                if (results[i] == PackageManager.PERMISSION_GRANTED) continue;
                if (Manifest.permission.CAMERA.equals(perms[i])) {
                    Prefs.setCamera(this, false);
                    setQuietly(camSwitch, false);
                    msg.append("没有摄像头权限，先只用计步判断。");
                } else if (Manifest.permission.ACTIVITY_RECOGNITION.equals(perms[i])) {
                    msg.append("没有“身体活动”权限，无法计步，只能按时间提醒。");
                } else if (Manifest.permission.POST_NOTIFICATIONS.equals(perms[i])) {
                    msg.append("没有通知权限，提醒时只会振动。");
                }
            }
            if (msg.length() > 0) toast(msg.toString());
            launchService();
        } else if (code == REQ_CAMERA_SWITCH) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            Prefs.setCamera(this, ok);
            setQuietly(camSwitch, ok);
            if (ok) reloadService();
            else toast("没有摄像头权限，无法开启摄像头辅助");
        } else if (code == REQ_CAMERA_TEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) runCameraTest();
            else toast("没有摄像头权限");
        }
    }

    private void setDesk(boolean on) {
        Prefs.setDeskMode(this, on);
        setQuietly(deskSwitch, on);
        applyDesk(on);
    }

    @SuppressWarnings("deprecation")
    private void applyDesk(boolean on) {
        deskView.setVisibility(on ? View.VISIBLE : View.GONE);
        Window w = getWindow();
        WindowManager.LayoutParams a = w.getAttributes();
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (on) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            a.screenBrightness = 0.1f;
            w.setStatusBarColor(Color.BLACK);
            w.setNavigationBarColor(Color.BLACK);
            flags &= ~light;
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            a.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            w.setStatusBarColor(C_BG);
            w.setNavigationBarColor(C_BG);
            flags |= light;
        }
        w.setAttributes(a);
        decor.setSystemUiVisibility(flags);
        if (on) render();
    }

    private void maybeAskBattery() {
        if (isFinishing() || ignoringBattery() || Prefs.askedBattery(this)) return;
        Prefs.setAskedBattery(this, true);
        new AlertDialog.Builder(this)
                .setTitle("允许后台持续运行")
                .setMessage("为了锁屏后也能按时提醒，请允许本应用“忽略电池优化”。\n\n国产手机还需要在系统设置里允许自启动、后台运行（不同品牌叫法不同）。")
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { requestIgnoreBattery(); }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    private boolean ignoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBattery() {
        if (ignoringBattery()) {
            toast("已经允许忽略电池优化了");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            openAppSettings();
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            toast("请在系统设置的应用列表里找到“起来走走”");
        }
    }

    // ------------------------------------------------------------------ 小工具

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void setQuietly(Switch sw, boolean on) {
        suppress = true;
        sw.setChecked(on);
        suppress = false;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private TextView centered(TextView t) {
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private GradientDrawable rounded(int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1.5f), strokeColor);
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(C_CARD, 18, 0));
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        c.setElevation(dp(1));
        return c;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topDp);
        return p;
    }

    private TextView button() {
        TextView t = text("", 16, C_PRIMARY, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(13), dp(12), dp(13));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private void styleButton(TextView t, String label, boolean filled) {
        t.setText(label);
        t.setTextColor(filled ? Color.WHITE : C_PRIMARY);
        GradientDrawable bg = rounded(filled ? C_PRIMARY : C_CARD, 14, filled ? 0 : C_PRIMARY);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), bg, null));
    }

    private TextView smallButton(String label) {
        TextView t = text(label, 14, C_PRIMARY, true);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(C_PRIMARY_SOFT, 10, 0), null));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private Switch makeSwitch(String label, boolean checked) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(16);
        sw.setTextColor(C_TEXT);
        sw.setChecked(checked);
        int[][] states = {new int[] {android.R.attr.state_checked}, new int[0]};
        sw.setThumbTintList(new ColorStateList(states, new int[] {C_PRIMARY, 0xFFFAFAFA}));
        sw.setTrackTintList(new ColorStateList(states, new int[] {0x882E7D5B, 0x33000000}));
        return sw;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_LINE);
        return v;
    }

    private TextView statRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = text(label, 14, C_SUB, false);
        TextView v = text("—", 15, C_TEXT, true);
        v.setGravity(Gravity.END);
        row.addView(l, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(v, new LinearLayout.LayoutParams(WRAP, WRAP));
        parent.addView(row, lp(MATCH, WRAP, 10));
        return v;
    }
}
