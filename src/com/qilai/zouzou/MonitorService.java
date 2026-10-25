package com.qilai.zouzou;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 后台监测服务：计步 + （可选）摄像头 + 定时判断 + 提醒。
 * 以前台服务运行，常驻一条通知；每 30 秒判断一次。
 */
public class MonitorService extends Service implements SensorEventListener {

    public static final String ACTION_START = "com.qilai.zouzou.START";
    public static final String ACTION_STOP = "com.qilai.zouzou.STOP";
    public static final String ACTION_MANUAL_BREAK = "com.qilai.zouzou.MANUAL_BREAK";
    public static final String ACTION_RELOAD = "com.qilai.zouzou.RELOAD";
    public static final String EXTRA_FROM_UI = "from_ui";

    static final String CH_MONITOR = "monitor";
    static final String CH_REMIND = "remind";
    static final int ID_ONGOING = 1;
    static final int ID_REMIND = 2;

    static final long TICK_MS = 30_000L;
    static final long CAM_INTERVAL_MS = 30_000L;
    static final long CAM_IDLE_INTERVAL_MS = 60_000L;
    static final long CAM_FRESH_MS = 100_000L;

    /** 给界面读取的最新状态（同一进程内）。 */
    public static volatile Status status = Status.stopped();

    public static final class Status {
        public boolean running;
        public long sittingMs;
        public long asOf;            // elapsedRealtime，sittingMs 对应的时刻
        public long sitLimitMs;
        public boolean reminding;
        public int state;
        public int presence;
        public long absentMs;
        public boolean cameraOn;
        public int camResult;
        public long camResultAt;     // elapsedRealtime，0 表示还没有结果
        public boolean stepSensor;
        public boolean stepsPermission;
        public int todaySteps;
        public long todaySitMs;
        public long todayLongestMs;
        public int todayBreaks;
        public int todayReminders;
        public String note = "";

        static Status stopped() {
            Status s = new Status();
            s.running = false;
            return s;
        }
    }

    private Handler handler;
    private SitTracker tracker;
    private SensorManager sensorManager;
    private Sensor stepSensor;
    private Sensor accelSensor;
    private PowerManager.WakeLock wakeLock;
    private FaceChecker faceChecker;
    private NotificationManager nm;

    private boolean running;
    private boolean fgCamera;
    private boolean cameraWanted;
    private boolean stepListening;
    private long stepTotal = -1;
    private long stepTotalAtTick = -1;
    private int todaySteps;
    private String stepsDay = "";
    private int camResult = FaceChecker.RESULT_NONE;
    private long camResultAt = 0;
    private long nextCamAt = 0;
    private boolean camBusy;
    private String note = "";

    private final float[] accelSum = new float[3];
    private int accelCount;

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        tracker = new SitTracker();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        faceChecker = new FaceChecker(this);
        ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        boolean fromUi = intent != null && intent.getBooleanExtra(EXTRA_FROM_UI, false);

