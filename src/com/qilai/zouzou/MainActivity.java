package com.qilai.zouzou;

import android.Manifest;
import android.animation.LayoutTransition;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    // 桌面常亮模式固定用黑底暗色，和主题无关
    private static final int D_TEXT = 0xFFD9DFDC;
    private static final int D_SUB = 0xFF7D8581;
    private static final int D_TRACK = 0xFF1B201E;
    private static final int D_ARC = 0xFF3E8F6D;
    private static final int D_WARN = 0xFFFF7070;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int REQ_START = 1;
    private static final int REQ_CAMERA_SWITCH = 2;
    private static final int REQ_CAMERA_TEST = 3;
    private static final int[] LIMITS = {30, 45, 60, 90};

    private static final int TAB_HOME = 0, TAB_TODAY = 1, TAB_SETTINGS = 2;
    private static final String[] TAB_NAMES = {"监测", "今天", "设置"};
    private static final int[] TAB_ICONS = {Ui.ICON_TIMER, Ui.ICON_CHART, Ui.ICON_TUNE};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Ui.Palette p;
    private FaceChecker tester;
    private boolean testing;
    private int renderedRunning = -1;
    private int renderedLimit = -1;
    private int renderedWarn = -1;
    private int tab = TAB_HOME;

    private final View[] pages = new View[3];
    private final View[] navPills = new View[3];
    private final Ui.Icon[] navIcons = new Ui.Icon[3];
    private final TextView[] navLabels = new TextView[3];

    private LinearLayout deskView;
    private Ui.Ring ring, deskRing;
    private TextView bigMinutes, ringLimit, statePill, stateLine, detailLine, noteLine;
    private GradientDrawable pillBg, pillDot;
    private LinearLayout reminderBanner;
    private TextView startBtn, breakBtn, camTestResult, battState;
    private final TextView[] chips = new TextView[LIMITS.length];
    private Ui.Toggle camSwitch, deskSwitch;
    private TextView miniSit, miniBreaks, miniSteps;
    private TextView statSit, statLongest, statBreaks, statReminders, statSteps, statSitSub;
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
        p = Ui.Palette.of(this);
        MonitorService.ensureChannels(this);
        tester = new FaceChecker(this);
        if (saved != null) tab = saved.getInt("tab", TAB_HOME);
        setContentView(buildUi());
        selectTab(tab, false);
        applyDesk(Prefs.deskMode(this));
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(refresher);
        ui.post(refresher);
        renderBattery();
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
        if (tab != TAB_HOME) {
            selectTab(TAB_HOME, true);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 界面

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(p.bg);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        FrameLayout content = new FrameLayout(this);
        shell.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        shell.addView(buildNav(), new LinearLayout.LayoutParams(MATCH, WRAP));
        root.addView(shell, new FrameLayout.LayoutParams(MATCH, MATCH));

        pages[TAB_HOME] = page(buildHome());
        pages[TAB_TODAY] = page(buildToday());
        pages[TAB_SETTINGS] = page(buildSettings());
        for (View v : pages) content.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));

        root.addView(buildDesk(), new FrameLayout.LayoutParams(MATCH, MATCH));
        return root;
    }

    private ScrollView page(LinearLayout col) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        col.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP));
        return scroll;
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LayoutTransition t = new LayoutTransition();
        t.enableTransitionType(LayoutTransition.CHANGING);
        t.setDuration(200);
        col.setLayoutTransition(t);
        return col;
    }

    // ---- 底部导航（Material 3 NavigationBar）

    private View buildNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(p.surface);
        nav.setPadding(dp(8), dp(10), dp(8), dp(14));
        nav.setElevation(dp(3));
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setClickable(true);
            item.setFocusable(true);
            item.setContentDescription(TAB_NAMES[i]);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(p.primary, 0x22)), null,
                    rounded(Color.WHITE, 16, 0)));

            FrameLayout pill = new FrameLayout(this);
            navIcons[i] = new Ui.Icon(this, TAB_ICONS[i], p.sub, 24);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(navIcons[i]);
            pill.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            navPills[i] = pill;
            item.addView(pill, new LinearLayout.LayoutParams(dp(64), dp(32)));

            navLabels[i] = text(TAB_NAMES[i], 12, p.sub, false);
            item.addView(navLabels[i], lp(WRAP, WRAP, 4));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx != tab) Ui.tick(v);
                    selectTab(idx, true);
                }
            });
            nav.addView(item, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        return nav;
    }

    private void selectTab(int idx, boolean animate) {
        tab = idx;
        for (int i = 0; i < 3; i++) {
            boolean sel = i == idx;
            navPills[i].setBackground(sel ? rounded(p.primaryContainer, 16, 0) : null);
            navIcons[i].setColor(sel ? p.onPrimaryContainer : p.sub);
            navLabels[i].setTextColor(sel ? p.text : p.sub);
            navLabels[i].setTypeface(sel ? medium() : Typeface.DEFAULT);
            View page = pages[i];
            if (!sel) {
                page.animate().cancel();
                page.setVisibility(View.GONE);
            } else if (page.getVisibility() != View.VISIBLE) {
                page.setVisibility(View.VISIBLE);
                if (animate) {
                    page.setAlpha(0f);
                    page.setTranslationY(dp(12));
                    page.animate().alpha(1f).translationY(0).setDuration(220)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            }
        }
    }

    // ---- 监测页

    private LinearLayout buildHome() {
        LinearLayout col = column();
        col.addView(header("起来走走", "久坐提醒 · 全程离线，不联网"));

        // 进度环 + 中间的大数字
        FrameLayout ringBox = new FrameLayout(this);
        ring = new Ui.Ring(this, 14);
        ring.setColors(p.surfaceHigh, p.primary);
        ringBox.addView(ring, new FrameLayout.LayoutParams(dp(248), dp(248), Gravity.CENTER));
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        center.addView(text("已连续坐", 14, p.sub, false));
        bigMinutes = text("0", 64, p.text, false);
        bigMinutes.setTypeface(medium());
        bigMinutes.setIncludeFontPadding(false);
        bigMinutes.setLetterSpacing(-0.02f);
        center.addView(bigMinutes, lp(WRAP, WRAP, 2));
        ringLimit = text("", 14, p.sub, false);
        center.addView(ringLimit, lp(WRAP, WRAP, 2));
        ringBox.addView(center, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        col.addView(ringBox, lp(MATCH, dp(260), 12));

        // 状态胶囊
        statePill = text("", 14, p.text, false);
        statePill.setTypeface(medium());
        statePill.setGravity(Gravity.CENTER_VERTICAL);
        statePill.setPadding(dp(14), dp(7), dp(16), dp(7));
        pillBg = rounded(p.surfaceHigh, 100, 0);
        statePill.setBackground(pillBg);
        pillDot = new GradientDrawable();
        pillDot.setShape(GradientDrawable.OVAL);
        pillDot.setSize(dp(8), dp(8));
        statePill.setCompoundDrawablesRelativeWithIntrinsicBounds(pillDot, null, null, null);
        statePill.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams pl = lp(WRAP, WRAP, 6);
        pl.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(statePill, pl);

        stateLine = centered(text("", 14, p.text, false));
        col.addView(stateLine, lp(MATCH, WRAP, 12));
        detailLine = centered(text("", 13, p.sub, false));
        col.addView(detailLine, lp(MATCH, WRAP, 4));
        noteLine = centered(text("", 13, p.warn, false));
        noteLine.setVisibility(View.GONE);
        col.addView(noteLine, lp(MATCH, WRAP, 6));

        // 提醒横幅
        reminderBanner = new LinearLayout(this);
        reminderBanner.setOrientation(LinearLayout.HORIZONTAL);
        reminderBanner.setGravity(Gravity.CENTER_VERTICAL);
        reminderBanner.setBackground(rounded(p.warnContainer, 20, 0));
        reminderBanner.setPadding(dp(16), dp(14), dp(16), dp(14));
        reminderBanner.addView(iconBadge(Ui.ICON_BELL, p.warn, p.onWarn), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout bt = new LinearLayout(this);
        bt.setOrientation(LinearLayout.VERTICAL);
        TextView b1 = text("该起来走走了！", 16, p.onWarnContainer, false);
        b1.setTypeface(medium());
        bt.addView(b1);
        bt.addView(text("带着手机走一走，或者离开座位几分钟。", 13, p.onWarnContainer, false), lp(WRAP, WRAP, 2));
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        btp.leftMargin = dp(14);
        reminderBanner.addView(bt, btp);
        reminderBanner.setVisibility(View.GONE);
        col.addView(reminderBanner, lp(MATCH, WRAP, 16));

        // 按钮
        startBtn = button();
        col.addView(startBtn, lp(MATCH, dp(56), 20));
        breakBtn = button();
        col.addView(breakBtn, lp(MATCH, dp(52), 10));
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.tick(v); onStartStop(); }
        });
        breakBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.tick(v); onManualBreak(); }
        });

        // 今天概览，点一下去“今天”页
        LinearLayout mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.HORIZONTAL);
        miniSit = miniTile(mini, "今日久坐", 0);
        miniBreaks = miniTile(mini, "起身", 10);
        miniSteps = miniTile(mini, "步数", 10);
        col.addView(sectionLabel("今天"), lp(MATCH, WRAP, 26));
        col.addView(mini, lp(MATCH, WRAP, 10));
        return col;
    }

    private TextView miniTile(LinearLayout row, String label, int leftDp) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackground(ripple(rounded(p.surface, 18, 0)));
        t.setClickable(true);
        t.addView(text(label, 12, p.sub, false));
        TextView v = text("—", 17, p.text, false);
        v.setTypeface(medium());
        v.setSingleLine(true);
        t.addView(v, lp(WRAP, WRAP, 4));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { Ui.tick(view); selectTab(TAB_TODAY, true); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, 1f);
        lp.leftMargin = dp(leftDp);
        row.addView(t, lp);
        return v;
    }

    // ---- 今天页

    private LinearLayout buildToday() {
        LinearLayout col = column();
        col.addView(header("今天", new SimpleDateFormat("M 月 d 日 EEEE", Locale.CHINA).format(new Date())));

        // 主卡片：久坐总计
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(rounded(p.primaryContainer, 24, 0));
        hero.setPadding(dp(20), dp(18), dp(20), dp(20));
        hero.addView(text("久坐总计", 14, p.onPrimaryContainer, false));
        statSit = text("—", 36, p.onPrimaryContainer, false);
        statSit.setTypeface(medium());
        hero.addView(statSit, lp(WRAP, WRAP, 6));
        statSitSub = text("", 13, p.onPrimaryContainer, false);
        statSitSub.setAlpha(0.8f);
        hero.addView(statSitSub, lp(WRAP, WRAP, 4));
        col.addView(hero, lp(MATCH, WRAP, 16));

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        statLongest = statTile(r1, Ui.ICON_SEAT, "最长一次连续坐", p.amberContainer, p.amber, 0);
        statBreaks = statTile(r1, Ui.ICON_FLAG, "起身次数", p.primaryContainer, p.primary, 12);
        col.addView(r1, lp(MATCH, WRAP, 12));
        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        statReminders = statTile(r2, Ui.ICON_BELL, "提醒次数", p.warnContainer, p.warn, 0);
        statSteps = statTile(r2, Ui.ICON_STEPS, "步数", p.blueContainer, p.blue, 12);
        col.addView(r2, lp(MATCH, WRAP, 12));

        col.addView(sectionLabel("怎么判断的"), lp(MATCH, WRAP, 28));
        LinearLayout how = card();
        String[] rules = {
                "带着手机走动约 50 步，算起身活动过，计时清零",
                "开了摄像头辅助时，连续 3 分钟看不到你，算离开座位",
                "坐满设定时间就振动提醒；没起身的话，每 5 分钟再提醒一次",
                "离开时没带手机、也没开摄像头，App 会以为你还坐着，回来点“我刚活动过”即可",
        };
        for (int i = 0; i < rules.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView n = centered(text(String.valueOf(i + 1), 12, p.onPrimaryContainer, false));
            n.setTypeface(medium());
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(p.primaryContainer);
            n.setBackground(dot);
            row.addView(n, new LinearLayout.LayoutParams(dp(22), dp(22)));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            tp.leftMargin = dp(12);
            row.addView(text(rules[i], 14, p.text, false), tp);
            how.addView(row, lp(MATCH, WRAP, i == 0 ? 0 : 14));
        }
        col.addView(how, lp(MATCH, WRAP, 10));
        return col;
    }

    private TextView statTile(LinearLayout row, int icon, String label, int badgeBg, int badgeFg, int leftDp) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(rounded(p.surface, 20, 0));
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        t.addView(iconBadge(icon, badgeBg, badgeFg), new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView v = text("—", 22, p.text, false);
        v.setTypeface(medium());
        v.setSingleLine(true);
        t.addView(v, lp(WRAP, WRAP, 14));
        t.addView(text(label, 13, p.sub, false), lp(WRAP, WRAP, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, 1f);
        lp.leftMargin = dp(leftDp);
        row.addView(t, lp);
        return v;
    }

    // ---- 设置页（参考 LocalSend：分组标题 + 圆角卡片 + 整行可点）

    private LinearLayout buildSettings() {
        LinearLayout col = column();
        col.addView(header("设置", "所有设置只保存在这台手机上"));

        col.addView(sectionLabel("提醒"), lp(MATCH, WRAP, 20));
        LinearLayout remind = card();
        remind.addView(rowTitle("提醒间隔", "连续坐满这么久就振动提醒"));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(rounded(p.surfaceHigh, 100, 0));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < LIMITS.length; i++) {
            final int v = LIMITS[i];
            TextView c = centered(text(v + " 分钟", 14, p.text, false));
            c.setPadding(0, dp(9), 0, dp(9));
            c.setClickable(true);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (v != Prefs.sitLimitMin(MainActivity.this)) Ui.tick(view);
                    setLimit(v);
                }
            });
            seg.addView(c, new LinearLayout.LayoutParams(0, WRAP, 1f));
            chips[i] = c;
        }
        remind.addView(seg, lp(MATCH, WRAP, 14));
        col.addView(remind, lp(MATCH, WRAP, 8));

        col.addView(sectionLabel("摄像头"), lp(MATCH, WRAP, 24));
        LinearLayout cam = card();
        cam.setPadding(0, dp(4), 0, dp(4));
        camSwitch = new Ui.Toggle(this, p);
        camSwitch.setChecked(Prefs.camera(this), false);
        cam.addView(switchRow("摄像头辅助判断",
                "把手机立在显示器旁、前置摄像头对着你，就能看出你是否离开了座位。手机平放或放在兜里时会自动只用计步。画面只在手机上即时分析，不保存。",
                camSwitch));
        cam.addView(inset(divider()), new LinearLayout.LayoutParams(MATCH, dp(1)));
        View test = actionRow("测试摄像头位置", "看看这个位置能不能看到你", null);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.tick(v); runCameraTest(); }
        });
        cam.addView(test);
        camTestResult = text("", 13, p.sub, false);
        camTestResult.setPadding(dp(14), dp(10), dp(14), dp(10));
        camTestResult.setVisibility(View.GONE);
        LinearLayout.LayoutParams rp = lp(MATCH, WRAP, 0);
        rp.leftMargin = dp(16);
        rp.rightMargin = dp(16);
        rp.bottomMargin = dp(12);
        cam.addView(camTestResult, rp);
        col.addView(cam, lp(MATCH, WRAP, 8));
        camSwitch.setListener(new Ui.OnToggle() {
            @Override public void onToggle(Ui.Toggle t, boolean on) { onCameraSwitch(on); }
        });

        col.addView(sectionLabel("显示"), lp(MATCH, WRAP, 24));
        LinearLayout disp = card();
        disp.setPadding(0, dp(4), 0, dp(4));
        deskSwitch = new Ui.Toggle(this, p);
        deskSwitch.setChecked(Prefs.deskMode(this), false);
        disp.addView(switchRow("桌面常亮模式",
                "屏幕常亮并调暗，大字显示已经坐了多久。适合手机立在桌上、插着充电时用。", deskSwitch));
        col.addView(disp, lp(MATCH, WRAP, 8));
        deskSwitch.setListener(new Ui.OnToggle() {
            @Override public void onToggle(Ui.Toggle t, boolean on) { setDesk(on); }
        });

        col.addView(sectionLabel("让提醒更可靠"), lp(MATCH, WRAP, 24));
        LinearLayout rel = card();
        rel.setPadding(0, dp(4), 0, dp(4));
        TextView tip = text("国产手机的后台管理比较严格。请把本应用的电池策略设为“不限制”，并允许自启动、后台运行，否则 App 可能会被系统关掉。", 13, p.sub, false);
        tip.setPadding(dp(18), dp(12), dp(18), dp(6));
        rel.addView(tip);
        battState = text("", 13, p.sub, false);
        View batt = actionRow("忽略电池优化", null, battState);
        batt.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.tick(v); requestIgnoreBattery(); }
        });
        rel.addView(batt);
        rel.addView(inset(divider()), new LinearLayout.LayoutParams(MATCH, dp(1)));
        View appSet = actionRow("应用设置", "自启动、后台运行、通知等", null);
        appSet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.tick(v); openAppSettings(); }
        });
        rel.addView(appSet);
        col.addView(rel, lp(MATCH, WRAP, 8));

        col.addView(sectionLabel("关于"), lp(MATCH, WRAP, 24));
        LinearLayout about = card();
        about.addView(rowTitle("起来走走 " + versionName(), "本应用没有联网权限，所有数据只保存在这台手机上。摄像头画面只在手机上即时分析，不保存。"));
        col.addView(about, lp(MATCH, WRAP, 8));
        return col;
    }

    private LinearLayout rowTitle(String title, String sub) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 16, p.text, false));
        if (sub != null) t.addView(text(sub, 13, p.sub, false), lp(MATCH, WRAP, 3));
        return t;
    }

    /** 整行可点的开关行：点文字也能切换。 */
    private View switchRow(String title, String sub, final Ui.Toggle sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        row.setBackground(ripple(null));
        row.setClickable(true);
        row.addView(rowTitle(title, sub), new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(WRAP, WRAP);
        sp.leftMargin = dp(16);
        row.addView(sw, sp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.toggleByUser(); }
        });
        return row;
    }

    private View actionRow(String title, String sub, TextView trailing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14));
        row.setBackground(ripple(null));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(rowTitle(title, sub), new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (trailing != null) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(WRAP, WRAP);
            tp.leftMargin = dp(12);
            row.addView(trailing, tp);
        }
        ImageView chev = new ImageView(this);
        chev.setImageDrawable(new Ui.Icon(this, Ui.ICON_CHEVRON, p.sub, 20));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(20), dp(20));
        cp.leftMargin = dp(6);
        row.addView(chev, cp);
        return row;
    }

    // ---- 桌面常亮模式（盖在最上层）

    private View buildDesk() {
        deskView = new LinearLayout(this);
        deskView.setOrientation(LinearLayout.VERTICAL);
        deskView.setGravity(Gravity.CENTER);
        deskView.setBackgroundColor(Color.BLACK);
        deskView.setPadding(dp(24), dp(24), dp(24), dp(24));
        deskView.setClickable(true);

        FrameLayout box = new FrameLayout(this);
        deskRing = new Ui.Ring(this, 10);
        deskRing.setColors(D_TRACK, D_ARC);
        box.addView(deskRing, new FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER));
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        center.addView(text("已连续坐", 16, D_SUB, false));
        deskMinutes = text("0", 104, D_TEXT, false);
        deskMinutes.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        deskMinutes.setIncludeFontPadding(false);
        center.addView(deskMinutes, lp(WRAP, WRAP, 0));
        center.addView(text("分钟", 16, D_SUB, false));
        box.addView(center, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        deskView.addView(box, new LinearLayout.LayoutParams(MATCH, dp(310)));

        deskState = centered(text("", 15, D_SUB, false));
        deskView.addView(deskState, lp(MATCH, WRAP, 20));
        deskReminder = centered(text("该起来走走了！", 28, D_WARN, false));
        deskReminder.setTypeface(medium());
        deskReminder.setVisibility(View.GONE);
        deskView.addView(deskReminder, lp(MATCH, WRAP, 16));
        TextView exit = centered(text("退出桌面模式", 14, D_SUB, false));
        exit.setPadding(dp(22), dp(11), dp(22), dp(11));
        exit.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(Color.BLACK, 100, 0xFF2A2F2D), null));
        exit.setClickable(true);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setDesk(false); }
        });
        LinearLayout.LayoutParams ep = lp(WRAP, WRAP, 40);
        ep.gravity = Gravity.CENTER_HORIZONTAL;
        deskView.addView(exit, ep);
        deskView.setVisibility(View.GONE);
        return deskView;
    }

    // ------------------------------------------------------------------ 刷新

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
        float frac = running ? Math.min(1f, sit / (limit * 60000f)) : 0f;

        bigMinutes.setText(String.valueOf(mins));
        ringLimit.setText("/ " + limit + " 分钟");
        ring.setProgress(frac, true);
        int w = warn ? 1 : 0;
        if (w != renderedWarn) {
            renderedWarn = w;
            bigMinutes.setTextColor(warn ? p.warn : p.text);
            ring.setColors(warn ? p.warnContainer : p.surfaceHigh, warn ? p.warn : p.primary);
            ring.setPulsing(warn);
            deskRing.setColors(D_TRACK, warn ? D_WARN : D_ARC);
            deskRing.setPulsing(warn);
            deskMinutes.setTextColor(warn ? D_WARN : D_TEXT);
        }

        renderPill(s, warn);
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
            styleButton(startBtn, running ? "停止监测" : "开始监测", running ? 1 : 0);
            styleButton(breakBtn, "我刚活动过", 2);
            breakBtn.setVisibility(running ? View.VISIBLE : View.GONE);
        }
        if (limit != renderedLimit) {
            renderedLimit = limit;
            for (int i = 0; i < LIMITS.length; i++) {
                boolean sel = LIMITS[i] == limit;
                chips[i].setTextColor(sel ? p.onPrimary : p.text);
                chips[i].setTypeface(sel ? medium() : Typeface.DEFAULT);
                chips[i].setBackground(sel ? rounded(p.primary, 100, 0) : ripple(null));
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
        statSitSub.setText(breaks == 0 ? "今天还没有起身记录" : "平均每 " + Prefs.minutes(sitT / (breaks + 1)) + " 起身一次");
        statLongest.setText(Prefs.minutes(longest));
        statBreaks.setText(breaks + " 次");
        statReminders.setText(rem + " 次");
        statSteps.setText(steps + " 步");
        miniSit.setText(Prefs.minutes(sitT));
        miniBreaks.setText(breaks + " 次");
        miniSteps.setText(String.valueOf(steps));

        deskMinutes.setText(String.valueOf(mins));
        deskRing.setProgress(frac, true);
        deskState.setText(running ? state : "还没开始监测");
        deskReminder.setVisibility(warn ? View.VISIBLE : View.GONE);
    }

    private void renderPill(MonitorService.Status s, boolean warn) {
        String label;
        int bg, fg, dot;
        if (!s.running) {
            label = "未开始监测"; bg = p.surfaceHigh; fg = p.sub; dot = p.sub;
        } else if (warn) {
            label = "该起来了"; bg = p.warnContainer; fg = p.onWarnContainer; dot = p.warn;
        } else if (s.state == SitTracker.STATE_MOVING) {
            label = "正在走动"; bg = p.blueContainer; fg = p.text; dot = p.blue;
        } else if (s.state == SitTracker.STATE_AWAY) {
            label = "离开座位"; bg = p.blueContainer; fg = p.text; dot = p.blue;
        } else {
            label = "监测中 · 坐着"; bg = p.primaryContainer; fg = p.onPrimaryContainer; dot = p.primary;
        }
        statePill.setText(label);
        statePill.setTextColor(fg);
        pillBg.setColor(bg);
        pillDot.setColor(dot);
    }

    private void renderBattery() {
        if (battState == null) return;
        boolean ok = ignoringBattery();
        battState.setText(ok ? "已允许" : "未允许");
        battState.setTextColor(ok ? p.primary : p.warn);
    }

    private String stateText(MonitorService.Status s) {
        if (!s.running) return "点“开始监测”后，App 会在后台持续判断你坐了多久。";
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
        showTestResult("检测中…请像平时一样看着电脑屏幕", p.surfaceHigh, p.sub);
        tester.check(new FaceChecker.Callback() {
            @Override public void onResult(final int r) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        testing = false;
                        boolean ok = r == FaceChecker.RESULT_FACE;
                        showTestResult(testText(r), ok ? p.primaryContainer : p.warnContainer,
                                ok ? p.onPrimaryContainer : p.onWarnContainer);
                    }
                });
            }
        });
    }

    private void showTestResult(String msg, int bg, int fg) {
        camTestResult.setVisibility(View.VISIBLE);
        camTestResult.setBackground(rounded(bg, 14, 0));
        camTestResult.setTextColor(fg);
        camTestResult.setText(msg);
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
                    camSwitch.setChecked(false, true);
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
            camSwitch.setChecked(ok, true);
            if (ok) reloadService();
            else toast("没有摄像头权限，无法开启摄像头辅助");
        } else if (code == REQ_CAMERA_TEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) runCameraTest();
            else toast("没有摄像头权限");
        }
    }

    private void setDesk(boolean on) {
        Prefs.setDeskMode(this, on);
        deskSwitch.setChecked(on, true);
        applyDesk(on);
    }

    @SuppressWarnings("deprecation")
    private void applyDesk(boolean on) {
        if (on && deskView.getVisibility() != View.VISIBLE) {
            deskView.setAlpha(0f);
            deskView.animate().alpha(1f).setDuration(250).start();
        }
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
            w.setStatusBarColor(p.bg);
            w.setNavigationBarColor(p.surface);
            if (p.dark) flags &= ~light;
            else flags |= light;
        }
        w.setAttributes(a);
        decor.setSystemUiVisibility(flags);
        if (on) render();
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, p.dark
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    private void maybeAskBattery() {
        if (isFinishing() || ignoringBattery() || Prefs.askedBattery(this)) return;
        Prefs.setAskedBattery(this, true);
        dialog()
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

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 小工具

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
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

    private View header(String title, String sub) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(4), dp(8), dp(4), 0);
        h.addView(text(title, 28, p.text, true));
        h.addView(text(sub, 13, p.sub, false), lp(WRAP, WRAP, 2));
        return h;
    }

    private TextView sectionLabel(String s) {
        TextView t = text(s, 14, p.primary, false);
        t.setTypeface(medium());
        t.setPadding(dp(6), 0, dp(6), 0);
        return t;
    }

    private View iconBadge(int icon, int bg, int fg) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(new Ui.Icon(this, icon, fg, 20));
        v.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(bg);
        v.setBackground(d);
        return v;
    }

    private GradientDrawable rounded(int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private RippleDrawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(p.text, 0x1C)), content,
                content == null ? rounded(Color.WHITE, 18, 0) : null);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(p.surface, 22, 0));
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        c.setClipToOutline(true);
        return c;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private TextView button() {
        TextView t = text("", 16, p.primary, false);
        t.setTypeface(medium());
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** kind：0 实心主按钮，1 浅色次要按钮（停止），2 描边按钮。 */
    private void styleButton(TextView t, String label, int kind) {
        t.setText(label);
        GradientDrawable bg;
        if (kind == 0) {
            t.setTextColor(p.onPrimary);
            bg = rounded(p.primary, 100, 0);
        } else if (kind == 1) {
            t.setTextColor(p.onWarnContainer);
            bg = rounded(p.warnContainer, 100, 0);
        } else {
            t.setTextColor(p.primary);
            bg = rounded(Color.TRANSPARENT, 100, p.line);
            bg.setStroke(dp(1), Ui.blend(p.line, p.sub, 0.35f));
        }
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(kind == 0 ? p.onPrimary : p.text, 0x28)), bg, null));
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(p.line);
        return v;
    }

    private View inset(View v) {
        FrameLayout f = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH, dp(1));
        lp.leftMargin = dp(18);
        lp.rightMargin = dp(18);
        f.addView(v, lp);
        return f;
    }
}