        if (ACTION_STOP.equals(action)) {
            Prefs.setWantRunning(this, false);
            stopMonitoring();
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean justStarted = false;
        if (!running) {
            if (action == null && !Prefs.wantRunning(this)) {
                // 系统重启了服务，但用户已经停止监测
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!startMonitoring(fromUi)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            justStarted = true;
        }

        if (ACTION_MANUAL_BREAK.equals(action)) {
            onBreak(tracker.manualBreak(SystemClock.elapsedRealtime()), true);
        } else if (ACTION_RELOAD.equals(action) && !justStarted) {
            applySettings(fromUi);
        } else if (ACTION_START.equals(action) && !justStarted) {
            // 已在运行时再次收到“开始”：刷新一下前台状态即可
            enterForeground(fgCamera || (fromUi && cameraActive()));
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (running) stopMonitoring();
        faceChecker.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ 启停

    private boolean startMonitoring(boolean fromUi) {
        loadSettings();
        boolean withCamera = cameraWanted && cameraPermitted() && fromUi;
        if (!enterForeground(withCamera)) {
            if (!withCamera || !enterForeground(false)) return false;
        }
        running = true;
        Prefs.setWantRunning(this, true);
        note = (cameraWanted && !fromUi) ? "摄像头需要在 App 里点“开始监测”后才能使用" : "";

        tracker.reset();
        String day = Prefs.today();
        long[] st = Prefs.loadStats(this, day);
        tracker.restoreDay(day, st[0], st[1], (int) st[2], (int) st[3]);
        todaySteps = (int) st[4];
        stepsDay = day;
        stepTotal = -1;
        stepTotalAtTick = -1;
        camResult = FaceChecker.RESULT_NONE;
        camResultAt = 0;
        camBusy = false;

        registerSteps();
        acquireWakeLock();
        handler.removeCallbacks(tickRunnable);
        handler.post(tickRunnable);
        handler.removeCallbacks(camRunnable);
        if (cameraActive()) scheduleCam(1500);
        publish();
        return true;
    }

    private void stopMonitoring() {
        if (running) saveStats();
        running = false;
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(camRunnable);
        handler.removeCallbacks(afterAccel);
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            sensorManager.unregisterListener(accelListener);
        }
        stepListening = false;
        releaseWakeLock();
        if (nm != null) nm.cancel(ID_REMIND);
        stopForeground(STOP_FOREGROUND_REMOVE);
        status = Status.stopped();
    }

    private boolean enterForeground(boolean withCamera) {
        Notification n = buildOngoing();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
                if (withCamera) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                startForeground(ID_ONGOING, n, type);
            } else if (Build.VERSION.SDK_INT >= 30) {
                startForeground(ID_ONGOING, n, withCamera ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA : 0);
            } else {
                startForeground(ID_ONGOING, n);
            }
            fgCamera = withCamera;
            return true;
        } catch (RuntimeException e) {
            note = "后台服务启动失败：" + e.getClass().getSimpleName();
            return false;
        }
    }

    private void loadSettings() {
        tracker.sitLimitMs = Prefs.sitLimitMin(this) * 60_000L;
        cameraWanted = Prefs.camera(this);
    }

    private void applySettings(boolean fromUi) {
        loadSettings();
        if (cameraActive() && !fgCamera && fromUi) {
            // 从界面打开了摄像头：让前台服务带上摄像头类型，锁屏后才能继续用
            if (enterForeground(true)) note = "";
        }
        handler.removeCallbacks(camRunnable);
        if (cameraActive()) {
            scheduleCam(1000);
        } else {
            camResult = FaceChecker.RESULT_NONE;
            camResultAt = 0;
        }
        registerSteps();
        updateOngoing();
        publish();
    }

    // ------------------------------------------------------------------ 定时判断

    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            doTick();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void doTick() {
        acquireWakeLock();
        registerSteps(); // 如果之后才授予了计步权限，这里补上
        long now = SystemClock.elapsedRealtime();
        String day = Prefs.today();
        if (!day.equals(stepsDay)) {
            stepsDay = day;
            todaySteps = 0;
        }
        int delta = 0;
        if (stepTotal >= 0) {
            if (stepTotalAtTick < 0) stepTotalAtTick = stepTotal;
            long d = stepTotal - stepTotalAtTick;
            if (d < 0 || d > 5000) d = 0; // 手机重启后计步器会归零
            delta = (int) d;
            stepTotalAtTick = stepTotal;
            todaySteps += delta;
        }
        if (cameraActive() && !camBusy && now - nextCamAt > 2 * CAM_IDLE_INTERVAL_MS) {
            scheduleCam(0); // 兜底：摄像头检查的循环意外断了就重新开始
        }
        int ev = tracker.tick(now, day, delta, currentPresence(now));
        if (ev == SitTracker.EVENT_REMIND) {
            postReminder();
        } else if (ev == SitTracker.EVENT_BREAK_DONE || ev == SitTracker.EVENT_RESET) {
            onBreak(ev, false);
            return;
        }
        updateOngoing();
        saveStats();
        publish();
    }

    private void onBreak(int ev, boolean manual) {
        if (nm != null) nm.cancel(ID_REMIND);
        if (ev == SitTracker.EVENT_BREAK_DONE && !manual) {
            vibrate(new long[] {0, 120, 120, 120}); // 轻轻振两下：休息已记录
        }
        updateOngoing();
        saveStats();
        publish();
    }

    private int currentPresence(long now) {
        if (!cameraActive()) return SitTracker.PRESENCE_NO_DATA;
        if (camResultAt == 0 || now - camResultAt > CAM_FRESH_MS) return SitTracker.PRESENCE_NO_DATA;
        if (camResult == FaceChecker.RESULT_FACE) return SitTracker.PRESENCE_PRESENT;
        if (camResult == FaceChecker.RESULT_NO_FACE) return SitTracker.PRESENCE_ABSENT;
        return SitTracker.PRESENCE_NO_DATA;
    }

    // ------------------------------------------------------------------ 计步

    private boolean stepsPermitted() {
        return Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    private void registerSteps() {
        if (stepListening || stepSensor == null || sensorManager == null || !stepsPermitted()) return;
        stepListening = sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER && e.values.length > 0) {
            stepTotal = (long) e.values[0];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ------------------------------------------------------------------ 摄像头

    private boolean cameraPermitted() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean cameraActive() {
        return cameraWanted && cameraPermitted();
    }

    private final Runnable camRunnable = new Runnable() {
        @Override public void run() {
            if (!running || !cameraActive()) return;
            startCameraCheck(); // 下一次在拿到结果后再安排
        }
    };

    private void scheduleCam(long delayMs) {
        handler.removeCallbacks(camRunnable);
        nextCamAt = SystemClock.elapsedRealtime() + delayMs;
        handler.postDelayed(camRunnable, delayMs);
    }

    private void startCameraCheck() {
        if (camBusy) return;
        camBusy = true;
        // 先看手机姿态：平放时前置摄像头只能看到天花板，没必要打开
        if (accelSensor != null && sensorManager != null) {
            accelSum[0] = accelSum[1] = accelSum[2] = 0;
            accelCount = 0;
            sensorManager.registerListener(accelListener, accelSensor, SensorManager.SENSOR_DELAY_UI, handler);
            handler.postDelayed(afterAccel, 700);
        } else {
            runFaceCheck();
        }
    }

    private final SensorEventListener accelListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            if (e.values.length < 3) return;
            accelSum[0] += e.values[0];
            accelSum[1] += e.values[1];
            accelSum[2] += e.values[2];
            accelCount++;
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final Runnable afterAccel = new Runnable() {
        @Override public void run() {
            if (sensorManager != null) sensorManager.unregisterListener(accelListener);
            if (!running) { camBusy = false; return; }
            if (accelCount > 0) {
                float x = accelSum[0] / accelCount, y = accelSum[1] / accelCount, z = accelSum[2] / accelCount;
                double g = Math.sqrt(x * x + y * y + z * z);
                if (g > 3 && Math.abs(z) / g > 0.9) {
                    onCamResult(FaceChecker.RESULT_FLAT);
                    return;
                }
            }
            runFaceCheck();
        }
    };

    private void runFaceCheck() {
        faceChecker.check(new FaceChecker.Callback() {
            @Override public void onResult(final int r) {
                handler.post(new Runnable() {
                    @Override public void run() { onCamResult(r); }
                });
            }
        });
    }

    private void onCamResult(int r) {
        camBusy = false;
        if (!running) return;
        camResult = r;
        camResultAt = SystemClock.elapsedRealtime();
        publish();
        if (cameraActive()) {
            // 看得到画面时 30 秒查一次；手机在兜里、平放或摄像头被占用时放慢到 60 秒，省电
            boolean useful = r == FaceChecker.RESULT_FACE || r == FaceChecker.RESULT_NO_FACE;
            scheduleCam(useful ? CAM_INTERVAL_MS : CAM_IDLE_INTERVAL_MS);
        }
    }

    // ------------------------------------------------------------------ 通知与振动

    static void ensureChannels(Context c) {
        NotificationManager m = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (m == null) return;
        if (m.getNotificationChannel(CH_MONITOR) == null) {
            NotificationChannel ch = new NotificationChannel(CH_MONITOR, "监测状态", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("后台监测时常驻的通知");
            ch.setShowBadge(false);
            m.createNotificationChannel(ch);
        }
        if (m.getNotificationChannel(CH_REMIND) == null) {
            NotificationChannel ch = new NotificationChannel(CH_REMIND, "久坐提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("坐太久时振动提醒");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[] {0, 500, 250, 500});
            ch.setSound(null, null);
            m.createNotificationChannel(ch);
        }
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent serviceIntent(int req, String action) {
        Intent i = new Intent(this, MonitorService.class).setAction(action);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildOngoing() {
        String title;
        if (!running) {
            title = "久坐监测已开启";
        } else if (tracker.isReminding()) {
            title = "已经坐了 " + Prefs.minutes(tracker.getSittingMs()) + "，该起来了";
        } else {
            title = "已连续坐 " + Prefs.minutes(tracker.getSittingMs());
        }
        String text = "每 " + (tracker.sitLimitMs / 60_000L) + " 分钟提醒 · " + stateWords();
        Icon icon = Icon.createWithResource(this, R.drawable.ic_stat);
        Notification.Builder b = new Notification.Builder(this, CH_MONITOR)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(openAppIntent())
                .addAction(new Notification.Action.Builder(icon, "我刚活动过", serviceIntent(1, ACTION_MANUAL_BREAK)).build())
                .addAction(new Notification.Action.Builder(icon, "停止监测", serviceIntent(2, ACTION_STOP)).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private String stateWords() {
        if (!running) return "准备中";
        switch (tracker.getState()) {
            case SitTracker.STATE_MOVING:
                return "正在走动";
            case SitTracker.STATE_AWAY:
                return "已离开座位";
            default:
                return tracker.getEffectivePresence() == SitTracker.PRESENCE_PRESENT ? "摄像头看到你在座位上" : "根据计步判断";
        }
    }

    private void updateOngoing() {
        if (!running || nm == null) return;
        try {
            nm.notify(ID_ONGOING, buildOngoing());
        } catch (RuntimeException ignored) {
        }
    }

    private void postReminder() {
        if (nm != null) {
            String title = "已经坐了 " + Prefs.minutes(tracker.getSittingMs()) + "，起来走走吧";
            String body = "带着手机走一走（约 " + tracker.breakSteps + " 步），或离开座位几分钟，就会重新计时。";
            Notification.Builder b = new Notification.Builder(this, CH_REMIND)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent())
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(this, R.drawable.ic_stat), "我已活动", serviceIntent(3, ACTION_MANUAL_BREAK)).build());
            try {
                nm.notify(ID_REMIND, b.build());
            } catch (RuntimeException ignored) {
            }
        }
        // 有的手机默认不弹横幅、不振动，这里再主动振一下
        vibrate(new long[] {0, 600, 300, 600, 300, 800});
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long[] pattern) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            VibrationEffect effect = VibrationEffect.createWaveform(pattern, -1);
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
            } else {
                v.vibrate(effect, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            }
        } catch (RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------------ 其他

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zouzou:monitor");
                wakeLock.setReferenceCounted(false);
            }
            // 每次判断时续期，即使程序异常也不会一直占着
            wakeLock.acquire(10 * 60_000L);
        } catch (RuntimeException ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException ignored) {
        }
    }

    private void saveStats() {
        Prefs.saveStats(this, tracker.getDayKey(), tracker.getTodaySitMs(), tracker.getTodayLongestMs(),
                tracker.getTodayBreaks(), tracker.getTodayReminders(), todaySteps);
    }

    private void publish() {
        Status s = new Status();
        long now = SystemClock.elapsedRealtime();
        s.running = running;
        s.sittingMs = tracker.getSittingMs();
        s.asOf = tracker.getLastTickMs() > 0 ? tracker.getLastTickMs() : now;
        s.sitLimitMs = tracker.sitLimitMs;
        s.reminding = tracker.isReminding();
        s.state = tracker.getState();
        s.presence = tracker.getEffectivePresence();
        s.absentMs = tracker.getAbsentMs(now);
        s.cameraOn = cameraActive();
        s.camResult = camResult;
        s.camResultAt = camResultAt;
        s.stepSensor = stepSensor != null;
        s.stepsPermission = stepsPermitted();
        s.todaySteps = todaySteps;
        s.todaySitMs = tracker.getTodaySitMs();
        s.todayLongestMs = tracker.getTodayLongestMs();
        s.todayBreaks = tracker.getTodayBreaks();
        s.todayReminders = tracker.getTodayReminders();
        s.note = note;
        status = s;
    }
}
